package com.osrscn.translate;

import com.osrscn.glyph.GlyphService;
import com.osrscn.text.Tags;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.Player;

/**
 * Translation facade: lookup table first, AI fallback second, then render to char-image tags.
 * Returns {@code null} when no translation is available yet (caller keeps the original text).
 *
 * <p>Call on the client thread (reads the local player name).
 */
@Singleton
public class Translator
{
	private static final String PLAYER_NAME = "[player name]";
	private static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,]\\d+)*");
	private static final Pattern WORDY = Pattern.compile("[A-Za-z]{3,}"); // has a real word worth AI-translating
	private static final Pattern TOP_THREE_WERE = Pattern.compile("(?i)(top three .+? were )(.+?)([.!?])");
	private static final Pattern TOP_CRAB_WAS = Pattern.compile("(?i)the top crab crusher was (.+?)([.!?])");
	private static final Pattern MEMBERS_LINE = Pattern.compile("(?i)^Members:\\s*(.+)$");
	// Combat-achievement "Monster: <name>" line: the prefix is fixed and the name lives in the name
	// table (incl. bosses), so compose "怪物：" + looked-up name instead of needing a whole-line entry.
	private static final Pattern MONSTER_LINE = Pattern.compile("(?i)^Monster:\\s*(.+)$");
	private static final Pattern REQUIRES_LINE = Pattern.compile("(?i)^Requires:\\s*(.+)$");
	// Examine "Price of <item>: GE average <Num0> HA value <Num1>" - the item lives in the name table, so
	// compose a consistent line from the looked-up name instead of needing a whole-line entry per item.
	private static final Pattern PRICE_LINE = Pattern.compile("(?i)^Price of (.+?):\\s*(.+)$");
	private static final Pattern PRICE_QTY = Pattern.compile("(?i)^(<Num\\d+>)\\s*x\\s*(.+)$");
	private static final Pattern GE_AVERAGE = Pattern.compile("(?i)GE average\\s*(<Num\\d+>)");
	private static final Pattern HA_VALUE = Pattern.compile("(?i)HA value\\s*(<Num\\d+>)");
	private static final Pattern PRICE_EACH = Pattern.compile("(?i)\\((<Num\\d+>)\\s*ea\\)");
	private static final Pattern LEVEL_REQUIREMENT = Pattern.compile(
			"(?i)^(<colNum\\d+>)?\\s*Level\\s+((?:<Num\\d+>)|\\d+(?:[.,]\\d+)*)\\s+(.+?)(</col>)?$");

	@Inject
	private Client client;
	@Inject
	private TranslationStore store;
	@Inject
	private AiTranslator ai;
	@Inject
	private GlyphService glyph;
	@Inject
	private MissingCollector missing;

	/**
	 * @param english  plain English source text (tags already stripped by the caller)
	 * @param colorRgb colour to render the Chinese glyphs in
	 * @return rendered Chinese (char-image tags), or null if not translated yet
	 */
	public String translate(String english, int colorRgb, int maxChars)
	{
		String zh = plain(english);
		if (zh == null)
		{
			return null;
		}
		return glyph.toImgTags(zh, colorRgb, maxChars);
	}

	/**
	 * Plain-text translation (lookup table then AI), without rendering to char-images. For Swing UI
	 * (the side panel) which draws with a real CJK font. Returns null if not translated yet.
	 */
	public String plain(String english)
	{
		return plainCore(english, null, null, null, false);
	}

	/**
	 * Like {@link #plain} but, on a table miss, records the line to {@link MissingCollector} tagged with
	 * the given transcript columns (category / NPC / speaker) so the collected file lines up with the data
	 * tables. Used by the dialogue handler, which knows the NPC and speaker.
	 */
	public String plainCollect(String english, String category, String subCategory, String source)
	{
		return plainCore(english, category, subCategory, source, true);
	}

	private String plainCore(String english, String category, String subCategory, String source, boolean collect)
	{
		if (english == null || english.trim().isEmpty())
		{
			return null;
		}
		String name = playerName();
		String query = name != null ? english.replace(name, PLAYER_NAME) : english;
		if (name != null)
		{
			String zh = store.lookupAny(query);
			if (zh != null)
			{
				return zh.replace(PLAYER_NAME, name);
			}
		}
		String zh = store.lookupAny(english);
		if (zh == null)
		{
			if (collect)
			{
				missing.record(query, category, subCategory, source);
			}
			zh = aiTranslate(english, true);
		}
		return zh;
	}

	/**
	 * Plain-text translation with no client access (no player-name substitution), safe to call from
	 * the Swing thread - used by the side panel's manual translate box.
	 */
	public String plainNoPlayer(String english)
	{
		if (english == null || english.trim().isEmpty())
		{
			return null;
		}
		return lookupOrAi(english, false);
	}

	/** Lookup table then AI fallback for an already player-name-substituted query. */
	private String lookupOrAi(String query, boolean protectPlayerNames)
	{
		String zh = store.lookupAny(query);
		if (zh == null)
		{
			zh = aiTranslate(query, protectPlayerNames);
		}
		return zh;
	}

	/**
	 * Lookup-only translation for menus/interface: no AI fallback (must be instant, no flicker).
	 * Tries {@code category} then falls back to NAME for entity-style text.
	 *
	 * @param maxChars wrap with {@code <br>} after this many visual chars (0 = no wrap)
	 * @param size     glyph point size ({@link GlyphService#uiSize()} / {@link GlyphService#smallSize()})
	 * @return rendered Chinese (char-image tags), or null if not in the table / not ready yet
	 */
	public String lookupRender(TranslationStore.Category category, String english, int colorRgb, int maxChars, int size)
	{
		if (english == null || english.trim().isEmpty())
		{
			return null;
		}
		String zh = store.lookup(category, english);
		if (zh == null && category != TranslationStore.Category.NAME)
		{
			zh = store.lookup(TranslationStore.Category.NAME, english);
		}
		if (zh == null)
		{
			return null;
		}
		return glyph.toImgTags(zh, colorRgb, maxChars, size);
	}

	// Menu options: action verbs first, then item actions and interface / name labels.
	private static final TranslationStore.Category[] MENU_ORDER = {
			TranslationStore.Category.ACTIONS, TranslationStore.Category.INVENTORY_ACTIONS,
			TranslationStore.Category.INTERFACE, TranslationStore.Category.NAME,
	};

	/**
	 * Render a whole menu option that embeds a coloured entity, e.g. {@code "Open <col=ff9040>Ardougne
	 * Journal</col>"} whose transcript key stores the colour as {@code <colNum0>}. Colours are templated to
	 * {@code <colNumN>}, looked up, then restored, so the rendered Chinese keeps the entity's colour. This is
	 * what {@code lookupRender}'s plain (colour-stripped) lookup misses. Lookup only, no AI.
	 *
	 * @return rendered char-image tags, or null if not in the tables.
	 */
	public String lookupRenderMenuOption(String option, int colorRgb, int size)
	{
		if (option == null || option.trim().isEmpty())
		{
			return null;
		}
		List<String> colors = Tags.colorTags(option);
		String zh = templateLookup(Tags.placeholdColors(option), MENU_ORDER);
		if (zh == null)
		{
			missing.record(Tags.stripTags(option).trim(), "interface", "", "");
			return null;
		}
		return glyph.toImgTags(Tags.restoreColors(zh, colors), colorRgb, 0, size);
	}

	// Tables tried, in order, for generic interface text (LVL_UP covers level-up chat messages).
	private static final TranslationStore.Category[] UI_ORDER = {
			TranslationStore.Category.INTERFACE, TranslationStore.Category.GAME_TEXT,
			TranslationStore.Category.LVL_UP, TranslationStore.Category.NAME,
			TranslationStore.Category.EXAMINE, TranslationStore.Category.AI_BAKED,
	};

	// Chat/game messages often come from dialogue captures even when shown in the chatbox.
	private static final TranslationStore.Category[] CHAT_ORDER = {
			TranslationStore.Category.DIALOGUE, TranslationStore.Category.GAME_TEXT,
			TranslationStore.Category.LVL_UP, TranslationStore.Category.INTERFACE,
			TranslationStore.Category.NAME, TranslationStore.Category.EXAMINE,
			TranslationStore.Category.AI_BAKED, TranslationStore.Category.DIALOGUE_EXPERIMENTAL,
	};

	/**
	 * Lookup-only render for interface widgets, trying several tables (interface labels, game text,
	 * names, examine, manual) so pages with mixed content (quests, spell info, ...) get covered.
	 * Numbers are templated to {@code <Num0>}, ... so a
	 * single table entry (e.g. {@code "Level <Num0><br>Sharp Eye<br>Increases ... <Num1>%."}) matches
	 * any concrete instance; the real numbers are substituted back into the translation. Colour tags
	 * are dropped first so their hex digits are not mistaken for numbers.
	 *
	 * @param aiFallback for descriptive boxes (spell/prayer/...), translate table-missing lines with
	 *                   the AI backend (async, cached). Off for general UI to avoid translating junk.
	 */
	public String lookupRenderUi(String text, int colorRgb, int maxChars, int size, boolean aiFallback)
	{
		Rendered r = renderUi(text, colorRgb, maxChars, size, aiFallback);
		return r == null ? null : r.text;
	}

	/** Result of {@link #renderUi}: the rendered text plus whether every translatable line is done. */
	public static final class Rendered
	{
		public final String text;
		public final boolean complete; // false if a real (wordy) line is still untranslated (AI pending)

		Rendered(String text, boolean complete)
		{
			this.text = text;
			this.complete = complete;
		}
	}

	/**
	 * Like {@link #lookupRenderUi} but also reports completeness. A multi-line info box whose AI lines
	 * are still pending renders partially and returns {@code complete == false}; the caller should keep
	 * re-translating it until complete, otherwise the box stays half-English forever (the partial would
	 * be cached and never upgraded once the AI results land).
	 */
	public Rendered renderUi(String text, int colorRgb, int maxChars, int size, boolean aiFallback)
	{
		return renderUi(text, colorRgb, maxChars, size, aiFallback, true);
	}

	/**
	 * @param persist whether AI translations of table-missing lines are written to the on-disk cache.
	 *                Player chat passes {@code false} (slang rarely recurs; keep it memory-only).
	 */
	public Rendered renderUi(String text, int colorRgb, int maxChars, int size, boolean aiFallback, boolean persist)
	{
		return renderWithOrder(text, colorRgb, maxChars, size, aiFallback, persist, UI_ORDER, "interface");
	}

	public Rendered renderChat(String text, int colorRgb, int maxChars, int size, boolean aiFallback, boolean persist)
	{
		// Game messages pass persist=true and are safe to collect; player chat passes persist=false
		// and must never be written to missing.tsv.
		return renderWithOrder(text, colorRgb, maxChars, size, aiFallback, persist, CHAT_ORDER, persist ? "gameText" : null);
	}

	private Rendered renderWithOrder(String text, int colorRgb, int maxChars, int size, boolean aiFallback, boolean persist,
			TranslationStore.Category[] order, String collectSource)
	{
		if (text == null || text.trim().isEmpty())
		{
			return null;
		}
		// Colours are looked up as <colNumN> placeholders (the data stores them that way) and restored to
		// real <col=..> tags afterwards, so the colour-aware glyph renderer can colour each segment.
		// First try the whole widget as one entry (multi-line info boxes like prayer/spell are combined).
		List<String> wholeColors = Tags.colorTags(text);
		String whole = lookupPeriodTolerant(Tags.placeholdColors(text), order);
		if (whole != null)
		{
			String img = glyph.toImgTags(Tags.restoreColors(whole, wholeColors), colorRgb, maxChars, size);
			return img == null ? null : new Rendered(img, true);
		}
		if (text.contains("<br>") || text.contains("<u") || text.contains("<str") || aiFallback)
		{
			// per-line: some widgets pack independent labels (e.g. chat tab "Game<br>On"); also lets the
			// AI fill individual lines a combined entry doesn't cover. Each line is placeheld/restored on
			// its own so colour indices match the data (which stores each entry from <colNum0>).
			String[] lines = text.split("<br>", -1);
			StringBuilder sb = new StringBuilder();
			boolean any = false;
			boolean all = true;
			for (int i = 0; i < lines.length; i++)
			{
				if (i > 0)
				{
					sb.append("<br>");
				}
				String line = lines[i];
				List<String> lineColors = Tags.colorTags(line);
				String lzh = lookupPeriodTolerant(Tags.placeholdColors(line), order);
				if (lzh == null)
				{
					// Styled link/quest lines (<u=..>Quest</u>) and struck items are stored plain in the
					// tables; strip styling, match the plain key, and recolour with the line's own colour.
					String plain = Tags.stripStyle(line);
					if (!plain.equals(line) && !plain.trim().isEmpty())
					{
						String pz = lookupPeriodTolerant(plain, order);
						if (pz != null)
						{
							// A single colour tag colours the whole line (quest links etc.); several tags
							// mean highlighted words in base-coloured text - painting everything with the
							// first highlight looks wrong, keep the base colour instead.
							int wrap = lineColors.size() > 1 ? colorRgb : Tags.firstColor(line, colorRgb);
							lzh = "<col=" + Tags.hex(wrap) + ">" + pz + "</col>";
						}
					}
				}
				if (lzh == null && aiFallback)
				{
					lzh = aiLine(Tags.stripCol(line), persist);
				}
				if (lzh != null)
				{
					sb.append(Tags.restoreColors(lzh, lineColors));
					any = true;
				}
				else
				{
					sb.append(line); // leave the original (with real colours) so English stays coloured
					if (WORDY.matcher(Tags.stripCol(line)).find())
					{
						all = false; // a real line is still untranslated (table miss + AI not ready)
						if (collectSource != null)
						{
							missing.record(Tags.stripTags(line).trim(), collectSource, "", "");
						}
					}
				}
			}
			if (!any)
			{
				return null;
			}
			String img = glyph.toImgTags(sb.toString(), colorRgb, maxChars, size);
			return img == null ? null : new Rendered(img, all);
		}
		if (collectSource != null && !text.contains("<br>"))
		{
			missing.record(Tags.stripTags(text).trim(), collectSource, "", "");
		}
		return null;
	}

	// Tables and live messages disagree on trailing punctuation ("You catch some raw shrimps" in the
	// table vs "...shrimps." in game), so on a miss retry once with it stripped, or with '.' added.
	private String lookupPeriodTolerant(String key, TranslationStore.Category[] order)
	{
		String zh = templateLookup(key, order);
		if (zh != null)
		{
			return zh;
		}
		String t = key.trim();
		if (t.isEmpty())
		{
			return null;
		}
		char last = t.charAt(t.length() - 1);
		if ((last == '.' || last == '!' || last == '?') && !t.endsWith(".."))
		{
			return templateLookup(t.substring(0, t.length() - 1), order);
		}
		if (Character.isLetterOrDigit(last))
		{
			return templateLookup(t + ".", order);
		}
		return null;
	}

	/** AI-translate one line, but only if it is real prose (skip numbers/symbols like "0/1"). */
	private String aiLine(String line, boolean persist)
	{
		String t = line.trim();
		if (!WORDY.matcher(t).find())
		{
			return null;
		}
		return aiTranslate(t, true, persist);
	}

	private String aiTranslate(String query, boolean protectPlayerNames)
	{
		return aiTranslate(query, protectPlayerNames, true);
	}

	private String aiTranslate(String query, boolean protectPlayerNames, boolean persist)
	{
		ProtectedText p = protectPlayerNames ? protectDynamicPlayerNames(query) : new ProtectedText(query);
		String zh = ai.translate(TranslationStore.normalize(p.text), persist);
		return zh == null ? null : p.restore(zh);
	}

	private ProtectedText protectDynamicPlayerNames(String text)
	{
		ProtectedText p = new ProtectedText(text);
		for (String name : currentPlayerNames())
		{
			p.protectName(name);
		}
		for (String name : topThreeNames(text))
		{
			p.protectName(name);
		}
		for (String name : topCrabName(text))
		{
			p.protectName(name);
		}
		return p;
	}

	private List<String> currentPlayerNames()
	{
		List<String> names = new ArrayList<>();
		if (client.getLocalPlayer() != null)
		{
			addName(names, client.getLocalPlayer().getName());
		}
		List<Player> players = client.getPlayers();
		if (players == null)
		{
			return names;
		}
		for (Player p : players)
		{
			if (p != null)
			{
				addName(names, p.getName());
			}
		}
		names.sort(Comparator.comparingInt(String::length).reversed());
		return names;
	}

	private static void addName(List<String> names, String name)
	{
		if (name == null)
		{
			return;
		}
		String n = name.trim();
		if (!n.isEmpty() && !names.contains(n))
		{
			names.add(n);
		}
	}

	private static List<String> topThreeNames(String text)
	{
		List<String> out = new ArrayList<>();
		Matcher m = TOP_THREE_WERE.matcher(text);
		if (!m.find())
		{
			return out;
		}
		for (String part : m.group(2).split("\\s*(?:,|&|\\band\\b)\\s*"))
		{
			String name = part.trim();
			if (looksLikePlayerName(name))
			{
				addName(out, name);
			}
		}
		return out;
	}

	private static List<String> topCrabName(String text)
	{
		List<String> out = new ArrayList<>(1);
		Matcher m = TOP_CRAB_WAS.matcher(text);
		if (m.find())
		{
			String name = m.group(1).trim();
			if (looksLikePlayerName(name))
			{
				out.add(name);
			}
		}
		return out;
	}

	private static boolean looksLikePlayerName(String name)
	{
		int len = name.length();
		return len >= 1 && len <= 12
				&& name.matches("[A-Za-z0-9 _-]+")
				&& Pattern.compile("[A-Za-z]").matcher(name).find();
	}

	private static final class ProtectedText
	{
		private final Map<String, String> replacements = new LinkedHashMap<>();
		private String text;

		private ProtectedText(String text)
		{
			this.text = text;
		}

		private void protectName(String name)
		{
			if (name == null || name.isEmpty())
			{
				return;
			}
			String placeholder = placeholderFor(name);
			Pattern p = Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(name) + "(?![A-Za-z0-9_])");
			Matcher m = p.matcher(text);
			StringBuffer sb = new StringBuffer();
			boolean found = false;
			while (m.find())
			{
				found = true;
				m.appendReplacement(sb, Matcher.quoteReplacement(placeholder));
			}
			if (found)
			{
				m.appendTail(sb);
				text = sb.toString();
				replacements.putIfAbsent(placeholder, name);
			}
		}

		private String placeholderFor(String name)
		{
			for (Map.Entry<String, String> e : replacements.entrySet())
			{
				if (e.getValue().equals(name))
				{
					return e.getKey();
				}
			}
			return "<osrscn-name-" + replacements.size() + ">";
		}

		private String restore(String zh)
		{
			String out = zh;
			for (Map.Entry<String, String> e : replacements.entrySet())
			{
				out = out.replace(e.getKey(), e.getValue());
			}
			return out;
		}
	}

	/**
	 * Number-templated table lookup; returns the raw Chinese (numbers substituted back) or null. The
	 * input may carry {@code <colNumN>} colour placeholders; numbers inside any {@code <...>} tag are
	 * left alone so those placeholders survive.
	 */
	private String templateLookup(String text, TranslationStore.Category[] order)
	{
		if (text.trim().isEmpty())
		{
			return null;
		}
		String playerList = playerListTemplateLookup(text);
		if (playerList != null)
		{
			return playerList;
		}
		List<String> nums = new ArrayList<>();
		String tpl = templatizeNumbers(text, nums);
		// Price lines render from the name table first, so every item is consistent (and beats the older
		// per-item cache entries with mixed wording).
		String zh = priceLineLookup(tpl, order);
		if (zh == null)
		{
			zh = lookupAnyOf(tpl, order);
		}
		if (zh == null)
		{
			zh = syntheticLookup(tpl, order);
		}
		if (zh == null && !nums.isEmpty())
		{
			zh = lookupAnyOf(text, order); // some entries are stored without number placeholders
			if (zh == null)
			{
				zh = syntheticLookup(text, order);
			}
		}
		if (zh == null)
		{
			return null;
		}
		for (int k = 0; k < nums.size(); k++)
		{
			zh = zh.replace("<Num" + k + ">", nums.get(k));
		}
		return zh;
	}

	/**
	 * Render an examine "Price of <item>: GE average <Num0> HA value <Num1>" line from the name table:
	 * the item name is looked up (incl. a leading "<Num> x " quantity), GE/HA wording is fixed, and the
	 * number placeholders are kept for the caller to substitute back. Returns null for non-price lines or
	 * when the item is not in the tables (so the caller falls back to a cached entry or the AI). Ground-item
	 * overhead prices are drawn by another plugin's overlay and are out of scope here.
	 */
	private String priceLineLookup(String text, TranslationStore.Category[] order)
	{
		String plain = Tags.stripColorPlaceholders(text);
		Matcher m = PRICE_LINE.matcher(plain);
		if (!m.matches())
		{
			return null;
		}
		String item = m.group(1).trim();
		String details = m.group(2).trim();
		String qty = null;
		Matcher q = PRICE_QTY.matcher(item);
		if (q.matches())
		{
			qty = q.group(1);
			item = q.group(2).trim();
		}
		String nameZh = lookupAnyOf(item, order);
		if (nameZh == null)
		{
			return null;
		}
		StringBuilder sb = new StringBuilder();
		if (qty != null)
		{
			sb.append(qty).append(" × ");
		}
		sb.append(nameZh).append("价格：");
		boolean any = false;
		Matcher ge = GE_AVERAGE.matcher(details);
		if (ge.find())
		{
			sb.append("GE 均价 ").append(ge.group(1));
			any = true;
		}
		Matcher ha = HA_VALUE.matcher(details);
		if (ha.find())
		{
			if (any)
			{
				sb.append('，');
			}
			sb.append("HA 估值 ").append(ha.group(1));
			any = true;
		}
		Matcher ea = PRICE_EACH.matcher(details);
		if (ea.find())
		{
			sb.append("（每个 ").append(ea.group(1)).append('）');
		}
		return any ? sb.toString() : null;
	}

	private String syntheticLookup(String text, TranslationStore.Category[] order)
	{
		Matcher members = MEMBERS_LINE.matcher(text);
		if (members.matches())
		{
			String name = Tags.stripColorPlaceholders(members.group(1)).trim();
			String zhName = lookupAnyOf(name, order);
			return zhName == null ? null : "会员：" + zhName;
		}

		Matcher monster = MONSTER_LINE.matcher(text);
		if (monster.matches())
		{
			String name = Tags.stripColorPlaceholders(monster.group(1)).trim();
			String zhName = lookupAnyOf(name, order);
			return zhName == null ? null : "怪物：" + zhName;
		}

		Matcher requires = REQUIRES_LINE.matcher(text);
		if (!requires.matches())
		{
			return null;
		}
		String[] parts = requires.group(1).split("\\s*,\\s*");
		StringBuilder sb = new StringBuilder("需要：");
		boolean translatedAny = false;
		for (int i = 0; i < parts.length; i++)
		{
			if (i > 0)
			{
				sb.append('，');
			}
			String part = parts[i].trim();
			String translated = translateRequirementPart(part, order);
			if (translated != null)
			{
				sb.append(translated);
				translatedAny = true;
			}
			else
			{
				sb.append(part);
			}
		}
		return translatedAny ? sb.toString() : null;
	}

	private String translateRequirementPart(String part, TranslationStore.Category[] order)
	{
		Matcher level = LEVEL_REQUIREMENT.matcher(part);
		if (level.matches())
		{
			String skill = Tags.stripColorPlaceholders(level.group(3)).trim();
			String zhSkill = lookupAnyOf(skill, order);
			if (zhSkill == null)
			{
				return null;
			}
			String open = level.group(1) == null ? "" : level.group(1);
			String close = level.group(4) == null ? "" : level.group(4);
			return open + level.group(2) + "级" + zhSkill + close;
		}

		String clean = Tags.stripColorPlaceholders(part).trim();
		String zh = lookupAnyOf(clean, order);
		return zh == null ? null : zh;
	}

	// "<level> <skill>" inside a diary requirement, e.g. "65 Slayer" -> "65级杀戮者".
	private static final Pattern REQ_SEG_LEVEL = Pattern.compile("^(\\d+)\\s+(.+)$");

	/**
	 * Translate a whole reconstructed journal task (prose, usually not in any table) to plain Chinese:
	 * the translation table first (so a hand-fixed entry wins), then the AI backend when {@code aiFallback}
	 * is on. Returns {@code null} when not translatable right now (table miss + AI off, or AI pending), so
	 * the caller leaves the task in English. Used by the experimental achievement/quest journal rebuild,
	 * which renders the colour-carrying requirement separately (see {@link #translateReqSegment}).
	 */
	public String translateJournalSentence(String plain, boolean aiFallback)
	{
		if (plain == null || plain.trim().isEmpty())
		{
			return "";
		}
		String zh = templateLookup(plain, UI_ORDER);
		if (zh != null)
		{
			return zh;
		}
		return aiFallback ? aiLine(plain, true) : null;
	}

	// Sentinel: this requirement segment carries a real name that the AI is still translating, so the
	// whole task should be retried next tick rather than rendered half-translated.
	public static final String REQ_PENDING = " PENDING ";

	/**
	 * Translate one diary requirement segment ("65 Slayer", "Started Desert Treasure I", "Eagles' Peak")
	 * to Chinese, preserving any leading/trailing spaces. Skills become "&lt;level&gt;级&lt;skill&gt;".
	 * Names come from the tables first; missing names fall back to the AI backend when {@code aiFallback}
	 * is on. Returns the segment unchanged when it is punctuation, or a name that can't be translated
	 * (AI off / not in tables); returns {@link #REQ_PENDING} when a name's AI translation is still in
	 * flight, so the caller can retry instead of showing English.
	 */
	public String translateReqSegment(String seg, boolean aiFallback)
	{
		if (seg == null || seg.isEmpty())
		{
			return seg;
		}
		int a = 0;
		int b = seg.length();
		while (a < b && seg.charAt(a) == ' ')
		{
			a++;
		}
		while (b > a && seg.charAt(b - 1) == ' ')
		{
			b--;
		}
		String core = seg.substring(a, b);
		if (core.isEmpty())
		{
			return seg;
		}
		String zh = translateReqCore(core, aiFallback);
		//noinspection StringEquality
		if (zh == REQ_PENDING)
		{
			return REQ_PENDING;
		}
		return zh == null ? seg : seg.substring(0, a) + zh + seg.substring(b);
	}

	/** @return Chinese, {@code null} to leave {@code core} as-is, or {@link #REQ_PENDING} if AI in flight. */
	private String translateReqCore(String core, boolean aiFallback)
	{
		Matcher level = REQ_SEG_LEVEL.matcher(core);
		if (level.matches())
		{
			String skill = level.group(2).trim();
			String zhSkill = reqName(skill, aiFallback);
			//noinspection StringEquality
			if (zhSkill == REQ_PENDING)
			{
				return REQ_PENDING;
			}
			return zhSkill == null ? null : level.group(1) + "级" + zhSkill;
		}
		if (core.startsWith("Started "))
		{
			String quest = core.substring("Started ".length()).trim();
			String zhQuest = reqName(quest, aiFallback);
			//noinspection StringEquality
			if (zhQuest == REQ_PENDING)
			{
				return REQ_PENDING;
			}
			return zhQuest == null ? null : "开始" + zhQuest;
		}
		return reqName(core, aiFallback);
	}

	/** Look a requirement name up in the tables, then AI; {@link #REQ_PENDING} while AI is in flight. */
	private String reqName(String name, boolean aiFallback)
	{
		String zh = lookupAnyOf(name, UI_ORDER);
		if (zh != null)
		{
			return zh;
		}
		if (aiFallback && WORDY.matcher(name).find())
		{
			String ai = aiLine(name, true);
			return ai != null ? ai : REQ_PENDING;
		}
		return null; // punctuation, or no AI: leave as-is
	}

	/**
	 * Replace numbers with {@code <Num0>}, {@code <Num1>}, ... but skip over {@code <...>} tags, so colour
	 * placeholders ({@code <colNum0>}) and {@code <br>} keep their digits.
	 */
	private static String templatizeNumbers(String s, List<String> numsOut)
	{
		StringBuilder out = new StringBuilder(s.length() + 8);
		int i = 0;
		int n = s.length();
		while (i < n)
		{
			char c = s.charAt(i);
			if (c == '<')
			{
				int end = s.indexOf('>', i);
				if (end != -1)
				{
					out.append(s, i, end + 1);
					i = end + 1;
					continue;
				}
			}
			if (Character.isDigit(c))
			{
				Matcher m = NUMBER.matcher(s);
				if (m.find(i) && m.start() == i)
				{
					out.append("<Num").append(numsOut.size()).append('>');
					numsOut.add(m.group());
					i = m.end();
					continue;
				}
			}
			out.append(c);
			i++;
		}
		return out.toString();
	}

	private String playerListTemplateLookup(String text)
	{
		Matcher m = TOP_THREE_WERE.matcher(text);
		if (m.find())
		{
			List<String> names = topThreeNames(text);
			if (names.size() == 3)
			{
				String query = m.replaceFirst(Matcher.quoteReplacement(
						m.group(1) + "[player 1], [player 2], & [player 3]" + m.group(3)));
				String zh = store.lookupAny(query);
				if (zh != null)
				{
					for (int i = 0; i < names.size(); i++)
					{
						String name = names.get(i);
						int n = i + 1;
						zh = zh.replace("[player " + n + "]", name).replace("[player" + n + "]", name);
					}
					return zh;
				}
			}
		}
		m = TOP_CRAB_WAS.matcher(text);
		if (!m.find())
		{
			return null;
		}
		String name = m.group(1).trim();
		if (!looksLikePlayerName(name))
		{
			return null;
		}
		String zh = store.lookupAny("The top crab crusher was [player]" + m.group(2));
		return zh == null ? null : zh.replace("[player]", name);
	}

	// Lowest-priority sweep when the scene's own order misses every table, so a translation filed under a
	// category that order doesn't list still resolves instead of being lost forever. Dialogue tables stay
	// last (experimental dead last) so any curated table the scene already tried keeps priority.
	private static final TranslationStore.Category[] FALLBACK_ALL = {
			TranslationStore.Category.NAME, TranslationStore.Category.INTERFACE,
			TranslationStore.Category.GAME_TEXT, TranslationStore.Category.EXAMINE,
			TranslationStore.Category.AI_BAKED, TranslationStore.Category.LVL_UP,
			TranslationStore.Category.ACTIONS, TranslationStore.Category.INVENTORY_ACTIONS,
			TranslationStore.Category.DIALOGUE, TranslationStore.Category.DIALOGUE_EXPERIMENTAL,
	};

	private static boolean inOrder(TranslationStore.Category[] order, TranslationStore.Category c)
	{
		for (TranslationStore.Category o : order)
		{
			if (o == c)
			{
				return true;
			}
		}
		return false;
	}

	private String lookupAnyOf(String key, TranslationStore.Category[] order)
	{
		String k = TranslationStore.normalize(key); // normalize once, not per category
		for (TranslationStore.Category c : order)
		{
			String zh = store.lookupNormalized(c, k);
			if (zh != null)
			{
				return zh;
			}
		}
		// P4: case-insensitive fallback (game text capitalises names the table stores lower-cased)
		String lower = k.toLowerCase();
		for (TranslationStore.Category c : order)
		{
			String zh = store.lookupLower(c, lower);
			if (zh != null)
			{
				return zh;
			}
		}
		// Universal last resort: try every category the scene's order skipped (lowest priority).
		for (TranslationStore.Category c : FALLBACK_ALL)
		{
			if (inOrder(order, c))
			{
				continue;
			}
			String zh = store.lookupNormalized(c, k);
			if (zh == null)
			{
				zh = store.lookupLower(c, lower);
			}
			if (zh != null)
			{
				return zh;
			}
		}
		return null;
	}

	public void clearAiCache()
	{
		ai.clearCache();
	}

	private String playerName()
	{
		if (client.getLocalPlayer() != null)
		{
			return client.getLocalPlayer().getName();
		}
		return null;
	}
}
