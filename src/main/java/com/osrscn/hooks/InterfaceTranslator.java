package com.osrscn.hooks;

import com.osrscn.glyph.GlyphService;
import com.osrscn.translate.Translator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetTextAlignment;

/**
 * Translates text in interface "pages" (skills, quests, settings, equipment, etc.).
 *
 * <p>Generic by design: it walks the widget tree of each open interface group and only replaces
 * text that is found in the interface table. Because that table holds real interface labels, the
 * lookup itself scopes what gets translated - arbitrary text (names, numbers) simply misses and is
 * left alone. Dialogue/chat groups are excluded since {@link DialogueHandler} owns them.
 */
@Slf4j
@Singleton
public class InterfaceTranslator
{
	private static final int WRAP_PAD = 8;
	private static final int MIN_WRAP = 6; // don't auto-wrap widgets narrower than this many glyphs
	// chat-tab labels render a touch smaller than normal UI text: the name pins to the top and the
	// On/Off filter (smaller still) tucks against the bottom, so the two glyph lines stay well apart.
	private static final int CHAT_TAB_NAME_SIZE = 10;
	private static final int CHAT_TAB_FILTER_SIZE = 9;

	// All per-group translation policy (excluded / small / per-frame-excluded / reconstruct / no-AI) and the
	// build-script hooks now live in one declarative table; see SurfaceRegistry. The chat box's message
	// lines are excluded there; its static tab labels are translated separately by translateChatTabs().
	private final SurfaceRegistry registry = SurfaceRegistry.build();

	private static final Set<Integer> CHAT_TAB_WIDGETS = new HashSet<>();
	// the On/Off/Filter/Hide indicator widgets: right-aligned in our horizontal layout (see
	// translateChatTabs). Everything else in CHAT_TAB_WIDGETS is a tab-name widget (left-aligned).
	private static final Set<Integer> CHAT_TAB_FILTERS = new HashSet<>();

	static
	{
		CHAT_TAB_FILTERS.add(InterfaceID.Chatbox.CHAT_GAME_FILTER);
		CHAT_TAB_FILTERS.add(InterfaceID.Chatbox.CHAT_PUBLIC_FILTER);
		CHAT_TAB_FILTERS.add(InterfaceID.Chatbox.CHAT_PRIVATE_FILTER);
		CHAT_TAB_FILTERS.add(InterfaceID.Chatbox.CHAT_FRIENDSCHAT_FILTER);
		CHAT_TAB_FILTERS.add(InterfaceID.Chatbox.CHAT_CLAN_FILTER);
		CHAT_TAB_FILTERS.add(InterfaceID.Chatbox.CHAT_TRADE_FILTER);

		CHAT_TAB_WIDGETS.add(InterfaceID.Chatbox.CHAT_ALL_TEXT1);
		CHAT_TAB_WIDGETS.add(InterfaceID.Chatbox.CHAT_GAME_TEXT1);
		CHAT_TAB_WIDGETS.add(InterfaceID.Chatbox.CHAT_PUBLIC_TEXT1);
		CHAT_TAB_WIDGETS.add(InterfaceID.Chatbox.CHAT_PRIVATE_TEXT1);
		CHAT_TAB_WIDGETS.add(InterfaceID.Chatbox.CHAT_FRIENDSCHAT_TEXT1);
		CHAT_TAB_WIDGETS.add(InterfaceID.Chatbox.CHAT_CLAN_TEXT1);
		CHAT_TAB_WIDGETS.add(InterfaceID.Chatbox.CHAT_TRADE_TEXT);
		CHAT_TAB_WIDGETS.addAll(CHAT_TAB_FILTERS);
	}

	@Inject
	private Client client;
	@Inject
	private Translator translator;
	@Inject
	private GlyphService glyph;
	@Inject
	private com.osrscn.OsrscnConfig config;

	// keyed by id+index: dynamic children (skill/quest lists) share one id, so id alone collides
	private final Map<Long, String> lastSet = new HashMap<>();
	private final Map<Long, Integer> lastColor = new HashMap<>(); // colour baked into the glyphs, for hover re-render
	private final Map<Long, String> original = new HashMap<>(); // -> original text, for instant restore
	// per journal root: the raw slot text the last tick saw, to debounce the client's multi-stage population
	private final Map<Integer, String> journalSig = new HashMap<>();
	// widgets we translated only partially (some AI lines pending): re-translate until complete
	private final Set<Long> incomplete = new HashSet<>();
	// prose slots (860) whose position we changed while compacting: original x/y to revert on restore/decommit
	private final Map<Long, Integer> movedX = new HashMap<>();
	private final Map<Long, Integer> movedY = new HashMap<>();
	// native width before we widened a slot: without reverting it, the next layout's column measurement
	// reads our own widened slots and the column inflates a little more on every re-layout / tab switch
	private final Map<Long, Integer> movedW = new HashMap<>();
	// where WE put each moved widget: live != placed means the client repositioned it (repopulation evidence),
	// and live == placed corroborates the geometry is still ours and safe to revert
	private final Map<Long, Integer> placedX = new HashMap<>();
	private final Map<Long, Integer> placedY = new HashMap<>();
	// chat tab labels are handled explicitly by component id (the generic walk's index-based key proved
	// unreliable for them across re-translation, leaving them stuck in Chinese on an English toggle).
	private final Map<Integer, String> tabOriginal = new HashMap<>();
	// original X/Y text alignment per tab widget, captured before we pin name=top / filter=bottom
	private final Map<Integer, Integer> tabAlignX = new HashMap<>();
	private final Map<Integer, Integer> tabAlignY = new HashMap<>();

	private static long widgetKey(Widget w)
	{
		return ((long) w.getId() << 21) | (w.getIndex() & 0x1FFFFF);
	}

	/** Re-translate every loaded interface root. Lookup self-scopes (only table hits are replaced). */
	public void translateOpen()
	{
		scan(false);
		translateChatTabs();
		reconstructJournals();
		reconstructWordSplit(); // PROTOTYPE Phase 3: new-style (word-per-widget) skill guide, group 860
	}

	// ===== Experimental whole-task journal translation (config.reconstructJournals) =====

	/**
	 * Translate the achievement diary / quest journal as whole tasks instead of per wrapped line.
	 *
	 * <p>The client hard-wraps each task across several fixed-height line slots, so the generic scan only
	 * ever sees half-sentences. Here we rebuild each task from its slots ({@link JournalReconstructor}),
	 * translate the whole task, then re-flow the rendered Chinese back across those same slots (one
	 * wrapped line per slot, blanking any left over). Reconstruction reads each slot's <em>current</em>
	 * text: once a task is translated its slots hold char-images / blanks, which {@code reconstruct()}
	 * classifies as structural, so a translated task is naturally skipped next tick (idempotent, needs no
	 * extra state, and a different diary's fresh English re-translates cleanly). Untranslatable tasks
	 * (table miss + AI off/pending) are left in English, exactly as the generic scan would.
	 */
	private void reconstructJournals()
	{
		if (!config.reconstructJournals())
		{
			return;
		}
		reconstructRoot(InterfaceID.Journalscroll.UNIVERSE);
		reconstructRoot(InterfaceID.Questjournal.UNIVERSE);
	}

	private static final int SKILL_GUIDE_NEW_GROUP = 860;
	private static final int PROSE_CHILD = 0x0e; // the guide's description column (word-per-widget prose)
	private static final int PROSE_COMPONENT_ID = (SKILL_GUIDE_NEW_GROUP << 16) | PROSE_CHILD;
	private static final int PROSE_SCROLLBAR_ID = (SKILL_GUIDE_NEW_GROUP << 16) | 0x10; // its scrollbar
	private String lastProseSig = ""; // debounce: only reflow the prose once its raw text settles
	private String committedSig = null; // the effective-English frame we last fully laid out (skip if unchanged)
	// Collection settle gate (2026-07-16 council action 6): missing rows and AI disk-cache writes only
	// happen once the prose signature has held still this many consecutive ticks (~300ms). Frames still
	// translate and render immediately - mid-relayout scrambles just never persist anywhere.
	private static final int COLLECT_SETTLE_TICKS = 15;
	private int settleTicks;
	private boolean collectOk;
	private final List<String> pendingCollect = new ArrayList<>(); // keys laid before settle; collected once stable
	private int gatedRuns; // debug: runs translated while the gate was closed (undercollection stays visible)
	private Integer proseNativeScrollHeight = null; // container scrollHeight before we shrank it
	private int appliedScrollHeight = -1; // what we last set; -2 = gave up (a clientscript keeps fighting us)
	private int scrollFights = 0;
	private String failedSig = null; // frame that last failed layout (translation pending): retry throttle
	private int pendingCooldown = 0;
	private boolean pendingHasAi = false; // last incomplete pass waited on AI (throttle) vs glyph upload (don't)
	private int sigTick = 0; // committed-state signature is only rebuilt every 8th tick (cost control)

	/**
	 * Reflow the new-style skill guide (group 860), whose prose is split one word per widget, into readable
	 * Chinese. Title, tabs and item/quest lists are whole labels translated in place ({@link #translateLabel});
	 * the prose column (child {@code 0x0e}) is reflowed by {@link #reflowProse}, which pins each translated
	 * line to the client's own line grid so fixed-position sprite icons stay put and inline links stay
	 * clickable. Gated on {@code skillGuideOverlay}.
	 */
	private void reconstructWordSplit()
	{
		if (!config.skillGuideOverlay())
		{
			return;
		}
		Widget root = client.getWidget(SKILL_GUIDE_NEW_GROUP, 0);
		if (root == null || root.isHidden())
		{
			return;
		}
		Widget container = client.getWidget(SKILL_GUIDE_NEW_GROUP, PROSE_CHILD);
		if (container == null)
		{
			return; // interface mid-close
		}
		List<Widget> slots = new ArrayList<>();
		collectTextSlots(root, slots);
		// The description column (widget child 0x0e) is real prose split one word per widget: reflow it
		// (icons, inline links and headers inside it are handled by reflowProse). Everything else - title,
		// tabs, and the item / quest name lists - is a complete label per widget, so translate those in
		// place (setText keeps the widget and its click target, so tabs stay clickable).
		List<Widget> prose = new ArrayList<>();
		for (Widget w : slots)
		{
			if ((w.getId() & 0xFFFF) == PROSE_CHILD)
			{
				// Only the container's DIRECT children take part in the flow. The wiki banner is a type-0
				// sub-layer child whose own children (caps/bars/badge/text) carry coordinates relative to
				// the LAYER (0..20) - flowed raw they shredded paragraph one. The banner moves as one unit.
				if (w.getParent() == container && flowMember(w))
				{
					prose.add(w);
				}
			}
			else
			{
				translateLabel(w);
			}
		}
		// The prose column also holds TEXTLESS children - sprite icons and horizontal divider lines
		// (getText()==null, so collectTextSlots skips them). They are part of the reading flow and must be
		// repositioned with the text, so collect them here.
		Widget[] kids = container.getDynamicChildren();
		if (kids != null)
		{
			for (Widget w : kids)
			{
				if (w == null)
				{
					continue;
				}
				if (w.getText() == null && w.getSpriteId() > 0 && flowMember(w))
				{
					prose.add(w);
				}
				// A sub-layer (the wiki banner) stays out of the flow, but its own label ("View X on the
				// Wiki") is translated in place - text only, geometry untouched, so the banner keeps its
				// centring and click behaviour.
				if (w.getType() == 0 && !w.isSelfHidden())
				{
					for (Widget[] group : new Widget[][]{
							w.getDynamicChildren(), w.getStaticChildren(), w.getNestedChildren()})
					{
						if (group == null)
						{
							continue;
						}
						for (Widget c : group)
						{
							if (c != null && c.getText() != null && !c.getText().isEmpty())
							{
								translateLabel(c);
							}
						}
					}
				}
			}
		}
		reflowProse(container, prose);
	}

