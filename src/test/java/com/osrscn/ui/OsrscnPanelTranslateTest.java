package com.osrscn.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Pins down the manual-translate pre-check in {@link OsrscnPanel}: text with no translatable English
 * must be reported straight away ("nothing to translate") instead of being polled for 15s and then
 * showing the misleading "translation timed out" message, because {@code Translator.aiTranslate}
 * refuses to dispatch letter-free text at all.
 *
 * <p>Only the package-private static predicate is touched - no Swing component and no panel instance
 * is created, so this needs no display and no client.
 */
public class OsrscnPanelTranslateTest
{
	@Test
	public void untranslatableInputNeedsNoTranslation()
	{
		assertFalse(OsrscnPanel.needsTranslation("429"));
		assertFalse(OsrscnPanel.needsTranslation("你好"));
		assertFalse(OsrscnPanel.needsTranslation("!!!"));
		// The AI guard strips tags/placeholders before looking for letters, so these need no request.
		assertFalse(OsrscnPanel.needsTranslation("<colNum0>429</col>"));
		assertFalse(OsrscnPanel.needsTranslation("<osrscn-name-0>"));
	}

	@Test
	public void englishInputNeedsTranslation()
	{
		assertTrue(OsrscnPanel.needsTranslation("Hello there"));
		assertTrue(OsrscnPanel.needsTranslation("Name: <osrscn-name-0>"));
	}

	@Test
	public void nullAndBlankInputNeedNoTranslation()
	{
		assertFalse(OsrscnPanel.needsTranslation(null));
		assertFalse(OsrscnPanel.needsTranslation(""));
		assertFalse(OsrscnPanel.needsTranslation("   "));
		assertFalse(OsrscnPanel.needsTranslation("\t\n"));
	}
}
