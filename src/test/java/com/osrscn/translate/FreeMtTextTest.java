package com.osrscn.translate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/**
 * Pure-logic tests for the free-MT text preparation, validation and restore round trip.
 *
 * <p>Wire v2: a colour run with a closing boundary is sent as a locally anchored
 * {@code {{open}}content{{close}}} token pair, so the {@code <colNumN>}/{@code </col>} markers travel
 * with the words Bing keeps and may move into Chinese word order. A run whose content is already
 * Chinese (a NAME term substituted by prepare, an established name, or a number) is a STRICT pair:
 * the reply must still wrap the same words (punctuation-insensitive). Every other run with a closing
 * boundary is a NON-STRICT pair: always for a closer-style run (closed by a literal {@code </col>}),
 * so the wrapped sentence keeps its colour while Bing translates inside it, and for a switch-style
 * run when its content looks like an English proper noun. Only switch-style base text (and an
 * unclosed run) is sent as plain text WITHOUT its markers - the safe single-colour fallback. A colour
 * closer is never sent. Every other protected span stays an opaque {@code {{n}}} token, inside or
 * outside a run, and token numbers are wire order.
 */
public class FreeMtTextTest
{
	private static TermMatch match(String raw, String en, String zh)
	{
		int start = raw.indexOf(en);
		assertTrue("term not present in raw: " + en, start >= 0);
		return new TermMatch(en, zh, start, start + en.length());
	}

	private static List<TermMatch> terms(TermMatch... items)
	{
		List<TermMatch> list = new ArrayList<>();
		Collections.addAll(list, items);
		return list;
	}

	private static List<String> list(String... items)
	{
		List<String> out = new ArrayList<>();
		Collections.addAll(out, items);
		return out;
	}

	/** A STRICT pair: an already-Chinese anchored run that must still wrap the same words. */
	private static FreeMtText.ColorPair pair(int open, int close, String content)
	{
		return new FreeMtText.ColorPair(open, close, content, true);
	}

	/** A NON-STRICT pair: a protected English proper noun; only the open/close order matters. */
	private static FreeMtText.ColorPair properPair(int open, int close, String content)
	{
		return new FreeMtText.ColorPair(open, close, content, false);
	}

	private static void assertPair(FreeMtText.Prepared p, int index, int open, int close, String content)
	{
		assertPair(p, index, open, close, content, true);
	}

	private static void assertProperPair(FreeMtText.Prepared p, int index, int open, int close,
			String content)
	{
		assertPair(p, index, open, close, content, false);
	}

	private static void assertPair(FreeMtText.Prepared p, int index, int open, int close, String content,
			boolean strict)
	{
		FreeMtText.ColorPair pair = p.colorPairs.get(index);
		assertEquals("pair " + index + " open token", open, pair.open);
		assertEquals("pair " + index + " close token", close, pair.close);
		assertEquals("pair " + index + " content", content, pair.content);
		assertEquals("pair " + index + " strict", strict, pair.strict);
	}

	/**
	 * Builds a {@link FreeMtText.Prepared} directly, for validate/restore cases that pin one specific
	 * wire reply rather than the result of a prepare() scan.
	 */
	private static FreeMtText.Prepared prepared(String text, List<String> tokens,
			List<String> substituted, FreeMtText.ColorPair... pairs)
	{
		FreeMtText.Prepared p = new FreeMtText.Prepared();
		p.text = text == null ? "" : text;
		p.tokens = tokens == null ? Collections.<String>emptyList() : tokens;
		p.substitutedTerms = substituted == null ? Collections.<String>emptyList() : substituted;
		List<FreeMtText.ColorPair> colorPairs = new ArrayList<>();
		if (pairs != null)
		{
			Collections.addAll(colorPairs, pairs);
		}
		p.colorPairs = colorPairs;
		return p;
	}

	/** Asserts a closer-style {@code <colNum0>content</col>} span is protected as one non-strict pair. */
	private static void assertProtected(String content, String expectedWire)
	{
		FreeMtText.Prepared p = FreeMtText.prepare("<colNum0>" + content + "</col>", null);

		assertEquals(expectedWire, p.text);
		assertEquals(1, p.colorPairs.size());
		assertEquals(list("<colNum0>", "</col>"), p.tokens);
		assertProperPair(p, 0, 0, 1, content);
	}

	/** Asserts a switch-style {@code <colNum0>content<colNum1>} run is still sent plain. */
	private static void assertSwitchRunNotProtected(String content)
	{
		FreeMtText.Prepared p = FreeMtText.prepare("<colNum0>" + content + "<colNum1>", null);

		assertEquals(content, p.text);
		assertTrue(p.tokens.isEmpty());
		assertTrue("no pair may be recorded for a dropped switch-style run", p.colorPairs.isEmpty());
	}

	// ---- golden fixtures (captured live Bing replies, 2026-11-20) ----------------------------

	private static final String GOLDEN_SWITCH_RAW =
			"I can start this quest by talking to <colNum0>Betty<colNum1> outside her home in "
					+ "<colNum2>Land's End<colNum3>.";

	private static List<TermMatch> goldenSwitchTerms()
	{
		return terms(match(GOLDEN_SWITCH_RAW, "Betty", "贝蒂"),
				match(GOLDEN_SWITCH_RAW, "Land's End", "兰兹角村"));
	}

	// ---- prepare: name runs become locally anchored token pairs -----------------------------

	@Test
	public void prepareAnchorsSwitchStyleNameRuns()
	{
		FreeMtText.Prepared p = FreeMtText.prepare(GOLDEN_SWITCH_RAW, goldenSwitchTerms());

		// Runs "Betty" and "Land's End" are already Chinese after NAME substitution, so each is sent
		// between two tokens that restore its own opening and closing boundary markers. The English
		// run between them loses both markers.
		assertEquals("I can start this quest by talking to {{0}}贝蒂{{1}} outside her home in "
				+ "{{2}}兰兹角村{{3}}.", p.text);
		assertEquals(list("<colNum0>", "<colNum1>", "<colNum2>", "<colNum3>"), p.tokens);
		assertEquals(2, p.colorPairs.size());
		assertPair(p, 0, 0, 1, "贝蒂");
		assertPair(p, 1, 2, 3, "兰兹角村");
		assertEquals(list("贝蒂", "兰兹角村"), p.substitutedTerms);
		assertFalse("the line still has English to translate", p.skip);
		// The trailing "." run is unclosed, so its markers are dropped and the "." survives.
		assertTrue("the anchor markers must never reach the wire", p.text.indexOf("</col>") < 0);
	}

	@Test
	public void validateGoldenSwitchReplyKeepsThePairs()
	{
		FreeMtText.Prepared p = FreeMtText.prepare(GOLDEN_SWITCH_RAW, goldenSwitchTerms());

		String zh = "我可以先在{{2}}兰兹角村{{3}}家门外与{{0}}贝蒂{{1}}对话开始这个任务。";
		assertEquals(FreeMtText.Result.OK, FreeMtText.validate(zh, p));
		assertEquals("我可以先在<colNum2>兰兹角村<colNum3>家门外与<colNum0>贝蒂<colNum1>对话开始这个任务。",
				FreeMtText.restore(zh, p));
	}

