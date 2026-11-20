package com.osrscn.translate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

/** Pure-logic tests for the NAME-table terminology hint (no RuneLite/network). */
public class TermGlossaryTest
{
	private static Map<String, String> table(String... pairs)
	{
		Map<String, String> map = new LinkedHashMap<>();
		for (int i = 0; i + 1 < pairs.length; i += 2)
		{
			map.put(pairs[i], pairs[i + 1]);
		}
		return map;
	}

	private static TermGlossary build(Map<String, String> table)
	{
		return TermGlossary.build(table, Set.of());
	}

	private static int listedTerms(String prompt)
	{
		int n = 0;
		for (String line : prompt.split("\n"))
		{
			if (line.startsWith("  ") && line.contains(" => "))
			{
				n++;
			}
		}
		return n;
	}

	@Test
	public void keepsOnlyUsableNameRows()
	{
		TermGlossary glossary = build(table(
				"Me", "我",                    // shorter than 4 characters
				"Use", "使用",                 // shorter than 4 characters
				"Hellatine", "Hellatine",      // no Chinese
				"Gate", "<br>门",              // a tag inside the value
				"Rune", "符文",                 // kept
				"Last Man Standing", "最后幸存者")); // kept
		assertEquals(2, glossary.size());
		assertTrue(glossary.promptFor("A rune stone.").contains("Rune => 符文"));
	}

	@Test
	public void matchesWholeWordsOnly()
	{
		TermGlossary glossary = build(table("Rune", "符文"));
		assertEquals("", glossary.promptFor("RuneScape is a game."));
		assertTrue(glossary.promptFor("A rune stone.").contains("Rune => 符文"));
		assertTrue(glossary.promptFor("RUNE").contains("Rune => 符文"));
	}

	@Test
	public void matchesMultiWordTerms()
	{
		TermGlossary glossary = build(table("Last Man Standing", "最后幸存者"));
		String prompt = glossary.promptFor(
				"You have not unlocked this spell. Scrolls are sold in the Last Man Standing reward shop.");
		assertTrue(prompt.contains("Last Man Standing => 最后幸存者"));
	}

	@Test
	public void ignoresTagsAndBracketPlaceholders()
	{
		TermGlossary glossary = build(table("Augury", "占卜术", "Fremennik", "费明尼克"));
		assertTrue(glossary.promptFor(
				"You haven't unlocked the ability to use <colNum0>Augury</col>.").contains("Augury => 占卜术"));
		assertEquals("", glossary.promptFor("Greetings, [Fremennik name]."));
	}

	@Test
	public void honoredIgnoreListWins()
	{
		TermGlossary plain = build(table("Well", "水井", "Augury", "占卜术"));
		assertTrue(plain.promptFor("Well met, Augury.").contains("Well => 水井"));
		TermGlossary filtered = TermGlossary.build(table("Well", "水井", "Augury", "占卜术"), Set.of("well"));
		String prompt = filtered.promptFor("Well met, Augury.");
		assertFalse(prompt.contains("Well => 水井"));
		assertTrue(prompt.contains("Augury => 占卜术"));
	}

	@Test
	public void longerTermCoversShorterTermInsideIt()
	{
		TermGlossary glossary = build(table("Hosidius", "霍西迪乌斯", "Lord Hosidius", "霍西迪乌斯领主"));
		String prompt = glossary.promptFor("Lord Hosidius rules this land.");
		assertTrue(prompt.contains("Lord Hosidius => 霍西迪乌斯领主"));
		assertFalse(prompt.contains("\n  Hosidius =>"));
		assertEquals(1, listedTerms(prompt));
	}

	@Test
	public void matchesPossessiveFormOfAName()
	{
		TermGlossary glossary = build(table("Lumbridge", "卢姆布里奇"));
		String prompt = glossary.promptFor("Lumbridge's bank is closed.");
		assertTrue(prompt.contains("Lumbridge => 卢姆布里奇"));
	}

