package com.osrscn.hooks;

import com.osrscn.glyph.GlyphService;
import com.osrscn.text.Tags;
import com.osrscn.translate.TranslationStore.Category;
import com.osrscn.translate.Translator;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.Menu;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Point;
import net.runelite.api.events.MenuOpened;

/**
 * Translates right-click menu entries and supplies translated text for the hover tooltip.
 *
 * <p>A menu entry has an option (the action verb, e.g. "Attack") and a target (the entity, e.g.
 * "{@code <col=ffff00>Goblin</col> (level-2)}"). The verb is looked up in the actions table, the
 * entity name in the name table, and a trailing "(level-N)" is localised; each part is rendered to
 * char-image tags. Lookup only - no AI - so menus never flicker or lag.
 */
@Singleton
public class MenuTranslator
{
	// White is OSRS's default for both a menu option (the verb) and a target with no colour tag; kept as
	// two named constants because they describe different fields, even though the value is the same.
	private static final int OPTION_COLOR = 0xffffff;
	private static final int DEFAULT_TARGET_COLOR = 0xffffff;
	// Current native menu renderer (Client build 32956056058.238): one 15px row, with strict
	// (baseline-13, baseline+3) mouse bounds and a baseline offset of 31px from menuY.
	private static final int NATIVE_HOVER_COLOR = 0xffff00;
	private static final int NATIVE_ROW_HEIGHT = 15;
	private static final int NATIVE_BASELINE_OFFSET = 31;
	private static final int NATIVE_HIT_TOP = 13;
	private static final int NATIVE_HIT_BOTTOM = 3;

	private static final Pattern LEVEL = Pattern.compile("\\((?:level|combat)-(\\d+)\\)", Pattern.CASE_INSENSITIVE);

	// "Use" on an inventory item selects it to apply to something else, and the confirm step reads
	// "Use <item> -> <target>" as a sentence; the generic table word (使用) only suits a direct "Use"
	// on an object/NPC. The distinction is the entry's MenuAction - runtime state the TSV schema can't
	// express - so the two scene words live here instead of in the actions table.
	private static final String USE_SELECT_ZH = "选用";
	private static final String USE_APPLY_ZH = "用";

	@Inject
	private Client client;
	@Inject
	private Translator translator;
	@Inject
	private GlyphService glyph;

	// Entry objects are pooled by the client. Identity locates a snapshot; the action and exact last
	// written strings must still match before that snapshot can repaint or restore anything.
	private final Map<MenuEntry, EntryState> entryStates = new IdentityHashMap<>();

	private static final class EntryState
	{
		final String option;
		final String target;
		final MenuAction type;
		final int identifier;
		final int param0;
		final int param1;
		final int itemId;
		final int worldViewId;
		final Consumer<MenuEntry> onClick;
		String liveOption;
		String liveTarget;
		int optionColor = -1;
		int targetColor = -1;
		boolean optionRendered;
		boolean targetRendered;

		EntryState(MenuEntry entry)
		{
			option = entry.getOption();
			target = entry.getTarget();
			type = entry.getType();
			identifier = entry.getIdentifier();
			param0 = entry.getParam0();
			param1 = entry.getParam1();
			itemId = entry.getItemId();
			worldViewId = entry.getWorldViewId();
			onClick = entry.onClick();
			liveOption = option;
			liveTarget = target;
		}

		boolean owns(MenuEntry entry)
		{
			return entry.getType() == type && entry.getIdentifier() == identifier
					&& entry.getParam0() == param0 && entry.getParam1() == param1
					&& entry.getItemId() == itemId && entry.getWorldViewId() == worldViewId
					&& entry.onClick() == onClick && Objects.equals(entry.getOption(), liveOption)
					&& Objects.equals(entry.getTarget(), liveTarget);
		}
	}

	/** Translate every entry (and sub-entries) of a freshly opened right-click menu, in place. */
	public void handleMenuOpened(MenuOpened event)
	{
		entryStates.clear();
		if (client.getMenu() != null)
		{
			refreshNativeColors();
		}
		else
		{
			translateEntries(event.getMenuEntries(), OPTION_COLOR);
		}
	}

	/**
	 * Re-translate the menu that is currently open. A brand-new glyph's sprite is only uploaded on the
	 * client cycle after it is first registered, so {@link Translator#lookupRender} returns null on the
	 * first right-click and that option/target is left in English. The menu is a one-shot ({@code
	 * onMenuOpened}) with no retry, so it would stay English until the menu is reopened (the "need a
	 * second right-click" bug). Calling this every client tick while the menu stays open lets such
	 * entries pick up their now-ready glyphs a cycle later, exactly like the per-tick interface scan
	 * does. Already-translated entries are no-ops: their char-image text strips to empty, so the lookups
	 * return null and leave them unchanged; untranslatable entries (other plugins, table misses) stay
	 * English as before.
	 */
	public void retranslateOpenMenu()
	{
		if (!client.isMenuOpen())
		{
			return;
		}
		refreshNativeColors();
	}

