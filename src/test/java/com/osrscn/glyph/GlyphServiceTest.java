package com.osrscn.glyph;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class GlyphServiceTest
{
	@Test
	public void foldsFullWidthLatinLettersAndDigitsBeforeRendering()
	{
		GlyphService glyph = new GlyphService();
		assertEquals("AZaz09", glyph.toImgTags("ＡＺａｚ０９", 0xffffff, 0, 11));
	}

	@Test
	public void leavesOtherFullWidthAndCompatibilityCharactersAlone()
	{
		assertEquals("！＠＃［］　中①", GlyphService.normalizeSeparators("！＠＃［］　中①"));
	}
}