	@Test
	public void matchesPossessiveFormWhenAMultiWordTermShadowsTheToken()
	{
		// "Adventurer's top" indexes the possessive token itself, so a miss-only fallback would find
		// that row and never consult the plain name; the base lookup must be unconditional.
		TermGlossary glossary = build(table(
				"Adventurer", "冒险家",
				"Adventurer's top", "冒险家的上衣"));
		String prompt = glossary.promptFor("Speak to the Adventurer's friend.");
		assertTrue(prompt.contains("Adventurer => 冒险家"));
		assertEquals(1, listedTerms(prompt));
	}

	@Test
	public void possessiveOfAnOrdinaryWordMatchesNothing()
	{
		TermGlossary glossary = build(table("Lumbridge", "卢姆布里奇"));
		assertEquals("", glossary.promptFor("It's a fine day."));
		assertEquals("", glossary.promptFor("The player's item was lost."));
	}

	@Test
	public void keepsMatchingATermThatItselfContainsAPossessive()
	{
		TermGlossary glossary = build(table("Red spiders' eggs", "红蜘蛛蛋"));
		String prompt = glossary.promptFor("Bring me 10 red spiders' eggs.");
		assertTrue(prompt.contains("Red spiders' eggs => 红蜘蛛蛋"));
	}

	@Test
	public void capsTheNumberOfTerms()
	{
		Map<String, String> table = new LinkedHashMap<>();
		StringBuilder text = new StringBuilder();
		for (int i = 0; i < 20; i++)
		{
			String en = "Term" + String.format("%02d", i);
			table.put(en, "词" + String.format("%02d", i));
			text.append(en).append(' ');
		}
		TermGlossary glossary = build(table);
		assertEquals(20, glossary.size());
		assertEquals("terms must be capped at MAX_TERMS", 12, listedTerms(glossary.promptFor(text.toString())));
	}

	@Test
	public void capsThePromptLength()
	{
		Map<String, String> table = new LinkedHashMap<>();
		StringBuilder text = new StringBuilder();
		for (int i = 0; i < 10; i++)
		{
			String en = "Longname" + String.format("%02d", i) + "x".repeat(34);
			table.put(en, "很长的中文名称");
			text.append(en).append(' ');
		}
		TermGlossary glossary = build(table);
		String prompt = glossary.promptFor(text.toString());
		assertFalse("the budget must still leave a usable block", prompt.isEmpty());
		// MAX_PROMPT_CHARS is the private constant 300 and covers the whole block, header included;
		// pinning the literal keeps a HEADER edit from silently widening the budget again.
		assertTrue("prompt exceeds its character budget: " + prompt.length(), prompt.length() <= 300);
		assertTrue("the character cap must cut before all 10 terms are listed", listedTerms(prompt) < 10);
	}

	@Test
	public void skipsAnOverBudgetTermButStillListsALaterShorterOne()
	{
		// Hits arrive longest first: the first term fits, the huge second one cannot fit after it,
		// and the short third one still can. A break on the budget would lose that third term.
		String first = "Aa" + "a".repeat(40);
		String second = "Bb" + "b".repeat(20);
		String third = "Rune";
		int afterFirst = TermGlossary.HEADER.length() + 1 + first.length() + 1 + 7;
		String secondZh = "乙".repeat(301 - afterFirst);
		TermGlossary glossary = build(table(first, "甲", second, secondZh, third, "符"));
		String prompt = glossary.promptFor(first + " " + second + " " + third + " appears.");
		assertTrue("the fitting first term must be listed", prompt.contains(first + " => 甲"));
		assertFalse("the over-budget term must be skipped", prompt.contains(second + " =>"));
		assertTrue("a later shorter term that fits must still be listed", prompt.contains("Rune => 符"));
		assertTrue("the block must stay within the literal budget", prompt.length() <= 300);
	}

	@Test
	public void alwaysKeepsTheFirstTermEvenWhenItAloneExceedsTheCharacterBudget()
	{
		String en = "Zz" + "z".repeat(400);
		TermGlossary glossary = build(table(en, "长"));
		String prompt = glossary.promptFor("A " + en + " appears.");
		assertFalse("the block must never be empty", prompt.isEmpty());
		assertTrue(prompt.contains(en));
		assertEquals(1, listedTerms(prompt));
		assertTrue("one oversized line is still emitted", prompt.length() > 300);
	}