	@Test
	public void validateGoldenMiniquestReplyKeepsThePairs()
	{
		String raw = "I can start this miniquest by talking to <colNum0>General Khazard<colNum1> "
				+ "south of <colNum2>Rellekka<colNum3>, assuming I can actually see him.";
		FreeMtText.Prepared p = FreeMtText.prepare(raw, terms(
				match(raw, "General Khazard", "卡扎德将军"), match(raw, "Rellekka", "雷勒卡城")));

		assertEquals("I can start this miniquest by talking to {{0}}卡扎德将军{{1}} south of "
				+ "{{2}}雷勒卡城{{3}}, assuming I can actually see him.", p.text);
		String zh = "我可以先在{{2}}雷勒卡城{{3}}以南与{{0}}卡扎德将军{{1}}对话开始这个小任务，"
				+ "前提是我真的能看到他。";
		assertEquals(FreeMtText.Result.OK, FreeMtText.validate(zh, p));
		assertEquals("我可以先在<colNum2>雷勒卡城<colNum3>以南与<colNum0>卡扎德将军<colNum1>"
				+ "对话开始这个小任务，前提是我真的能看到他。", FreeMtText.restore(zh, p));
	}

	@Test
	public void prepareSwitchStyleWithoutTermsProtectsBothEnglishNames()
	{
		FreeMtText.Prepared p = FreeMtText.prepare(GOLDEN_SWITCH_RAW, null);

		// Without NAME terms the names stay English, but they are proper nouns: protecting them keeps
		// their colour and lets Bing keep or translate them inside the pair. The sentence run between
		// them is not proper-noun shaped, so it still loses both markers.
		assertEquals("I can start this quest by talking to {{0}}Betty{{1}} outside her home in "
				+ "{{2}}Land's End{{3}}.", p.text);
		assertEquals(list("<colNum0>", "<colNum1>", "<colNum2>", "<colNum3>"), p.tokens);
		assertEquals(2, p.colorPairs.size());
		assertProperPair(p, 0, 0, 1, "Betty");
		assertProperPair(p, 1, 2, 3, "Land's End");
		assertTrue(p.substitutedTerms.isEmpty());
		assertFalse(p.skip);
		// Round trip: every source marker is restored and the plain run text is untouched.
		assertEquals(GOLDEN_SWITCH_RAW, FreeMtText.restore(p.text, p));
	}

	@Test
	public void prepareAnchorsCloserStyleTermRun()
	{
		String raw = "Use <colNum0>Special Attack</col>";
		FreeMtText.Prepared p = FreeMtText.prepare(raw, terms(match(raw, "Special Attack", "战技")));

		assertEquals("Use {{0}}战技{{1}}", p.text);
		assertEquals(list("<colNum0>", "</col>"), p.tokens);
		assertEquals(1, p.colorPairs.size());
		assertPair(p, 0, 0, 1, "战技");
		assertFalse(p.skip);

		assertEquals(FreeMtText.Result.OK, FreeMtText.validate("使用 {{0}}战技{{1}}", p));
		assertEquals("使用 <colNum0>战技</col>", FreeMtText.restore("使用 {{0}}战技{{1}}", p));
	}

	@Test
	public void prepareProtectsCloserStyleEnglishProperNoun()
	{
		String raw = "You haven't unlocked the ability to use <colNum0>Preserve</col>.";
		FreeMtText.Prepared p = FreeMtText.prepare(raw, null);

		// "Preserve" is on the ignore list, so no TermMatch is offered. It is protected anyway as an
		// English proper noun: the colour is kept and Bing may keep the word or translate it inside
		// the pair; both outcomes stay glued to the right words.
		assertEquals("You haven't unlocked the ability to use {{0}}Preserve{{1}}.", p.text);
		assertEquals(list("<colNum0>", "</col>"), p.tokens);
		assertEquals(1, p.colorPairs.size());
		assertProperPair(p, 0, 0, 1, "Preserve");
		assertTrue(p.substitutedTerms.isEmpty());
		assertFalse(p.skip);

		String kept = "你还没有解锁使用 {{0}}Preserve{{1}} 的能力。";
		assertEquals(FreeMtText.Result.OK, FreeMtText.validate(kept, p));
		assertEquals("你还没有解锁使用 <colNum0>Preserve</col> 的能力。", FreeMtText.restore(kept, p));

		String translated = "你还没解锁使用{{0}}保护{{1}}的能力。";
		assertEquals(FreeMtText.Result.OK, FreeMtText.validate(translated, p));
		assertEquals("你还没解锁使用<colNum0>保护</col>的能力。",
				FreeMtText.restore(translated, p));
	}

	@Test
	public void prepareProtectsProperNounShapedEnglishRuns()
	{
		assertProtected("Preserve", "{{0}}Preserve{{1}}");
		assertProtected("(Betty)", "{{0}}(Betty){{1}}");
		assertProtected("Clue Scroll (Easy)", "{{0}}Clue Scroll (Easy){{1}}");
		assertProtected("Mage of Zamorak", "{{0}}Mage of Zamorak{{1}}");
		assertProtected("Land's End", "{{0}}Land's End{{1}}");
		assertProtected("DON'T", "{{0}}DON'T{{1}}");
	}

	@Test
	public void prepareDropsNonProperNounSwitchStyleRuns()
	{
		// A switch-style run (closed by the next colour marker, not by </col>) is protected only when
		// it is already Chinese or proper-noun shaped; everything else stays plain, because protecting
		// switch base text was measured to corrupt Bing output.
		assertSwitchRunNotProtected("Set chat mode:"); // strong punctuation: not a name
		assertSwitchRunNotProtected(" outside her home in "); // lowercase sentence words
		assertSwitchRunNotProtected("You can't do that."); // lowercase words after the first
		assertSwitchRunNotProtected("线索卷轴 (Easy)"); // a CJK word is not an English name start
		assertSwitchRunNotProtected("Barbarian guard"); // second word is lowercase and not a connector
		assertSwitchRunNotProtected("Ancient Magicks Lunar Spellbook Extra Words"); // five words
		assertSwitchRunNotProtected("Aaaaaaaaaa Bbbbbbbbbb Cccccccccc Dddddddddd"); // over 40 chars
		assertSwitchRunNotProtected(""); // empty run
	}

	@Test
	public void prepareKeepsSwitchStyleBaseRunPlain()
	{
		// The switch rule must not change: the lower-case base text between two proper nouns keeps no
		// markers, while the English names on both sides stay protected.
		String raw = "<colNum0>Betty<colNum1> outside her home in <colNum2>Land's End<colNum3>.";
		FreeMtText.Prepared p = FreeMtText.prepare(raw, null);

		assertEquals("{{0}}Betty{{1}} outside her home in {{2}}Land's End{{3}}.", p.text);
		assertEquals(2, p.colorPairs.size());
		assertProperPair(p, 0, 0, 1, "Betty");
		assertProperPair(p, 1, 2, 3, "Land's End");
	}

	@Test
	public void prepareProtectsNonProperCloserStyleRun()
	{
		// Live probe: a closer-style span wrapping sentence-like English is protected anyway, so its
		// colour survives while Bing translates the content inside the pair.
		String raw = "<colNum0>Set chat mode:</col> Group (/@g)";
		FreeMtText.Prepared p = FreeMtText.prepare(raw, null);

		assertEquals("{{0}}Set chat mode:{{1}} Group (/@g)", p.text);
		assertEquals(list("<colNum0>", "</col>"), p.tokens);
		assertEquals(1, p.colorPairs.size());
		assertProperPair(p, 0, 0, 1, "Set chat mode:");
		assertFalse(p.skip);
		assertEquals(FreeMtText.Result.OK,
				FreeMtText.validate("{{0}}设置聊天模式：{{1}} 群组 （/@g）", p));
	}

