package com.osrscn.glyph;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Singleton;

import com.osrscn.OsrscnConfig;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;
import net.runelite.client.game.ChatIconManager;

/**
 * Renders Simplified Chinese characters to inline chat-icon images so they can be drawn on the
 * native OSRS UI via {@code <img=N>} tags. Glyphs are generated on demand from a bundled OFL CJK font
 * (no pre-rendered atlas) and cached per (size, colour, codepoint).
 *
 * <p>Three sizes are used: {@link #dialogueSize()} (user-configurable) for dialogue, a fixed
 * {@link #uiSize()} for interface/menus/chat, and a smaller fixed {@link #smallSize()} for hover
 * tooltips and cramped info boxes. Glyphs keep the font's top leading (no top crop) so they sit on
 * the native text baseline rather than floating up.
 *
 * <p>{@link ChatIconManager#registerChatIcon} uploads the sprite on the next client cycle, so the
 * icon's {@code <img>} index is not valid the instant we register. We therefore cache the
 * registration id (stable) and resolve the index fresh on each use; {@link #toImgTags} returns
 * {@code null} while any glyph in the string is still pending, so the caller retries next tick.
 *
 * <p>Use on the client thread.
 */
@Slf4j
@Singleton
public class GlyphService
{
	private static final String CROP_REF = "国中永鬱"; // dense glyphs that span the full ink box
	// CJK closing punctuation that must not start a wrapped line (kinsoku): keep it with the
	// preceding glyph so a line never begins with a stray comma/period/percent/bracket.
	private static final String NO_LEADING = "、。，．；：！？）｝】》」』〉〕…—·％”’";
	private static final int FAILED = -2; // font cannot render this codepoint (permanent)

	// Emergency fallback only: the real default is the bundled OFL font (see BUNDLED_FONT). These
	// families are used solely when no custom path, external drop-in, or bundled font is present. Non-free
	// system fonts (Microsoft YaHei = Founder, ...) are deliberately excluded so we never rasterise them.
	private static final String[] FONT_CANDIDATES = {
			"Source Han Sans SC", "思源黑体", "Noto Sans CJK SC", "Noto Sans SC", "PingFang SC", "Heiti SC"
	};

	// The default glyph font: Source Han Sans SC (SIL Open Font License) bundled in the JAR, so Chinese
	// renders for every user out of the box (including those without a system CJK font). See OFL-1.1.txt.
	private static final String BUNDLED_FONT = "/com/osrscn/SourceHanSansSC-Regular.otf";

	// Optional external drop-in: an .otf/.ttf placed here overrides the bundled font without touching config.
	private static final File FONT_DIR = new File(RuneLite.RUNELITE_DIR, "osrscn/font");

	@Inject
	private ChatIconManager chatIconManager;
	@Inject
	private OsrscnConfig config;

	// Only dialogue follows the user's size setting; all other surfaces use fixed conservative sizes so
	// dense UI never overflows or mixes visibly different sizes (skill/prayer/HP vs the rest).
	private static final int UI_SIZE = 11;    // interface / menus / chat / overhead bubbles
	private static final int SMALL_SIZE = 11; // hover tooltips and cramped info boxes

	// Coverage threshold for flattening an antialiased glyph to a 1-bit mask (see render()).
	// macOS' Java2D antialiases even when the AA hint is OFF, leaving no fully-opaque pixels
	// for RuneLite's IndexedSprite to keep, so small glyphs would render blank. 0x60 keeps
	// thin CJK strokes legible at the tiny in-game sizes.
	private static final int GLYPH_ALPHA_THRESHOLD = 0x60;

	/** Point size for NPC/player dialogue text (the only user-configurable size). */
	public int dialogueSize()
	{
		return clamp(config.mainFontSize());
	}

	/** Fixed point size for interface, menus, chat and overhead bubbles. */
	public int uiSize()
	{
		return UI_SIZE;
	}

	/** Fixed point size for hover tooltips and cramped info boxes (skill/prayer/magic/emote, HP bar). */
	public int smallSize()
	{
		return SMALL_SIZE;
	}