	// The wiki banner's frame furniture: end caps (917/919, 9x16), long bars (918/920, 173x9) and the WIKI
	// badge (2420, 40x14). They are absolutely positioned at y=0..20 (the client places the drawn banner
	// itself) yet render nowhere near there, so flowing them shredded paragraph one with invisible spacers.
	// The banner's functional part - the "View X on the Wiki" icon+link row - is separate and flows fine.
	private static final Set<Integer> BANNER_SPRITES = new HashSet<>(java.util.Arrays.asList(917, 918, 919, 920, 2420));

	/**
	 * True when a prose-column child takes part in the reading flow. Excluded: hidden slots, pool widgets
	 * still parked at (0,0), and - crucially - anything not ABSOLUTELY positioned. The wiki banner's frame
	 * (side caps, 173px bars, the WIKI badge and its text) is anchored to the container's bottom, so its
	 * raw coordinates read 0..20 and once sorted the whole banner shredded itself into paragraph one. The
	 * client keeps anchored widgets placed correctly on its own, including after we shrink the scroll area.
	 */
	private boolean flowMember(Widget w)
	{
		return !w.isSelfHidden() && !parkedAtOrigin(w)
				&& w.getXPositionMode() == 0 && w.getYPositionMode() == 0
				// banner furniture carries empty TEXT, so it arrives through the text walk - the sprite-id
				// quarantine must sit here on the shared path, not in the textless-sprite loop
				&& !BANNER_SPRITES.contains(w.getSpriteId());
	}

	/**
	 * True for a pool widget still parked at (0,0): the client creates children there and positions them a
	 * step later. Laying one out would prepend it to the flow (real prose starts at x/y >= 2). The ledger is
	 * consulted so a widget WE moved - whose live position is ours, not the pool's - is never mistaken for
	 * parked.
	 */
	private boolean parkedAtOrigin(Widget w)
	{
		return nativeX(w) == 0 && nativeY(w) == 0;
	}

	/** In-place whole-label translation of one widget (title / tab / item / quest name), keeping it clickable. */
	private void translateLabel(Widget w)
	{
		String t = w.getText();
		if (t == null || t.isEmpty() || t.contains("<img="))
		{
			return;
		}
		long key = widgetKey(w);
		if (t.equals(lastSet.get(key)))
		{
			return;
		}
		int size = glyph.uiSize();
		int maxChars = glyph.wrapChars(w.getWidth() - WRAP_PAD, size);
		if (maxChars < MIN_WRAP)
		{
			maxChars = 0; // short labels (tabs) shouldn't force-wrap
		}
		// Full interface render: splits <br> (item name + "Requires:" line), looks each line up, and keeps
		// the <col> requirement colour - item / quest names sit in the tables like any other UI text.
		Translator.Rendered r = translator.renderUi(t, w.getTextColor(), maxChars, size, config.aiFillInterface());
		if (r == null)
		{
			return;
		}
		original.put(key, t);
		w.setText(r.text);
		w.setTextShadowed(false);
		if (r.text.contains("<br>"))
		{
			w.setLineHeight(glyph.glyphHeight(size));
		}
		lastSet.put(key, r.text);
	}

	private enum ProseKind
	{
		WORD,   // translatable prose word
		ICON,   // game sprite / divider line - flows as inline or block decoration
		LINK,   // clickable cross-reference (<u=ffff00>...</u>) - translate in place, keep the widget
		HITBOX, // transparent rectangle carrying a link's click listener: moved to wherever its link goes
		HEADER, // white section title (e.g. "Getting Started")
		OURS    // a slot we already translated or blanked this session (idempotent skip)
	}

	/**
	 * Classify one prose slot. Widget TYPE comes first: the real 860 dump shows sprites/dividers/hitboxes
	 * all carry an EMPTY text (not null), so a text-based check would misfile them as spacers - the exact
	 * bug that left icons stranded under the compacted text. Types: 3=rectangle (the invisible click hitbox
	 * of a link, opacity 255 + listener), 5=graphic (icon sprite), 9=line (divider).
	 */
	private ProseKind classify(Widget w)
	{
		int type = w.getType();
		if (type == 5 || type == 9)
		{
			return ProseKind.ICON;
		}
		if (type == 3)
		{
			// Only a LINE-SIZED rectangle is a link hitbox. The container also holds a huge transparent
			// rectangle (full-panel click zone, y=0): row-matching against it once backed the entire first
			// text row as "links" and shattered the paragraph into yellow fragments. Never touch it.
			return w.getHeight() <= 24 && w.getWidth() <= 250 ? ProseKind.HITBOX : ProseKind.OURS;
		}
		long key = widgetKey(w);
		String cur = w.getText();
		if (cur == null)
		{
			return ProseKind.ICON;
		}
		if (cur.equals(lastSet.get(key)))
		{
			return ProseKind.OURS; // exactly our char-image head or our blank
		}
		if (cur.isEmpty())
		{
			return ProseKind.OURS; // client spacer / already blank
		}
		if (cur.contains("<img="))
		{
			return ProseKind.ICON;
		}
		// A link is underlined or (yellow AND actually clickable). A merely-yellow word with no listener is
		// prose emphasis: keep it in the word run so the sentence translates whole instead of fragmenting.
		if (cur.contains("<u=") || ((w.getTextColor() & 0xFFFFFF) == 0xffff00 && w.hasListener()))
		{
			return ProseKind.LINK;
		}
		if ((w.getTextColor() & 0xFFFFFF) == 0xffffff)
		{
			return ProseKind.HEADER;
		}
		return ProseKind.WORD;
	}

	/**
	 * Reflow the word-per-widget description column (child {@code 0x0e}) into compact readable Chinese by
	 * treating the whole column as one inline flow (like an HTML paragraph engine) laid back onto the native
	 * widgets. Reading order (sort by y then x) is walked with a running cursor (cx,cy): a run of words is
	 * translated whole and its Chinese is poured across lines one-line-per-widget (never folding many lines
	 * into one widget, which garbled before); a sprite icon is repositioned inline to the cursor so it flows
	 * with the text it annotates (never left at its English x, which floated before); an inline link is
	 * rendered yellow into its own widget (click listener kept) at the cursor; a white header breaks the
	 * line. Because Chinese is denser the flow always ends higher than the English did, so it compacts with
	 * no blank gaps and nothing overlaps. Everything moved records its native x/y for restore.
	 *
	 * <p>Each pass first restores our slots to English (so it re-lays from a clean frame in one tick, no
	 * flicker) then re-flows; it only runs once the effective English is stable two ticks and stops once a
	 * complete pass is committed. If the client rewrites a slot we own (tab switch) we drop our state and
	 * wait for the rebuild to settle.
	 */
	private void reflowProse(Widget container, List<Widget> prose)
	{
		if (prose.size() < 2)
		{
			return;
		}
		// Sort by *native* position (moved widgets report their stored original), so once we compact the flow
		// the reading order - and therefore the signature computed from it - stays stable. Sorting by the live
		// (moved) position would change the order every tick and thrash debounce/commit forever. Y goes
		// through row CLUSTERING (adjacent native ys within 8px = one visual row): icons sit a few px off
		// their row's text baseline, and a fixed 16px bucket once split an icon (y=138) from its own link
		// (y=140) across a bucket edge, tearing pairs apart. Clusters are gap-based, so no boundary exists.
		java.util.TreeSet<Integer> ys = new java.util.TreeSet<>();
		for (Widget w : prose)
		{
			ys.add(nativeY(w));
		}
		Map<Integer, Integer> rowOf = new HashMap<>();
		int rowIdx = -1;
		int prevY = Integer.MIN_VALUE;
		for (int y : ys)
		{
			if (y - prevY > 8)
			{
				rowIdx++;
			}
			rowOf.put(y, rowIdx);
			prevY = y;
		}
		prose.sort((a, b) -> {
			int ra = rowOf.get(nativeY(a));
			int rb = rowOf.get(nativeY(b));
			return ra != rb ? Integer.compare(ra, rb) : Integer.compare(nativeX(a), nativeX(b));
		});

		String decision;
		// Repopulation check runs EVERY tick, before anything can short-circuit, and walks the container's
		// FULL child array - including hidden widgets. A tab switch hides pooled widgets it doesn't need
		// instead of resetting them; state ops that only saw the visible list once purged the position
		// ledger of hidden-but-still-moved hitboxes/icons, and when they came back their old laid position
		// masqueraded as "native" (links shattering paragraph one, icons on wrong rows, the vanishing wiki).
		String repop = repopReason(container);
		if (repop != null)
		{
			decommitRepop(container);
			committedSig = null;
			failedSig = null;
			lastProseSig = effectiveEnglishSig(container, prose);
			settleTicks = 0;
			pendingCollect.clear();
			decision = "REPOP[" + repop + "]";
		}
		else if (committedSig != null)
		{
			// Committed steady state. Keep the scroll region we shrank pinned (a clientscript may re-grow it)
			// and clamp scrollY into range every tick; only rebuild the (expensive) signature every 8th tick.
			maintainProseScroll(container);
			if ((++sigTick & 7) != 0)
			{
				settleTicks++;
				collectSettled();
				decision = "SKIP";
			}
			else
			{
				String sig = effectiveEnglishSig(container, prose);
				if (sig.equals(committedSig))
				{
					settleTicks++;
					collectSettled();
					decision = "SKIP";
				}
				else if (!sig.equals(lastProseSig))
				{
					lastProseSig = sig; // a non-owned slot changed: wait for it to hold still
					settleTicks = 0;
					decision = "DEBOUNCE";
				}
				else
				{
					committedSig = null; // stable divergent frame: drop the commit so the next tick re-lays
					settleTicks = 0;
					decision = "DEBOUNCE";
				}
			}
		}
		else
		{
			String sig = effectiveEnglishSig(container, prose);
			if (!sig.equals(lastProseSig))
			{
				lastProseSig = sig;
				settleTicks = 0;
				decision = "DEBOUNCE"; // still settling: wait for two identical ticks
			}
			else if (sig.equals(failedSig) && pendingCooldown-- > 0)
			{
				settleTicks++;
				decision = "LAID_WAIT"; // translation still pending: don't re-attempt 50x/s
			}
			else
			{
				settleTicks++;
				collectOk = settleTicks >= COLLECT_SETTLE_TICKS;
				// Re-lay from a clean English frame (revert our slots first, all within this one tick). The
				// native-coordinate snapshot is taken BEFORE the revert: relative coordinates only refresh
				// when the client re-lays the frame, so reading them back mid-tick after our own writes is
				// unreliable - the ledger/snapshot is the single source of truth for native geometry.
				Map<Long, int[]> nat = new HashMap<>();
				for (Widget w : prose)
				{
					nat.put(widgetKey(w), new int[]{nativeX(w), nativeY(w)});
				}
				restoreOwnedToEnglish(container);
				boolean complete = layoutInline(container, prose, nat, glyph.uiSize());
				if (complete)
				{
					committedSig = sig;
					failedSig = null;
				}
				else if (pendingHasAi)
				{
					committedSig = null;
					failedSig = sig;
					pendingCooldown = 30; // ~600ms before re-asking the AI about this same frame
				}
				else
				{
					committedSig = null;
					failedSig = null; // glyphs upload within a frame or two: retry every tick, no cooldown
				}
				decision = complete ? "LAID_OK" : "LAID_PENDING";
			}
		}
	}