	@Test
	public void prepareProtectsCloserStyleSentenceRunFromUserReportOne()
	{
		// User bug report: <col=7f0000>A Shell of a Friend</col> translated but lost its colour,
		// because the run was not proper-noun shaped. Every closer-style run is protected now.
		String raw = "<colNum0>A Shell of a Friend</col>";
		FreeMtText.Prepared p = FreeMtText.prepare(raw, null);

		assertEquals("{{0}}A Shell of a Friend{{1}}", p.text);
		assertEquals(list("<colNum0>", "</col>"), p.tokens);
		assertEquals(1, p.colorPairs.size());
		assertProperPair(p, 0, 0, 1, "A Shell of a Friend");
		assertFalse(p.skip);

		String zh = "{{0}}朋友的空壳{{1}}";
		assertEquals(FreeMtText.Result.OK, FreeMtText.validate(zh, p));
		assertEquals("<colNum0>朋友的空壳</col>", FreeMtText.restore(zh, p));
	}

	@Test
	public void prepareProtectsCloserStyleSentenceRunFromUserReportTwo()
	{
		// User bug report: a trailing <col=ffffff>…</col> span lost its colour after translation.
		String raw = "Description: <colNum0>Kill the Shellbane Gryphon 25 times.</col>";
		FreeMtText.Prepared p = FreeMtText.prepare(raw, null);

		assertEquals("Description: {{0}}Kill the Shellbane Gryphon 25 times.{{1}}", p.text);
		assertEquals(list("<colNum0>", "</col>"), p.tokens);
		assertEquals(1, p.colorPairs.size());
		assertProperPair(p, 0, 0, 1, "Kill the Shellbane Gryphon 25 times.");

		String zh = "描述：{{0}}击杀壳毒狮鹫25次。{{1}}";
		assertEquals(FreeMtText.Result.OK, FreeMtText.validate(zh, p));
		// A trailing colour span must restore exactly one marker for the downstream colour guard.
		assertEquals("描述：<colNum0>击杀壳毒狮鹫25次。</col>", FreeMtText.restore(zh, p));
	}

	@Test
	public void prepareProtectsEveryCloserStyleSentenceRun()
	{
		String raw = "<colNum0>Hello adventurer, welcome to Lumbridge!</col> and "
				+ "<colNum1>You can't do that.</col>";
		FreeMtText.Prepared p = FreeMtText.prepare(raw, null);

		// Sentence punctuation, lower-case words and long content are fine: a closer-style run is
		// protected regardless of shape.
		assertEquals("{{0}}Hello adventurer, welcome to Lumbridge!{{1}} and "
				+ "{{2}}You can't do that.{{3}}", p.text);
		assertEquals(4, p.tokens.size());
		assertEquals(2, p.colorPairs.size());
		assertProperPair(p, 0, 0, 1, "Hello adventurer, welcome to Lumbridge!");
		assertProperPair(p, 1, 2, 3, "You can't do that.");

		String zh = "{{0}}你好，冒险家，欢迎来到伦布里奇！{{1}} 和 {{2}}你不能这么做。{{3}}";
		assertEquals(FreeMtText.Result.OK, FreeMtText.validate(zh, p));
		assertEquals("<colNum0>你好，冒险家，欢迎来到伦布里奇！</col> 和 "
				+ "<colNum1>你不能这么做。</col>", FreeMtText.restore(zh, p));
	}

	@Test
	public void prepareProtectsCloserStyleRunWithOpaqueToken()
	{
		String raw = "<colNum0>Hello [player name]<br>world</col>";
		FreeMtText.Prepared p = FreeMtText.prepare(raw, null);

		// The tokens inside the span stay inside the non-strict pair; Bing translates the text around
		// them and the pair keeps the colour on the whole sentence.
		assertEquals("{{0}}Hello {{1}}{{2}}world{{3}}", p.text);
		assertEquals(list("<colNum0>", "[player name]", "<br>", "</col>"), p.tokens);
		assertEquals(1, p.colorPairs.size());
		assertProperPair(p, 0, 0, 3, "Hello {{1}}{{2}}world");

		String zh = "{{0}}你好 {{1}}{{2}}世界{{3}}";
		assertEquals(FreeMtText.Result.OK, FreeMtText.validate(zh, p));
		assertEquals("<colNum0>你好 [player name]<br>世界</col>", FreeMtText.restore(zh, p));
	}

	@Test
	public void prepareProtectsMultipleEnglishProperNounsAndValidatesInsidePairTranslation()
	{
		String raw = "You have unlocked the <colNum0>Ancient Magicks</col> and the "
				+ "<colNum1>Lunar Spellbook</col>.";
		FreeMtText.Prepared p = FreeMtText.prepare(raw, null);

		assertEquals("You have unlocked the {{0}}Ancient Magicks{{1}} and the "
				+ "{{2}}Lunar Spellbook{{3}}.", p.text);
		assertEquals(2, p.colorPairs.size());
		assertProperPair(p, 0, 0, 1, "Ancient Magicks");
		assertProperPair(p, 1, 2, 3, "Lunar Spellbook");

		// Captured live reply: Bing translated inside both pairs and kept them glued in order.
		String zh = "你已经解锁了{{0}}古代魔法{{1}}和{{2}}月球法术书{{3}}。";
		assertEquals(FreeMtText.Result.OK, FreeMtText.validate(zh, p));
		assertEquals("你已经解锁了<colNum0>古代魔法</col>和<colNum1>月球法术书</col>。",
				FreeMtText.restore(zh, p));
	}

	@Test
	public void validateAcceptsReorderedEnglishProperNounPairs()
	{
		// Captured live reply for the switch-style line: pairs kept in English, reordered correctly.
		FreeMtText.Prepared p = FreeMtText.prepare(GOLDEN_SWITCH_RAW, null);
		String zh = "我可以通过在{{2}}Land's End{{3}}的{{0}}Betty{{1}}家门外与她对话开始这个任务。";
		assertEquals(FreeMtText.Result.OK, FreeMtText.validate(zh, p));
		assertEquals("我可以通过在<colNum2>Land's End<colNum3>的<colNum0>Betty<colNum1>"
				+ "家门外与她对话开始这个任务。", FreeMtText.restore(zh, p));
	}

	@Test
	public void prepareDoesNotAnchorPunctuationOrSpaceRuns()
	{
		String raw = "<colNum0>Betty<colNum1> <colNum2>Land's End<colNum3>";
		FreeMtText.Prepared p = FreeMtText.prepare(raw, terms(
				match(raw, "Betty", "贝蒂"), match(raw, "Land's End", "兰兹角村")));

		// The middle run is a single space: it needs no protection, so both of its boundaries are
		// dropped even though the two name runs beside it stay anchored.
		assertEquals("{{0}}贝蒂{{1}} {{2}}兰兹角村{{3}}", p.text);
		assertEquals(2, p.colorPairs.size());
		assertPair(p, 0, 0, 1, "贝蒂");
		assertPair(p, 1, 2, 3, "兰兹角村");
	}

	@Test
	public void prepareDoesNotAnchorPunctuationOnlyRun()
	{
		String raw = "A<colNum0>.<colNum1>B";
		FreeMtText.Prepared p = FreeMtText.prepare(raw, null);

		assertEquals("A.B", p.text);
		assertTrue(p.tokens.isEmpty());
		assertTrue(p.colorPairs.isEmpty());
	}

