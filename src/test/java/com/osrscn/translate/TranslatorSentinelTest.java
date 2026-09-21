package com.osrscn.translate;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class TranslatorSentinelTest
{
	@Test
	public void pendingSentinelKeepsRuntimeNulBoundaries()
	{
		assertEquals('\0', Translator.REQ_PENDING.charAt(0));
		assertEquals("PENDING", Translator.REQ_PENDING.substring(1, 8));
		assertEquals('\0', Translator.REQ_PENDING.charAt(8));
		assertEquals(9, Translator.REQ_PENDING.length());
	}
}