	/**
	 * Signature of the column's effective English (our stored original where we own a slot, else live text),
	 * plus the container width (a resize must re-layout) and each sprite's native geometry (an icon moving
	 * or resizing natively means the client rebuilt the page).
	 */
	private String effectiveEnglishSig(Widget container, List<Widget> prose)
	{
		StringBuilder sb = new StringBuilder();
		sb.append(container.getWidth()).append('\n');
		for (Widget w : prose)
		{
			long key = widgetKey(w);
			String cur = w.getText();
			if (cur == null)
			{
				sb.append("SPR:").append(w.getSpriteId());
			}
			else
			{
				boolean ours = cur.equals(lastSet.get(key));
				sb.append(ours ? original.getOrDefault(key, "") : cur);
			}
			// Native geometry is part of the frame identity: the client fills pooled widgets in stages (text
			// first, position a frame later), and laying out such a half-placed frame filed the wiki link
			// into the middle of paragraph one. Position changes now fail the debounce until settled.
			sb.append('@').append(nativeX(w)).append(',').append(nativeY(w)).append('\n');
		}
		return sb.toString();
	}

	/**
	 * Non-null with a short diagnostic when the client repopulated a slot we own. Text mismatch on an owned
	 * slot is the primary signal; geometry is compared through {@code getOriginalX/Y} - the very fields we
	 * wrote - because relative coordinates only update when the client re-lays the frame, so comparing them
	 * right after our own pass false-positives and decommits our freshly laid page into a blank. Walks the
	 * container's FULL child array so hidden pooled widgets are covered too.
	 */
	private String repopReason(Widget container)
	{
		Widget[] kids = container.getDynamicChildren();
		if (kids == null)
		{
			return null;
		}
		for (Widget w : kids)
		{
			if (w == null)
			{
				continue;
			}
			long key = widgetKey(w);
			String cur = w.getText();
			if (lastSet.containsKey(key) && cur != null && !cur.equals(lastSet.get(key)))
			{
				return "TXT idx=" + w.getIndex();
			}
			// Geometry: the client rebuilding the page rewrites original x/y to its English layout.
			Integer px = placedX.get(key);
			if (px != null && (w.getOriginalX() != px || w.getOriginalY() != placedY.get(key)))
			{
				return "GEO idx=" + w.getIndex() + " orig=" + w.getOriginalX() + "," + w.getOriginalY()
						+ " placed=" + px + "," + placedY.get(key);
			}
		}
		return null;
	}

	/**
	 * Tab-switch decommit: blank our char-images (never refill stored English - the client is repopulating
	 * these pooled slots and stale refills both flash ghost text and can leak garbage sentences into the AI
	 * cache), revert geometry only where the original fields still corroborate as ours, then purge ALL prose
	 * state for this container (a shrinking tab reuses indexes, so per-member removal leaves ghost entries).
	 * MUST walk the full child array: hidden pooled widgets we moved would otherwise keep our laid position
	 * while the purge below forgets their true native one - the position then poisons every later layout.
	 */
	private void decommitRepop(Widget container)
	{
		Widget[] kids = container.getDynamicChildren();
		if (kids != null)
		{
			for (Widget w : kids)
			{
				if (w == null)
				{
					continue;
				}
				long key = widgetKey(w);
				String cur = w.getText();
				if (lastSet.containsKey(key) && cur != null && cur.equals(lastSet.get(key)) && cur.contains("<img="))
				{
					w.setText("");
					w.setTextShadowed(true); // the client's repopulation reuses the slot with native shadow
				}
				Integer px = placedX.get(key);
				boolean corroborated = px == null
						|| (w.getOriginalX() == px && w.getOriginalY() == placedY.get(key));
				if (corroborated)
				{
					revertGeom(key, w);
				}
				// not corroborated = the client already re-laid this widget itself: leave its geometry alone
			}
		}
		purgeProseState();
		restoreProseScroll();
	}

	/** Forget every prose-state entry for the 860 content column, including ghost indexes this tab never used. */
	private void purgeProseState()
	{
		java.util.function.Predicate<Long> isProse = k -> (k >>> 21) == (PROSE_COMPONENT_ID & 0xFFFFFFFFL);
		lastSet.keySet().removeIf(isProse);
		original.keySet().removeIf(isProse);
		lastColor.keySet().removeIf(isProse);
		incomplete.removeIf(isProse);
		movedX.keySet().removeIf(isProse);
		movedY.keySet().removeIf(isProse);
		movedW.keySet().removeIf(isProse);
		placedX.keySet().removeIf(isProse);
		placedY.keySet().removeIf(isProse);
	}

	/**
	 * Revert every slot we own to its stored English and native position, and forget it (clean re-layout
	 * base). Walks the container's full child array so hidden pooled widgets we moved revert too.
	 */
	private void restoreOwnedToEnglish(Widget container)
	{
		Widget[] kids = container.getDynamicChildren();
		if (kids == null)
		{
			return;
		}
		for (Widget w : kids)
		{
			if (w == null)
			{
				continue;
			}
			long key = widgetKey(w);
			boolean owned = lastSet.containsKey(key) || movedX.containsKey(key);
			if (!owned)
			{
				continue;
			}
			String eng = original.get(key);
			if (eng != null)
			{
				w.setText(eng); // both char-image heads and blanked words carry their original English word
				w.setTextShadowed(true); // we disabled the shadow for glyphs; native prose is shadowed
			}
			revertGeom(key, w);
			original.remove(key);
			lastColor.remove(key);
			lastSet.remove(key);
			incomplete.remove(key);
		}
	}

	/** One resolved atom of the inline flow: a word run, an icon, an inline link, or a white header. */
	private static final class FlowItem
	{
		static final int WORD = 0;
		static final int ICON = 1;
		static final int LINK = 2;
		static final int HEADER = 3;
		int kind;
		List<Widget> widgets; // run words (or merged header slots); a single-element list for icon/link
		String zh;            // translated Chinese (null for icons)
		int color;
		int iconWidth;
		int iconHeight;
		int iconNativeX;      // native x from the snapshot (a block keeps it so centering survives)
		boolean block;        // full-width decoration (divider line): own row, keep native x
		boolean lineStart;    // icon that natively begins a line (skill-list rows): keep it starting one
		boolean gapBefore;    // an English paragraph break precedes this item
	}