	@Test
	public void prepareDoesNotAnchorTokenOnlyRun()
	{
		String raw = "<colNum0><br><colNum1>";
		FreeMtText.Prepared p = FreeMtText.prepare(raw, null);

		// The run content is one opaque token, i.e. no visible text at all: nothing to protect, so
		// both markers are dropped and only the token remains.
		assertEquals("{{0}}", p.text);
		assertEquals(list("<br>"), p.tokens);
		assertTrue(p.colorPairs.isEmpty());
		assertTrue("only protected markup is left", p.skip);
	}

	@Test
	public void prepareGoldenContestantTokenOnlyRunKeepsOpaqueTokens()
	{
		String raw = "Please welcome our newest contestant:<br><colNum0>[player name]<colNum1>!";
		FreeMtText.Prepared p = FreeMtText.prepare(raw, null);

		// The colour run holds only "[player name]" (an opaque token), so it is not anchored; the
		// opaque <br> and [player name] tokens are still kept exactly as sent.
		assertEquals("Please welcome our newest contestant:{{0}}{{1}}!", p.text);
		assertEquals(list("<br>", "[player name]"), p.tokens);
		assertTrue(p.colorPairs.isEmpty());
		assertFalse(p.skip);
		assertEquals(FreeMtText.Result.OK,
				FreeMtText.validate("请欢迎我们最新的参赛者：{{0}}{{1}}！", p));
	}

	@Test
	public void prepareDropsUnmatchedCloser()
	{
		FreeMtText.Prepared p = FreeMtText.prepare("Hello</col> world", null);

		assertEquals("Hello world", p.text);
		assertTrue(p.tokens.isEmpty());
		assertTrue(p.colorPairs.isEmpty());
		assertFalse(p.skip);
	}

	@Test
	public void prepareDropsMarkersOfRunStillOpenAtEndOfLine()
	{
		String raw = "Start <colNum0>贝蒂";
		FreeMtText.Prepared p = FreeMtText.prepare(raw, null);

		// No closing boundary exists, so no pair can be validated: the markers are dropped and the
		// established Chinese name is kept.
		assertEquals("Start 贝蒂", p.text);
		assertTrue(p.tokens.isEmpty());
		assertTrue(p.colorPairs.isEmpty());
		assertEquals(p.text, FreeMtText.restore(p.text, p));
	}

	@Test
	public void prepareAnchorsMultipleRunsAroundOpaqueMarkup()
	{
		String raw = "Hey, [milady/sirrah] <br> <colNum0>Betty<colNum1> outside "
				+ "<colNum2>Land's End<colNum3>, then <colNum4>General Khazard<colNum5> here.";
		FreeMtText.Prepared p = FreeMtText.prepare(raw, terms(
				match(raw, "Betty", "贝蒂"),
				match(raw, "Land's End", "兰兹角村"),
				match(raw, "General Khazard", "卡扎德将军")));

		// Three anchored name runs, two English runs losing their markers, and two opaque tokens
		// before the first colour marker: token numbers follow wire order, not allocation order.
		assertEquals("Hey, {{0}} {{1}} {{2}}贝蒂{{3}} outside {{4}}兰兹角村{{5}}, then "
				+ "{{6}}卡扎德将军{{7}} here.", p.text);
		assertEquals(list("[milady/sirrah]", "<br>", "<colNum0>", "<colNum1>", "<colNum2>",
				"<colNum3>", "<colNum4>", "<colNum5>"), p.tokens);
		assertEquals(3, p.colorPairs.size());
		assertPair(p, 0, 2, 3, "贝蒂");
		assertPair(p, 1, 4, 5, "兰兹角村");
		assertPair(p, 2, 6, 7, "卡扎德将军");
		assertFalse(p.skip);
	}

	@Test
	public void prepareAnchorsRunContainingOpaqueToken()
	{
		String raw = "<colNum0>Betty<br></col>";
		FreeMtText.Prepared p = FreeMtText.prepare(raw, terms(match(raw, "Betty", "贝蒂")));

		// The <br> token is allocated inside the run before the run's own pair tokens, but the wire
		// numbering is explicitly reassigned to wire order.
		assertEquals("{{0}}贝蒂{{1}}{{2}}", p.text);
		assertEquals(list("<colNum0>", "<br>", "</col>"), p.tokens);
		assertEquals(1, p.colorPairs.size());
		assertPair(p, 0, 0, 2, "贝蒂{{1}}");

		// The pair content contains a token, so validate only checks the pair order.
		assertEquals(FreeMtText.Result.OK, FreeMtText.validate("你好 {{0}}贝蒂{{1}}{{2}}", p));
		assertEquals("你好 <colNum0>贝蒂<br></col>",
				FreeMtText.restore("你好 {{0}}贝蒂{{1}}{{2}}", p));
	}

	@Test
	public void prepareAnchorsDigitRunAndRestoresIt()
	{
		String raw = "<colNum0>5</col>";
		FreeMtText.Prepared p = FreeMtText.prepare(raw, null);

		assertEquals("{{0}}5{{1}}", p.text);
		assertEquals(list("<colNum0>", "</col>"), p.tokens);
		assertEquals(1, p.colorPairs.size());
		assertPair(p, 0, 0, 1, "5");
		assertTrue("a digit-only line has nothing left to translate", p.skip);
		assertEquals(raw, FreeMtText.restore(p.text, p));
	}

	@Test
	public void prepareTreatsMultiDigitColorIndexAsRun()
	{
		String raw = "<colNum10>Betty<colNum11> x";
		FreeMtText.Prepared p = FreeMtText.prepare(raw, terms(match(raw, "Betty", "贝蒂")));

		assertEquals("{{0}}贝蒂{{1}} x", p.text);
		assertEquals(list("<colNum10>", "<colNum11>"), p.tokens);
		assertEquals(1, p.colorPairs.size());
		assertPair(p, 0, 0, 1, "贝蒂");
	}

	@Test
	public void prepareDegradesDuplicateColorMarkerToOpaqueToken()
	{
		// placeholdColors numbers every opening tag once. A repeated index cannot open a second run,
		// so it stays an opaque token instead: the line still translates and that marker is restored
		// verbatim; the first copy is only colour, which an unanchored English run drops.
		String raw = "A<colNum0>x<colNum0>y";
		FreeMtText.Prepared p = FreeMtText.prepare(raw, null);

		assertEquals("Ax{{0}}y", p.text);
		assertEquals(list("<colNum0>"), p.tokens);
		assertTrue(p.colorPairs.isEmpty());
		assertFalse(p.skip);
		assertEquals(FreeMtText.Result.OK, FreeMtText.validate("甲x{{0}}y", p));
		assertEquals("甲x<colNum0>y", FreeMtText.restore("甲x{{0}}y", p));
	}

	@Test
	public void prepareTreatsOutOfRangeColorIndexAsOpaqueTag()
	{
		// Not an index placeholdColors can emit: it must degrade to a protected span, never throw.
		String raw = "<colNum99999999999>Hello";
		FreeMtText.Prepared p = FreeMtText.prepare(raw, null);

		assertTrue("no colour run is opened for an unusable index", p.colorPairs.isEmpty());
		assertEquals("{{0}}Hello", p.text);
		assertEquals(list("<colNum99999999999>"), p.tokens);
		assertFalse(p.skip);
	}

	// ---- prepare: protection ----------------------------------------------------------------

	@Test
	public void prepareProtectsBracketAndNamePlaceholders()
	{
		String raw = "[milady/sirrah] hello <osrscn-name-0>";
		FreeMtText.Prepared p = FreeMtText.prepare(raw, null);

		assertFalse(p.skip);
		assertEquals("{{0}} hello {{1}}", p.text);
		assertEquals(list("[milady/sirrah]", "<osrscn-name-0>"), p.tokens);
		assertEquals(raw, FreeMtText.restore(p.text, p));
	}

