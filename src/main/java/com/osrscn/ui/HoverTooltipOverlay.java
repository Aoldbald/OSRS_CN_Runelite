package com.osrscn.ui;

import com.osrscn.OsrscnConfig;
import com.osrscn.ToggleService;
import com.osrscn.glyph.GlyphService;
import com.osrscn.hooks.MenuTranslator;
import java.awt.Dimension;
import java.awt.Graphics2D;
import javax.inject.Inject;
import net.runelite.api.Client;
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
	private final Client client;
	private final TooltipManager tooltipManager;
	private final ToggleService toggle;
	private final OsrscnConfig config;
	private final MenuTranslator menuTranslator;
	private final GlyphService glyph;

	@Inject
	HoverTooltipOverlay(Client client, TooltipManager tooltipManager, ToggleService toggle,
			OsrscnConfig config, MenuTranslator menuTranslator, GlyphService glyph)
	{
		this.client = client;
		this.tooltipManager = tooltipManager;
		this.toggle = toggle;
		this.config = config;
		this.menuTranslator = menuTranslator;
		this.glyph = glyph;
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
		if (client.getVarcIntValue(VarClientInt.TOOLTIP_TIMEOUT) > client.getGameCycle()
				|| client.getVarcIntValue(VarClientInt.TOOLTIP_VISIBLE) == 1)
		{
			return null;
		}

		String optZh = menuTranslator.translateOption(option, glyph.smallSize());
		String tgtZh = (target == null || target.isEmpty()) ? null
				: menuTranslator.translateTarget(target, glyph.smallSize());
		if (optZh == null && tgtZh == null)
		{
			return null; // nothing translated yet - let the native text stand
		}

		String optPart = (optZh != null) ? optZh : option;
		String tgtPart = (tgtZh != null) ? tgtZh : (target == null ? "" : target);
		String text = tgtPart.isEmpty() ? optPart : optPart + " " + tgtPart;
		tooltipManager.addFront(new Tooltip(text));
		return null;
	}

	private static boolean isTrivial(String option)
	{
		return option.equals("Walk here") || option.equals("Cancel") || option.equals("Continue");
	}
}