	/**
	 * The inline flow: walk the (now all-English) column in reading order and lay it out onto the native
	 * widgets under a compacting cursor. Runs all-or-nothing - phase 1 translates every run/link/header
	 * without touching a widget; if <em>any</em> is still pending (AI off/loading, or glyphs uploading) it
	 * returns false immediately so the whole column stays English (no half-translated, jittering frame).
	 * Only when everything is ready does phase 2 pour the Chinese in, reposition icons/dividers inline, and
	 * blank the leftover word slots. Finishes by shrinking the scroll region to the compacted height.
	 */
	private boolean layoutInline(Widget container, List<Widget> prose, Map<Long, int[]> nat, int size)
	{
		int gw = glyph.glyphWidth(size);
		int lineH = glyph.glyphHeight(size) + 2; // +2 leading: the raw glyph ink height sets lines touching
		boolean ai = config.aiFillInterface();

		// All geometry below reads the pre-revert native snapshot (nat), never live relative coordinates:
		// mid-tick relative values are stale after our own writes and would corrupt grouping and the ledger.
		int col = 0;
		int leftMargin = Integer.MAX_VALUE;
		int startY = Integer.MAX_VALUE;
		for (Widget w : prose)
		{
			ProseKind k = classify(w);
			if (k == ProseKind.OURS || k == ProseKind.HITBOX)
			{
				continue; // hitboxes shadow their link's row: they must not skew the margins
			}
			int[] p = nat.get(widgetKey(w));
			if (k == ProseKind.WORD || k == ProseKind.LINK)
			{
				leftMargin = Math.min(leftMargin, p[0]);
			}
			startY = Math.min(startY, p[1]);
			col = Math.max(col, p[0] + w.getWidth());
		}
		if (col < MIN_WRAP * gw || leftMargin == Integer.MAX_VALUE)
		{
			return true; // nothing to lay out
		}
		if (startY == Integer.MAX_VALUE)
		{
			startY = 4;
		}

		// NOTE: links are identified by their text alone (<u=..> / yellow+listener). An earlier "hitbox
		// backing" classifier looked tidy but hitboxes spawn at (0,0) before the client positions them, and
		// row-matching those turned the first text row into fake links. Hitboxes are only ever PAIRED to an
		// already-identified link for repositioning, never used to classify.

		// Phase 1: build + translate every item, no widget mutation. If anything is still pending we keep
		// WALKING (so every missing sentence fires its async AI request in this same pass and they all
		// translate concurrently) and only then report incomplete. Bailing at the first pending key made
		// page warm-up serial: one new key discovered per retry cooldown, tens of seconds for a fresh page.
		boolean ready = true;
		pendingHasAi = false;
		List<FlowItem> items = new ArrayList<>();
		List<Widget> hitboxes = new ArrayList<>();
		int prevNativeY = -1;
		int i = 0;
		while (i < prose.size())
		{
			Widget a = prose.get(i);
			ProseKind k = classify(a);
			if (k == ProseKind.OURS)
			{
				i++;
				continue;
			}
			if (k == ProseKind.HITBOX)
			{
				// a link's invisible click rectangle: repositioned onto its link after the links are placed.
				// It must not update prevNativeY (it shares the link's row) or take part in the flow itself.
				hitboxes.add(a);
				i++;
				continue;
			}
			int ay = nat.get(widgetKey(a))[1];
			boolean gap = prevNativeY >= 0 && ay - prevNativeY > 20;

			if (k == ProseKind.ICON)
			{
				FlowItem it = new FlowItem();
				it.kind = FlowItem.ICON;
				it.widgets = java.util.Collections.singletonList(a);
				it.iconWidth = Math.max(a.getWidth(), 1);
				it.iconHeight = Math.max(a.getHeight(), 1);
				// near-column-wide, tall, or an actual LINE widget = page decoration (divider), not inline
				it.block = a.getType() == 9
						|| it.iconWidth >= (col - leftMargin) * 6 / 10 || it.iconHeight > 2 * lineH;
				it.iconNativeX = nat.get(widgetKey(a))[0];
				// an icon that natively started its line (the skill-interaction rows) keeps starting one, so
				// those entries read one-per-line like the English page instead of running together
				it.lineStart = it.iconNativeX <= leftMargin + 4;
				it.gapBefore = gap;
				items.add(it);
				prevNativeY = ay;
				i++;
				continue;
			}
			if (k == ProseKind.HEADER)
			{
				// A header may itself be split word-per-widget: merge consecutive same-row header slots into
				// one item so it translates whole instead of once per fragment.
				List<Widget> parts = new ArrayList<>();
				StringBuilder rawH = new StringBuilder();
				int j = i;
				while (j < prose.size())
				{
					Widget hw = prose.get(j);
					if (classify(hw) != ProseKind.HEADER || nat.get(widgetKey(hw))[1] != ay)
					{
						break;
					}
					parts.add(hw);
					if (rawH.length() > 0)
					{
						rawH.append(' ');
					}
					rawH.append(hw.getText());
					j++;
				}
				String zh = plainZh(rawH.toString(), ai);
				if (zh == null || glyph.toImgTags(zh, 0xffffff, 0, size) == null)
				{
					ready = false; // keep walking: warm every remaining translation this pass
					pendingHasAi |= zh == null;
					prevNativeY = ay;
					i = j;
					continue;
				}
				FlowItem it = new FlowItem();
				it.kind = FlowItem.HEADER;
				it.widgets = parts;
				it.zh = zh;
				it.color = parts.get(0).getTextColor() & 0xFFFFFF;
				it.gapBefore = gap;
				items.add(it);
				prevNativeY = ay;
				i = j;
				continue;
			}
			if (k == ProseKind.LINK)
			{
				// links carry their colour in the <u=RRGGBB> tag (yellow normally, GREEN for a completed
				// quest) - render the Chinese in the same colour instead of hardcoding yellow
				int lc = linkColor(a.getText(), a.getTextColor() & 0xFFFFFF);
				String zh = plainZh(a.getText(), ai);
				if (zh == null || glyph.toImgTags(zh, lc, 0, size) == null)
				{
					ready = false;
					pendingHasAi |= zh == null;
					prevNativeY = ay;
					i++;
					continue;
				}
				FlowItem it = new FlowItem();
				it.kind = FlowItem.LINK;
				it.widgets = java.util.Collections.singletonList(a);
				it.zh = zh;
				it.color = lc;
				it.gapBefore = gap;
				items.add(it);
				prevNativeY = ay;
				i++;
				continue;
			}

			// WORD run: gather consecutive words (no big native y-gap).
			List<Widget> words = new ArrayList<>();
			StringBuilder raw = new StringBuilder();
			int lastY = ay;
			int maxNativeY = ay;
			int j = i;
			while (j < prose.size())
			{
				Widget ww = prose.get(j);
				if (classify(ww) != ProseKind.WORD)
				{
					break;
				}
				int wy = nat.get(widgetKey(ww))[1];
				if (j > i && wy - lastY > 20)
				{
					break; // paragraph gap
				}
				words.add(ww);
				if (raw.length() > 0)
				{
					raw.append(' ');
				}
				raw.append(ww.getText());
				lastY = wy;
				maxNativeY = Math.max(maxNativeY, wy);
				j++;
			}
			int bodyColor = dominantColor(words, a.getTextColor());
			String english = JournalReconstructor.strip(raw.toString()).trim();
			String zh = english.isEmpty() ? null : plainZh(english, ai);
			if (zh == null)
			{
				// Tiny glue fragments between two adjacent links ("or a", "on the") miss the tables and the
				// AI refuses them - they'd block the whole page forever. Map the common ones, pass any other
				// short scrap through as literal English; only real sentences may hold the page pending.
				zh = glueZh(english);
			}
			if (zh == null || glyph.toImgTags(zh, bodyColor, 0, size) == null)
			{
				ready = false; // keep walking so the remaining runs fire their AI requests too
				pendingHasAi |= zh == null;
				prevNativeY = maxNativeY;
				i = j;
				continue;
			}
			FlowItem it = new FlowItem();
			it.kind = FlowItem.WORD;
			it.widgets = words;
			it.zh = zh;
			it.color = bodyColor;
			it.gapBefore = gap;
			items.add(it);
			prevNativeY = maxNativeY;
			i = j;
		}
		if (!ready)
		{
			return false; // some translations still pending (all fired concurrently above): retry next tick
		}

		// Phase 2: everything is ready - pour it onto the widgets under the compacting cursor. rowH is the
		// current line's height (an inline icon taller than the text grows the row instead of overlapping the
		// next line); every line break advances cy by rowH then resets it.
		int padPx = gw; // right-edge safety margin inside the column
		int cx = leftMargin;
		int cy = startY;
		int rowH = lineH;
		for (FlowItem it : items)
		{
			if (it.gapBefore)
			{
				if (cx > leftMargin)
				{
					cx = leftMargin;
					cy += rowH;
					rowH = lineH;
				}
				cy += lineH / 2; // preserve the paragraph break as a half-line gap
			}
			if (it.kind == FlowItem.ICON)
			{
				Widget icon = it.widgets.get(0);
				seedNative(icon, nat);
				if (it.block)
				{
					// divider / full-width art: own row, keep its native x (centering stays intact)
					if (cx > leftMargin)
					{
						cx = leftMargin;
						cy += rowH;
						rowH = lineH;
					}
					moveWidget(icon, it.iconNativeX, cy);
					icon.revalidate();
					cy += it.iconHeight + 4;
					continue;
				}
				if (cx > leftMargin && (it.lineStart || cx + it.iconWidth > col))
				{
					cx = leftMargin;
					cy += rowH;
					rowH = lineH;
				}
				moveWidget(icon, cx, cy);
				icon.revalidate();
				rowH = Math.max(rowH, it.iconHeight);
				cx += it.iconWidth + 2;
				continue;
			}
			if (it.kind == FlowItem.HEADER)
			{
				if (cx > leftMargin)
				{
					cx = leftMargin;
					cy += rowH;
					rowH = lineH;
				}
				seedNative(it.widgets.get(0), nat);
				int lines = placeSingle(it.widgets.get(0), it.zh, it.color, false,
						leftMargin, cy, col - leftMargin - padPx, size, gw, lineH);
				for (int p = 1; p < it.widgets.size(); p++)
				{
					blankWord(it.widgets.get(p)); // merged header fragments
				}
				cy += lines * lineH;
				continue;
			}
			if (it.kind == FlowItem.LINK)
			{
				int w = advancePx(it.zh, gw);
				if (cx > leftMargin && cx + w > col - padPx)
				{
					cx = leftMargin;
					cy += rowH;
					rowH = lineH;
				}
				seedNative(it.widgets.get(0), nat);
				int lines = placeSingle(it.widgets.get(0), it.zh, it.color, true,
						cx, cy, col - cx - padPx, size, gw, lineH);
				// The click listener lives on a separate invisible rectangle at the link's ENGLISH spot
				// (860 dump: type=3, opacity 255, hasListener) - drag it onto the Chinese or the link is dead.
				placeLinkHitbox(it.widgets.get(0), hitboxes, nat, cx, cy,
						Math.min(w, col - cx - padPx));
				if (lines > 1)
				{
					cy += (lines - 1) * lineH;
					cx = leftMargin; // a wrapped link ends its row; prose resumes on the next line
					cy += rowH;
					rowH = lineH;
				}
				else
				{
					cx += w + 2;
				}
				continue;
			}

			// WORD run: wrap by pixel budget (first line = what's left of the current row, rest = full column)
			// and pour one line per word widget at the cursor.
			List<Widget> words = it.widgets;
			if (cx == leftMargin)
			{
				// a run that begins at a line start must not open with closing punctuation (", 你需要..."
				// happens when the preceding fragment became a header/link) - drop the orphaned leaders
				it.zh = trimLeadingPunct(it.zh);
			}
			int firstPx = col - cx - padPx;
			int restPx = col - leftMargin - padPx;
			if (firstPx < 2 * gw && cx > leftMargin)
			{
				cx = leftMargin;
				cy += rowH;
				rowH = lineH;
				firstPx = restPx;
			}
			List<String> lines = glyph.wrapPlain(it.zh, firstPx, restPx, size);
			if (lines.size() > words.size() && cx > leftMargin)
			{
				// more lines than slots from a mid-row start: restart the run at the left margin (each line
				// then gets the full column, minimising lines) before falling back to slot absorption
				cx = leftMargin;
				cy += rowH;
				rowH = lineH;
				lines = glyph.wrapPlain(it.zh, restPx, restPx, size);
			}
			int lastStartX = cx;
			for (int k = 0; k < lines.size() && k < words.size(); k++)
			{
				boolean last = k == words.size() - 1 && lines.size() > words.size();
				int x = k == 0 ? cx : leftMargin;
				if (k > 0)
				{
					cy += rowH;
					rowH = lineH;
				}
				lastStartX = x;
				seedNative(words.get(k), nat);
				if (last)
				{
					// final slot absorbs every remaining line (it sits at the left margin by construction)
					StringBuilder rem = new StringBuilder();
					int extra = 0;
					for (int p = k; p < lines.size(); p++)
					{
						String piece = glyph.toImgTags(lines.get(p), it.color, 0, size);
						if (piece == null)
						{
							continue;
						}
						if (rem.length() > 0)
						{
							rem.append("<br>");
							extra++;
						}
						rem.append(piece);
					}
					writeRunPiece(words.get(k), rem.toString(), col - x - padPx, x, cy, lineH, true);
					cy += extra * lineH;
					lastStartX = leftMargin;
				}
				else
				{
					String piece = glyph.toImgTags(lines.get(k), it.color, 0, size);
					if (piece != null)
					{
						writeRunPiece(words.get(k), piece, advancePx(lines.get(k), gw) + 2, x, cy, lineH, false);
					}
				}
			}
			for (int p = lines.size(); p < words.size(); p++)
			{
				blankWord(words.get(p));
			}
			cx = lastStartX + advancePx(lines.isEmpty() ? "" : lines.get(lines.size() - 1), gw) + 2;
		}
		// Park sub-layer children (the wiki banner) right under the compacted content: the banner natively
		// sits at the ENGLISH page bottom (e.g. y=716), far past the shrunk scroll height, i.e. invisible.
		// Moving the layer moves its children with it, so the banner arrives whole and functional.
		Widget[] all = container.getDynamicChildren();
		if (all != null)
		{
			List<Widget> layers = new ArrayList<>();
			for (Widget w : all)
			{
				if (w != null && w.getType() == 0 && !w.isSelfHidden() && w.getHeight() > 0
						&& w.getXPositionMode() == 0 && w.getYPositionMode() == 0 && !parkedAtOrigin(w))
				{
					layers.add(w);
				}
			}
			layers.sort(java.util.Comparator.comparingInt(this::nativeY));
			for (Widget layer : layers)
			{
				if (cx > leftMargin)
				{
					cx = leftMargin;
					cy += rowH;
					rowH = lineH;
				}
				cy += lineH / 2;
				moveWidget(layer, nativeX(layer), cy);
				layer.revalidate();
				cy += Math.max(layer.getHeight(), 1) + 4;
			}
		}
		applyProseScroll(container, cy + rowH);
		return true;
	}