	@Test
	public void prepareNumbersTokensInWireOrder()
	{
		String raw = "<col=ff0000>Hello <colNum0>5</col> <col=ffffff>world</col>";
		FreeMtText.Prepared p = FreeMtText.prepare(raw, null);

		assertFalse(p.skip);
		// <col=ff0000> is before the first placeholder, so it is a plain opaque token; the digit run
		// is anchored; the closing </col> after "world" has no open run and is dropped.
		assertEquals("{{0}}Hello {{1}}5{{2}} {{3}}world", p.text);
		assertEquals(list("<col=ff0000>", "<colNum0>", "</col>", "<col=ffffff>"), p.tokens);
		assertEquals(1, p.colorPairs.size());
		assertPair(p, 0, 1, 2, "5");
	}

	@Test
	public void prepareLeavesUnclosedOpenersAlone()
	{
		String raw = "Use < 5 and [x now";
		FreeMtText.Prepared p = FreeMtText.prepare(raw, null);

		assertEquals(raw, p.text);
		assertTrue(p.tokens.isEmpty());
		assertFalse(p.skip);
	}

	// ---- prepare: skip guards ---------------------------------------------------------------

	@Test
	public void prepareSkipsNull()
	{
		FreeMtText.Prepared p = FreeMtText.prepare(null, null);

		assertTrue(p.skip);
		assertEquals("", p.text);
		assertTrue(p.tokens.isEmpty());
		assertTrue(p.substitutedTerms.isEmpty());
		assertTrue(p.colorPairs.isEmpty());
	}

	@Test
	public void prepareSkipsEmpty()
	{
		assertTrue(FreeMtText.prepare("", null).skip);
	}

	@Test
	public void prepareSkipsBlank()
	{
		assertTrue(FreeMtText.prepare("  \n\t  ", null).skip);
	}

	@Test
	public void prepareSkipsOverMaxLength()
	{
		String tooLong = "a".repeat(FreeMtText.MAX_TEXT_CHARS + 1);
		assertTrue(FreeMtText.prepare(tooLong, null).skip);
	}

	@Test
	public void prepareAcceptsExactlyMaxLength()
	{
		String fits = "a".repeat(FreeMtText.MAX_TEXT_CHARS);
		FreeMtText.Prepared p = FreeMtText.prepare(fits, null);

		assertFalse(p.skip);
		assertEquals(fits, p.text);
	}

	@Test
	public void prepareSkipsPlaceholderConflict()
	{
		assertTrue(FreeMtText.prepare("Hello {{0}} world", null).skip);
		assertTrue(FreeMtText.prepare("Hello }} world", null).skip);
		assertTrue(FreeMtText.prepare("Hello {{ token", null).skip);
	}

	@Test
	public void prepareSkipsPureNumbers()
	{
		assertTrue(FreeMtText.prepare("429", null).skip);
		assertTrue(FreeMtText.prepare("3,000", null).skip);
		assertTrue(FreeMtText.prepare("!!! 42 ???", null).skip);
	}

	@Test
	public void prepareSkipsTagOnlyLine()
	{
		FreeMtText.Prepared p = FreeMtText.prepare("<colNum0></col>", null);

		// An empty closer run has no content to protect, so it stays plain and nothing is left at all.
		assertTrue(p.skip);
		assertEquals("", p.text);
		assertTrue(p.tokens.isEmpty());
		assertTrue(p.colorPairs.isEmpty());
	}

	@Test
	public void prepareSkipsWhenOnlyAnchoredChineseRemains()
	{
		// The only visible text is the substituted Chinese name, which is already protected by the
		// anchor pair: there is no English left for Bing to translate.
		String raw = "<colNum0>Augury</col>";
		FreeMtText.Prepared p = FreeMtText.prepare(raw,
				terms(new TermMatch("Augury", "占卜术", raw.indexOf("Augury"),
						raw.indexOf("Augury") + "Augury".length())));

		assertTrue(p.skip);
		assertEquals("{{0}}占卜术{{1}}", p.text);
		assertEquals(list("占卜术"), p.substitutedTerms);
	}

	@Test
	public void prepareSkipsTermOnlyLine()
	{
		String raw = "Augury";
		FreeMtText.Prepared p = FreeMtText.prepare(raw,
				terms(new TermMatch("Augury", "占卜术", 0, raw.length())));

		assertTrue(p.skip);
		assertEquals("占卜术", p.text);
		assertEquals(list("占卜术"), p.substitutedTerms);
	}

	// ---- prepare: term substitution ---------------------------------------------------------

	@Test
	public void prepareSubstitutesTerm()
	{
		String raw = "Use Dragon scimitar now";
		FreeMtText.Prepared p = FreeMtText.prepare(raw,
				terms(match(raw, "Dragon scimitar", "龙金弯刀")));

		assertFalse(p.skip);
		assertEquals("Use 龙金弯刀 now", p.text);
		assertEquals(list("龙金弯刀"), p.substitutedTerms);
		assertTrue(p.tokens.isEmpty());
	}

	@Test
	public void preparePrefersLongestTermAtSameStart()
	{
		String raw = "Dragon scimitar";
		FreeMtText.Prepared p = FreeMtText.prepare(raw, terms(
				new TermMatch("Dragon", "龙", 0, 6),
				new TermMatch("Dragon scimitar", "龙金弯刀", 0, raw.length())));

		assertEquals("龙金弯刀", p.text);
		assertEquals(list("龙金弯刀"), p.substitutedTerms);
	}

	@Test
	public void prepareSubstitutesOnlyMatchedOccurrence()
	{
		// Only the first occurrence is provided: prepare must consume the given offsets and not
		// run a blanket replace over the whole line.
		String raw = "Dragon scimitar and Dragon scimitar";
		FreeMtText.Prepared p = FreeMtText.prepare(raw,
				terms(new TermMatch("Dragon scimitar", "龙金弯刀", 0, 15)));

		assertEquals("龙金弯刀 and Dragon scimitar", p.text);
		assertEquals(list("龙金弯刀"), p.substitutedTerms);
	}

	@Test
	public void prepareInterleavesTermsAndAnchoredRuns()
	{
		String raw = "Use Dragon scimitar on <colNum0>5</col>";
		FreeMtText.Prepared p = FreeMtText.prepare(raw,
				terms(match(raw, "Dragon scimitar", "龙金弯刀")));

		assertFalse(p.skip);
		assertEquals("Use 龙金弯刀 on {{0}}5{{1}}", p.text);
		assertEquals(list("<colNum0>", "</col>"), p.tokens);
		assertEquals(1, p.colorPairs.size());
		assertPair(p, 0, 0, 1, "5");
		assertEquals("Use 龙金弯刀 on <colNum0>5</col>", FreeMtText.restore(p.text, p));
	}

	@Test
	public void prepareLeavesTermInsideCloserStyleRunSubstituted()
	{
		// The run still holds English ("Use"), so it cannot be anchored; because it is closer-style it
		// is protected as a non-strict pair anyway: the term is substituted and Bing translates the
		// rest inside the pair, keeping the colour.
		String raw = "<colNum0>Use Dragon scimitar</col>";
		FreeMtText.Prepared p = FreeMtText.prepare(raw,
				terms(match(raw, "Dragon scimitar", "龙金弯刀")));

		assertFalse(p.skip);
		assertEquals("{{0}}Use 龙金弯刀{{1}}", p.text);
		assertEquals(list("龙金弯刀"), p.substitutedTerms);
		assertEquals(list("<colNum0>", "</col>"), p.tokens);
		assertEquals(1, p.colorPairs.size());
		assertProperPair(p, 0, 0, 1, "Use 龙金弯刀");
	}

