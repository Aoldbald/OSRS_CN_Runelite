package com.osrscn.hooks;

import com.osrscn.glyph.GlyphService;
import com.osrscn.translate.Translator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
	private final Map<Long, String> original = new HashMap<>(); // -> original text, for instant restore
	// per journal root: the raw slot text the last tick saw, to debounce the client's multi-stage population
	private final Map<Integer, String> journalSig = new HashMap<>();
	// widgets we translated only partially (some AI lines pending): re-translate until complete
	private final Set<Long> incomplete = new HashSet<>();
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
		if (s.excluded || (perFrame && s.perFrameExcluded))
		{
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
			// already shows our translation. Revisit only if it was partial (AI pending) so we can
			// upgrade it once the missing lines are cached; otherwise it would stay half-English forever.
			if (!incomplete.contains(key))
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
		Translator.Rendered r = translator.renderUi(text, w.getTextColor(), maxChars, size, aiFallback);
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
		lastSet.clear();
		original.clear();
		incomplete.clear();
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
		}
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

	/** Forget what we translated so everything re-translates (e.g. after a font-size change). */
	public void reset()
	{
		lastSet.clear();
		original.clear();
		incomplete.clear();
		tabOriginal.clear();
		journalSig.clear();
	}
}
