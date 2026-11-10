package com.osrscn.sprite;

import java.awt.image.BufferedImage;
import java.util.EnumMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.SpritePixels;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.util.ImageUtil;

/**
 * Installs the Chinese redraws listed in {@link SpriteEntries}.
 *
 * <p>{@code client.getSpriteOverrides()} is keyed by sprite (archive) id, and the client checks it
 * every time it blits that sprite, so a single {@code put} is enough for the override to take effect
 * everywhere the sprite is used - no widget rebuild, no interface patch.
 *
 * <h2>Details that cost real debugging time</h2>
 * <ul>
 *   <li>{@code getWidgetSpriteCache().reset()} after changing the map: a widget that already drew
 *       the sprite keeps the old bitmap cached and would keep showing English.</li>
 *   <li>Remove only the ids this plugin added. {@code getSpriteOverrides()} is shared with core
 *       plugins (Interface Styles, Poison, ...), so clearing it would strip their art too.</li>
 *   <li>Load through {@link ImageUtil#getImageSpritePixels(BufferedImage, Client)}, not
 *       {@code getImageIndexedSprite}: the latter quantises to a 255-colour palette and drops
 *       partial alpha, which turns near-black pixels transparent.</li>
 *   <li>Everything runs on the client thread; the override map is not thread safe and is read while
 *       drawing.</li>
 * </ul>
 */
@Slf4j
@Singleton
public class SpriteOverrides
{
	@Inject
	private Client client;
	@Inject
	private ClientThread clientThread;

	/** Decoded sprites, kept so a re-enable does not decode the PNGs again. */
	private final Map<SpriteEntries, SpritePixels> decoded = new EnumMap<>(SpriteEntries.class);

	public void install()
	{
		clientThread.invoke(this::putAll);
	}

	public void uninstall()
	{
		clientThread.invoke(this::removeAll);
	}

	private void putAll()
	{
		SpriteEntries[] entries = SpriteEntries.values();
		int applied = 0;
		for (SpriteEntries entry : entries)
		{
			SpritePixels pixels = decode(entry);
			if (pixels == null)
			{
				continue;
			}
			client.getSpriteOverrides().put(entry.getSpriteId(), pixels);
			applied++;
		}
		// widgets that already painted these sprites still hold the English bitmap
		client.getWidgetSpriteCache().reset();
		log.info("OSRSCN: applied {} of {} Chinese sprite overrides", applied, entries.length);
	}

	private void removeAll()
	{
		for (SpriteEntries entry : SpriteEntries.values())
		{
			client.getSpriteOverrides().remove(entry.getSpriteId());
		}
		client.getWidgetSpriteCache().reset();
	}

	private SpritePixels decode(SpriteEntries entry)
	{
		SpritePixels cached = decoded.get(entry);
		if (cached != null)
		{
			return cached;
		}
		try
		{
			// relative to this enum's package: com/osrscn/sprite/<fileName>
			BufferedImage image = ImageUtil.loadImageResource(SpriteEntries.class, entry.getFileName());
			if (image == null)
			{
				log.warn("OSRSCN: sprite image missing: {}", entry.getFileName());
				return null;
			}
			// The sprite is blitted 1:1, so a wrong-sized canvas would spill over the surrounding
			// interface. Refusing it keeps the English original, which is the lesser evil.
			if (image.getWidth() != entry.getWidth() || image.getHeight() != entry.getHeight())
			{
				log.warn("OSRSCN: sprite {} is {}x{} but the original is {}x{}; override skipped",
						entry.getFileName(), image.getWidth(), image.getHeight(),
						entry.getWidth(), entry.getHeight());
				return null;
			}
			SpritePixels pixels = ImageUtil.getImageSpritePixels(image, client);
			decoded.put(entry, pixels);
			return pixels;
		}
		catch (Exception e)
		{
			log.warn("OSRSCN: failed to load sprite {}", entry.getFileName(), e);
			return null;
		}
	}
}
