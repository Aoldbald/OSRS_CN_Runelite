package com.osrscn.text;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared helpers for the OSRS rich-text tags ({@code <col=RRGGBB>}, {@code </col>}, {@code <br>}, ...).
 * Previously each hook re-implemented these with slightly different regexes.
 */
public final class Tags
{
	// <u=RRGGBB> (coloured underline links, e.g. quest requirements) carry colour just like <col=RRGGBB>.
	private static final Pattern COL = Pattern.compile("<(?:col|u)=([0-9a-fA-F]{6})", Pattern.CASE_INSENSITIVE);
	private static final Pattern COL_TAG = Pattern.compile("</?col[^>]*>", Pattern.CASE_INSENSITIVE);
	private static final Pattern ALL_TAGS = Pattern.compile("<[^>]+>");
	// Opening colour tags only (<col=..>, <colHIGHLIGHT>); </col> has a '/' and is intentionally excluded.
	private static final Pattern OPEN_COL = Pattern.compile("<col[=a-zA-Z0-9]*?>");
	private static final Pattern COL_PLACEHOLDER = Pattern.compile("<colNum\\d+>");
	// Every colour/underline/strikethrough tag + colour placeholder, for stripping a line to plain text.
	private static final Pattern STYLE = Pattern.compile("(?i)</?(?:col|u|str)[^>]*>|<colNum\\d+>");

	private Tags()
	{
	}

	/** First {@code <col=RRGGBB>} colour in the string, or {@code fallback} if there is none. */
	public static int firstColor(String s, int fallback)
	{
		if (s == null)
		{
			return fallback;
		}
		Matcher m = COL.matcher(s);
		return m.find() ? Integer.parseInt(m.group(1), 16) : fallback;
	}

	/** Remove every {@code <col>}/{@code </col>} tag, leaving other tags and the text intact. */
	public static String stripCol(String s)
	{
		return s == null ? "" : COL_TAG.matcher(s).replaceAll("");
	}

	/** Remove every tag and collapse whitespace; {@code <br>} becomes a space so words stay separated. */
	public static String stripTags(String s)
	{
		if (s == null)
		{
			return "";
		}
		return ALL_TAGS.matcher(s.replace("<br>", " ").replace('\u00A0', ' '))
				.replaceAll("").replaceAll("\\s+", " ").trim();
	}

	/** Lower-case 6-digit hex of an RGB value, for building {@code <col=RRGGBB>} tags. */
	public static String hex(int rgb)
	{
		return String.format("%06x", rgb & 0xFFFFFF);
	}

	// The <colNumN> colour-placeholder scheme below mirrors RuneLingual (BSD 2-Clause, see NOTICE) so we
	// can reuse its transcript data, whose English keys store colours as <colNum0>, <colNum1>, ... with
	// </col> left literal. We must produce identical keys at lookup time or coloured text never matches.

	/** Opening colour tags ({@code <col=..>}, {@code <colHIGHLIGHT>}) in order; {@code </col>} is excluded. */
	public static List<String> colorTags(String s)
	{
		List<String> out = new ArrayList<>();
		if (s != null)
		{
			Matcher m = OPEN_COL.matcher(s);
			while (m.find())
			{
				out.add(m.group());
			}
		}
		return out;
	}

	/** Replace opening colour tags with {@code <colNum0>}, {@code <colNum1>}, ... (transcript data format). */
	public static String placeholdColors(String s)
	{
		if (s == null)
		{
			return "";
		}
		String[] parts = OPEN_COL.split(s);
		StringBuilder sb = new StringBuilder(s.length());
		for (int i = 0; i < parts.length; i++)
		{
			sb.append(parts[i]);
			if (i < parts.length - 1)
			{
				sb.append("<colNum").append(i).append('>');
			}
		}
		return sb.toString();
	}

	/** Inverse of {@link #placeholdColors}: substitute the captured tags back in by index. */
	public static String restoreColors(String s, List<String> tags)
	{
		if (s == null)
		{
			return "";
		}
		for (int i = 0; i < tags.size(); i++)
		{
			s = s.replace("<colNum" + i + ">", tags.get(i));
		}
		return s;
	}

	/** Drop colour placeholders and any literal {@code </col>}, for single-colour glyph rendering. */
	public static String stripColorPlaceholders(String s)
	{
		if (s == null)
		{
			return "";
		}
		return COL_TAG.matcher(COL_PLACEHOLDER.matcher(s).replaceAll("")).replaceAll("");
	}

	/** Drop every colour/underline/strikethrough tag and colour placeholder, leaving plain text, so a
	 *  styled line ({@code <u=ff0000>Temple of Ikov</u>}, struck {@code <str>...}) can match a plain
	 *  transcript key (quest/diary names are stored without styling). */
	public static String stripStyle(String s)
	{
		return s == null ? "" : STYLE.matcher(s).replaceAll("");
	}
}