	@Test
	public void returnsEmptyWhenNothingMatches()
	{
		TermGlossary glossary = build(table("Augury", "占卜术"));
		assertEquals("", glossary.promptFor("Nothing here."));
		assertEquals("", glossary.promptFor(""));
		assertEquals("", glossary.promptFor(null));
	}

	@Test
	public void emptyTableBuildsEmptyIndex()
	{
		assertEquals(0, TermGlossary.build(table(), Set.of()).size());
		assertEquals("", TermGlossary.build(table(), Set.of()).promptFor("Anything at all."));
	}

	@Test
	public void keepsTheTableSpellingInThePrompt()
	{
		TermGlossary glossary = build(table("Augury", "占卜术"));
		String prompt = glossary.promptFor("augury is a prayer.");
		// Exact block: the shared header, then "  en => zh" lines (the spelling AiTranslator detects).
		assertEquals(TermGlossary.HEADER + "\n  Augury => 占卜术", prompt);
	}

	@Test
	public void reviewedIgnoreListKeepsCommonWordsOut()
	{
		TermGlossary glossary = TermGlossary.build(
				table("Well", "水井", "Coins", "金币", "Safe", "保险箱"), GlossaryIgnore.TERMS);
		assertEquals("", glossary.promptFor("Well met, keep the coins safe."));
	}

	@Test
	public void reviewedIgnoreListLeavesProperNounsAlone()
	{
		TermGlossary glossary = TermGlossary.build(
				table("Hosidius", "霍西迪乌斯", "Augury", "占卜术"), GlossaryIgnore.TERMS);
		String prompt = glossary.promptFor("Lord Hosidius teaches Augury.");
		assertTrue(prompt.contains("Hosidius => 霍西迪乌斯"));
		assertTrue(prompt.contains("Augury => 占卜术"));
	}

	@Test
	public void reviewedIgnoreListIsNormalised()
	{
		assertFalse("the reviewed list must not be empty", GlossaryIgnore.TERMS.isEmpty());
		for (String term : GlossaryIgnore.TERMS)
		{
			assertEquals("terms are lower-case", term.toLowerCase(Locale.ROOT), term);
			assertEquals("terms are trimmed", term.trim(), term);
			assertFalse("terms carry no tabs", term.indexOf('\t') >= 0);
		}
	}

	@Test
	public void ignoreListCoversRegularPluralForms()
	{
		assertEquals("", TermGlossary.build(table("Parrots", "鹦鹉"), Set.of("parrot"))
				.promptFor("Parrots everywhere."));
		assertEquals("", TermGlossary.build(table("Parrot", "鹦鹉"), Set.of("parrots"))
				.promptFor("A parrot on the branch."));
	}

	@Test
	public void ignoreListCoversIrregularPlurals()
	{
		assertEquals("", TermGlossary.build(table("Dwarves", "矮人"), Set.of("dwarf"))
				.promptFor("The dwarves march."));
	}

	@Test
	public void ignoreListCoversPossessiveForms()
	{
		assertEquals("", TermGlossary.build(table("Guthix's", "古西克斯"), Set.of("guthix"))
				.promptFor("Guthix's altar."));
	}

	@Test
	public void ignoreListDoesNotTruncateANameAtItsPossessive()
	{
		// Regression: cutting a term at its first apostrophe reduced "King's message" to "king", so
		// the reviewed list silently suppressed all 154 NAME rows that contain a possessive.
		TermGlossary glossary = TermGlossary.build(table(
						"King's message", "国王的信件",
						"Chef's delight", "厨师的美味",
						"Wizards' Tower", "巫师塔",
						"Guthix's", "古西克斯"),
				Set.of("king", "chef", "wizard", "guthix"));
		assertTrue(glossary.promptFor("Read the King's message.")
				.contains("King's message => 国王的信件"));
		assertTrue(glossary.promptFor("Chef's delight is served.")
				.contains("Chef's delight => 厨师的美味"));
		assertTrue(glossary.promptFor("Meet me at the Wizards' Tower.")
				.contains("Wizards' Tower => 巫师塔"));
		// A term that is nothing but a possessive is still suppressed by its base name.
		assertEquals("", glossary.promptFor("Guthix's altar."));
	}

