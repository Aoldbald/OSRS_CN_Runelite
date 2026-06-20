package com.osrscn.ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.font.FontRenderContext;
import java.awt.geom.Rectangle2D;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Briefly shows the current language in the top-centre of the viewport when toggled, instead of
 * spamming the chat box with a message on every switch. Overlays draw with a real AWT font, so we
 * render the text directly (no char-images needed).
 */
@Singleton
public class ToggleOverlay extends Overlay
{
	private static final long FLASH_MS = 1400;
	private static final Font FONT = resolveFont();

	private volatile long until;
	private volatile String text = "";

	@Inject
	ToggleOverlay()
	{
		setPosition(OverlayPosition.TOP_CENTER);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	/** Show {@code message} for a short while. */
	public void flash(String message)
	{
		this.text = message;
		this.until = System.currentTimeMillis() + FLASH_MS;
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		long now = System.currentTimeMillis();
		if (now > until || text.isEmpty())
		{
			return null;
		}
		g.setFont(FONT);
		FontRenderContext frc = g.getFontRenderContext();
		Rectangle2D b = FONT.getStringBounds(text, frc);
		int padX = 8;
		int padY = 4;
		int w = (int) b.getWidth() + padX * 2;
		int h = (int) b.getHeight() + padY * 2;

		// fade out over the last 400ms
		long left = until - now;
		float alpha = left > 400 ? 1f : Math.max(0f, left / 400f);

		g.setColor(new Color(0f, 0f, 0f, 0.6f * alpha));
		g.fillRoundRect(0, 0, w, h, 8, 8);
		g.setColor(new Color(1f, 0.6f, 0.12f, alpha)); // brand orange
		int baseline = padY + (int) -b.getY();
		g.drawString(text, padX, baseline);
		return new Dimension(w, h);
	}

	private static Font resolveFont()
	{
		for (String name : new String[]{"Microsoft YaHei", "PingFang SC", "SimHei"})
		{
			Font f = new Font(name, Font.BOLD, 16);
			if (f.getFamily().equalsIgnoreCase(name) && f.canDisplay('中'))
			{
				return f;
			}
		}
		return new Font(Font.SANS_SERIF, Font.BOLD, 16);
	}
}
