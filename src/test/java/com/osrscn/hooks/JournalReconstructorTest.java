package com.osrscn.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/**
 * Unit tests for {@link JournalReconstructor}, driven by real widget texts captured from the
 * Kharidian Desert achievement diary (Journalscroll group 741). Each raw line below is exactly the
 * {@code Widget.getText()} that the OSRS client produced (English, AI off), so these tests verify the
 * tag-stripping and task-boundary grouping together against ground truth.
 */
public class JournalReconstructorTest
{
	private static List<JournalReconstructor.Unit> run(String... lines)
	{
		return JournalReconstructor.reconstruct(Arrays.asList(lines));
	}

	/** A task that fits one slot, requirement on the same line, stays a single 1-line content unit. */
	@Test
	public void singleLineTaskWithRequirement()
	{
		List<JournalReconstructor.Unit> u = run(
			"<col=000000>Catch a Golden Warbler.<col=ffffff>(<col=000080><str>5 Hunter</str><col=ffffff>)");
		assertEquals(1, u.size());
		assertTrue(u.get(0).content);
		assertEquals(1, u.get(0).count);
		assertEquals("Catch a Golden Warbler.(5 Hunter)", u.get(0).english);
	}

	/** A task with no requirement, ending in a period, is complete on its own. */
	@Test
	public void singleLineTaskNoRequirement()
	{
		List<JournalReconstructor.Unit> u = run("<col=000000>Enter the Kalphite Hive.");
		assertEquals(1, u.size());
		assertEquals("Enter the Kalphite Hive.", u.get(0).english);
	}

	/** A sentence wrapped across two slots (line 1 does not end a sentence) joins into one unit. */
	@Test
	public void sentenceWrappedAcrossTwoSlots()
	{
		List<JournalReconstructor.Unit> u = run(
			"<col=000000>Open the Sarcophagus in the first room of Pyramid",
			"Plunder.<col=ffffff>(<col=000080><str>21 Thieving</str>, <col=000080><str>Started Icthlarin's Little Helper</str><col=ffffff>)",
			"<col=000000>Cut a desert cactus open to fill a waterskin.");
		assertEquals(2, u.size());
		assertEquals(2, u.get(0).count);
		assertEquals(
			"Open the Sarcophagus in the first room of Pyramid Plunder.(21 Thieving, Started Icthlarin's Little Helper)",
			u.get(0).english);
		assertEquals("Cut a desert cactus open to fill a waterskin.", u.get(1).english);
	}

	/** A complete sentence whose requirement wraps onto its own line still pulls the requirement in. */
	@Test
	public void requirementWrappedOntoOwnLine()
	{
		List<JournalReconstructor.Unit> u = run(
			"<col=000000>Refill your waterskins in the Desert using Lunar magic.",
			"<col=ffffff>(<col=800000>68 Magic<col=000080>, <col=800000>Dream Mentor<col=000080><col=ffffff>)",
			"<col=000000>Kill the Kalphite Queen.");
		assertEquals(2, u.size());
		assertEquals(2, u.get(0).count);
		assertEquals(
			"Refill your waterskins in the Desert using Lunar magic. (68 Magic, Dream Mentor)",
			u.get(0).english);
	}

	/** A sentence wrapped to two lines plus a requirement on a third line = one 3-line unit. */
	@Test
	public void sentenceAndRequirementAcrossThreeSlots()
	{
		List<JournalReconstructor.Unit> u = run(
			"<col=000000>Slay a Dust Devil in the desert cave with a Slayer helmet",
			"<col=000000>equipped.",
			"<col=ffffff>(<col=800000>65 Slayer<col=000080>, <col=000080><str>10 Defence</str>, <col=800000>55 Crafting<col=000080>, <col=800000>Started Desert Treasure I<col=000080><col=ffffff>)");
		assertEquals(1, u.size());
		assertEquals(3, u.get(0).count);
		assertEquals(
			"Slay a Dust Devil in the desert cave with a Slayer helmet equipped. (65 Slayer, 10 Defence, 55 Crafting, Started Desert Treasure I)",
			u.get(0).english);
	}