	@Test
	public void reviewedIgnoreListSilencesHighFrequencyFunctionWords()
	{
		// Every row below is a reviewed common word, so the whole table must filter out. Removing one
		// entry from GlossaryIgnore.TERMS (typically "will") leaves that row in the index and the
		// empty prompt fails; the truncation half of the bug is guarded by the test above.
		Map<String, String> table = table(
				"Will", "威尔", "Well", "水井", "Safe", "保险箱", "Watch", "手表",
				"Head", "头部", "Bank", "银行", "Coins", "金币", "Gold", "黄金",
				"Start", "开始", "Level", "等级", "Fire", "火焰", "Water", "水",
				"Tree", "树", "Door", "门");
		TermGlossary glossary = TermGlossary.build(table, GlossaryIgnore.TERMS);
		assertTrue("the reviewed list must keep 'will'", GlossaryIgnore.TERMS.contains("will"));
		assertEquals("every row is a reviewed common word", 0, glossary.size());
		assertEquals("", glossary.promptFor("Will you head to the bank, take the coins and gold,"
				+ " start the fire, watch the water and open the door by the tree"
				+ " while the level is safe and well?"));
	}

	@Test
	public void ignoreListDoesNotSwallowOtherTerms()
	{
		TermGlossary glossary = TermGlossary.build(
				table("Parrots", "鹦鹉", "Augury", "占卜术"), Set.of("parrot"));
		String prompt = glossary.promptFor("Parrots and Augury.");
		assertTrue(prompt.contains("Augury => 占卜术"));
		assertFalse(prompt.contains("Parrots"));
	}

	@Test
	public void reviewedIgnoreListSilencesTheTermsAddedAfterTheBranchReview()
	{
		// The branch review flagged these rows: ordinary words whose table entry is an NPC name
		// (Reach, Mark, Grace, Grew), or entity-like phrases forced onto ordinary sentences
		// (Cargo crate, Port task, A Goblin, My life, Don't Know What).
		for (String term : new String[] {"cargo crate", "reach", "port task", "don't know what",
				"my life", "a goblin", "mark", "grace", "grew"})
		{
			assertTrue(term + " must be in the reviewed list", GlossaryIgnore.TERMS.contains(term));
		}
		TermGlossary glossary = TermGlossary.build(table(
						"Cargo crate", "货舱",
						"Reach", "里奇",
						"Port task", "港口任务",
						"Don't Know What", "不知何物",
						"My life", "我的生活",
						"A Goblin", "一个哥布林",
						"Mark", "马克",
						"Grace", "格蕾丝",
						"Grew", "格鲁",
						"Lumbridge", "仑桥镇"),
				GlossaryIgnore.TERMS);
		assertEquals("only the real place name survives", 1, glossary.size());
		String prompt = glossary.promptFor("I will reach the port task board in Lumbridge,"
				+ " Mark of Grace, my life, a goblin, grew to the size of a cart.");
		assertTrue(prompt.contains("Lumbridge => 仑桥镇"));
		assertEquals("exactly one reference line", 1, listedTerms(prompt));
	}

	// ---- free MT: raw-offset term matches (TermGlossary.matches) ----------------------

	@Test
	public void matchesReportsRawOffsetsInStartOrder()
	{
		TermGlossary glossary = build(table("Dragon scimitar", "龙金弯刀", "Lumbridge", "仑桥镇"));
		String raw = "Buy a DRAGON SCIMITAR, then visit lumbridge.";
		List<TermMatch> matches = glossary.matches(raw);
		assertEquals(2, matches.size());

		TermMatch scimitar = matches.get(0);
		// en is the spelling in the raw line, not the table spelling (TermMatch documents this).
		assertEquals("DRAGON SCIMITAR", scimitar.en);
		assertEquals("龙金弯刀", scimitar.zh);
		assertEquals(raw.indexOf("DRAGON SCIMITAR"), scimitar.start);
		assertEquals(scimitar.start + "DRAGON SCIMITAR".length(), scimitar.end);
		assertEquals("DRAGON SCIMITAR", raw.substring(scimitar.start, scimitar.end));

		TermMatch lumbridge = matches.get(1);
		assertEquals("lumbridge", lumbridge.en);
		assertEquals("仑桥镇", lumbridge.zh);
		assertEquals(raw.indexOf("lumbridge"), lumbridge.start);
		assertEquals(lumbridge.start + "lumbridge".length(), lumbridge.end);
		assertTrue("matches must be sorted by raw start", scimitar.start < lumbridge.start);
	}