	/**
	 * Move a link's invisible click hitbox onto the link's new position and width. The hitbox is a type-3
	 * transparent rectangle sharing the link's native row; it is matched by native geometry and consumed so
	 * a row with several links pairs each hitbox once. Without this the visible Chinese link is dead while
	 * an unmarked zone at the old English spot still clicks.
	 */
	private void placeLinkHitbox(Widget link, List<Widget> hitboxes, Map<Long, int[]> nat, int x, int y, int wPx)
	{
		int[] lp = nat.get(widgetKey(link));
		if (lp == null)
		{
			return;
		}
		for (java.util.Iterator<Widget> iter = hitboxes.iterator(); iter.hasNext(); )
		{
			Widget hb = iter.next();
			int[] hp = nat.get(widgetKey(hb));
			if (hp == null || Math.abs(hp[1] - lp[1]) > 8)
			{
				continue; // not this link's row
			}
			if (hp[0] > lp[0] + 200 || hp[0] + hb.getWidth() + 8 < lp[0])
			{
				continue; // no horizontal overlap with the link's native span
			}
			seedNative(hb, nat);
			long key = widgetKey(hb);
			movedW.putIfAbsent(key, hb.getOriginalWidth());
			moveWidget(hb, x, y);
			hb.setOriginalWidth(Math.max(wPx, 1));
			hb.setWidth(Math.max(wPx, 1));
			hb.revalidate();
			iter.remove();
			return;
		}
	}

	/**
	 * Seed the native-position ledger from the pre-revert snapshot before moving a widget. moveWidget's own
	 * putIfAbsent would read live relative coordinates, which are stale mid-tick right after our reverts and
	 * would poison the ledger with compacted positions as "native".
	 */
	private void seedNative(Widget w, Map<Long, int[]> nat)
	{
		long key = widgetKey(w);
		int[] p = nat.get(key);
		if (p != null)
		{
			movedX.putIfAbsent(key, p[0]);
			movedY.putIfAbsent(key, p[1]);
		}
	}

	private static final String NO_LEADING_PUNCT = "、。，．；：！？）｝】》」』〉〕…—·％”’,.;:!?)";

	/** Colour of a link from its {@code <u=RRGGBB>} tag (green = completed quest), else the widget colour. */
	private static int linkColor(String text, int fallback)
	{
		int u = text == null ? -1 : text.indexOf("<u=");
		if (u >= 0)
		{
			int end = text.indexOf('>', u);
			if (end > u + 3)
			{
				try
				{
					return Integer.parseInt(text.substring(u + 3, end).trim(), 16) & 0xFFFFFF;
				}
				catch (NumberFormatException ignored)
				{
					// plain <u> or malformed: fall through to the widget colour
				}
			}
		}
		return fallback;
	}

	/**
	 * Chinese for the glue scraps left between two adjacent inline links ("...with the [Fishing skill]
	 * or a [Range]..."). Tables miss them and the AI refuses such stubs, which would pin the whole page
	 * in English forever. Unknown scraps up to a few words pass through literally; longer text returns
	 * null and keeps the normal pending path.
	 */
	private static String glueZh(String english)
	{
		switch (english.toLowerCase(java.util.Locale.ROOT))
		{
			case "or":
			case "or a":
			case "or an":
			case "or on a":
			case "or the":
				return "或";
			case "and":
			case "and a":
			case "and an":
			case "and the":
				return "和";
			case "the":
			case "a":
			case "an":
			case "of":
			case "of the":
				return "";
			case "on":
			case "on a":
			case "on an":
			case "on the":
			case "in":
			case "in a":
			case "in the":
			case "at":
			case "at a":
			case "at the":
				return "在";
			case "with":
			case "with a":
			case "with the":
			case "using":
			case "using the":
				return "用";
			case "from":
			case "from a":
			case "from the":
				return "从";
			case "to":
			case "to a":
			case "to the":
				return "到";
			case "such as the":
			case "such as a":
			case "such as":
				return "例如";
			default:
				return english.length() <= 14 ? english : null; // short scrap: show literally, never block
		}
	}

	/** Drop closing punctuation (and following spaces) stranded at the start of a line-leading run. */
	private static String trimLeadingPunct(String zh)
	{
		int i = 0;
		while (i < zh.length()
				&& (NO_LEADING_PUNCT.indexOf(zh.charAt(i)) >= 0 || Character.isWhitespace(zh.charAt(i))))
		{
			i++;
		}
		return i == 0 ? zh : zh.substring(i);
	}

	/** Advance width in px of a rendered plain segment: CJK counts a full glyph, anything else half. */
	private static int advancePx(String plain, int gw)
	{
		int half = (gw + 1) / 2;
		int px = 0;
		for (int i = 0; i < plain.length(); )
		{
			int cp = plain.codePointAt(i);
			px += cp >= 0x2E80 ? gw : half;
			i += Character.charCount(cp);
		}
		return px;
	}

	private static final String SKILLGUIDE_TAG = "skillguide860"; // provenance tag on every collected 860 row
	private static final Pattern LEADING_PUNCT = Pattern.compile("^[,.;:!?]+\\s*");

	/** Translate a stripped English fragment to plain Chinese (tags removed), or null if pending. */
	private String plainZh(String english, boolean ai)
	{
		String raw = JournalReconstructor.strip(english == null ? "" : english).trim();
		if (raw.isEmpty())
		{
			return null;
		}
		// A run that starts right after an inline link keeps the link's trailing punctuation (", first
		// obtain a mould..."); the SANITISED form is the true table key, so only that form may ever be
		// collected - recording the punctuated raw would bake an unreachable key.
		Matcher lead = LEADING_PUNCT.matcher(raw);
		String key = lead.find() ? raw.substring(lead.end()) : raw;
		boolean clean = key.equals(raw);
		if (!collectOk && !key.isEmpty())
		{
			// gate closed (frame still settling): translate for the screen now, collect once it holds
			if (pendingCollect.size() < 400 && !pendingCollect.contains(key))
			{
				pendingCollect.add(key);
			}
			gatedRuns++;
		}
		// The exact key first: every table row and AI-cache entry accumulated so far lives under it.
		String zh = translator.translateJournalSentence(raw, ai, SKILLGUIDE_TAG, collectOk && clean);
		if (zh == null && !clean && !key.isEmpty())
		{
			zh = translator.translateJournalSentence(key, ai, SKILLGUIDE_TAG, collectOk);
		}
		return zh == null ? null : zh.replaceAll("<[^>]*>", ""); // char images can't carry residual tags
	}

	/**
	 * The frame held still long enough: replay the (cheap, lookup-only) translations of everything
	 * laid before the gate opened, this time with collection on - so committing a fast layout never
	 * silently loses the rows, and nothing mid-relayout ever reaches the file.
	 */
	private void collectSettled()
	{
		if (settleTicks < COLLECT_SETTLE_TICKS || pendingCollect.isEmpty())
		{
			return;
		}
		for (String en : pendingCollect)
		{
			translator.translateJournalSentence(en, config.aiFillInterface(), SKILLGUIDE_TAG, true);
		}
		log.info("OSRSCN-860 settle-collect: {} runs recorded after {} stable ticks",
				pendingCollect.size(), settleTicks);
		pendingCollect.clear();
	}

	/**
	 * Write one rendered piece (a single line, or the absorbing tail with {@code <br>}s) into a word slot at
	 * (x,y) with an explicit pixel width. Width is recorded in {@code movedW} before mutation so the next
	 * layout can restore the native width - without that the column measurement reads our own widened slots
	 * and inflates a little more on every re-layout.
	 */
	private void writeRunPiece(Widget slot, String img, int widthPx, int x, int y, int lineH, boolean multi)
	{
		if (img == null || img.isEmpty())
		{
			return;
		}
		long key = widgetKey(slot);
		original.putIfAbsent(key, slot.getText() == null ? "" : slot.getText());
		movedW.putIfAbsent(key, slot.getOriginalWidth());
		slot.setText(img);
		slot.setTextShadowed(false);
		slot.setOriginalWidth(Math.max(widthPx, 1));
		slot.setWidth(Math.max(widthPx, 1));
		if (multi || img.contains("<br>"))
		{
			slot.setLineHeight(lineH);
		}
		moveWidget(slot, x, y);
		slot.revalidate();
		lastSet.put(key, img);
	}

	/**
	 * Place a single-widget label (header / link) at (x,y), wrapping to {@code availPx} when it is too wide
	 * (the extra lines stay inside this same widget, so a link's click listener covers all of them). Links
	 * keep their {@code <u=ffff00>} underline wrapper. Returns the number of lines written (0 = pending).
	 */
	private int placeSingle(Widget w, String zh, int color, boolean underline, int x, int y,
			int availPx, int size, int gw, int lineH)
	{
		List<String> lines = glyph.wrapPlain(zh, availPx, availPx, size);
		if (lines.isEmpty())
		{
			return 0;
		}
		StringBuilder sb = new StringBuilder();
		int maxPx = 0;
		for (int k = 0; k < lines.size(); k++)
		{
			String img = glyph.toImgTags(lines.get(k), color, 0, size);
			if (img == null)
			{
				return 0; // glyphs still uploading (phase 1 pre-checked, so this is a rare race)
			}
			if (k > 0)
			{
				sb.append("<br>");
			}
			sb.append(img);
			maxPx = Math.max(maxPx, advancePx(lines.get(k), gw));
		}
		String out = underline
				? "<u=" + String.format("%06x", color & 0xFFFFFF) + ">" + sb + "</u>"
				: sb.toString();
		long key = widgetKey(w);
		original.putIfAbsent(key, w.getText() == null ? "" : w.getText());
		movedW.putIfAbsent(key, w.getOriginalWidth());
		w.setText(out);
		w.setTextShadowed(false);
		w.setOriginalWidth(Math.max(maxPx, gw)); // widget width == ink width: no dead click-zone past the text
		w.setWidth(Math.max(maxPx, gw));
		if (lines.size() > 1)
		{
			w.setLineHeight(lineH);
		}
		moveWidget(w, x, y);
		w.revalidate();
		lastColor.put(key, color);
		lastSet.put(key, out);
		return lines.size();
	}

	/** Blank a word slot the flow didn't need (its Chinese went into an earlier line-head). */
	private void blankWord(Widget w)
	{
		long key = widgetKey(w);
		if (!original.containsKey(key))
		{
			original.put(key, w.getText() == null ? "" : w.getText());
		}
		w.setText("");
		lastSet.put(key, "");
	}

	/** Move a prose widget to (x,y) in the compacted flow, remembering its native position to revert later. */
	private void moveWidget(Widget w, int x, int y)
	{
		long key = widgetKey(w);
		movedX.putIfAbsent(key, w.getRelativeX());
		movedY.putIfAbsent(key, w.getRelativeY());
		placedX.put(key, x); // always the latest target: live != placed later means the client moved it
		placedY.put(key, y);
		w.setOriginalX(x);
		w.setOriginalY(y);
	}

	/** Native (pre-move) x/y of a prose widget: the stored original if we moved it, else its live position. */
	private int nativeX(Widget w)
	{
		Integer x = movedX.get(widgetKey(w));
		return x != null ? x : w.getRelativeX();
	}

	private int nativeY(Widget w)
	{
		Integer y = movedY.get(widgetKey(w));
		return y != null ? y : w.getRelativeY();
	}

	/** Put a moved prose widget back at its native position and width (English toggle / decommit). */
	private void revertGeom(long key, Widget w)
	{
		Integer ox = movedX.remove(key);
		Integer oy = movedY.remove(key);
		Integer ow = movedW.remove(key);
		placedX.remove(key);
		placedY.remove(key);
		if (ox != null)
		{
			w.setOriginalX(ox);
		}
		if (oy != null)
		{
			w.setOriginalY(oy);
		}
		if (ow != null)
		{
			w.setOriginalWidth(ow); // mirror both fields: the next layout reads getWidth() for the column
			w.setWidth(ow);
		}
		if (ox != null || oy != null || ow != null)
		{
			w.revalidate();
		}
	}