	@Test
	public void prepareAcceptsNullAndEmptyTerms()
	{
		String raw = "Hello <colNum0>world</col>";

		FreeMtText.Prepared withNull = FreeMtText.prepare(raw, null);
		assertEquals("Hello {{0}}world{{1}}", withNull.text);
		assertFalse(withNull.skip);
		assertEquals(list("<colNum0>", "</col>"), withNull.tokens);
		assertEquals(1, withNull.colorPairs.size());
		assertProperPair(withNull, 0, 0, 1, "world");

		FreeMtText.Prepared withEmpty = FreeMtText.prepare(raw,
				Collections.<TermMatch>emptyList());
		assertEquals(withNull.text, withEmpty.text);
		assertEquals(withNull.tokens, withEmpty.tokens);
		assertEquals(withNull.colorPairs.size(), withEmpty.colorPairs.size());
	}

	@Test
	public void prepareKeepsNewlinesAndQuotes()
	{
		String raw = "Say \"hello\"\nworld\tplease";
		FreeMtText.Prepared p = FreeMtText.prepare(raw, null);

		assertFalse(p.skip);
		assertEquals(raw, p.text);
		assertTrue(p.tokens.isEmpty());
	}

	@Test
	public void prepareTermOffsetsWithQuotedNewline()
	{
		String raw = "Say \"Dragon scimitar\"\nnow";
		FreeMtText.Prepared p = FreeMtText.prepare(raw,
				terms(match(raw, "Dragon scimitar", "龙金弯刀")));

		assertFalse(p.skip);
		assertEquals("Say \"龙金弯刀\"\nnow", p.text);
		assertTrue(p.tokens.isEmpty());
	}

	// ---- restore ----------------------------------------------------------------------------

	@Test
	public void restoreMultipleTokensWithCjkAndPunctuation()
	{
		FreeMtText.Prepared p = prepared("{{0}} -> {{1}}",
				list("[你好，世界]", "<col=ffff00>"), list());

		assertEquals("[你好，世界] -> <col=ffff00>", FreeMtText.restore(p.text, p));
	}

	@Test
	public void restoreRoundTripsAnchoredCloserStyleLine()
	{
		String raw = "<colNum0>贝蒂</col> 使用 <colNum1>100</col>";
		FreeMtText.Prepared p = FreeMtText.prepare(raw, null);

		assertEquals("{{0}}贝蒂{{1}} 使用 {{2}}100{{3}}", p.text);
		assertEquals(raw, FreeMtText.restore(p.text, p));
	}

	@Test
	public void restoreCloserStyleRunWithOpaqueTokenRestoresMarkers()
	{
		String raw = "Hello <colNum0>wide <br> world</col>";
		FreeMtText.Prepared p = FreeMtText.prepare(raw, null);

		assertEquals("Hello {{0}}wide {{1}} world{{2}}", p.text);
		assertEquals("Hello <colNum0>wide <br> world</col>", FreeMtText.restore(p.text, p));
	}

	@Test
	public void restoreDuplicateLiteralTokensRestoreBothLiterals()
	{
		// Two adjacent anchored runs share the marker <colNum1>: it closes the first run and opens
		// the second, so both tokens legitimately carry the same literal.
		String raw = "<colNum0>贝蒂<colNum1>兰兹角村<colNum2>";
		FreeMtText.Prepared p = FreeMtText.prepare(raw, null);

		assertEquals("{{0}}贝蒂{{1}}{{2}}兰兹角村{{3}}", p.text);
		assertEquals(4, p.tokens.size());
		assertEquals("<colNum1>", p.tokens.get(1));
		assertEquals("the same literal is reused by the next pair's open token",
				p.tokens.get(1), p.tokens.get(2));
		assertEquals(2, p.colorPairs.size());
		assertPair(p, 0, 0, 1, "贝蒂");
		assertPair(p, 1, 2, 3, "兰兹角村");
		String reply = "甲{{0}}贝蒂{{1}}{{2}}兰兹角村{{3}}";
		assertEquals(FreeMtText.Result.OK, FreeMtText.validate(reply, p));
		assertEquals("甲<colNum0>贝蒂<colNum1><colNum1>兰兹角村<colNum2>",
				FreeMtText.restore(reply, p));
	}

	@Test
	public void elevenTokensRoundTripWithoutTokenOneEatingTen()
	{
		StringBuilder raw = new StringBuilder("Hello");
		for (int i = 0; i < 11; i++)
		{
			raw.append(" <x").append(i).append('>');
		}
		raw.append(" world");

		FreeMtText.Prepared p = FreeMtText.prepare(raw.toString(), null);
		assertFalse(p.skip);
		assertEquals(11, p.tokens.size());
		assertTrue("the eleventh token is numbered {{10}}, not a second {{1}}",
				p.text.contains("{{10}}"));
		assertEquals("the prepared line keeps all eleven protected spans",
				raw.toString(), FreeMtText.restore(p.text, p));

		String zh = "你好{{0}}一{{1}}二{{2}}三{{3}}四{{4}}五{{5}}"
				+ "六{{6}}七{{7}}八{{8}}九{{9}}十{{10}}尾";
		assertEquals("eleven ordered markers are a valid reply",
				FreeMtText.Result.OK, FreeMtText.validate(zh, p));

		String restored = FreeMtText.restore(zh, p);
		assertFalse("no marker may survive the restore", restored.contains("{{"));
		assertTrue("replacing {{1}} must not consume {{10}}", restored.contains("<x10>"));
		assertTrue(restored.startsWith("你好<x0>一<x1>"));
		assertTrue(restored.endsWith("<x10>尾"));
	}

	// ---- validate: acceptance -----------------------------------------------------------------

	@Test
	public void validateOk()
	{
		FreeMtText.Prepared p = prepared("Use {{0}}龙金弯刀{{1}} on",
				list("<colNum0>", "</col>"), list("龙金弯刀"), pair(0, 1, "龙金弯刀"));

		assertEquals(FreeMtText.Result.OK,
				FreeMtText.validate("在{{0}}龙金弯刀{{1}}上使用", p));
	}

	@Test
	public void validateOkWithoutSubstitutedTerms()
	{
		FreeMtText.Prepared p = prepared("Hello {{0}}", list("<a>"), list());

		assertEquals(FreeMtText.Result.OK, FreeMtText.validate("你好{{0}}", p));
	}

	@Test
	public void validateAcceptsReorderedAnchoredPairs()
	{
		// Bing may move whole anchored runs into Chinese word order; each pair only has to stay
		// complete (open before close, same text), never in its original section order.
		FreeMtText.Prepared p = prepared("{{0}}甲{{1}} {{2}}乙{{3}}",
				list("<colNum0>", "</col>", "<colNum1>", "</col>"), list(),
				pair(0, 1, "甲"), pair(2, 3, "乙"));

		assertEquals(FreeMtText.Result.OK,
				FreeMtText.validate("{{2}}乙{{3}} {{0}}甲{{1}}", p));
	}

	@Test
	public void validateAcceptsPairWithOpaqueTokenContent()
	{
		// A content containing a token cannot be compared as text: the pair order is the only check.
		FreeMtText.Prepared p = prepared("{{0}}贝蒂{{1}}{{2}}",
				list("<colNum0>", "<br>", "</col>"), list(), pair(0, 2, "贝蒂{{1}}"));

		assertEquals(FreeMtText.Result.OK,
				FreeMtText.validate("你好 {{0}}贝蒂{{2}}{{1}}", p));
	}