	@Test
	public void masksTagsAndBracketPlaceholdersWhileKeepingRawOffsets()
	{
		TermGlossary glossary = build(table("Dragon scimitar", "龙金弯刀", "Fremennik", "费明尼克"));
		String tagged = "You have not unlocked <colNum0>Dragon scimitar</col> yet.";
		List<TermMatch> matches = glossary.matches(tagged);
		assertEquals(1, matches.size());
		assertEquals("Dragon scimitar", matches.get(0).en);
		assertEquals(tagged.indexOf("Dragon scimitar"), matches.get(0).start);
		assertEquals(tagged.indexOf("Dragon scimitar") + "Dragon scimitar".length(),
				matches.get(0).end);

		// A term that is itself inside a tag is masked away, exactly like promptFor's tag strip.
		assertEquals(0, glossary.matches("A <Dragon scimitar> is not a real tag.").size());
		// [...] placeholders are player names: no term may match inside one, but the same word
		// outside brackets still matches.
		assertEquals(0, glossary.matches("Greetings, [Fremennik name].").size());
		assertEquals(1, glossary.matches("Greetings, Fremennik.").size());
	}

	@Test
	public void keepsRawOffsetsWhenLowerCaseWouldChangeTheLineLength()
	{
		// P2-10: U+0130 expands to "i" + U+0307 under String.toLowerCase, which would shift every
		// later offset by one; matches must fall back to a per-character mapping so the term after
		// it still reports raw indexes.
		TermGlossary glossary = build(table("Dragon scimitar", "龙金弯刀"));
		String raw = "\u0130stanbul then a Dragon scimitar.";
		assertTrue("the premise: whole-string lower-casing must change the length",
				raw.toLowerCase(Locale.ROOT).length() != raw.length());
		List<TermMatch> matches = glossary.matches(raw);
		assertEquals(1, matches.size());
		assertEquals(raw.indexOf("Dragon scimitar"), matches.get(0).start);
		assertEquals(raw.indexOf("Dragon scimitar") + "Dragon scimitar".length(),
				matches.get(0).end);
		assertEquals("Dragon scimitar", raw.substring(matches.get(0).start, matches.get(0).end));
	}

	@Test
	public void keepsOverlapsAndPutsTheLongestTermFirstAtTheSameStart()
	{
		TermGlossary glossary = build(table(
				"Dragon", "龙", "Dragon scimitar", "龙金弯刀", "Scimitar", "弯刀"));
		List<TermMatch> matches = glossary.matches("A Dragon scimitar.");
		assertEquals(3, matches.size());
		assertEquals("Dragon scimitar", matches.get(0).en);
		assertEquals(2, matches.get(0).start);
		assertEquals(17, matches.get(0).end);
		// Same start: the longer term is listed first; overlaps are left for the caller to resolve.
		assertEquals("Dragon", matches.get(1).en);
		assertEquals(2, matches.get(1).start);
		assertEquals(8, matches.get(1).end);
		assertEquals("scimitar", matches.get(2).en); // the raw spelling, lower-case in this line
		assertEquals(9, matches.get(2).start);
		assertEquals(17, matches.get(2).end);
	}

	@Test
	public void matchesHonoursTheIgnoreListAndPossessiveForms()
	{
		TermGlossary glossary = TermGlossary.build(
				table("Lumbridge", "仑桥镇", "Parrots", "鹦鹉"), Set.of("parrot"));
		List<TermMatch> matches = glossary.matches("Lumbridge's parrots are loud.");
		assertEquals(1, matches.size());
		assertEquals("Lumbridge", matches.get(0).en);
		assertEquals("仑桥镇", matches.get(0).zh);
		assertEquals(0, matches.get(0).start);
		assertEquals("Lumbridge".length(), matches.get(0).end);
		assertEquals(0, glossary.matches("The parrots are loud.").size());
	}