	// ===== 860 prose scroll-region management (the compacted Chinese is much shorter than the English) =====

	/** After a committed layout: shrink the scroll region to the Chinese content height. */
	private void applyProseScroll(Widget container, int contentBottom)
	{
		if (container.getScrollHeight() <= 0 || appliedScrollHeight == -2)
		{
			return; // not a scroll container, or a clientscript kept fighting us and we gave up
		}
		if (proseNativeScrollHeight == null)
		{
			proseNativeScrollHeight = container.getScrollHeight();
		}
		int newH = Math.max(container.getHeight(), contentBottom + 8);
		container.setScrollHeight(newH);
		int maxY = Math.max(0, newH - container.getHeight());
		if (container.getScrollY() > maxY)
		{
			container.setScrollY(maxY);
		}
		container.revalidateScroll();
		syncScrollbar(container);
		appliedScrollHeight = newH;
	}

	/**
	 * Tell the native scrollbar its range changed. Without this the bar keeps the OLD range: after we
	 * shrink for Chinese and the player toggles back to English, the bar refuses to scroll past the
	 * shrunken height and the page bottom is unreachable until a tab switch rebuilds it.
	 */
	private void syncScrollbar(Widget container)
	{
		client.runScript(net.runelite.api.ScriptID.UPDATE_SCROLLBAR,
				PROSE_SCROLLBAR_ID, PROSE_COMPONENT_ID, container.getScrollY());
	}

	/** Committed steady state: re-pin our scroll height if a clientscript re-grew it; clamp scrollY. */
	private void maintainProseScroll(Widget container)
	{
		if (appliedScrollHeight <= 0)
		{
			return;
		}
		if (container.getScrollHeight() != appliedScrollHeight)
		{
			if (++scrollFights > 5)
			{
				appliedScrollHeight = -2; // stop fighting: dead space at the bottom beats a tug-of-war
				return;
			}
			container.setScrollHeight(appliedScrollHeight);
			container.revalidateScroll();
			syncScrollbar(container);
		}
		int maxY = Math.max(0, appliedScrollHeight - container.getHeight());
		if (container.getScrollY() > maxY)
		{
			container.setScrollY(maxY);
		}
	}

	/** Give the scroll region back to the client (decommit / EN toggle / reset). */
	private void restoreProseScroll()
	{
		if (proseNativeScrollHeight != null)
		{
			Widget container = client.getWidget(SKILL_GUIDE_NEW_GROUP, PROSE_CHILD);
			if (container != null)
			{
				container.setScrollHeight(proseNativeScrollHeight);
				int maxY = Math.max(0, proseNativeScrollHeight - container.getHeight());
				if (container.getScrollY() > maxY)
				{
					container.setScrollY(maxY);
				}
				container.revalidateScroll();
				syncScrollbar(container);
			}
		}
		proseNativeScrollHeight = null;
		appliedScrollHeight = -1;
		scrollFights = 0;
	}

	/**
	 * Fully hand the 860 prose column back to the client: our text out, native geometry back, scroll region
	 * restored, all state purged. Used by {@link #reset()} (font change) and when the guide toggle is turned
	 * off. Safe when the interface is closed (only purges state then).
	 */
	public void revertSkillGuide()
	{
		Widget container = client.getWidget(SKILL_GUIDE_NEW_GROUP, PROSE_CHILD);
		if (container != null)
		{
			Widget[] kids = container.getDynamicChildren();
			if (kids != null)
			{
				for (Widget w : kids)
				{
					if (w == null)
					{
						continue;
					}
					long key = widgetKey(w);
					if (!lastSet.containsKey(key) && !movedX.containsKey(key))
					{
						continue; // never ours
					}
					String eng = original.get(key);
					String cur = w.getText();
					if (eng != null && cur != null && (cur.equals(lastSet.get(key)) || cur.contains("<img=")))
					{
						w.setText(eng);
						w.setTextShadowed(true);
					}
					revertGeom(key, w);
				}
			}
		}
		restoreProseScroll();
		purgeProseState();
		committedSig = null;
		failedSig = null;
		lastProseSig = "";
		settleTicks = 0;
		pendingCollect.clear();
	}

	/** Most common colour among a segment's words, so a stray coloured word doesn't tint the paragraph. */
	private static int dominantColor(List<Widget> words, int fallback)
	{
		HashMap<Integer, Integer> hist = new HashMap<>();
		int best = 0;
		int bestColor = fallback & 0xFFFFFF;
		for (Widget w : words)
		{
			int c = w.getTextColor() & 0xFFFFFF;
			int cnt = hist.merge(c, 1, Integer::sum);
			if (cnt > best)
			{
				best = cnt;
				bestColor = c;
			}
		}
		return bestColor;
	}

	private void reconstructRoot(int componentId)
	{
		Widget root = client.getWidget(componentId);
		if (root == null || root.isHidden())
		{
			return;
		}
		List<Widget> slots = new ArrayList<>();
		collectTextSlots(root, slots);
		if (slots.isEmpty())
		{
			return;
		}
		// Debounce the client's multi-stage population. When a diary opens / is switched the client first writes
		// the bare task sentences, then a frame later appends the colour-coded "(requirement)" (red = unmet, navy
		// = met, strike-through on met). Translating a half-built frame races that update and produces the flicker
		// / all-black / missing-requirement states. So only act once the raw slot text is identical two ticks
		// running, i.e. the client has finished building this diary. Our own translation changes the text too, so
		// this also means we re-evaluate once after writing (a harmless no-op tick).
		StringBuilder sigBuf = new StringBuilder();
		for (Widget w : slots)
		{
			String t = w.getText();
			sigBuf.append(t == null ? "" : t).append('\n');
		}
		String sig = sigBuf.toString();
		if (!sig.equals(journalSig.put(componentId, sig)))
		{
			return; // still settling: wait one more tick before translating
		}
		// When the diary / difficulty is switched (or the scroll rebuilt) the client reuses these widgets and
		// overwrites at least one slot we translated with fresh English. That can leave a *mixed* frame - one
		// slot fresh English, a sibling still our char-image - which the reconstructor mis-groups (it treats a
		// "<img=" slot as structural), so the leftover image gets stuck and the task's text vanishes and can no
		// longer be restored. To stay robust, detect any such overwrite and first "decommit" the whole root:
		// revert every slot we still own to its stored English and forget our state for this root, so the rest
		// of this method re-groups and re-translates the full task list from one clean all-English slate.
		boolean repopulated = false;
		for (Widget w : slots)
		{
			String t = w.getText();
			if (t != null && !t.isEmpty() && !t.contains("<img="))
			{
				String last = lastSet.get(widgetKey(w));
				if (last != null && !t.equals(last))
				{
					repopulated = true;
					break;
				}
			}
		}
		if (repopulated)
		{
			for (Widget w : slots)
			{
				long key = widgetKey(w);
				String cur = w.getText();
				String eng = original.get(key);
				// only revert slots that still hold exactly our char-image translation; never touch text the
				// client rewrote, and never push stored English into a blank (which the new diary may not use).
				if (eng != null && cur != null && cur.equals(lastSet.get(key)) && cur.contains("<img="))
				{
					w.setText(eng);
				}
				original.remove(key);
				lastSet.remove(key);
				incomplete.remove(key);
			}
		}
		List<String> texts = new ArrayList<>(slots.size());
		for (Widget w : slots)
		{
			texts.add(w.getText() == null ? "" : w.getText());
		}
		List<JournalReconstructor.Unit> units = JournalReconstructor.reconstruct(texts);

		int size = glyph.uiSize();
		boolean aiFallback = config.aiFillInterface();
		for (JournalReconstructor.Unit unit : units)
		{
			if (!unit.content)
			{
				translateHeader(unit, slots, size, aiFallback); // difficulty band / area subtitle headers
				continue;
			}
			Widget head = slots.get(unit.start);
			int slotChars = glyph.wrapChars(head.getWidth() - WRAP_PAD, size);
			if (slotChars < MIN_WRAP)
			{
				slotChars = MIN_WRAP;
			}
			// Always split the requirement "(...)" off first and translate it inline, so its live colours
			// (red = unmet, navy = met, strike-through on met parts) survive even when the sentence itself
			// hits a promoted whole-task table entry. The requirement state is per player, so it must be
			// read from the widget every render, never baked into a flat translation. The sentence part still
			// goes table-first inside translateJournalSentence, so promoted whole tasks stay instant.
			String raw = rawJoin(slots, unit.start, unit.count);
			int reqStart = findRequirementStart(raw);
			String sentenceRaw = reqStart >= 0 ? raw.substring(0, reqStart) : raw;
			String reqRaw = reqStart >= 0 ? raw.substring(reqStart) : "";
			String sentenceZh = translator.translateJournalSentence(
					JournalReconstructor.strip(sentenceRaw).trim(), aiFallback);
			if (sentenceZh == null)
			{
				continue; // prose not translatable yet (AI off/pending): leave the task in English
			}
			// Stored table / AI-cache values can carry <str> (a completed task was cached with its strike tag).
			// Strip it: a <str> line can't cross our glyph-images, it would only strike a stray ASCII digit.
			sentenceZh = sentenceZh.replaceAll("(?i)</?str>", "");
			// Paint the sentence in its own colour, taken from the leading inline "<col=...>" if present (the
			// red "Achievement Diary - <area>" title, the green "completed" difficulty band) and otherwise the
			// widget's colour. Without this the sentence always rendered black. The requirement keeps its own
			// per-segment colours from translateReqInline.
			// Completion handling. A finished task is struck through in English; our CJK glyph-images can't carry
			// a strike line, and the font pack has no check-mark glyph, so a done task is rendered in dim grey
			// instead. Detected live from the sentence's own <str> every render (a <str> inside the requirement
			// only means that requirement is met), so it updates automatically as you complete tasks.
			boolean done = sentenceRaw.contains("<str>");
			int sentenceColor = done ? 0x404040 : (headerColor(sentenceRaw, head.getTextColor()) & 0xFFFFFF);
			String sentenceColorTag = "<col=" + String.format("%06x", sentenceColor) + ">";
			String zh;
			if (reqRaw.isEmpty())
			{
				zh = sentenceColorTag + sentenceZh;
			}
			else
			{
				String reqZh = translateReqInline(reqRaw, aiFallback);
				if (reqZh == null)
				{
					continue; // a requirement name is AI-pending: leave English, retry next tick
				}
				zh = sentenceColorTag + sentenceZh + reqZh;
			}
			String img = glyph.toImgTags(zh, head.getTextColor(), slotChars, size);
			if (img == null)
			{
				continue; // some glyphs still uploading: retry next tick
			}
			String[] pieces = img.split("<br>", -1);
			if (pieces.length > unit.count)
			{
				// Chinese wrapped to more lines than the task had slots: skip rather than cram many <br>
				// lines into one widget (the client caps a widget at 100 lines and crashes past it).
				continue;
			}
			for (int k = 0; k < unit.count; k++)
			{
				Widget w = slots.get(unit.start + k);
				long key = widgetKey(w);
				// overwrite, not putIfAbsent: at translate time the slot still holds its true current
				// English, so this is always the right text to restore (even after a different diary reused
				// the widget).
				original.put(key, w.getText() == null ? "" : w.getText());
				String text;
				if (k < unit.count - 1)
				{
					text = k < pieces.length ? pieces[k] : "";
				}
				else
				{
					// last slot absorbs any leftover wrapped lines so no Chinese is dropped
					StringBuilder rem = new StringBuilder();
					for (int p = k; p < pieces.length; p++)
					{
						if (p > k)
						{
							rem.append("<br>");
						}
						rem.append(pieces[p]);
					}
					text = rem.toString();
				}
				w.setText(text);
				w.setTextShadowed(false);
				if (text.contains("<br>"))
				{
					w.setLineHeight(glyph.glyphHeight(size));
				}
				lastSet.put(key, text); // lets restore() put English back even on the blanked slots
			}
		}
	}