	private static int clamp(int size)
	{
		return Math.max(8, Math.min(28, size));
	}

	// Colours pre-warmed for every translated character so its first appearance never flashes English
	// while the sprite uploads: white (menu/UI), OSRS blue (public chat), yellow (overhead/highlight),
	// near-black (game messages / dialogue / default widget text). Rare widget colours stay lazy.
	private static final int[] PREWARM_COLORS = {0xFFFFFF, 0x0000FF, 0xFFFF00, 0x000000};

	private final Map<Long, Integer> regId = new HashMap<>(); // (size,colour,codepoint) -> registration id / FAILED
	private int[] prewarmCps;   // codepoints still to warm (null when idle/done)
	private int[] prewarmSizes; // distinct sizes to warm each codepoint at
	private int prewarmIdx;     // next codepoint index to process
	private final Map<Integer, Font> fonts = new HashMap<>();
	private final Map<Integer, Integer> glyphW = new HashMap<>();
	private final Map<Integer, Integer> glyphH = new HashMap<>(); // cropped ink height per size
	private final Map<Integer, Integer> cropTop = new HashMap<>(); // first ink row to crop from, per size
	private String fontName;
	private boolean fontResolved;
	private Font customBase;      // loaded from config.fontPath(), or null to use the system font

	/** Rendered pixel width of a full-width CJK glyph, for wrap calculations. */
	public int glyphWidth()
	{
		return glyphWidth(uiSize());
	}

	public int glyphWidth(int size)
	{
		Integer cached = glyphW.get(size);
		if (cached != null)
		{
			return cached;
		}
		Font f = fontFor(size);
		int w = (f == null) ? size : Math.max(1, metrics(f).stringWidth("中"));
		glyphW.put(size, w);
		return w;
	}

	/**
	 * How many full-width glyphs of {@code size} fit in {@code widthPx} pixels. Used to insert
	 * {@code <br>} breaks, since a char-image string has no spaces for the client to wrap on. Returns
	 * 0 when nothing fits (callers treat 0 as "no wrap").
	 */
	public int wrapChars(int widthPx, int size)
	{
		int gw = glyphWidth(size);
		return widthPx >= gw ? widthPx / gw : 0;
	}

	/**
	 * Rendered pixel height of a glyph image. Native widget line height is sized for the small bitmap
	 * font, so multi-line char-image text overflows unless the caller raises the line height to this.
	 */
	public int glyphHeight()
	{
		return glyphHeight(uiSize());
	}

	public int glyphHeight(int size)
	{
		Integer cached = glyphH.get(size);
		if (cached != null)
		{
			return cached;
		}
		computeCrop(size);
		return glyphH.get(size);
	}