	@Test
	public void matchesReturnsEmptyListForMissingInputOrEmptyIndex()
	{
		TermGlossary glossary = build(table("Rune", "符文"));
		assertTrue(glossary.matches(null).isEmpty());
		assertTrue(glossary.matches("").isEmpty());
		assertTrue(glossary.matches("Nothing here.").isEmpty());
		assertTrue(TermGlossary.build(table(), Set.of()).matches("A rune stone.").isEmpty());
	}

	@Test
	public void promptForKeepsTheNormalisationThatMatchesMustNotAdopt()
	{
		// P2-9: promptFor is not a wrapper around matches. plain() folds curly quotes and en/em
		// dashes and turns <br> into a space, so these lines still yield terminology hints; matches
		// reads the raw line and must not claim a span it would have to cut.
		TermGlossary glossary = build(table(
				"King's message", "国王的信件",
				"Zamorak-Brew", "扎莫拉克酒",
				"Dragon scimitar", "龙金弯刀"));
		assertTrue(glossary.promptFor("Read the King\u2019s message.")
				.contains("King's message => 国王的信件"));
		assertTrue(glossary.promptFor("A Zamorak\u2014Brew, please.")
				.contains("Zamorak-Brew => 扎莫拉克酒"));
		assertTrue(glossary.promptFor("A Dragon<br>scimitar.")
				.contains("Dragon scimitar => 龙金弯刀"));

		assertTrue(glossary.matches("Read the King\u2019s message.").isEmpty());
		assertTrue(glossary.matches("A Zamorak\u2014Brew, please.").isEmpty());
		assertTrue(glossary.matches("A Dragon<br>scimitar.").isEmpty());
	}

	@Test
	public void doesNotMatchATermHiddenInsideALongerWord()
	{
		TermGlossary glossary = build(table("Rune", "符文", "Dragon scimitar", "龙金弯刀"));
		// The review's literal cases: a term that is only part of a longer spelling is not a term.
		assertEquals(0, glossary.matches("RuneScape is a game.").size());
		assertEquals(0, glossary.matches("Dragonscimitar is sharp.").size());
		// These lines still carry a real token for the term, so the token index does hand it to the
		// span scan and only the whole-word boundary can reject the embedded occurrence. If
		// isWordBoundary always returned true, the two assertions below would see 2 and 1 matches.
		List<TermMatch> rune = glossary.matches("A rune stone and RuneScape.");
		assertEquals(1, rune.size());
		assertEquals("rune", rune.get(0).en);
		assertEquals(2, rune.get(0).start);
		assertEquals(0, glossary.matches("Dragon scimitars are sharp.").size());
	}

	@Test
	public void matchesEveryOccurrenceOfTheSameTerm()
	{
		TermGlossary glossary = build(table("Rune", "符文"));
		List<TermMatch> matches = glossary.matches("Rune and Rune.");
		assertEquals(2, matches.size());
		assertEquals(0, matches.get(0).start);
		assertEquals("Rune", matches.get(0).en);
		assertEquals(9, matches.get(1).start);
		assertEquals("Rune", matches.get(1).en);
		assertEquals("符文", matches.get(1).zh);
	}

	@Test
	public void matchesATermAtTheEndOfTheString()
	{
		TermGlossary glossary = build(table("Lumbridge", "仑桥镇"));
		List<TermMatch> matches = glossary.matches("Visit Lumbridge");
		assertEquals(1, matches.size());
		assertEquals("Lumbridge", matches.get(0).en);
		assertEquals("Visit ".length(), matches.get(0).start);
		assertEquals("Visit Lumbridge".length(), matches.get(0).end);
		// The other outer edge: the term is the whole string, so start == 0 and end == length.
		assertEquals(1, glossary.matches("Lumbridge").size());
	}
}
