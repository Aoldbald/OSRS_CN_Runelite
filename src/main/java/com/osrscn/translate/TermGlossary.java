package com.osrscn.translate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Per-line terminology hint for AI translation, built from the local NAME table.
 *
 * <p>The model cannot see the lookup tables, so it has to guess whether an OSRS noun already has an
 * established Chinese name - and often keeps the noun in English instead. {@link #promptFor} finds
 * the NAME rows that actually occur in one line, so a request can carry only those translations
 * instead of the whole table.
 *
 * <p>Pure data structure (no RuneLite dependency) and immutable after {@link #build}, so the one
 * instance built at store load time is shared by every request. It must only be consulted for an
 * admitted request: a full-table scan per lookup would be ~10x slower, and a per-tick scan would
 * repeat the work for every pending line.
 */
final class TermGlossary
{
	// Short names ("Me", "On", "Up") are ordinary words far more often than they are entity names.
	private static final int MIN_TERM_LENGTH = 4;
	// Bounds prompt growth. Measured hits are ~0.7 per line, so these only cut pathological lines.
	private static final int MAX_TERMS = 12;
	private static final int MAX_PROMPT_CHARS = 300;
	// Shared with AiTranslator's echo guard so the detection string cannot drift from the block.
	static final String HEADER =
			"Terminology (reference translations; use only when the text refers to that named thing):";
	// Irregular plurals a suffix rule cannot derive; the ignore check treats each pair as one term,
	// so the reviewed list only needs the dictionary form (e.g. "dwarf" also ignores "dwarves").
	private static final Map<String, Set<String>> IRREGULAR = irregular();
	private static final Pattern TAGS = Pattern.compile("<[^>]*>");
	private static final Pattern BRACKET_PLACEHOLDERS = Pattern.compile("\\[[^\\]]*\\]");
	private static final Pattern TOKEN_SPLIT = Pattern.compile("[^a-z0-9']+");

	private static final class Term
	{
		final String en;
		final String zh;

		Term(String en, String zh)
		{
			this.en = en;
			this.zh = zh;
		}
	}

	private static final class Hit
	{
		final Term term;
		final List<int[]> spans;

		Hit(Term term, List<int[]> spans)
		{
			this.term = term;
			this.spans = spans;
		}
	}

	private final Map<String, List<Term>> byToken;
	private final int size;

	private TermGlossary(Map<String, List<Term>> byToken, int size)
	{
		this.byToken = byToken;
		this.size = size;
	}

	/**
	 * Build the index from NAME rows. Rows that cannot be a useful hint are dropped: shorter than
	 * {@link #MIN_TERM_LENGTH}, no Chinese, value identical to the key, a tag in the value, or listed
	 * in {@code ignored} (an ignored term also covers its regular plural/possessive and irregular
	 * plural forms). A duplicate English key keeps the first row, matching TranslationStore's
	 * putIfAbsent load order.
	 *
	 * @param nameTable English -> Chinese rows (the NAME category map)
	 * @param ignored   lower-case English terms that must never be forced as terminology (may be empty)
	 */
	static TermGlossary build(Map<String, String> nameTable, Set<String> ignored)
	{
		Map<String, Term> unique = new LinkedHashMap<>();
		if (nameTable != null)
		{
			for (Map.Entry<String, String> row : nameTable.entrySet())
			{
				String en = row.getKey() == null ? "" : row.getKey().trim();
				String zh = row.getValue() == null ? "" : row.getValue().trim();
				if (en.length() < MIN_TERM_LENGTH || en.indexOf('<') >= 0)
				{
					continue;
				}
				if (zh.isEmpty() || zh.indexOf('<') >= 0 || zh.equalsIgnoreCase(en) || !hasCjk(zh))
				{
					continue;
				}
				String lower = en.toLowerCase(Locale.ROOT);
				if (ignored != null && isIgnored(lower, ignored))
				{
					continue;
				}
				unique.putIfAbsent(lower, new Term(en, zh));
			}
		}
		if (unique.isEmpty())
		{
			return new TermGlossary(Collections.emptyMap(), 0);
		}
		Map<String, List<Term>> byToken = new HashMap<>(unique.size() * 2);
		for (Term term : unique.values())
		{
			Set<String> tokens = new LinkedHashSet<>();
			for (String token : TOKEN_SPLIT.split(term.en.toLowerCase(Locale.ROOT)))
			{
				if (!token.isEmpty() && tokens.add(token))
				{
					byToken.computeIfAbsent(token, k -> new ArrayList<>()).add(term);
				}
			}
		}
		return new TermGlossary(Collections.unmodifiableMap(byToken), unique.size());
	}

	/** Number of usable terms in the index (diagnostics/tests); 0 when the NAME table was empty. */
	int size()
	{
		return size;
	}

	/**
	 * @return a prompt block listing reference translations for NAME terms present in
	 *         {@code text}, or an empty string when none match. Tags and {@code [...]} placeholders
	 *         are removed before matching, so a name/player placeholder can never produce a hit.
	 */
	String promptFor(String text)
	{
		if (text == null || byToken.isEmpty())
		{
			return "";
		}
		String plain = plain(text);
		if (plain.isEmpty())
		{
			return "";
		}
		String lower = plain.toLowerCase(Locale.ROOT);
		Set<Term> candidates = new HashSet<>();
		for (String token : TOKEN_SPLIT.split(lower))
		{
			List<Term> list = byToken.get(token);
			if (list != null)
			{
				candidates.addAll(list);
			}
			// "Lumbridge's" is a single token, so the plain name is only reachable through its base
			// form. Unconditional, not only on a miss: a multi-word term such as "Adventurer's top"
			// also indexes the possessive token and would otherwise shadow the plain name.
			if (token.endsWith("'s") && token.length() > 3)
			{
				List<Term> base = byToken.get(token.substring(0, token.length() - 2));
				if (base != null)
				{
					candidates.addAll(base);
				}
			}
		}
		if (candidates.isEmpty())
		{
			return "";
		}
		String padded = " " + lower + " ";
		List<Hit> hits = new ArrayList<>();
		for (Term term : candidates)
		{
			List<int[]> spans = spans(padded, term.en.toLowerCase(Locale.ROOT));
			if (!spans.isEmpty())
			{
				hits.add(new Hit(term, spans));
			}
		}
		if (hits.isEmpty())
		{
			return "";
		}
		// Longest first, so "Lord Hosidius" wins and shorter hits fully inside it are skipped.
		// Ties are broken by position then spelling, so the prompt is stable across requests.
		hits.sort(Comparator
				.comparingInt((Hit hit) -> hit.term.en.length()).reversed()
				.thenComparingInt(hit -> hit.spans.get(0)[0])
				.thenComparing(hit -> hit.term.en, String.CASE_INSENSITIVE_ORDER));
		List<int[]> covered = new ArrayList<>();
		List<Hit> chosen = new ArrayList<>();
		// The budget covers the whole block, so the header and its newline count as well.
		int chars = HEADER.length() + 1;
		for (Hit hit : hits)
		{
			if (allCovered(hit.spans, covered))
			{
				continue;
			}
			// "  " + en + " => " + zh + "\n" really costs en + zh + 7 characters.
			int lineLen = hit.term.en.length() + hit.term.zh.length() + 7;
			if (chosen.size() >= MAX_TERMS)
			{
				break;
			}
			// Test before adding so the block never overshoots; the first hit is always kept, or a
			// pathological line could yield an empty block that carries no terminology at all.
			// A hit that does not fit is skipped, not fatal: hits arrive longest first, so a later,
			// shorter hit may still fit where this one cannot.
			if (!chosen.isEmpty() && chars + lineLen > MAX_PROMPT_CHARS)
			{
				continue;
			}
			chosen.add(hit);
			for (int[] span : hit.spans)
			{
				covered.add(span);
			}
			chars += lineLen;
		}
		StringBuilder prompt = new StringBuilder(128);
		prompt.append(HEADER).append('\n');
		for (Hit hit : chosen)
		{
			prompt.append("  ").append(hit.term.en).append(" => ").append(hit.term.zh).append('\n');
		}
		return prompt.toString().trim();
	}

	/** Strip tags and {@code [...]} placeholders, then canonicalize the same way lookups do. */
	private static String plain(String text)
	{
		String s = TAGS.matcher(text).replaceAll(" ");
		s = BRACKET_PLACEHOLDERS.matcher(s).replaceAll(" ");
		return TranslationStore.normalize(s);
	}

	/** Occurs of {@code term} in {@code padded} that stand on word boundaries. */
	private static List<int[]> spans(String padded, String term)
	{
		List<int[]> out = new ArrayList<>();
		int from = 0;
		while ((from = padded.indexOf(term, from)) >= 0)
		{
			int end = from + term.length();
			char before = padded.charAt(from - 1);
			char after = padded.charAt(end);
			if (!Character.isLetterOrDigit(before) && before != '_'
					&& !Character.isLetterOrDigit(after) && after != '_')
			{
				out.add(new int[] {from, end});
			}
			from++;
		}
		return out;
	}

	private static boolean allCovered(List<int[]> spans, List<int[]> covered)
	{
		for (int[] span : spans)
		{
			boolean hit = false;
			for (int[] c : covered)
			{
				if (span[0] >= c[0] && span[1] <= c[1])
				{
					hit = true;
					break;
				}
			}
			if (!hit)
			{
				return false;
			}
		}
		return true;
	}

	/** True when {@code lower} is listed, or is an inflected form of a listed term (or vice versa). */
	private static boolean isIgnored(String lower, Set<String> ignored)
	{
		if (ignored.isEmpty())
		{
			return false;
		}
		if (ignored.contains(lower))
		{
			return true;
		}
		for (String variant : variants(lower))
		{
			if (ignored.contains(variant))
			{
				return true;
			}
		}
		return false;
	}

	/** Regular plural/possessive forms of one lower-case term, plus its irregular plural (if any). */
	private static Set<String> variants(String lower)
	{
		Set<String> out = new HashSet<>();
		String base = lower;
		// Only a trailing possessive is stripped. Cutting at the first apostrophe reduced
		// "King's message" to "king", so the reviewed list silently swallowed every such name.
		if (base.endsWith("'s"))
		{
			base = base.substring(0, base.length() - 2);
			out.add(base);
		}
		else if (base.endsWith("s'"))
		{
			base = base.substring(0, base.length() - 1);
			out.add(base);
		}
		if (base.endsWith("ies") && base.length() > 4)
		{
			out.add(base.substring(0, base.length() - 3) + "y");
		}
		if (base.endsWith("es") && base.length() > 3)
		{
			out.add(base.substring(0, base.length() - 2));
			out.add(base.substring(0, base.length() - 1));
		}
		if (base.endsWith("s") && !base.endsWith("ss") && base.length() > 2)
		{
			out.add(base.substring(0, base.length() - 1));
		}
		if (!base.endsWith("s"))
		{
			out.add(base + "s");
			if (base.endsWith("y") && base.length() > 1)
			{
				out.add(base.substring(0, base.length() - 1) + "ies");
			}
		}
		Set<String> irregular = IRREGULAR.get(base);
		if (irregular != null)
		{
			out.addAll(irregular);
		}
		return out;
	}

	/** Both directions of the irregular plurals worth recognising for the ignore check. */
	private static Map<String, Set<String>> irregular()
	{
		String[][] pairs = {
				{"man", "men"}, {"woman", "women"}, {"child", "children"}, {"foot", "feet"},
				{"tooth", "teeth"}, {"goose", "geese"}, {"mouse", "mice"}, {"ox", "oxen"},
				{"person", "people"}, {"wolf", "wolves"}, {"elf", "elves"}, {"dwarf", "dwarves"},
				{"leaf", "leaves"}, {"knife", "knives"}, {"life", "lives"}, {"wife", "wives"},
				{"shelf", "shelves"}, {"thief", "thieves"}, {"half", "halves"}, {"loaf", "loaves"},
				{"calf", "calves"}, {"self", "selves"}, {"scarf", "scarves"}, {"sheaf", "sheaves"},
		};
		Map<String, Set<String>> map = new HashMap<>();
		for (String[] pair : pairs)
		{
			map.computeIfAbsent(pair[0], k -> new HashSet<>()).add(pair[1]);
			map.computeIfAbsent(pair[1], k -> new HashSet<>()).add(pair[0]);
		}
		return map;
	}

	private static boolean hasCjk(String s)
	{
		for (int i = 0; i < s.length(); i++)
		{
			char c = s.charAt(i);
			if (c >= 0x3400 && c <= 0x9FFF)
			{
				return true;
			}
		}
		return false;
	}
}