	private void refreshNativeColors()
	{
		Menu root = client.getMenu();
		if (root == null)
		{
			translateEntries(client.getMenuEntries(), OPTION_COLOR);
			return;
		}
		Point mouse = client.getMouseCanvasPosition();
		MenuEntry hovered = hoveredEntry(root, client.getMenuScroll(), mouse);
		applyMenu(root, hovered);
	}

	private void applyMenu(Menu menu, MenuEntry hovered)
	{
		for (MenuEntry entry : menu.getMenuEntries())
		{
			translateInPlace(entry, entry == hovered ? NATIVE_HOVER_COLOR : OPTION_COLOR);
			Menu sub = entry.getSubMenu();
			if (sub != null)
			{
				applyMenu(sub, hovered);
			}
		}
	}

	private MenuEntry hoveredEntry(Menu menu, int scroll, Point mouse)
	{
		if (mouse == null)
		{
			return null;
		}
		MenuEntry[] entries = menu.getMenuEntries();
		// The native renderer draws an active submenu over its parent. Prefer the deepest menu whose
		// real bounds contain the mouse so root and submenu rows never share hover state.
		for (MenuEntry entry : entries)
		{
			Menu sub = entry.getSubMenu();
			if (sub != null)
			{
				MenuEntry hit = hoveredEntry(sub, 0, mouse);
				if (hit != null)
				{
					return hit;
				}
			}
		}
		int index = nativeEntryAt(menu.getMenuX(), menu.getMenuY(), menu.getMenuWidth(), entries.length,
				scroll, mouse.getX(), mouse.getY());
		return index >= 0 ? entries[index] : null;
	}

	static int nativeEntryAt(int menuX, int menuY, int menuWidth, int entryCount, int scroll,
			int mouseX, int mouseY)
	{
		for (int index = 0; index < entryCount; index++)
		{
			if (entryCount - 1 - index < scroll)
			{
				continue;
			}
			int baseline = menuY + (entryCount - 1 - index - scroll) * NATIVE_ROW_HEIGHT
					+ NATIVE_BASELINE_OFFSET;
			if (mouseX > menuX && mouseX < menuX + menuWidth
					&& mouseY > baseline - NATIVE_HIT_TOP && mouseY < baseline + NATIVE_HIT_BOTTOM)
			{
				return index;
			}
		}
		return -1;
	}

	private void translateEntries(MenuEntry[] entries, int color)
	{
		for (MenuEntry entry : entries)
		{
			translateInPlace(entry, color);
			Menu sub = entry.getSubMenu();
			if (sub != null)
			{
				translateEntries(sub.getMenuEntries(), color);
			}
		}
	}

	private void translateInPlace(MenuEntry entry, int rowColor)
	{
		if (entry.getType().name().startsWith("RUNELITE"))
		{
			entryStates.remove(entry);
			return; // keep custom plugin actions matchable by their English text
		}
		EntryState state = entryStates.get(entry);
		if (state != null && !state.owns(entry))
		{
			entryStates.remove(entry);
			state = null;
		}
		if (state == null)
		{
			String option = entry.getOption();
			String target = entry.getTarget();
			if (option == null || target == null || option.contains("<img=") || target.contains("<img="))
			{
				return;
			}
			state = new EntryState(entry);
			entryStates.put(entry, state);
		}

		if (!state.optionRendered || state.optionColor != rowColor)
		{
			String opt = translateOption(state.option, state.type, glyph.uiSize(), rowColor);
			if (opt != null)
			{
				entry.setOption(opt);
				state.liveOption = opt;
				state.optionRendered = true;
				state.optionColor = rowColor;
			}
		}
		int targetColor = Tags.firstColor(state.target, rowColor);
		if (!state.targetRendered || state.targetColor != targetColor)
		{
			String tgt = translateTarget(state.target, glyph.uiSize(), rowColor);
			if (tgt != null)
			{
				entry.setTarget(tgt);
				state.liveTarget = tgt;
				state.targetRendered = true;
				state.targetColor = targetColor;
			}
		}
	}

	/**
	 * Put the English option/target back on a clicked entry, so plugins that match on English text (and
	 * the game's own action handling) work. Safe because actions execute by opcode/params, not text, and
	 * the menu is already closed - this has no visual effect.
	 */
	public void restoreForClick(MenuEntry entry)
	{
		if (entry == null)
		{
			return;
		}
		EntryState state = entryStates.remove(entry);
		if (state == null || !state.owns(entry))
		{
			return;
		}
		if (state.optionRendered) entry.setOption(state.option);
		if (state.targetRendered) entry.setTarget(state.target);
	}