	/**
	 * Find the shared vertical crop window for a size from dense reference glyphs: the first and last
	 * rows that actually contain ink. OSRS draws inline {@code <img>} aligned to the text baseline, so
	 * cropping away the font's empty top leading and below-baseline descent keeps glyphs from floating
	 * up with a gap underneath, and saves vertical space.
	 */
	private void computeCrop(int size)
	{
		Font f = fontFor(size);
		if (f == null)
		{
			cropTop.put(size, 0);
			glyphH.put(size, size);
			return;
		}
		FontMetrics fm = metrics(f);
		int ascent = fm.getAscent();
		int full = Math.max(1, ascent + fm.getDescent());
		int w = Math.max(1, fm.stringWidth(CROP_REF));

		BufferedImage img = new BufferedImage(w, full, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
		g.setFont(f);
		g.setColor(Color.WHITE);
		g.drawString(CROP_REF, 0, ascent);
		g.dispose();

		int top = -1;
		int bot = -1;
		for (int y = 0; y < full; y++)
		{
			for (int x = 0; x < w; x++)
			{
				if ((img.getRGB(x, y) >>> 24) != 0)
				{
					if (top < 0)
					{
						top = y;
					}
					bot = y;
					break;
				}
			}
		}
		if (top < 0)
		{
			top = 0;
			bot = full - 1;
		}
		cropTop.put(size, top);
		glyphH.put(size, bot - top + 1);
	}

	/**
	 * Convert a translated string to a renderable one: CJK characters become {@code <img=N>} tags
	 * coloured with {@code colorRgb}; tags, ASCII and whitespace pass through unchanged.
	 *
	 * @return the rendered string, or {@code null} if some glyph is not uploaded yet (retry later)
	 */
	public String toImgTags(String text, int colorRgb)
	{
		return toImgTags(text, colorRgb, 0, uiSize());
	}

	public String toImgTags(String text, int colorRgb, int maxChars)
	{
		return toImgTags(text, colorRgb, maxChars, uiSize());
	}

	/**
	 * @param maxChars insert a {@code <br>} after this many visual characters so long lines wrap
	 *                 inside their widget (a glyph string has no spaces for the client to break on);
	 *                 0 disables wrapping.
	 * @param size     point size to render at ({@link #dialogueSize()}, {@link #uiSize()} or {@link #smallSize()})
	 */
	public String toImgTags(String text, int colorRgb, int maxChars, int size)
	{
		if (text == null || text.isEmpty())
		{
			return text;
		}
		text = normalizeSeparators(text);
		StringBuilder out = new StringBuilder(text.length() * 6);
		int i = 0;
		int len = text.length();
		int lineChars = 0;
		boolean ready = true;
		boolean pendingBreak = false; // wrap limit reached; emit the <br> at the next safe boundary
		int currentColor = colorRgb; // inline <col=..> tags recolour following glyphs; </col> resets
		while (i < len)
		{
			char c = text.charAt(i);
			// a pending wrap break is only safe before a CJK glyph or whitespace, never inside an ASCII
			// run (so "15%" never splits into "1"/"5%") nor before closing punctuation (kinsoku, so a
			// line never starts with a stray "、"); defer past tags too
			if (pendingBreak && c != '<' && (text.codePointAt(i) >= 0x2E80 || Character.isWhitespace(c))
					&& NO_LEADING.indexOf(c) < 0)
			{
				out.append("<br>");
				lineChars = 0;
				pendingBreak = false;
			}
			if (c == '<')
			{
				int end = text.indexOf('>', i);
				if (end != -1)
				{
					out.append(text, i, end + 1);
					if (text.startsWith("<br>", i))
					{
						lineChars = 0;
						pendingBreak = false;
					}
					else if (text.regionMatches(true, i, "<col=", 0, 5))
					{
						currentColor = parseColTag(text, i, end, colorRgb);
					}
					else if (text.regionMatches(true, i, "</col>", 0, 6))
					{
						currentColor = colorRgb;
					}
					i = end + 1;
					continue;
				}
			}
			int cp = text.codePointAt(i);
			int cc = Character.charCount(cp);
			if (cp < 0x2E80) // ascii + latin-ish: keep native
			{
				out.append(text, i, i + cc);
				lineChars++;
				i += cc;
			}
			else
			{
				int id = glyphRegId(cp, currentColor & 0xFFFFFF, size);
				if (id == FAILED)
				{
					out.append(text, i, i + cc); // font can't render: leave literal
					lineChars++;
				}
				else
				{
					int idx = chatIconManager.chatIconIndex(id);
					if (idx >= 0)
					{
						out.append("<img=").append(idx).append('>');
						lineChars++;
					}
					else
					{
						ready = false; // sprite not uploaded yet
					}
				}
				i += cc;
			}
			if (maxChars > 0 && lineChars >= maxChars && i < len)
			{
				pendingBreak = true; // break at the next safe boundary, not mid-token
			}
		}
		return ready ? out.toString() : null;
	}

	/**
	 * Queue a glyph pre-warm: register (and so start the upload of) every {@code codepoints} character at the
	 * {@link #PREWARM_COLORS} and the sizes actually in use, drained a few per tick by {@link #prewarmStep}.
	 * Registration is the latency-bearing step; doing it ahead of need means the sprite is already uploaded by
	 * the time a surface first draws the character, so it never flashes English. Re-queue after {@link
	 * #reloadFont} (which clears the cache). Call on the client thread.
	 */
	public void beginPrewarm(java.util.Collection<Integer> codepoints)
	{
		if (codepoints == null || codepoints.isEmpty())
		{
			prewarmCps = null;
			return;
		}
		int[] cps = new int[codepoints.size()];
		int k = 0;
		for (int cp : codepoints)
		{
			cps[k++] = cp;
		}
		// distinct sizes only (uiSize and smallSize are usually equal; dialogue follows the config)
		java.util.TreeSet<Integer> sizes = new java.util.TreeSet<>();
		sizes.add(uiSize());
		sizes.add(smallSize());
		sizes.add(dialogueSize());
		int[] sz = new int[sizes.size()];
		int j = 0;
		for (int s : sizes)
		{
			sz[j++] = s;
		}
		prewarmCps = cps;
		prewarmSizes = sz;
		prewarmIdx = 0;
	}

	/** True while a pre-warm queue still has characters to register. */
	public boolean prewarmPending()
	{
		return prewarmCps != null;
	}

	/**
	 * Register up to {@code codepointBudget} more pre-warm characters (all colours/sizes each). Keep the
	 * budget small so the per-tick rasterise cost stays well under a frame. Call from a per-tick hook until
	 * {@link #prewarmPending} is false. Call on the client thread.
	 */
	public void prewarmStep(int codepointBudget)
	{
		if (prewarmCps == null)
		{
			return;
		}
		int end = Math.min(prewarmIdx + codepointBudget, prewarmCps.length);
		for (; prewarmIdx < end; prewarmIdx++)
		{
			int cp = prewarmCps[prewarmIdx];
			for (int size : prewarmSizes)
			{
				for (int color : PREWARM_COLORS)
				{
					glyphRegId(cp, color, size);
				}
			}
		}
		if (prewarmIdx >= prewarmCps.length)
		{
			prewarmCps = null; // drained
		}
	}

	private int glyphRegId(int codepoint, int colorRgb, int size)
	{
		long key = ((long) size << 45) | ((long) colorRgb << 21) | codepoint;
		Integer cached = regId.get(key);
		if (cached != null)
		{
			return cached;
		}
		int id = FAILED;
		try
		{
			BufferedImage img = render(codepoint, colorRgb, size);
			if (img != null)
			{
				id = chatIconManager.registerChatIcon(img);
			}
		}
		catch (Exception e)
		{
			log.warn("glyph render failed for U+{}", Integer.toHexString(codepoint), e);
		}
		regId.put(key, id);
		return id;
	}

	/** Colour-independent 1-bit pixel mask of a rasterized glyph, cached per (size, codepoint). */
	private static final class Mask
	{
		static final Mask EMPTY = new Mask(0, 0, new long[0]);
		final int w;
		final int h;
		final long[] bits; // row-major solid-pixel bitset

		Mask(int w, int h, long[] bits)
		{
			this.w = w;
			this.h = h;
			this.bits = bits;
		}

		boolean solid(int x, int y)
		{
			int i = y * w + x;
			return (bits[i >> 6] & (1L << (i & 63))) != 0;
		}
	}

	private final Map<Long, Mask> masks = new HashMap<>(); // (size,codepoint) -> mask

	private BufferedImage render(int codepoint, int colorRgb, int size)
	{
		Mask m = maskFor(codepoint, size);
		if (m == null)
		{
			return null;
		}
		// pure black collides with the IndexedSprite transparent slot (index 0) and renders blank;
		// nudge it to near-black, which is visually identical.
		int solid = 0xFF000000 | ((colorRgb == 0 ? 0x010101 : colorRgb) & 0xFFFFFF);
		BufferedImage img = new BufferedImage(m.w, m.h, BufferedImage.TYPE_INT_ARGB);
		for (int y = 0; y < m.h; y++)
		{
			for (int x = 0; x < m.w; x++)
			{
				if (m.solid(x, y))
				{
					img.setRGB(x, y, solid);
				}
			}
		}
		return img;
	}

	/** Rasterize a glyph once per (size, codepoint); per-colour sprites just repaint the mask. */
	private Mask maskFor(int codepoint, int size)
	{
		long key = ((long) size << 21) | codepoint;
		Mask cached = masks.get(key);
		if (cached != null)
		{
			return cached == Mask.EMPTY ? null : cached;
		}
		Font f = fontFor(size);
		if (f == null || !f.canDisplay(codepoint))
		{
			masks.put(key, Mask.EMPTY);
			return null;
		}
		String s = new String(Character.toChars(codepoint));

		FontMetrics fm = metrics(f);
		int w = Math.max(1, fm.stringWidth(s));
		int ascent = fm.getAscent();
		int full = Math.max(1, ascent + fm.getDescent());

		BufferedImage img = new BufferedImage(w, full, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		// IndexedSprite conversion keeps only fully-opaque pixels. Render aliased (AA off) so
		// Windows/Linux produce crisp fully-opaque glyphs; macOS ignores this hint and antialiases
		// anyway, so coverage is flattened to the 1-bit mask below (which is also what we cache).
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
		g.setFont(f);
		g.setColor(Color.WHITE);
		g.drawString(s, 0, ascent);
		g.dispose();

		// crop to the shared ink window so glyphs sit tight on the baseline (no top/bottom gap)
		glyphHeight(size); // ensure crop window computed
		int top = Math.min(cropTop.get(size), full - 1);
		int ch = Math.min(glyphH.get(size), full - top);

		long[] bits = new long[(w * ch + 63) >> 6];
		for (int y = 0; y < ch; y++)
		{
			for (int x = 0; x < w; x++)
			{
				int a = (img.getRGB(x, top + y) >>> 24) & 0xFF;
				if (a >= GLYPH_ALPHA_THRESHOLD)
				{
					int i = y * w + x;
					bits[i >> 6] |= 1L << (i & 63);
				}
			}
		}
		Mask m = new Mask(w, ch, bits);
		masks.put(key, m);
		return m;
	}

	/**
	 * Reload the glyph font from {@code config.fontPath()} (a bundled/system-independent .ttf/.otf) and
	 * drop every cached glyph so the new font takes effect immediately. Call at start-up and whenever
	 * the font (or size) setting changes. A blank/invalid path falls back to the system CJK font.
	 */
	public void reloadFont()
	{
		String path = config.fontPath() == null ? "" : config.fontPath().trim();
		// clear caches so glyphs re-render with the new font / size
		fonts.clear();
		regId.clear();
		masks.clear();
		glyphW.clear();
		glyphH.clear();
		cropTop.clear();
		prewarmCps = null; // drop any in-flight pre-warm; the caller re-queues after reloadFont
		customBase = null;
		fontName = null;
		fontResolved = false;
		// Priority: user-configured path > external drop-in (FONT_DIR) > bundled OFL font (JAR) > system CJK.
		File file = !path.isEmpty() ? new File(path) : externalDropInFont();
		if (file != null)
		{
			try
			{
				// TRUETYPE_FONT loads both .ttf and OpenType .otf (CFF included)
				customBase = Font.createFont(Font.TRUETYPE_FONT, file);
				if (!customBase.canDisplay('中'))
				{
					log.warn("OSRSCN: font '{}' can't display CJK, using bundled font", file);
					customBase = null;
				}
				else
				{
					log.info("OSRSCN glyph font: {} ({})", customBase.getFontName(), file);
				}
			}
			catch (Exception e)
			{
				log.warn("OSRSCN: failed to load font '{}', using bundled font", file, e);
				customBase = null;
			}
		}
		// Default: the OFL font bundled in the JAR, so Chinese renders even without any system CJK font.
		if (customBase == null)
		{
			try (InputStream in = GlyphService.class.getResourceAsStream(BUNDLED_FONT))
			{
				if (in != null)
				{
					customBase = Font.createFont(Font.TRUETYPE_FONT, in);
					if (!customBase.canDisplay('中'))
					{
						customBase = null;
					}
					else
					{
						log.info("OSRSCN glyph font: bundled {}", customBase.getFontName());
					}
				}
			}
			catch (Exception e)
			{
				log.warn("OSRSCN: failed to load bundled font, using system font", e);
				customBase = null;
			}
		}
	}

	/**
	 * Optional external drop-in font in {@code ~/.runelite/osrscn/font/}: lets a user swap the glyph font
	 * without setting a config path. Overrides the bundled font. Returns the first {@code .otf}/{@code .ttf}
	 * found, or null (the common case), in which case the bundled JAR font is used.
	 */
	private static File externalDropInFont()
	{
		File[] files = FONT_DIR.listFiles((dir, name) ->
		{
			String n = name.toLowerCase(java.util.Locale.ROOT);
			return n.endsWith(".otf") || n.endsWith(".ttf");
		});
		if (files == null || files.length == 0)
		{
			return null;
		}
		java.util.Arrays.sort(files);
		return files[0];
	}

	private Font fontFor(int size)
	{
		Font cached = fonts.get(size);
		if (cached != null)
		{
			return cached;
		}
		Font f;
		if (customBase != null)
		{
			f = customBase.deriveFont((float) size);
		}
		else
		{
			resolveFontName();
			if (fontName == null)
			{
				return null;
			}
			f = new Font(fontName, Font.PLAIN, size);
		}
		fonts.put(size, f);
		return f;
	}

	private void resolveFontName()
	{
		if (fontResolved)
		{
			return;
		}
		fontResolved = true;
		for (String name : FONT_CANDIDATES)
		{
			Font candidate = new Font(name, Font.PLAIN, 12); // probe size; family is what matters here
			if (candidate.getFamily().equalsIgnoreCase(name) && candidate.canDisplay('中'))
			{
				fontName = name;
				log.info("OSRSCN glyph font: {}", name);
				return;
			}
		}
		Font fallback = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
		if (fallback.canDisplay('中'))
		{
			fontName = Font.SANS_SERIF;
			log.info("OSRSCN glyph font: logical SansSerif");
		}
		else
		{
			log.error("OSRSCN: no CJK-capable font found");
		}
	}

	/** Parse the RGB of a {@code <col=RRGGBB>} tag occupying {@code text[start..end]}, or {@code fallback}. */
	private static int parseColTag(String text, int start, int end, int fallback)
	{
		int eq = text.indexOf('=', start);
		if (eq < 0 || eq >= end)
		{
			return fallback;
		}
		int n = 0;
		StringBuilder hex = new StringBuilder(6);
		for (int i = eq + 1; i < end && n < 6; i++)
		{
			char c = text.charAt(i);
			boolean isHex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
			if (!isHex)
			{
				break;
			}
			hex.append(c);
			n++;
		}
		if (n == 0)
		{
			return fallback;
		}
		try
		{
			return Integer.parseInt(hex.toString(), 16);
		}
		catch (NumberFormatException e)
		{
			return fallback;
		}
	}

	/**
	 * The OSRS bitmap font can't draw the interpunct used in foreign names (shows as '?'), nor most
	 * other non-ASCII punctuation below the CJK range - ellipsis, em-dash, curly quotes pass through
	 * as garbage glyphs (chars &lt; 0x2E80 are kept native, see the render loop). Fold them to ASCII
	 * the font does have; CJK punctuation at U+3000+ is glyph-rendered and unaffected.
	 */
	private static String normalizeSeparators(String s)
	{
		boolean dirty = false;
		for (int i = 0; i < s.length(); i++)
		{
			char c = s.charAt(i);
			if (c >= 0x80 && c < 0x2E80)
			{
				dirty = true;
				break;
			}
		}
		if (!dirty)
		{
			return s;
		}
		return s.replace('·', '.').replace('・', '.').replace('‧', '.').replace('•', '.')
				.replace("…", "...")
				.replace('—', '-').replace('–', '-')
				.replace('“', '"').replace('”', '"')
				.replace('‘', '\'').replace('’', '\'')
				.replace('\u00A0', ' ');
	}

	private static FontMetrics metrics(Font f)
	{
		BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = probe.createGraphics();
		g.setFont(f);
		FontMetrics fm = g.getFontMetrics();
		g.dispose();
		return fm;
	}
}
