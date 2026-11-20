package com.osrscn.translate;

/**
 * One NAME-table term found in a raw (unmodified) line, with offsets into that raw string.
 *
 * <p>Free MT cannot send a terminology prompt, so it substitutes the established Chinese name
 * directly into the text it sends. {@link TermGlossary#matches} produces these matches on the raw
 * string (tags and {@code [...]} placeholders masked out), and {@link FreeMtText#prepare} consumes
 * them in one scan so tag tokens and term substitutions never shift each other's offsets.
 *
 * <p>Package-private value type: {@code start}/{@code end} are half-open indexes into the string
 * passed to {@link TermGlossary#matches}, and {@code en} is the spelling as it appears there.
 */
final class TermMatch
{
	/** English spelling as it appears in the raw line (original case). */
	final String en;
	/** Established Chinese name from the NAME table. */
	final String zh;
	/** Inclusive raw-string start offset. */
	final int start;
	/** Exclusive raw-string end offset. */
	final int end;

	TermMatch(String en, String zh, int start, int end)
	{
		this.en = en;
		this.zh = zh;
		this.start = start;
		this.end = end;
	}
}