	@Test
	public void validatePairWithOpaqueTokenContentSkipsTextComparison()
	{
		// Content containing {{n}} is protected markup, so the pair's inner text cannot be compared:
		// even a completely different inner text is accepted (only the pair order is enforced).
		FreeMtText.Prepared p = prepared("{{0}}贝蒂{{1}}{{2}}",
				list("<colNum0>", "<br>", "</col>"), list(), pair(0, 2, "贝蒂{{1}}"));

		assertEquals(FreeMtText.Result.OK,
				FreeMtText.validate("你好 {{0}}完全不同的文字{{2}}{{1}}", p));
	}

	@Test
	public void validatePairContentIgnoresConvertedTrailingDot()
	{
		// Real Bing reply (2026-11-20 live probe): the ASCII "." inside the anchored content became
		// the full-width "。". The line must still be accepted, or it would stay English forever.
		String raw = "You have unlocked <colNum0>Augury.</col>";
		FreeMtText.Prepared p = FreeMtText.prepare(raw,
				terms(match(raw, "Augury", "占卜术")));

		String zh = "你已经解锁了{{0}}占卜术。{{1}}";
		assertEquals(FreeMtText.Result.OK, FreeMtText.validate(zh, p));
		assertEquals("the restored marker keeps the full-width text Bing produced",
				"你已经解锁了<colNum0>占卜术。</col>", FreeMtText.restore(zh, p));
	}

	@Test
	public void validatePairContentIgnoresConvertedParentheses()
	{
		// Real Bing reply (2026-11-20 live probe): "(贝蒂)" came back as "（贝蒂）".
		String raw = "Press <colNum0>(Betty)</col> to continue.";
		FreeMtText.Prepared p = FreeMtText.prepare(raw,
				terms(match(raw, "Betty", "贝蒂")));

		String zh = "按 {{0}}（贝蒂）{{1}} 继续。";
		assertEquals(FreeMtText.Result.OK, FreeMtText.validate(zh, p));
		assertEquals("按 <colNum0>（贝蒂）</col> 继续。", FreeMtText.restore(zh, p));
	}

	@Test
	public void validatePairContentIgnoresConvertedColonAndComma()
	{
		// Real Bing reply (2026-11-20 live probe): "草药学:" came back as "草药学：".
		String colonRaw = "Your <colNum0>Herblore:</col> level is now 50.";
		FreeMtText.Prepared colon = FreeMtText.prepare(colonRaw,
				terms(match(colonRaw, "Herblore", "草药学")));
		assertEquals(FreeMtText.Result.OK,
				FreeMtText.validate("你的{{0}}草药学：{{1}}等级现在是50。", colon));
		assertEquals("你的<colNum0>草药学：</col>等级现在是50。",
				FreeMtText.restore("你的{{0}}草药学：{{1}}等级现在是50。", colon));

		// The comma run also carries the trailing space of "Betty, ".
		String commaRaw = "Talk to <colNum0>Betty, <colNum1>then continue.";
		FreeMtText.Prepared comma = FreeMtText.prepare(commaRaw,
				terms(match(commaRaw, "Betty", "贝蒂")));
		assertEquals(FreeMtText.Result.OK,
				FreeMtText.validate("先和{{0}}贝蒂，{{1}}然后继续。", comma));
	}

	@Test
	public void validateAcceptsTranslatedTextInsideNonStrictPair()
	{
		FreeMtText.Prepared p = prepared("{{0}}Preserve{{1}}",
				list("<colNum0>", "</col>"), list(), properPair(0, 1, "Preserve"));

		// A protected English proper noun may stay English or be translated inside its pair; both
		// outcomes keep the colour on the right words, so no word comparison applies.
		assertEquals(FreeMtText.Result.OK,
				FreeMtText.validate("你还没有解锁使用 {{0}}Preserve{{1}} 的能力。", p));
		assertEquals(FreeMtText.Result.OK,
				FreeMtText.validate("你还没有解锁使用{{0}}保护{{1}}的能力。", p));
	}

	@Test
	public void validateStillRejectsChangedWordsInsideStrictPair()
	{
		// The relaxed rule is for non-strict proper-noun pairs only: an anchored Chinese run must
		// still wrap the same words, otherwise the line falls back safely.
		FreeMtText.Prepared p = prepared("{{0}}占卜术{{1}}",
				list("<colNum0>", "</col>"), list(), pair(0, 1, "占卜术"));

		assertEquals(FreeMtText.Result.PAIR_BROKEN,
				FreeMtText.validate("{{0}}保护{{1}}", p));
	}

	@Test
	public void validateOpaqueTokensMayReorder()
	{
		// Opaque tokens may travel with their surroundings: every token must appear exactly once.
		FreeMtText.Prepared p = prepared("Hello {{0}} world {{1}}", list("<br>", "<i>"), list());

		assertEquals(FreeMtText.Result.OK,
				FreeMtText.validate("你好 {{1}} 世界 {{0}}", p));
	}

	@Test
	public void validateGoldenCrossesSingleAndDoubleDigitTokenIndices()
	{
		// <colNumN> token indices and {{n}} markers must never be confused: a single-digit marker
		// must not capture the double-digit one.
		StringBuilder raw = new StringBuilder("A");
		StringBuilder wire = new StringBuilder("A");
		for (int i = 0; i <= 10; i++)
		{
			raw.append("<colNum").append(i).append(">甲").append(i).append("</col>");
			wire.append("{{").append(i * 2).append("}}甲").append(i)
					.append("{{").append(i * 2 + 1).append("}}");
		}
		FreeMtText.Prepared p = FreeMtText.prepare(raw.toString(), null);
		assertEquals(wire.toString(), p.text);
		assertEquals("eleven anchored runs", 11, p.colorPairs.size());

		StringBuilder reply = new StringBuilder();
		for (int i = 10; i >= 0; i--)
		{
			reply.append("{{").append(i * 2).append("}}甲").append(i)
					.append("{{").append(i * 2 + 1).append("}}");
		}
		assertEquals(FreeMtText.Result.OK, FreeMtText.validate(reply.toString(), p));
		String restored = FreeMtText.restore(reply.toString(), p);
		for (int i = 0; i <= 10; i++)
		{
			assertTrue("colour " + i + " must stay attached to its own text: " + restored,
					restored.contains("<colNum" + i + ">甲" + i));
		}
		assertFalse("a single-digit marker must not capture the double-digit run",
				restored.contains("<colNum1>甲10"));
	}

	// ---- validate: pair rejection -------------------------------------------------------------

	@Test
	public void validateTokenOrder()
	{
		FreeMtText.Prepared p = prepared("{{0}}甲{{1}}",
				list("<colNum0>", "</col>"), list(), pair(0, 1, "甲"));

		assertEquals(FreeMtText.Result.TOKEN_ORDER,
				FreeMtText.validate("{{1}}甲{{0}}", p));
	}

	@Test
	public void validatePairBrokenWhenContentChanged()
	{
		FreeMtText.Prepared p = prepared("{{0}}甲{{1}}",
				list("<colNum0>", "</col>"), list(), pair(0, 1, "甲"));

		assertEquals("a moved/renamed pair must reject the line, never paint a wrong word",
				FreeMtText.Result.PAIR_BROKEN, FreeMtText.validate("{{0}}乙{{1}}", p));
	}

