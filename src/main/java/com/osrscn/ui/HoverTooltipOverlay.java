package com.osrscn.ui;

import com.osrscn.OsrscnConfig;
import com.osrscn.ToggleService;
import com.osrscn.glyph.GlyphService;
import com.osrscn.hooks.MenuTranslator;
import com.osrscn.translate.TranslationStore;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.Objects;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.VarClientInt;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.tooltip.Tooltip;
import net.runelite.client.ui.overlay.tooltip.TooltipManager;

/**
 * Draws a translated tooltip near the cursor for the default (left-click) menu entry, so the
 * top-left hover action text is shown in Chinese without rewriting the native UI text.
 */
public class HoverTooltipOverlay extends Overlay
{
	// A part that fell back to English may just be waiting on a glyph sprite upload (one client cycle),
	// so an incomplete result is retried for this many cycles before it is latched.
	private static final int RETRY_CYCLES = 10;

	private final Client client;
	private final TooltipManager tooltipManager;
	private final ToggleService toggle;
	private final OsrscnConfig config;
	private final MenuTranslator menuTranslator;
	private final GlyphService glyph;
	private final TranslationStore store;

	// One-entry memo (only one entry is hovered at a time). Translating costs a full table-lookup
	// pipeline plus a miss-collector write, and render() runs on every drawn frame while the hovered
	// entry almost never changes; misses are memoised too, since the miss path is the expensive one.
	// Client thread only (Overlay#render), so no synchronisation.
	private String memoOption;
	private String memoTarget;
	private MenuAction memoType;
	private int memoSize;
	private String memoFontPath;
	private int memoFontSize;
	private int memoStoreSize;
	private String memoText;      // tooltip to draw, or null for "nothing translated"
	private boolean memoComplete; // false while some part is still English
	private int memoCycle;        // game cycle the memo inputs were first seen at

	@Inject
	HoverTooltipOverlay(Client client, TooltipManager tooltipManager, ToggleService toggle,
			OsrscnConfig config, MenuTranslator menuTranslator, GlyphService glyph, TranslationStore store)
	{
		this.client = client;
		this.tooltipManager = tooltipManager;
		this.toggle = toggle;
		this.config = config;
		this.menuTranslator = menuTranslator;
		this.glyph = glyph;
		this.store = store;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!toggle.isChineseEnabled() || !config.translateMenus() || client.isMenuOpen())
		{
			return null;
		}

		MenuEntry[] entries = client.getMenuEntries();
		if (entries.length == 0)
		{
			return null;
		}
		MenuEntry entry = entries[entries.length - 1];
		String option = entry.getOption();
		String target = entry.getTarget();
		if (option == null || option.isEmpty() || isTrivial(option))
		{
			return null;
		}

		// don't fight the client's own tooltip handling
		int cycle = client.getGameCycle();
		if (client.getVarcIntValue(VarClientInt.TOOLTIP_TIMEOUT) > cycle
				|| client.getVarcIntValue(VarClientInt.TOOLTIP_VISIBLE) == 1)
		{
			return null;
		}

		MenuAction type = entry.getType();
		int size = glyph.smallSize();
		// fontPath/mainFontSize are the settings that trigger GlyphService#reloadFont, which invalidates
		// every cached <img=N>; the table size changes when the store finishes (or grows) its load.
		String fontPath = config.fontPath();
		int fontSize = config.mainFontSize();
		int storeSize = store.size();
		boolean fresh = Objects.equals(option, memoOption)
				&& Objects.equals(target, memoTarget)
				&& type == memoType
				&& size == memoSize
				&& fontSize == memoFontSize
				&& storeSize == memoStoreSize
				&& Objects.equals(fontPath, memoFontPath);
		if (!fresh || (!memoComplete && cycle - memoCycle < RETRY_CYCLES))
		{
			String optZh = menuTranslator.translateOption(option, type, size);
			String tgtZh = (target == null || target.isEmpty()) ? null
					: menuTranslator.translateTarget(target, size);
			String optPart = (optZh != null) ? optZh : option;
			String tgtPart = (tgtZh != null) ? tgtZh : (target == null ? "" : target);
			memoText = (optZh == null && tgtZh == null) ? null
					: (tgtPart.isEmpty() ? optPart : optPart + " " + tgtPart);
			memoComplete = optZh != null && (tgtZh != null || tgtPart.isEmpty());
			if (!fresh)
			{
				memoCycle = cycle;
				memoOption = option;
				memoTarget = target;
				memoType = type;
				memoSize = size;
				memoFontPath = fontPath;
				memoFontSize = fontSize;
				memoStoreSize = storeSize;
			}
		}
		if (memoText == null)
		{
			return null; // nothing translated yet - let the native text stand
		}

		tooltipManager.addFront(new Tooltip(memoText));
		return null;
	}

	private static boolean isTrivial(String option)
	{
		return option.equals("Walk here") || option.equals("Cancel") || option.equals("Continue");
	}
}
