package com.osrscn.hooks;

import java.util.ArrayList;
import java.util.List;

/**
 * Reconstructs full logical "tasks/paragraphs" from the per-line widget fragments of the
 * achievement diary (Journalscroll, group 741) and quest journal (Questjournal, group 119).
 *
 * <p>The OSRS client wraps prose to fixed-width line slots, so one logical task is split across
 * several consecutive widgets at hard wrap points computed from the <em>English</em> text width.
 * Translating each fragment on its own gives meaningless half-sentences (and the Chinese would wrap
 * at the wrong place). This class joins the fragments back into one string per task so the caller can
 * translate the whole task, then re-flow the translation into the same slots.
 *
 * <p>Pure string logic, no RuneLite dependency, so it is unit-testable against captured widget dumps
 * (see {@code JournalReconstructorTest}). The caller supplies the raw widget texts in render order
 * (top to bottom); this returns {@link Unit}s that cover every input line exactly once.
 */
public final class JournalReconstructor
{
	/**
	 * One span of consecutive line slots. A <em>content</em> unit ({@link #content} == true) is a real
	 * task/sentence whose {@link #english} is the reconstructed plain text to translate. A structural
	 * unit (blank line, section header, title) has {@code content == false} and {@code english == null};
	 * the caller leaves those slots untouched.
	 */
	public static final class Unit
	{
		public final int start;      // index of the first source line this unit covers
		public final int count;      // number of consecutive lines covered (>= 1)
		public final String english; // reconstructed plain text (content units), else null
		public final boolean content;

		Unit(int start, int count, String english, boolean content)
		{
			this.start = start;
			this.count = count;
			this.english = english;
			this.content = content;
		}
	}

	private JournalReconstructor()
	{
	}

	/**
	 * Group {@code lines} (raw widget texts, top-to-bottom) into units. Every input index is covered by
	 * exactly one returned unit, in order, so the caller can map units back to widgets 1:1.
	 */
	public static List<Unit> reconstruct(List<String> lines)
	{
		List<Unit> units = new ArrayList<>();
		int i = 0;
		int n = lines.size();
		while (i < n)
		{
			if (!isContent(lines.get(i)))
			{
				units.add(new Unit(i, 1, null, false)); // blank / header / title: pass through
				i++;
				continue;
			}
			// Accumulate fragments until the task is complete: keep going while the running text is not
			// yet a finished sentence/requirement AND the next line is itself content (not a header/blank).
			int j = i;
			StringBuilder sb = new StringBuilder(strip(lines.get(i)));
			while (j + 1 < n && isContent(lines.get(j + 1)))
			{
				String next = strip(lines.get(j + 1));
				if (isComplete(sb.toString(), next))
				{
					break;
				}
				j++;
				if (sb.length() > 0 && !next.isEmpty())
				{
					sb.append(' ');
				}
				sb.append(next);
			}
			units.add(new Unit(i, j - i + 1, collapse(sb.toString()), true));
			i = j + 1;
		}
		return units;
	}

	/**
	 * Is the accumulated task text finished, given the text that would come next?
	 *
	 * <p>Not finished while a {@code (requirement)} is still open (unbalanced parens), or when the next
	 * line begins a {@code (requirement)} that belongs to this task (wrapped onto its own line). Finished
	 * once parens are balanced and the text ends in a sentence terminator or a closing paren.
	 */
	static boolean isComplete(String accumulated, String next)
	{
		String text = accumulated.trim();
		if (text.isEmpty())
		{
			return false;
		}
		if (parenDepth(text) > 0)
		{
			return false; // a requirement list is still open and wraps onto the next line(s)
		}
		if (next != null && next.trim().startsWith("("))
		{
			return false; // the requirement is wrapped onto the next line; pull it in
		}
		char last = text.charAt(text.length() - 1);
		return last == '.' || last == '!' || last == '?' || last == ')';
	}

	/** A line carries real task text if, after stripping tags, it has any letter or digit. */
	static boolean isContent(String rawLine)
	{
		if (rawLine == null)
		{
			return false;
		}
		// Already our translation: rendered Chinese always carries <img=N> glyph tags, while raw English
		// diary text never does. Treat translated lines as structural so a finished task is not re-grouped
		// and re-translated (its rendered text keeps literal ASCII like "70"/"(" which would otherwise look
		// like fresh content and feed an amplifying loop -> a widget with 100+ <br> lines -> client crash).
		if (rawLine.contains("<img="))
		{
			return false;
		}
		// Yellow lines are difficulty section headers ("Easy"/"Medium"/"Hard"/"Elite") and the area
		// subtitle - never task text. Treat them as structural so they delimit tasks instead of merging.
		if (rawLine.contains("<col=ffff00"))
		{
			return false;
		}
		String s = strip(rawLine);
		for (int k = 0; k < s.length(); k++)
		{
			if (Character.isLetterOrDigit(s.charAt(k)))
			{
				return true;
			}
		}
		return false;
	}

	/** Net unclosed '(' count (never negative), ignoring any inside angle-bracket tags. */
	private static int parenDepth(String s)
	{
		int depth = 0;
		for (int k = 0; k < s.length(); k++)
		{
			char c = s.charAt(k);
			if (c == '(')
			{
				depth++;
			}
			else if (c == ')' && depth > 0)
			{
				depth--;
			}
		}
		return depth;
	}

	/** Remove all {@code <...>} tags (col/str/img/br/...) leaving plain text. */
	static String strip(String s)
	{
		if (s == null || s.isEmpty())
		{
			return "";
		}
		StringBuilder out = new StringBuilder(s.length());
		int i = 0;
		int n = s.length();
		while (i < n)
		{
			char c = s.charAt(i);
			if (c == '<')
			{
				int end = s.indexOf('>', i);
				if (end != -1)
				{
					i = end + 1;
					continue;
				}
			}
			out.append(c);
			i++;
		}
		return out.toString();
	}

	/** Collapse runs of whitespace to single spaces and trim (joined fragments can double-space). */
	static String collapse(String s)
	{
		StringBuilder out = new StringBuilder(s.length());
		boolean prevSpace = false;
		for (int k = 0; k < s.length(); k++)
		{
			char c = s.charAt(k);
			boolean ws = c == ' ' || c == '\t' || c == ' ';
			if (ws)
			{
				if (!prevSpace && out.length() > 0)
				{
					out.append(' ');
				}
				prevSpace = true;
			}
			else
			{
				out.append(c);
				prevSpace = false;
			}
		}
		int len = out.length();
		while (len > 0 && out.charAt(len - 1) == ' ')
		{
			out.setLength(--len);
		}
		return out.toString();
	}
}