	/**
	 * @param type the entry's MenuAction, used to pick the scene word for "Use" (null = generic)
	 * @return rendered option, or null to leave the English option unchanged
	 */
	public String translateOption(String option, MenuAction type, int size)
	{
		return translateOption(option, type, size, OPTION_COLOR);
	}

	private String translateOption(String option, MenuAction type, int size, int color)
	{
		// Scene-specific "Use" must run before the table lookups, which would return the generic word.
		String use = useZh(Tags.stripTags(option), type);
		if (use != null)
		{
			return glyph.toImgTags(use, color, 0, size);
		}
		// Whole-option entries that embed a coloured name ("Open <col=..>Ardougne Journal</col>") are stored
		// colour-templated in ACTIONS; match that first so the embedded name is translated and keeps its colour.
		String whole = translator.lookupRenderMenuOption(option, color, size);
		if (whole != null)
		{
			return whole;
		}
		String plain = Tags.stripTags(option);
		if (plain.isEmpty())
		{
			return null;
		}
		// general world actions live in ACTIONS; item actions (Wear/Wield/Eat/Drink/...) in INVENTORY_ACTIONS
		String r = translator.lookupRender(Category.ACTIONS, plain, color, 0, size);
		if (r == null)
		{
			r = translator.lookupRender(Category.INVENTORY_ACTIONS, plain, color, 0, size);
		}
		if (r == null)
		{
			// some menu options are interface/tab labels (e.g. "Sailing Options") stored in INTERFACE
			r = translator.lookupRender(Category.INTERFACE, plain, color, 0, size);
		}
		return r;
	}

	/** Scene word for a "Use" option, or null to fall through to the table translation (使用). */
	private static String useZh(String plainOption, MenuAction type)
	{
		if (type == null || !"Use".equals(plainOption))
		{
			return null;
		}
		switch (type)
		{
			case WIDGET_TARGET:
				return USE_SELECT_ZH; // selecting the inventory item to use on something else
			case WIDGET_TARGET_ON_WIDGET:
			case WIDGET_TARGET_ON_GAME_OBJECT:
			case WIDGET_TARGET_ON_NPC:
			case WIDGET_TARGET_ON_PLAYER:
			case WIDGET_TARGET_ON_GROUND_ITEM:
				return USE_APPLY_ZH; // "Use <item> -> <target>" reads as 用 <item> -> <target>
			default:
				return null; // direct "Use" on an object/NPC keeps the generic word
		}
	}

	/** @return rendered target (name + localised level), or null to leave the English target */
	public String translateTarget(String target, int size)
	{
		return translateTarget(target, size, DEFAULT_TARGET_COLOR);
	}

	private String translateTarget(String target, int size, int rowColor)
	{
		// "Use <item> -> <target>" (item selected, hovering another) joins two names; the combined
		// string never matches the name table, so translate each side and keep the separator.
		int arrow = target.indexOf(" -> ");
		if (arrow >= 0)
		{
			String left = target.substring(0, arrow);
			String right = target.substring(arrow + 4);
			String lr = translateTarget(left, size, rowColor);
			String rr = translateTarget(right, size, rowColor);
			if (lr == null && rr == null)
			{
				return null;
			}
			return (lr != null ? lr : left) + " -> " + (rr != null ? rr : right);
		}
		String plainTarget = Tags.stripTags(target);
		if (plainTarget.isEmpty())
		{
			return null;
		}
		int color = Tags.firstColor(target, rowColor);

		String levelSuffix = "";
		Matcher lm = LEVEL.matcher(plainTarget);
		if (lm.find())
		{
			String lvl = translator.lookupRender(Category.GAME_TEXT, "level", color, 0, size);
			String lvlText = (lvl != null) ? lvl : "level";
			levelSuffix = "<col=" + Tags.hex(color) + "> (" + lvlText + "-" + lm.group(1) + ")</col>";
		}

		String name = LEVEL.matcher(plainTarget).replaceAll("").trim();
		String renderedName = translator.lookupRender(Category.NAME, name, color, 0, size);
		if (renderedName == null)
		{
			// some targets are interface/tab labels (e.g. "Sailing Options") stored in INTERFACE
			renderedName = translator.lookupRender(Category.INTERFACE, name, color, 0, size);
		}
		if (renderedName == null)
		{
			// skill-guide link targets ("Courses tab", "Cook's Assistant quest") live in SKILL_GUIDE
			renderedName = translator.lookupRender(Category.SKILL_GUIDE, name, color, 0, size);
		}
		if (renderedName == null)
		{
			return null; // not in table (or not ready): leave English so it stays readable
		}
		return renderedName + levelSuffix;
	}

}
