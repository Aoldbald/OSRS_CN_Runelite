package com.osrscn.sprite;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;
import javax.imageio.ImageIO;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Guards the only link between a sprite id and its redrawn PNG. A typo here is silent in the
 * client - the sprite simply stays English - and a resized canvas would paint over the game.
 */
public class SpriteEntriesTest
{
	@Test
	public void everyEntryHasAResourceOnTheClasspath() throws Exception
	{
		for (SpriteEntries entry : SpriteEntries.values())
		{
			try (InputStream in = SpriteEntries.class.getResourceAsStream(entry.getFileName()))
			{
				assertNotNull("missing resource for " + entry.name() + ": "
						+ entry.getFileName() + " (expected next to SpriteEntries.class)", in);
				BufferedImage image = ImageIO.read(in);
				assertNotNull("not a readable image: " + entry.getFileName(), image);
				assertEquals(entry.name() + " canvas width", entry.getWidth(), image.getWidth());
				assertEquals(entry.name() + " canvas height", entry.getHeight(), image.getHeight());
			}
		}
	}

	@Test
	public void spriteIdsAndFileNamesAreUnique()
	{
		Set<Integer> ids = new HashSet<>();
		Set<String> names = new HashSet<>();
		for (SpriteEntries entry : SpriteEntries.values())
		{
			assertTrue("duplicate sprite id " + entry.getSpriteId(), ids.add(entry.getSpriteId()));
			assertTrue("duplicate file name " + entry.getFileName(), names.add(entry.getFileName()));
		}
	}

	@Test
	public void fileNamesFollowTheDocumentedConvention()
	{
		for (SpriteEntries entry : SpriteEntries.values())
		{
			String expectedPrefix = String.format("%05d_", entry.getSpriteId());
			assertTrue(entry.getFileName() + " should start with " + expectedPrefix,
					entry.getFileName().startsWith(expectedPrefix));
			assertTrue(entry.getFileName() + " should end with _zh.png",
					entry.getFileName().endsWith("_zh.png"));
		}
	}
}
