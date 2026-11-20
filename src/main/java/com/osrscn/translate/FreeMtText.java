package com.osrscn.translate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text preparation for the free machine-translation backend, plus reply validation and restore.
 *
 * <p>Free MT has no terminology prompt and no protected-span syntax, so the established Chinese
 * term names are substituted directly into the text and RuneLite markup/placeholders are replaced
 * with {@code {{n}}} markers that the model is asked to keep. Everything here is pure logic: no
 * RuneLite and no network.
 *
 * <p>Colour runs use a locally anchored design. A "run" is the visible text belonging to one opening
 * colour placeholder produced by {@code Tags.placeholdColors} ({@code <colNumN>}): the next
 * placeholder or a literal {@code </col>} closes it, and the line before the first placeholder is
 * not a run. The placeholder is not a colour value - {@code Translator}/{@code Tags.restoreColors}
 * paint it later - so a colour marker may travel anywhere in the Chinese translation as long as it
 * stays attached to the words it colours. That is what the wire does:
 *
 * <ul>
 * <li>a run whose content is already Chinese after NAME substitution (no ASCII letters, at least one
 * CJK character or digit) is a STRICT anchor: protected as {@code {{open}}content{{close}}}, two
 * distinct tokens whose literals are the run's opening marker and its closing boundary
 * ({@code <colNumM>} or {@code </col>}). Bing treats an opaque token pair as a unit and keeps it
 * glued to text that needs no translation, so the markers restore in Chinese word order;</li>
 * <li>every other closed run becomes a NON-STRICT pair: a closer-style run (closed by the literal
 * {@code </col>}) is ALWAYS protected, so the whole wrapped sentence keeps its colour while Bing
 * translates the content inside the pair, and a switch-style run is protected when its content looks
 * like an English proper noun (short, 1-4 capitalised/digit-led words, no sentence punctuation; see
 * {@link #isProperNounShape}). Bing may keep the English text or translate inside such a pair, and
 * either outcome keeps the colour on the words it belongs to;</li>
 * <li>only a switch-style base run that is neither Chinese nor proper-noun shaped is sent as plain
 * text without its markers. Its colour is lost and the line renders in the base colour, which is the
 * existing safe fallback: never paint the wrong words. Protecting switch base text was measured to
 * corrupt Bing output, so the switch rule stays conservative;</li>
 * <li>a literal {@code </col>} is never sent to the provider.</li>
 * </ul>
 *
 * <p>Every other {@code <...>} / {@code [...]} span is still an opaque {@code {{n}}} token, inside
 * or outside a run. Token numbers are assigned in wire order (the opening token of a run is written
 * before the opaque tokens inside its content, so the allocation is renumbered after the scan).
 *
 * <p>{@link #prepare(String, List)} performs a single left-to-right scan over the raw line. Term
 * offsets come from the raw string and are consumed exactly as given, so substituting a term never
 * shifts the offsets of a later tag (and vice versa).
 */
final class FreeMtText
{
	/** Longest input accepted for one free-MT request. */
	static final int MAX_TEXT_CHARS = 2000;

	/** Why {@link #prepare(String, List)} refused a line, or how {@link #validate} classified a reply. */
	enum Result
	{
		OK,
		NO_CJK,
		ECHO,
		TOKEN_MISSING,
		TOKEN_ORDER,
		PAIR_BROKEN,
		LEFTOVER
	}

	/**
	 * One protected colour run: the token pair that restores its opening and closing markers around
	 * the content Bing kept, so the translation is painted with the colour it was translated from.
	 */
	static final class ColorPair
	{
		/** Token index of the run's opening marker, restored as {@code <colNumN>}. */
		final int open;
		/** Token index of the run's closing boundary ({@code <colNumM>} or {@code </col>}). */
		final int close;
		/** The protected content as it appears in {@link Prepared#text} (may contain {{n}} tokens). */
		final String content;
		/**
		 * true = already-Chinese anchored run: the reply must still wrap the same words;
		 * false = protected English proper noun: Bing may keep or translate it, so only the pair
		 * order is enforced.
		 */
		final boolean strict;

		ColorPair(int open, int close, String content, boolean strict)
		{
			this.open = open;
			this.close = close;
			this.content = content;
			this.strict = strict;
		}
	}

	/** Outcome of {@link #prepare(String, List)}: what to send, the protected spans, and whether to skip. */
	static final class Prepared
	{
		String text = "";
		/** The literal restored for every {@code {{n}}} marker of {@link #text}. */
		List<String> tokens = Collections.emptyList();
		List<String> substitutedTerms = Collections.emptyList();
		/** Protected colour runs: their tokens restore the colour markers around the kept text. */
		List<ColorPair> colorPairs = Collections.emptyList();
		boolean skip;
	}

	/** One opening colour placeholder, as produced by {@code Tags.placeholdColors}. */
	private static final Pattern COLOR_PLACEHOLDER = Pattern.compile("<colNum(\\d+)>");
	/** The literal colour closer; it only ends a run and is never sent to the provider. */
	private static final String COLOR_CLOSE = "</col>";

	private FreeMtText()
	{
	}

	/**
	 * Replaces the closed colour runs with locally anchored token pairs (strict for already-Chinese
	 * content, non-strict for English proper nouns), every other RuneLite markup/{@code [...]} span
	 * with a {@code {{n}}} token, and NAME-table terms with their Chinese names in one scan of
	 * {@code english}.
	 */
	static Prepared prepare(String english, List<TermMatch> terms)
	{
		Prepared prepared = new Prepared();
		if (english == null || english.length() > MAX_TEXT_CHARS
				|| english.contains("{{") || english.contains("}}"))
		{
			prepared.skip = true;
			return prepared;
		}

		List<TermMatch> candidates = terms == null
				? Collections.<TermMatch>emptyList() : terms;
		List<Part> out = new ArrayList<>();
		List<PendingPair> pendingPairs = new ArrayList<>();
		List<String> substituted = new ArrayList<>();
		// placeholdColors numbers every opening tag once. A repeated index cannot be paired
		// unambiguously, so it stays an opaque token instead of opening a second run.
		List<Integer> usedColorIndices = new ArrayList<>();
		Matcher color = COLOR_PLACEHOLDER.matcher(english);
		Run run = null;
		int i = 0;
		while (i < english.length())
		{
			// A usable colour placeholder closes the open run with its own literal and opens the next
			// run. Adjacent markers therefore close an empty run, exactly like the empty spans they
			// stand for. An unusable one (unparsable, out of range or repeated) falls through and is
			// tokenised as an opaque span instead.
			if (english.charAt(i) == '<' && english.startsWith("<colNum", i))
			{
				color.region(i, english.length());
				if (color.lookingAt())
				{
					Integer parsed = parseColorIndex(color.group(1));
					if (parsed != null && !usedColorIndices.contains(parsed))
					{
						String marker = color.group();
						if (run != null)
						{
							flush(run, marker, out, pendingPairs);
						}
						usedColorIndices.add(parsed);
						run = new Run(marker);
						i = color.end();
						continue;
					}
				}
			}

			char c = english.charAt(i);
			if (c == '<')
			{
				if (english.startsWith(COLOR_CLOSE, i))
				{
					// A closer only ends a run; with no run open it is dropped silently and it is
					// never sent - Bing only needs the anchored pairs to keep the text attached.
					if (run != null)
					{
						flush(run, COLOR_CLOSE, out, pendingPairs);
						run = null;
					}
					i += COLOR_CLOSE.length();
					continue;
				}
				int gt = english.indexOf('>', i + 1);
				if (gt >= 0)
				{
					append(out, run, Part.token(english.substring(i, gt + 1)));
					i = gt + 1;
					continue;
				}
			}
			else if (c == '[')
			{
				int close = english.indexOf(']', i + 1);
				if (close >= 0)
				{
					append(out, run, Part.token(english.substring(i, close + 1)));
					i = close + 1;
					continue;
				}
			}

			TermMatch best = null;
			for (TermMatch term : candidates)
			{
				if (term == null || term.start != i || term.end <= i || term.end > english.length())
				{
					continue;
				}
				if (best == null || term.end > best.end)
				{
					best = term;
				}
			}
			if (best != null)
			{
				append(out, run, Part.text(best.zh));
				substituted.add(best.zh);
				i = best.end;
				continue;
			}

			append(out, run, Part.text(String.valueOf(c)));
			i++;
		}
		if (run != null)
		{
			// No closing boundary: no pair can be validated, so the markers are dropped.
			flush(run, null, out, pendingPairs);
		}

		// Number the tokens in wire order and render the final string. The allocation order is not
		// wire order: an anchored run's opening token is written before the opaque tokens inside its
		// content, so the token list is rebuilt here rather than during the scan.
		List<String> tokens = new ArrayList<>();
		StringBuilder text = new StringBuilder(english.length() + 16);
		for (Part part : out)
		{
			if (part.literal != null)
			{
				part.token = tokens.size();
				tokens.add(part.literal);
				text.append("{{").append(part.token).append("}}");
			}
			else
			{
				text.append(part.text);
			}
		}
		List<ColorPair> colorPairs = new ArrayList<>(pendingPairs.size());
		for (PendingPair pair : pendingPairs)
		{
			colorPairs.add(new ColorPair(pair.open.token, pair.close.token,
					render(pair.content), pair.strict));
		}

		prepared.text = text.toString();
		prepared.tokens = tokens;
		prepared.substitutedTerms = substituted;
		prepared.colorPairs = colorPairs;
		// Nothing left to translate: only tokens (wire syntax or protected markup), anchored Chinese
		// names and digits. Anchored content never needs a second stripping pass.
		prepared.skip = !hasLatinLetter(prepared.text);
		return prepared;
	}

	/**
	 * Classifies a free-MT reply. {@code zh} must be non-empty (the caller guarantees it);
	 * {@code prepared} is the line {@link #prepare} produced.
	 *
	 * <p>Opaque tokens only have to occur exactly once, never in their original order: Bing moves
	 * anchored runs with their words, so the tokens inside a run travel with it. Each colour pair
	 * must still be complete and in open-then-close order. A STRICT pair (an already-Chinese run)
	 * must also still wrap the same words, otherwise a moved or renamed pair would paint the
	 * translation with a colour it was not translated from; that word comparison ignores punctuation
	 * and whitespace, because Bing rewrites ASCII punctuation inside an anchored run to its
	 * full-width form ({@code 占卜术.} becomes {@code 占卜术。}). A NON-STRICT pair protects a
	 * non-anchored colour span (every closer-style run, or a switch-style English proper noun):
	 * Bing translates that content inside the pair, so only its order is enforced.
	 */
	static Result validate(String zh, Prepared prepared)
	{
		if (prepared == null)
		{
			prepared = new Prepared();
		}
		String reply = zh == null ? "" : zh;
		String withoutTerms = reply;
		if (prepared.substitutedTerms != null)
		{
			for (String term : prepared.substitutedTerms)
			{
				if (term != null && !term.isEmpty())
				{
					withoutTerms = withoutTerms.replace(term, "");
				}
			}
		}
		if (!hasCjk(withoutTerms))
		{
			return Result.NO_CJK;
		}
		if (flatten(reply).equals(flatten(prepared.text)))
		{
			return Result.ECHO;
		}

		List<String> tokens = prepared.tokens == null
				? Collections.<String>emptyList() : prepared.tokens;
		for (int i = 0; i < tokens.size(); i++)
		{
			String marker = "{{" + i + "}}";
			int first = reply.indexOf(marker);
			if (first < 0 || reply.indexOf(marker, first + marker.length()) >= 0)
			{
				return Result.TOKEN_MISSING;
			}
		}

		List<ColorPair> pairs = prepared.colorPairs == null
				? Collections.<ColorPair>emptyList() : prepared.colorPairs;
		for (ColorPair pair : pairs)
		{
			String openMarker = "{{" + pair.open + "}}";
			String closeMarker = "{{" + pair.close + "}}";
			int open = reply.indexOf(openMarker);
			int close = reply.indexOf(closeMarker);
			if (open < 0 || close < 0)
			{
				return Result.TOKEN_MISSING;
			}
			if (close <= open)
			{
				return Result.TOKEN_ORDER;
			}
			if (pair.strict && pair.content != null && pair.content.indexOf("{{") < 0)
			{
				// An already-Chinese pair must still wrap its own words: a moved or renamed pair would
				// paint the translation with a colour it was not translated from. Punctuation and
				// whitespace are dropped before comparing, because Bing converts the ASCII punctuation
				// of the run to its full-width form (a dot becomes 。, "(" becomes （, ...).
				String between = reply.substring(open + openMarker.length(), close);
				if (!wordKey(between).equals(wordKey(pair.content)))
				{
					return Result.PAIR_BROKEN;
				}
			}
		}

		String stripped = reply;
		for (int i = 0; i < tokens.size(); i++)
		{
			stripped = stripped.replace("{{" + i + "}}", "");
		}
		if (stripped.contains("{{") || stripped.contains("}}"))
		{
			return Result.LEFTOVER;
		}
		return Result.OK;
	}

	/**
	 * The comparison key of an anchored pair: only letters and digits survive (CJK included, because
	 * {@link Character#isLetterOrDigit(int)} covers it), so whitespace, punctuation and symbols may
	 * be rewritten by the provider without rejecting the line. The words themselves still have to
	 * match, which is what keeps a moved or renamed pair from painting the wrong words.
	 */
	private static String wordKey(String text)
	{
		StringBuilder key = new StringBuilder(text.length());
		for (int i = 0; i < text.length(); )
		{
			int codePoint = text.codePointAt(i);
			if (Character.isLetterOrDigit(codePoint))
			{
				key.appendCodePoint(codePoint);
			}
			i += Character.charCount(codePoint);
		}
		return key.toString();
	}

	/** Puts the original protected spans (and anchored colour markers) back into a validated reply. */
	static String restore(String zh, Prepared prepared)
	{
		String out = zh == null ? "" : zh;
		if (prepared == null || prepared.tokens == null)
		{
			return out;
		}
		// Anchored runs are plain tokens too: the opening token restores {@code <colNumN>} and the
		// closing boundary restores {@code <colNumM>} or {@code </col>}, wherever Bing kept the pair.
		for (int i = 0; i < prepared.tokens.size(); i++)
		{
			if (prepared.tokens.get(i) != null)
			{
				out = out.replace("{{" + i + "}}", prepared.tokens.get(i));
			}
		}
		return out;
	}

	/** Parses a colour placeholder index, or null when it is not a usable index (never throws). */
	private static Integer parseColorIndex(String digits)
	{
		try
		{
			int index = Integer.parseInt(digits);
			return index < 0 ? null : index;
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}

	/** True when {@code text} contains any CJK character from U+3400 through U+9FFF. */
	static boolean hasCjk(String text)
	{
		if (text == null)
		{
			return false;
		}
		for (int i = 0; i < text.length(); i++)
		{
			char c = text.charAt(i);
			if (c >= '\u3400' && c <= '\u9FFF')
			{
				return true;
			}
		}
		return false;
	}

	/** Trims and collapses every whitespace run to one space; null becomes the empty string. */
	static String flatten(String text)
	{
		return text == null ? "" : text.trim().replaceAll("\\s+", " ");
	}

	/** True when {@code text} contains at least one ASCII letter. */
	private static boolean hasLatinLetter(String text)
	{
		if (text == null)
		{
			return false;
		}
		for (int i = 0; i < text.length(); i++)
		{
			char c = text.charAt(i);
			if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z'))
			{
				return true;
			}
		}
		return false;
	}

	/** Appends a part to the open run, or to the plain wire when no run is open. */
	private static void append(List<Part> out, Run run, Part part)
	{
		if (run == null)
		{
			out.add(part);
		}
		else
		{
			run.content.add(part);
		}
	}

	/**
	 * Ends a run. A closed run is written as an open token, its kept content and a close token (and
	 * recorded so the colour markers restore with it) when its content is already Chinese (a STRICT
	 * anchor), when it is closer-style (a NON-STRICT pair, so the whole wrapped sentence keeps its
	 * colour while Bing translates inside it), or when it is switch-style but looks like an English
	 * proper noun. Any other run is written as plain text with no markers, including a run that is
	 * still open at the end of the line ({@code closeLiteral == null}).
	 */
	private static void flush(Run run, String closeLiteral, List<Part> out, List<PendingPair> pendingPairs)
	{
		if (closeLiteral == null)
		{
			// No closing boundary: no pair can be validated, so the markers are dropped.
			out.addAll(run.content);
			return;
		}
		boolean anchored = isAnchored(run.content);
		if (!anchored)
		{
			// A closer-style run is always protected: wrapping the whole span in a token pair keeps
			// its colour while Bing translates the content inside it (live-probed). A switch-style
			// base run is protected only when it looks like an English proper noun; protecting switch
			// base text was measured to corrupt Bing output.
			boolean closerStyle = COLOR_CLOSE.equals(closeLiteral);
			if (run.content.isEmpty() || (!closerStyle && !isProperNounShape(run.content)))
			{
				out.addAll(run.content);
				return;
			}
		}
		Part open = Part.token(run.open);
		Part close = Part.token(closeLiteral);
		out.add(open);
		out.addAll(run.content);
		out.add(close);
		pendingPairs.add(new PendingPair(open, close, run.content, anchored));
	}

	/**
	 * True when a run is already Chinese and so must not be translated again: its visible text is
	 * non-empty, holds no ASCII letter, and holds at least one CJK character or ASCII digit. Opaque
	 * {@code {{n}}} placeholders inside the run are protected markup, not text, and do not count (a
	 * token-only or punctuation-only run is not anchored). The name terms are already substituted,
	 * so anchored content is the established Chinese name; Bing then never translates it.
	 */
	private static boolean isAnchored(List<Part> content)
	{
		boolean hasCjkOrDigit = false;
		for (Part part : content)
		{
			if (part.literal != null)
			{
				continue; // an opaque token is not translatable visible text
			}
			for (int i = 0; i < part.text.length(); i++)
			{
				char c = part.text.charAt(i);
				if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z'))
				{
					return false;
				}
				if ((c >= '\u3400' && c <= '\u9FFF') || (c >= '0' && c <= '9'))
				{
					hasCjkOrDigit = true;
				}
			}
		}
		return hasCjkOrDigit;
	}

	/** Lower-case words that may appear inside a proper noun ("Mage of Zamorak", "Al Kharid"). */
	private static final Set<String> NAME_CONNECTORS = Set.of(
			"the", "of", "and", "a", "an", "de", "von", "di", "du", "al", "bin", "ibn");

	/**
	 * True when a switch-style English run looks like a proper noun and should therefore keep its
	 * colour even though it is not pre-translated: 1-4 short words, no sentence punctuation, and every
	 * word either capitalised/digit-led or a lower-case connector, with at least one capitalised or
	 * digit word. Such a span becomes a non-strict pair: Bing may keep the English name or translate
	 * it inside the pair, and either outcome keeps the colour on the words it belongs to. A
	 * closer-style run is protected regardless of shape, so this test only decides switch-style base
	 * text. The content must be plain text - a run holding an opaque token is not treated as a name.
	 */
	private static boolean isProperNounShape(List<Part> content)
	{
		StringBuilder visible = new StringBuilder();
		for (Part part : content)
		{
			if (part.literal != null)
			{
				return false; // a placeholder inside the span: not a name candidate
			}
			visible.append(part.text);
		}
		String text = visible.toString().trim();
		if (text.isEmpty() || text.length() > 40 || hasSentencePunctuation(text))
		{
			return false;
		}
		String[] words = text.split("\\s+");
		if (words.length > 4)
		{
			return false;
		}
		boolean hasCapitalOrDigit = false;
		for (String word : words)
		{
			String core = wordCore(word);
			if (core.isEmpty())
			{
				return false;
			}
			char first = core.charAt(0);
			if (Character.isUpperCase(first) || Character.isDigit(first))
			{
				hasCapitalOrDigit = true;
			}
			else if (!NAME_CONNECTORS.contains(core.toLowerCase(Locale.ROOT)))
			{
				return false;
			}
		}
		return hasCapitalOrDigit;
	}

	/** True when {@code text} holds punctuation that appears in a sentence but never in a name. */
	private static boolean hasSentencePunctuation(String text)
	{
		for (int i = 0; i < text.length(); i++)
		{
			char c = text.charAt(i);
			if (c == ',' || c == ';' || c == ':' || c == '!' || c == '?'
					|| c == '\uFF0C' || c == '\uFF1B' || c == '\uFF1A' || c == '\uFF01'
					|| c == '\uFF1F')
			{
				return true;
			}
		}
		return false;
	}

	/** The word without its leading/trailing non-letter-or-digit characters ("(Easy)" -> "Easy"). */
	private static String wordCore(String word)
	{
		int start = 0;
		int end = word.length();
		while (start < end && !Character.isLetterOrDigit(word.charAt(start)))
		{
			start++;
		}
		while (end > start && !Character.isLetterOrDigit(word.charAt(end - 1)))
		{
			end--;
		}
		return word.substring(start, end);
	}

	/** Renders parts as wire text, using the token numbers assigned in wire order. */
	private static String render(List<Part> parts)
	{
		StringBuilder out = new StringBuilder();
		for (Part part : parts)
		{
			out.append(part.literal != null ? "{{" + part.token + "}}" : part.text);
		}
		return out.toString();
	}

	/** A colour run being scanned: its opening literal and the parts of its content. */
	private static final class Run
	{
		final String open;
		final List<Part> content = new ArrayList<>();

		Run(String open)
		{
			this.open = open;
		}
	}

	/** One part of the wire: literal text, or one {@code {{n}}} token reference. */
	private static final class Part
	{
		/** Literal text of this part; null when this part is a token. */
		final String text;
		/** Literal restored for this token; null when this part is text. */
		final String literal;
		/** Wire-order token number, assigned after the scan (-1 for text). */
		int token = -1;

		private Part(String text, String literal)
		{
			this.text = text;
			this.literal = literal;
		}

		static Part text(String text)
		{
			return new Part(text, null);
		}

		static Part token(String literal)
		{
			return new Part(null, literal);
		}
	}

	/** A closed run awaiting the wire-order numbering of its two tokens. */
	private static final class PendingPair
	{
		final Part open;
		final Part close;
		/** The run content parts, kept together so the colour pair can render its text. */
		final List<Part> content;
		/** true = already-Chinese strict anchor, false = protected English proper noun. */
		final boolean strict;

		PendingPair(Part open, Part close, List<Part> content, boolean strict)
		{
			this.open = open;
			this.close = close;
			this.content = content;
			this.strict = strict;
		}
	}
}
