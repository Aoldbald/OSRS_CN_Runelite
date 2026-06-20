package com.osrscn.ui;

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
 * the welcome-screen banner widget draws. The image is bundled (credit: RuneLingual).
 */
@Slf4j
@Singleton
public class LoginBanner
{
	private static final String RESOURCE = "/com/osrscn/login_banner_zh.png";

	@Inject
	private Client client;
	@Inject
	private ClientThread clientThread;

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
			sprite = ImageUtil.getImageSpritePixels(img, client);
		}
		catch (Exception e)
		{
			log.warn("OSRSCN: failed to load login banner", e);
		}
		return sprite;
	}
}
