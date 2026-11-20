package com.osrscn.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Pins down the manual-translate pre-checks in {@link OsrscnPanel}: text with no translatable English
 * must be reported straight away ("nothing to translate") instead of being polled for 15s and then
 * showing the misleading "translation timed out" message, because {@code Translator.aiTranslate}
 * refuses to dispatch letter-free text at all. Identity-bearing text is refused the same way by the
 * privacy guard, and a cache/table miss with AI translation switched off will never be served either,
 * so both must explain themselves as soon as the first lookup misses.
 *
 * <p>Only the package-private static predicates are touched - no Swing component and no panel instance
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

	@Test
	public void identityFramedInputExplainsWhyItIsBlocked()
	{
		String trading = OsrscnPanel.translateBlockReason("Trading with: Zezima", false, true);
		assertNotNull(trading);
		assertTrue(trading.contains("身份信息"));

		assertNotNull(OsrscnPanel.translateBlockReason("The top three crab crushers were A, B, & C!", false, true));
		assertNotNull(OsrscnPanel.translateBlockReason("Showing items: rune da", false, true));
		assertNotNull(OsrscnPanel.translateBlockReason("offers:", false, true));
	}

	@Test
	public void ordinaryProseStillGetsTranslated()
	{
		assertNull(OsrscnPanel.translateBlockReason("Hello there", false, true));
		// Identity-ish openers that are not the real frames must keep going to the AI.
		assertNull(OsrscnPanel.translateBlockReason("Trading with friends is fun.", false, true));
		assertNull(OsrscnPanel.translateBlockReason("Showing items are listed below.", false, true));
	}

	@Test
	public void blockReasonIsNotShownOnceTranslated()
	{
		// A baked table row is the useful answer for an identity frame, so the panel only explains a miss.
		assertNull(OsrscnPanel.translateBlockReason("Showing items: bull", true, true));
		assertNull(OsrscnPanel.translateBlockReason("Trading with: Zezima", true, true));
		assertNull(OsrscnPanel.translateBlockReason(null, false, true));
	}

	@Test
	public void aiOffExplainsHowToEnableIt()
	{
		// A table/cache miss can never be served while AI is off, so the panel must point at the setting
		// instead of polling until the timeout message blames the backend.
		String reason = OsrscnPanel.translateBlockReason("The crystal gate awaits.", false, false);
		assertNotNull(reason);
		assertTrue(reason.contains("启用 AI 翻译"));
		assertFalse(reason.contains("超时"));
		// With AI on the same prose is a normal request: keep polling for the translation.
		assertNull(OsrscnPanel.translateBlockReason("The crystal gate awaits.", false, true));
	}

	@Test
	public void identityReasonWinsOverAiOff()
	{
		// The privacy guard blocks an identity frame even with AI enabled, so it must never be blamed on
		// the AI switch.
		String reason = OsrscnPanel.translateBlockReason("Trading with: Zezima", false, false);
		assertNotNull(reason);
		assertTrue(reason.contains("身份信息"));
		assertFalse(reason.contains("启用 AI 翻译"));
	}

	@Test
	public void aiOffNeverOverwritesATranslation()
	{
		// A cached AI translation is served with AI off; that hit must keep the panel silent.
		assertNull(OsrscnPanel.translateBlockReason("The crystal gate awaits.", true, false));
		assertNull(OsrscnPanel.translateBlockReason("Showing items: bull", true, false));
	}

	@Test
	public void nullAndBlankHaveNoReasonWhenAiIsOff()
	{
		assertNull(OsrscnPanel.translateBlockReason(null, false, false));
		assertNull(OsrscnPanel.translateBlockReason("", false, false));
		assertNull(OsrscnPanel.translateBlockReason("   ", false, false));
	}
}