	@Test
	public void validatePairBrokenWhenTwoPairsSwapTheirContent()
	{
		FreeMtText.Prepared p = prepared("{{0}}甲{{1}} {{2}}乙{{3}}",
				list("<colNum0>", "</col>", "<colNum1>", "</col>"), list(),
				pair(0, 1, "甲"), pair(2, 3, "乙"));

		assertEquals(FreeMtText.Result.PAIR_BROKEN,
				FreeMtText.validate("{{0}}乙{{1}} {{2}}甲{{3}}", p));
	}

	@Test
	public void validateStillRejectsPairWrappingDifferentWordsAfterPunctuationFolding()
	{
		FreeMtText.Prepared p = prepared("{{0}}占卜术.{{1}}",
				list("<colNum0>", "</col>"), list(), pair(0, 1, "占卜术."));

		// Folding punctuation must not fold the words: the same pair around another established
		// name still paints a colour the run was not translated from.
		assertEquals(FreeMtText.Result.PAIR_BROKEN,
				FreeMtText.validate("{{0}}附魔。{{1}}", p));
		assertEquals(FreeMtText.Result.PAIR_BROKEN,
				FreeMtText.validate("{{0}}占卜。{{1}}", p));
	}

	@Test
	public void validateRejectsLettersAddedToOrRemovedFromPairContent()
	{
		FreeMtText.Prepared p = prepared("{{0}}占卜术{{1}}",
				list("<colNum0>", "</col>"), list(), pair(0, 1, "占卜术"));

		assertEquals("an added Latin word changes the pair key", FreeMtText.Result.PAIR_BROKEN,
				FreeMtText.validate("{{0}}占卜术abc{{1}}", p));
		assertEquals("a dropped character changes the pair key", FreeMtText.Result.PAIR_BROKEN,
				FreeMtText.validate("{{0}}占卜{{1}}", p));
	}

	@Test
	public void validateMissingPairToken()
	{
		FreeMtText.Prepared p = prepared("{{0}}甲{{1}}",
				list("<colNum0>", "</col>"), list(), pair(0, 1, "甲"));

		assertEquals(FreeMtText.Result.TOKEN_MISSING,
				FreeMtText.validate("甲{{0}}", p));
	}

	// ---- validate: token / leftover rejection -------------------------------------------------

	@Test
	public void validateTokenMissing()
	{
		FreeMtText.Prepared p = prepared("Hello {{0}} {{1}}", list("<a>", "<b>"), list());

		assertEquals(FreeMtText.Result.TOKEN_MISSING,
				FreeMtText.validate("你好{{0}}", p));
	}

	@Test
	public void validateTokenDuplicated()
	{
		FreeMtText.Prepared p = prepared("Hello {{0}} {{1}}", list("<a>", "<b>"), list());

		assertEquals(FreeMtText.Result.TOKEN_MISSING,
				FreeMtText.validate("你好{{0}}{{0}}{{1}}", p));
	}

	@Test
	public void validateLeftover()
	{
		FreeMtText.Prepared p = prepared("Hello {{0}}", list("<a>"), list());

		assertEquals(FreeMtText.Result.LEFTOVER,
				FreeMtText.validate("你好{{0}}{{x}}", p));
	}

	@Test
	public void validateNullTokenListTreatedAsEmpty()
	{
		assertEquals(FreeMtText.Result.OK,
				FreeMtText.validate("你好世界", prepared("Hello world", null, null)));
		// A null token list means "no known tokens": there is nothing to match, so no marker check.
		assertEquals(FreeMtText.Result.OK,
				FreeMtText.validate("你好世界", prepared("Hello {{0}} {{1}}", null, null)));
	}

	// ---- validate: CJK and echo --------------------------------------------------------------

	@Test
	public void validateNoCjkPureEnglish()
	{
		assertEquals(FreeMtText.Result.NO_CJK,
				FreeMtText.validate("Hello {{0}}", prepared("Hello {{0}}", list("<a>"), list())));
	}

	@Test
	public void validateNoCjkWhenTermLeftUntranslated()
	{
		// The only CJK in zh comes from the substituted term itself: that is not a translation.
		FreeMtText.Prepared p = prepared("Use 占卜术 now", list(), list("占卜术"));

		assertEquals(FreeMtText.Result.NO_CJK, FreeMtText.validate("Use 占卜术 now", p));
	}

	@Test
	public void validateNoCjkWhenOnlyAnchoredTermsAreInTheReply()
	{
		String raw = "<colNum0>Augury</col> and <colNum1>Beta</col>";
		FreeMtText.Prepared p = FreeMtText.prepare(raw,
				terms(match(raw, "Augury", "占卜术")));

		assertEquals(list("占卜术"), p.substitutedTerms);
		// Augury is anchored strict; the English "Beta" is a protected non-strict proper noun.
		assertEquals(2, p.colorPairs.size());
		assertPair(p, 0, 0, 1, "占卜术");
		assertProperPair(p, 1, 2, 3, "Beta");
		// Reordered pairs differ from the source wire, so this cannot be classified as ECHO.
		assertEquals(FreeMtText.Result.NO_CJK, FreeMtText.validate(
				"{{2}}Beta{{3}} and {{0}}占卜术{{1}}", p));
	}

	@Test
	public void validateEcho()
	{
		assertEquals(FreeMtText.Result.ECHO,
				FreeMtText.validate("Hello 世界", prepared("Hello 世界", list(), list())));
	}

	@Test
	public void validateEchoWithAnchoredWire()
	{
		FreeMtText.Prepared p = prepared("{{0}}世界{{1}}",
				list("<colNum0>", "</col>"), list(), pair(0, 1, "世界"));

		assertEquals(FreeMtText.Result.ECHO, FreeMtText.validate("{{0}}世界{{1}}", p));
	}

	@Test
	public void validateEchoAgainstPreparedText()
	{
		// ECHO compares against the prepared text (already containing the Chinese term), so the
		// remaining non-term CJK still makes it an echo rather than NO_CJK.
		FreeMtText.Prepared p = prepared("龙金弯刀 使用", list(), list("龙金弯刀"));

		assertEquals(FreeMtText.Result.ECHO, FreeMtText.validate("龙金弯刀 使用", p));
	}

	@Test
	public void validateEchoIgnoresWhitespace()
	{
		FreeMtText.Prepared p = prepared("Hello  世界  please\nnow", list(), list());

		assertEquals(FreeMtText.Result.ECHO,
				FreeMtText.validate("Hello 世界 please now", p));
	}

	// ---- helpers ----------------------------------------------------------------------------

	@Test
	public void flattenCollapsesWhitespaceAndHandlesNull()
	{
		assertEquals("", FreeMtText.flatten(null));
		assertEquals("", FreeMtText.flatten("   "));
		assertEquals("Hello world", FreeMtText.flatten("  Hello \n\t  world  "));
		assertEquals("a b", FreeMtText.flatten("a\t b"));
	}

	@Test
	public void hasCjkBoundaries()
	{
		assertFalse(FreeMtText.hasCjk(null));
		assertFalse(FreeMtText.hasCjk(""));
		assertFalse(FreeMtText.hasCjk("abc"));
		assertTrue(FreeMtText.hasCjk("中文"));
		assertTrue(FreeMtText.hasCjk("a\u4E2Db"));
		assertTrue(FreeMtText.hasCjk("\u3400"));
		assertTrue(FreeMtText.hasCjk("\u9FFF"));
		assertFalse(FreeMtText.hasCjk("\u33FF"));
		assertFalse(FreeMtText.hasCjk("\uA000"));
	}

	@Test
	public void maxTextCharsConstant()
	{
		assertEquals(2000, FreeMtText.MAX_TEXT_CHARS);
	}
}