	/**
	 * Translate a journal's structural single-slot headers: the difficulty bands ("Easy"/"Medium"/"Hard"/
	 * "Elite", already in the data table) and the area subtitle ("Karamja Area Tasks"). Reconstruction
	 * skips these because they delimit tasks, so they would otherwise stay English. Looks the stripped
	 * label up the normal way and re-renders it in the header's own colour (parsed from the inline
	 * {@code <col=...>} if present, e.g. the yellow difficulty bands).
	 */
	private void translateHeader(JournalReconstructor.Unit unit, List<Widget> slots, int size, boolean aiFallback)
	{
		if (unit.count != 1)
		{
			return;
		}
		Widget w = slots.get(unit.start);
		String text = w.getText();
		if (text == null || text.isEmpty() || text.contains("<img="))
		{
			return; // empty separator line or already our translation
		}
		long key = widgetKey(w);
		if (text.equals(lastSet.get(key)))
		{
			return;
		}
		String plain = JournalReconstructor.strip(text).trim();
		if (plain.isEmpty())
		{
			return; // pure tag / whitespace line: nothing to translate
		}
		int slotChars = glyph.wrapChars(w.getWidth() - WRAP_PAD, size);
		if (slotChars < MIN_WRAP)
		{
			slotChars = 0; // short labels: don't force-wrap them
		}
		String r = translator.lookupRenderUi(plain, headerColor(text, w.getTextColor()), slotChars, size, aiFallback);
		if (r == null)
		{
			return; // unknown label (and AI off/pending): leave it in English
		}
		original.put(key, text);
		w.setText(r);
		w.setTextShadowed(false);
		if (r.contains("<br>"))
		{
			w.setLineHeight(glyph.glyphHeight(size));
		}
		lastSet.put(key, r);
	}

	/** Glyph colour for a header: the inline {@code <col=rrggbb>} if present, else the widget's own colour. */
	private static int headerColor(String text, int widgetColor)
	{
		int c = text.indexOf("<col=");
		if (c >= 0)
		{
			// Mixed-colour line (base text with highlighted words): the tag colour only covers the
			// highlights, so painting the whole translation with it looks wrong - keep the base colour.
			if (c > 0 && text.lastIndexOf("</col>") < text.length() - "</col>".length())
			{
				return widgetColor;
			}
			int end = text.indexOf('>', c);
			if (end > c + 5)
			{
				try
				{
					return Integer.parseInt(text.substring(c + 5, end).trim(), 16);
				}
				catch (NumberFormatException ignored)
				{
					// malformed colour tag: fall back to the widget's own colour
				}
			}
		}
		return widgetColor;
	}

	private void collectTextSlots(Widget w, List<Widget> out)
	{
		if (w == null)
		{
			return;
		}
		if (w.getText() != null)
		{
			out.add(w);
		}
		collectTextSlotsArray(w.getStaticChildren(), out);
		collectTextSlotsArray(w.getDynamicChildren(), out);
		collectTextSlotsArray(w.getNestedChildren(), out);
	}

	private void collectTextSlotsArray(Widget[] children, List<Widget> out)
	{
		if (children == null)
		{
			return;
		}
		for (Widget c : children)
		{
			collectTextSlots(c, out);
		}
	}

	/** Join a unit's raw slot texts (tags kept) with spaces, mirroring the client's wrap. */
	private static String rawJoin(List<Widget> slots, int start, int count)
	{
		StringBuilder sb = new StringBuilder();
		for (int k = 0; k < count; k++)
		{
			String t = slots.get(start + k).getText();
			if (t == null)
			{
				t = "";
			}
			if (sb.length() > 0 && !t.isEmpty())
			{
				sb.append(' ');
			}
			sb.append(t);
		}
		return sb.toString();
	}

	/** First literal '(' that opens the requirement, ignoring '(' inside {@code <...>} tags; -1 if none. */
	static int findRequirementStart(String raw)
	{
		int i = 0;
		int n = raw.length();
		while (i < n)
		{
			char c = raw.charAt(i);
			if (c == '<')
			{
				int end = raw.indexOf('>', i);
				if (end != -1)
				{
					i = end + 1;
					continue;
				}
			}
			if (c == '(')
			{
				// Include a colour tag sitting immediately before the '(' (the client paints the opening paren
				// white via "<col=ffffff>(") so the paren keeps its colour instead of falling back to black.
				if (i >= 1 && raw.charAt(i - 1) == '>')
				{
					int open = raw.lastIndexOf('<', i - 1);
					if (open >= 0 && raw.startsWith("<col=", open))
					{
						return open;
					}
				}
				return i;
			}
			i++;
		}
		return -1;
	}

	/**
	 * Translate the names inside a diary requirement {@code (...)} while keeping every {@code <...>} tag in
	 * place, so the requirement's live colours (and any strike-through) survive. Each plain run between tags
	 * goes to {@link Translator#translateReqSegment} ("65 Slayer" -> "65级杀戮"); punctuation/spaces pass through.
	 */
	private String translateReqInline(String reqRaw, boolean aiFallback)
	{
		StringBuilder out = new StringBuilder(reqRaw.length() + 8);
		StringBuilder run = new StringBuilder();
		int i = 0;
		int n = reqRaw.length();
		while (i < n)
		{
			char c = reqRaw.charAt(i);
			if (c == '<')
			{
				int end = reqRaw.indexOf('>', i);
				if (end != -1)
				{
					if (run.length() > 0)
					{
						String seg = translator.translateReqSegment(run.toString(), aiFallback);
						if (Translator.REQ_PENDING.equals(seg))
						{
							return null; // a requirement name is still being translated: retry next tick
						}
						out.append(seg);
						run.setLength(0);
					}
					// Drop strike-through tags: the client strikes met requirements, but our CJK text renders as
					// glyph-images that a <str> line can't cross (only a stray ASCII digit gets struck, which looks
					// broken). The met/unmet colour (navy / dark red) already conveys state, so keep colour, drop <str>.
					String tag = reqRaw.substring(i, end + 1);
					if (!"<str>".equals(tag) && !"</str>".equals(tag))
					{
						out.append(tag);
					}
					i = end + 1;
					continue;
				}
			}
			run.append(c);
			i++;
		}
		if (run.length() > 0)
		{
			String seg = translator.translateReqSegment(run.toString(), aiFallback);
			if (Translator.REQ_PENDING.equals(seg))
			{
				return null;
			}
			out.append(seg);
		}
		return out.toString();
	}

	// ===== TEMP DEBUG P1-1 (remove after we know the achievement/quest journal widget structure) =====
	// Achievement-diary task scroll (Journalscroll, group 741) and quest journal (Questjournal, group
	// 119): dump every text widget's id/index/colour/bounds/text so we can see whether one task is a
	// single widget or wrapped across several QJ lines. Gated on the developer-only "开发者：日志转储"
	// ===== END TEMP DEBUG P1-1 =====

	/**
	 * Translate the chat channel tabs (Game/Public/.../Trade + On/Off) by component id.
	 *
	 * <p>OSRS stacks each tab name and its On/Off filter as two widgets sharing one ~56x22 button,
	 * separated by a single {@code <br>} line: each widget actually holds "token + blank line" (or
	 * "blank line + token") and the client vertically centres that 2-line block, which is how the name
	 * lands in the top half and the filter in the bottom half. Our CJK char-images are taller than a
	 * native line, so once both halves carry glyphs the two blocks overlap vertically. We drop the
	 * {@code <br>} trick and instead pin each widget with real vertical alignment: the name is
	 * top-aligned and the filter bottom-aligned (both horizontally centred), with the filter rendered a
	 * notch smaller than the name. The name uses the empty space at the top while the small green filter
	 * tucks against the bottom, keeping the native top/bottom stacking without the two glyph lines
	 * touching. The "All" tab has no filter, so its name is centred instead of pinned to the top.
	 */
	private void translateChatTabs()
	{
		for (int id : CHAT_TAB_WIDGETS)
		{
			Widget w = client.getWidget(id);
			if (w == null || w.isHidden())
			{
				continue;
			}
			String text = w.getText();
			if (text == null || text.contains("<img="))
			{
				continue; // empty or already our translation
			}
			// the name comes as "Public<br> " and the filter as "<br>On"; strip the <br> so each widget
			// renders a single line that we then pin vertically.
			String clean = text.replace("<br>", " ").trim();
			if (clean.isEmpty())
			{
				continue;
			}
			boolean isFilter = CHAT_TAB_FILTERS.contains(id);
			boolean nameOnly = id == InterfaceID.Chatbox.CHAT_ALL_TEXT1; // "All" tab has no filter line
			int size = isFilter ? CHAT_TAB_FILTER_SIZE : CHAT_TAB_NAME_SIZE;
			String r = translator.lookupRenderUi(clean, w.getTextColor(), 0, size, config.aiFillInterface());
			if (r == null)
			{
				continue;
			}
			int yAlign;
			if (isFilter)
			{
				yAlign = WidgetTextAlignment.BOTTOM;
			}
			else if (nameOnly)
			{
				yAlign = WidgetTextAlignment.CENTER;
			}
			else
			{
				yAlign = WidgetTextAlignment.TOP;
			}
			tabOriginal.put(id, text);
			tabAlignX.putIfAbsent(id, w.getXTextAlignment());
			tabAlignY.putIfAbsent(id, w.getYTextAlignment());
			w.setText(r);
			w.setXTextAlignment(WidgetTextAlignment.CENTER);
			w.setYTextAlignment(yAlign);
			w.setTextShadowed(false);
		}
	}

	/** Put the English chat tab labels back (English toggle). */
	private void restoreChatTabs()
	{
		for (int id : CHAT_TAB_WIDGETS)
		{
			Widget w = client.getWidget(id);
			String orig = tabOriginal.get(id);
			if (w != null && orig != null && w.getText() != null && w.getText().contains("<img="))
			{
				w.setText(orig);
				Integer ax = tabAlignX.get(id);
				Integer ay = tabAlignY.get(id);
				if (ax != null)
				{
					w.setXTextAlignment(ax);
				}
				if (ay != null)
				{
					w.setYTextAlignment(ay);
				}
			}
		}
		tabOriginal.clear();
		tabAlignX.clear();
		tabAlignY.clear();
	}

