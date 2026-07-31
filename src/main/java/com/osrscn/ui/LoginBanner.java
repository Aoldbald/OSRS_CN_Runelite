package com.osrscn.ui;

import com.osrscn.OsrscnPlugin;
import com.osrscn.glyph.GlyphService;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.SpritePixels;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.util.ImageUtil;

/**
 * Replaces the login screen banner art with a localised Chinese version, by overriding the sprite
 * the welcome-screen banner widget draws. The image is bundled (credit: RuneLingual); the version /
 * origin line is drawn on in code so the bundled PNG never has to be re-exported.
 */
@Slf4j
@Singleton
public class LoginBanner
{
	private static final String RESOURCE = "/com/osrscn/login_banner_zh.png";

	// Origin line, placed in the empty dark strip below the artwork, which ends about three quarters of
	// the way down and leaves the bottom band clear across the full width.
	//
	// Every measurement is a fraction of the image, never a pixel count: the dev banner is 1952x544 and
	// the released one is 480x134 (shrunk to satisfy the Plugin Hub image check), so hard-coded
	// coordinates draw off-canvas for real users while looking right in a source build - and the drift
	// check whitelists this file, so nothing would have caught it.
	private static final double NOTICE_X_FRAC = 0.025;
	private static final double NOTICE_BASELINE_FRAC = 0.92;
	private static final double NOTICE_SIZE_FRAC = 0.088;
	private static final double NOTICE_MAX_WIDTH_FRAC = 0.62; // shrink rather than run into the art
	private static final Color NOTICE_COLOR = new Color(0xff, 0x98, 0x1f);
	private static final Color NOTICE_SHADOW = new Color(0, 0, 0, 190);
	private static final String NOTICE_TAIL = " 只在 RuneLite 插件库发布，永久免费";

	@Inject
	private Client client;
	@Inject
	private ClientThread clientThread;
	@Inject
	private GlyphService glyph;

	private SpritePixels sprite;

	public void apply()
	{
		clientThread.invoke(() ->
		{
			SpritePixels s = banner();
			if (s != null)
			{
				client.getWidgetSpriteOverrides().put(InterfaceID.WelcomeScreen.BANNER_ARTCANVAS, s);
			}
		});
	}

	public void remove()
	{
		clientThread.invoke(() ->
				client.getWidgetSpriteOverrides().remove(InterfaceID.WelcomeScreen.BANNER_ARTCANVAS));
	}

	private SpritePixels banner()
	{
		if (sprite != null)
		{
			return sprite;
		}
		try
		{
			BufferedImage img = ImageUtil.loadImageResource(LoginBanner.class, RESOURCE);
			drawNotice(img);
			sprite = ImageUtil.getImageSpritePixels(img, client);
		}
		catch (Exception e)
		{
			log.warn("OSRSCN: failed to load login banner", e);
		}
		return sprite;
	}

	/**
	 * Draw the version / origin line onto the banner, using the plugin's own glyph font so every
	 * character is one the bundled subset covers.
	 */
	private void drawNotice(BufferedImage img)
	{
		int size = Math.max(8, (int) Math.round(img.getHeight() * NOTICE_SIZE_FRAC));
		Font font = glyph.font(size);
		if (font == null)
		{
			return;
		}
		int x = (int) Math.round(img.getWidth() * NOTICE_X_FRAC);
		int baseline = (int) Math.round(img.getHeight() * NOTICE_BASELINE_FRAC);
		int maxWidth = (int) Math.round(img.getWidth() * NOTICE_MAX_WIDTH_FRAC);
		int shadow = Math.max(1, size / 24);
		String text = "OSRSCN " + OsrscnPlugin.versionLabel() + NOTICE_TAIL;
		Graphics2D g = img.createGraphics();
		try
		{
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			g.setFont(font);
			int width = g.getFontMetrics().stringWidth(text);
			if (width > maxWidth)
			{
				g.setFont(font.deriveFont(font.getSize2D() * maxWidth / width));
			}
			g.setColor(NOTICE_SHADOW);
			g.drawString(text, x + shadow, baseline + shadow);
			g.setColor(NOTICE_COLOR);
			g.drawString(text, x, baseline);
		}
		finally
		{
			g.dispose();
		}
	}
}