	/** Yellow difficulty headers and blank spacers are structural: they delimit, never merge. */
	@Test
	public void headersAndBlanksAreStructural()
	{
		assertFalse(JournalReconstructor.isContent("<col=ffff00>Easy"));
		assertFalse(JournalReconstructor.isContent(" "));
		assertFalse(JournalReconstructor.isContent("<col=000000>"));
		assertFalse(JournalReconstructor.isContent("<str>"));
		assertTrue(JournalReconstructor.isContent("<col=000000>Kill a Vulture."));
	}

	/**
	 * A line that already holds our translation (char-images) is structural, so a finished task is not
	 * re-grouped and re-translated. Regression for the crash where re-processing rendered Chinese (which
	 * keeps literal "70"/"(" between img tags) amplified into a 100+ {@code <br>} widget and crashed OSRS.
	 */
	@Test
	public void translatedLinesAreStructural()
	{
		assertFalse(JournalReconstructor.isContent("<img=2114><img=418> (<col=800000>70<img=2566><img=2733>)"));
		assertFalse(JournalReconstructor.isContent("(<col=000080><str><img=2729></str><col=ffffff>) <img=2230>"));
		// raw English (no <img=>) is still content and gets translated
		assertTrue(JournalReconstructor.isContent("<col=000000>Catch a Golden Warbler.<col=ffffff>(5 Hunter)"));
	}

	/** A struck-through (completed) task is treated like any other content line. */
	@Test
	public void struckThroughTaskIsContent()
	{
		List<JournalReconstructor.Unit> u = run(
			"<str>Enter the Desert with a set of Desert robes equipped.");
		assertEquals(1, u.size());
		assertTrue(u.get(0).content);
		assertEquals("Enter the Desert with a set of Desert robes equipped.", u.get(0).english);
	}

	/** A header between two tasks keeps them separate and is emitted as its own structural unit. */
	@Test
	public void headerSeparatesTasks()
	{
		List<JournalReconstructor.Unit> u = run(
			"<col=000000>Kill a Vulture.",
			"<col=ffff00>Medium",
			"<col=000000>Mine some Granite.<col=ffffff>(<col=000080><str>45 Mining</str><col=ffffff>)");
		assertEquals(3, u.size());
		assertTrue(u.get(0).content);
		assertFalse(u.get(1).content); // the "Medium" header
		assertEquals(1, u.get(1).count);
		assertTrue(u.get(2).content);
		assertEquals("Mine some Granite.(45 Mining)", u.get(2).english);
	}

	/** Every input index is covered exactly once, in order (units tile the input). */
	@Test
	public void unitsTileTheInputExactly()
	{
		String[] lines = {
			"<col=ffff00>Easy",
			" ",
			"<col=000000>Open the Sarcophagus in the first room of Pyramid",
			"Plunder.<col=ffffff>(<col=000080><str>21 Thieving</str><col=ffffff>)",
			"<col=000000>Kill a Vulture.",
		};
		List<JournalReconstructor.Unit> u = run(lines);
		int covered = 0;
		int expectedStart = 0;
		for (JournalReconstructor.Unit unit : u)
		{
			assertEquals(expectedStart, unit.start);
			expectedStart += unit.count;
			covered += unit.count;
		}
		assertEquals(lines.length, covered);
	}

	/**
	 * The split point is the colour tag that paints the opening '(' (so the requirement part keeps the paren's
	 * white colour), or the literal '(' itself when no colour tag immediately precedes it.
	 */
	@Test
	public void findRequirementStartLocatesTheRequirement()
	{
		String raw = "<col=000000>Catch a Golden Warbler.<col=ffffff>"
			+ "(<col=000080><str>5 Hunter</str><col=ffffff>)";
		int idx = InterfaceTranslator.findRequirementStart(raw);
		assertEquals(raw.indexOf("<col=ffffff>("), idx);
		assertTrue(raw.startsWith("<col=ffffff>(", idx));

		// no colour tag right before '(' -> split at the '(' itself
		String bare = "Catch a Golden Warbler. (5 Hunter)";
		assertEquals(bare.indexOf('('), InterfaceTranslator.findRequirementStart(bare));
	}

	/** A task with no requirement has no split point. */
	@Test
	public void findRequirementStartIsMinusOneWithoutRequirement()
	{
		assertEquals(-1, InterfaceTranslator.findRequirementStart("<col=000000>Enter the Kalphite Hive."));
		assertEquals(-1, InterfaceTranslator.findRequirementStart("Kill a Vulture."));
	}
}