	/**
	 * Re-translate only the groups the client rewrites every frame. Call from a per-frame hook
	 * (before render). Walk only known small roots so it costs almost nothing the rest of the time.
	 */
	public void translateRedraw()
	{
		// Per-frame freshness pass: walk every visible interface root and re-translate, so any text the
		// client rewrote to English this frame (panel switch/redraw, per-frame tooltip rewrite, etc.) is
		// translated before it is drawn. This is what kills the "flash" generally instead of per-panel.
		// walk() prunes hidden subtrees and translateWidget short-circuits already-translated widgets
		// (lastSet), so the pass is cheap. Hit-test-sensitive panels (EXCLUDE_FROM_PERFRAME) are skipped
		// here and kept fresh by the per-tick backstop + their script hook.
		scan(true);
		translateChatTabs();
	}

	private void scan(boolean perFrame)
	{
		Widget[] roots = client.getWidgetRoots();
		if (roots == null)
		{
			return;
		}
		for (Widget root : roots)
		{
			walk(root, perFrame);
		}
	}

	/**
	 * Translate a single interface group's subtree right now. Called from a script hook the moment a
	 * client script rebuilds that group's text (e.g. opening/switching to the Character Summary panel),
	 * so the freshly written English is translated before the frame draws instead of flashing until the
	 * next per-tick scan. One-shot per call - not a per-frame path - so it never fights the panel's own
	 * hover/tooltip widgets (unlike adding the group to REDRAW_GROUPS, which broke its mouseover).
	 */
	public void translateGroup(int rootComponentId)
	{
		Widget root = client.getWidget(rootComponentId);
		if (root == null || root.isHidden())
		{
			return;
		}
		walk(root, false);
	}

	/**
	 * Like {@link #translateGroup} but for a whole interface group given only its group id: translate every
	 * loaded root that belongs to {@code groupId}. Use when the group has no single named root component
	 * (e.g. the old skill guide): we walk whatever root the client registered for it, exactly as the
	 * per-tick scan reaches it, instead of guessing which component index is the container.
	 */
	/**
	 * Translate one component (and its subtree) directly via {@code client.getWidget(componentId)}. Needed
	 * for components a group-root walk can't reach: e.g. the skill guide's TITLE / CATEGORIES are group 214
	 * but live outside the root that {@link #translateGroupId} walks, so they must be addressed directly.
	 */
	public void translateComponent(int componentId)
	{
		Widget w = client.getWidget(componentId);
		if (w == null || w.isHidden())
		{
			return;
		}
		walk(w, false);
	}

	// True while the game-tick slow lane is walking its groups; client-thread only, so a plain field.
	private boolean slowLane;

	/**
	 * Translate the {@link SurfaceRegistry.Surface#slowScan} groups. Called once per game tick (600ms)
	 * instead of the 50x/s client-tick scan: these are big miss-heavy lists whose recursion and lookup
	 * cost at client-tick rate tanks the frame rate. A full-tree walk (not translateGroupId) because
	 * side panels live nested inside the top-level root, not as their own widget root; non-slowScan
	 * widgets are skipped in translateWidget, so the fast lanes' work is not duplicated.
	 */
	public void translateSlowGroups()
	{
		slowLane = true;
		try
		{
			scan(false);
		}
		finally
		{
			slowLane = false;
		}
	}

	public void translateGroupId(int groupId)
	{
		Widget[] roots = client.getWidgetRoots();
		if (roots == null)
		{
			return;
		}
		for (Widget root : roots)
		{
			if (root != null && (root.getId() >>> 16) == groupId)
			{
				walk(root, false);
			}
		}
	}

	/**
	 * Re-translate whatever surface(s) the just-fired client script rebuilds, per the registry. Called from
	 * onScriptPostFired so a panel's freshly-written English is translated before the frame draws, instead of
	 * flashing until the next per-tick scan. No-op for scripts no surface registers.
	 */
	public void translateForScript(int scriptId)
	{
		java.util.List<SurfaceRegistry.Target> targets = registry.forScript(scriptId);
		if (targets == null)
		{
			return;
		}
		for (SurfaceRegistry.Target t : targets)
		{
			switch (t.reach)
			{
				case GROUP_ROOT:
					translateGroup(t.id);
					break;
				case GROUP_ID:
					translateGroupId(t.id);
					break;
				case COMPONENT:
					translateComponent(t.id);
					break;
			}
		}
	}

	private void walk(Widget w, boolean perFrame)
	{
		// Prune entire hidden subtrees instead of recursing into them (translateWidget would skip each
		// hidden widget anyway, but recursing into a closed panel's deep tree is wasted work - RuneLite's
		// own UI lags on deep hidden trees without this). This makes the scan cheap enough to raise to
		// per-frame later, and lowers the current per-tick cost now. Visible widgets are unaffected.
		if (w == null || w.isHidden())
		{
			return;
		}
		// Prune slow-scan subtrees from the fast lanes entirely: merely skipping them in
		// translateWidget still recurses hundreds of rows and clones their child arrays 50x/s,
		// which is what actually tanked FPS with the world switcher open.
		if (!slowLane && registry.forGroup(w.getId() >>> 16).slowScan)
		{
			return;
		}
		translateWidget(w, perFrame);
		walkArray(w.getStaticChildren(), perFrame);
		walkArray(w.getDynamicChildren(), perFrame);
		walkArray(w.getNestedChildren(), perFrame);
	}

	private void walkArray(Widget[] children, boolean perFrame)
	{
		if (children == null)
		{
			return;
		}
		for (Widget c : children)
		{
			walk(c, perFrame);
		}
	}

	private void translateWidget(Widget w, boolean perFrame)
	{
		if (w == null || w.isHidden())
		{
			return;
		}
		// Decide by the widget's own group (info boxes can be nested under another group's root). All policy
		// comes from the registry: excluded/chatbox never translate; the per-frame pass additionally skips
		// hit-test-sensitive panels (kept on the tick backstop + their script hook, to not break mouseover);
		// reconstruct groups are owned by reconstructJournals.
		int grp = w.getId() >>> 16;
		SurfaceRegistry.Surface s = registry.forGroup(grp);
		if (s.excluded || (perFrame && s.perFrameExcluded) || (s.slowScan != slowLane))
		{
			// slowScan != slowLane: fast lanes skip slow groups; the slow lane only translates them
			// (it re-walks the whole tree to reach nested side panels, so skip everything else).
			return;
		}
		if (s.reconstruct && config.reconstructJournals())
		{
			return; // whole-task reconstruction owns these groups (see reconstructJournals)
		}
		int size = s.small ? glyph.smallSize() : glyph.uiSize();
		boolean aiFallback = config.aiFillInterface() && !s.noAi;
		long key = widgetKey(w);
		String text = w.getText();
		if (text == null || text.isEmpty())
		{
			return;
		}
		if (text.contains("<img="))
		{
			// already shows our translation. Revisit if it was partial (AI pending), or if the widget's
			// colour changed since we baked the glyphs - char-images don't follow a native recolour, so
			// hover highlights (e.g. the music list) need a re-render in the new colour.
			Integer baked = lastColor.get(key);
			boolean recolour = baked != null && baked != w.getTextColor();
			if (!incomplete.contains(key) && !recolour)
			{
				return;
			}
			String eng = original.get(key);
			if (eng == null)
			{
				incomplete.remove(key);
				return;
			}
			text = eng; // re-translate from the stored English, not the partial char-images
		}
		else if (text.equals(lastSet.get(key)))
		{
			return;
		}

		int maxChars = glyph.wrapChars(w.getWidth() - WRAP_PAD, size);
		// narrow widgets (buttons like emote/chat tabs) shouldn't auto-wrap short labels into a mess
		if (maxChars < MIN_WRAP)
		{
			maxChars = 0;
		}

		// Look the whole widget up as one templated string (numbers -> <Num0>...), so multi-line info
		// boxes (prayer/spell/XP) and any numbered label match a single table entry. Info boxes also
		// fall back to AI for description lines the table doesn't have (e.g. "Requires Mage Arena I").
		// Reconstruct groups reaching this point mean the reflow toggle is OFF: their per-widget lines
		// are wrapped sentence halves, so translate them but never collect (same for noCollect panels).
		Translator.Rendered r = (s.reconstruct || s.noCollect)
				? translator.renderUiNoCollect(text, w.getTextColor(), maxChars, size, aiFallback)
				: translator.renderUi(text, w.getTextColor(), maxChars, size, aiFallback);
		if (r == null)
		{
			return;
		}
		original.put(key, text); // remember the English we are replacing, for instant restore
		w.setText(r.text);
		// native text shadow draws the glyph twice at a 1px offset, which on char-images looks
		// like doubled/overlapping characters (e.g. the chat tab buttons) - turn it off.
		w.setTextShadowed(false);
		if (r.text.contains("<br>"))
		{
			w.setLineHeight(glyph.glyphHeight(size));
		}
		lastSet.put(key, r.text);
		lastColor.put(key, w.getTextColor());
		// keep retrying partial boxes (AI lines pending) until every translatable line is done
		if (r.complete)
		{
			incomplete.remove(key);
		}
		else
		{
			incomplete.add(key);
		}
	}

	/** Put the original English back on every interface widget we translated (instant EN switch). */
	public void restore()
	{
		Widget[] roots = client.getWidgetRoots();
		if (roots != null)
		{
			for (Widget root : roots)
			{
				restoreWalk(root);
			}
		}
		restoreChatTabs();
		restoreProseScroll();
		lastSet.clear();
		lastColor.clear();
		original.clear();
		incomplete.clear();
		movedX.clear();
		movedY.clear();
		movedW.clear();
		placedX.clear();
		placedY.clear();
		// the 860 signatures must die with the state: a surviving committedSig would match the very first
		// re-derived frame after an EN->CN toggle and SKIP forever, sticking the guide in English
		committedSig = null;
		failedSig = null;
		lastProseSig = "";
		settleTicks = 0;
		pendingCollect.clear();
	}

	private void restoreWalk(Widget w)
	{
		if (w == null)
		{
			return;
		}
		long key = widgetKey(w);
		String orig = original.get(key);
		// restore if the widget still holds our translation: either the exact string we last wrote, or
		// any char-image text (it may have been re-translated/AI-upgraded to a string != lastSet, which
		// would otherwise get stuck in Chinese on the next English toggle).
		String cur = w.getText();
		if (orig != null && cur != null && (cur.equals(lastSet.get(key)) || cur.contains("<img=")))
		{
			w.setText(orig);
			if ((key >>> 21) == (PROSE_COMPONENT_ID & 0xFFFFFFFFL))
			{
				w.setTextShadowed(true); // 860 prose is natively shadowed; we disabled it for glyphs
			}
		}
		revertGeom(key, w); // 860 prose widgets we compacted: put back at their native x/y
		restoreArray(w.getStaticChildren());
		restoreArray(w.getDynamicChildren());
		restoreArray(w.getNestedChildren());
	}

	private void restoreArray(Widget[] children)
	{
		if (children == null)
		{
			return;
		}
		for (Widget c : children)
		{
			restoreWalk(c);
		}
	}

	/** Forget what we translated so everything re-translates (e.g. after a font-size change). Client thread. */
	public void reset()
	{
		// put the 860 prose back to English FIRST: clearing the maps below would orphan its stored English
		// (the column would keep old-size char-images with no way back until the client rebuilds it)
		revertSkillGuide();
		lastSet.clear();
		lastColor.clear();
		original.clear();
		incomplete.clear();
		tabOriginal.clear();
		journalSig.clear();
		movedX.clear();
		movedY.clear();
		movedW.clear();
		placedX.clear();
		placedY.clear();
	}
}
