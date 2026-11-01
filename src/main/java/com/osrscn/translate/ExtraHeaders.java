package com.osrscn.translate;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Request;

/**
 * User-supplied extra HTTP headers for AI requests ({@code apiExtraHeaders}).
 *
 * <p>Spec format is the single-line reference syntax {@code Name: Value; Name2: Value2} (one header
 * per segment, split on the first colon, so values may contain colons such as {@code https://x/y}).
 * Parsing is total: a bad entry is skipped with a warning and never fails the request. Values are
 * fixed strings - there is no run id expansion - and are never written to the log.
 */
@Slf4j
final class ExtraHeaders
{
	/** RFC 7230 token: header names allow only these characters (no space, colon or non-ASCII). */
	private static final Pattern NAME = Pattern.compile("^[!#$%&'*+\\-.^_`|~0-9A-Za-z]+$");

	/** Headers that would break authentication or HTTP framing, or must not come from the client. */
	private static final Set<String> BLOCKED = Set.of(
			"authorization", "host", "connection", "content-length", "content-type",
			"transfer-encoding", "content-encoding", "accept-encoding", "proxy-authorization",
			"x-forwarded-for", "forwarded", "range", "cookie");

	private static final int MAX_HEADERS = 32;
	private static final int MAX_SPEC_LENGTH = 4096;

	private ExtraHeaders()
	{
	}

	/**
	 * Parse the configured spec into applied headers, in insertion order.
	 *
	 * @param spec {@code Name: Value; Name2: Value2}; null or blank means no extra headers
	 * @return an unmodifiable map; empty when nothing valid was configured. Never throws.
	 */
	static Map<String, String> parse(String spec)
	{
		String text = spec == null ? "" : spec;
		if (text.length() > MAX_SPEC_LENGTH)
		{
			text = text.substring(0, MAX_SPEC_LENGTH);
			// Cut at the last complete segment: a hard cut could split a value in half (a shortened
			// session id silently fails at the server), so the partial tail is dropped instead.
			int cut = Math.max(text.lastIndexOf(';'),
					Math.max(text.lastIndexOf('\r'), text.lastIndexOf('\n')));
			log.warn("OSRSCN AI: 附加请求头设定超过 {} 个字符，只保留完整条目（末尾不完整的段已丢弃）", MAX_SPEC_LENGTH);
			if (cut < 0)
			{
				// Not a single complete segment before the cut: send nothing rather than a partial header.
				return Collections.emptyMap();
			}
			text = text.substring(0, cut);
		}
		if (text.trim().isEmpty())
		{
			return Collections.emptyMap();
		}
		Map<String, String> headers = new LinkedHashMap<>();
		Map<String, String> applied = new LinkedHashMap<>(); // lower-case name -> name actually used
		boolean capped = false;
		String[] segments = text.split("[;\\r\\n]");
		for (int i = 0; i < segments.length; i++)
		{
			int number = i + 1;
			String segment = segments[i].trim();
			if (segment.isEmpty())
			{
				continue;
			}
			int colon = segment.indexOf(':');
			if (colon < 0)
			{
				log.warn("OSRSCN AI: 附加请求头第 {} 段已忽略（缺少冒号）", number);
				continue;
			}
			String name = segment.substring(0, colon).trim();
			String value = segment.substring(colon + 1).trim();
			if (name.isEmpty())
			{
				log.warn("OSRSCN AI: 附加请求头第 {} 段已忽略（名称为空）", number);
				continue;
			}
			if (!NAME.matcher(name).matches())
			{
				log.warn("OSRSCN AI: 附加请求头第 {} 段已忽略（名称不合法）", number);
				continue;
			}
			String lowered = name.toLowerCase(Locale.ROOT);
			if (BLOCKED.contains(lowered))
			{
				log.warn("OSRSCN AI: 附加请求头 {} 属于保留头，已忽略", name);
				continue;
			}
			if (value.isEmpty())
			{
				log.warn("OSRSCN AI: 附加请求头第 {} 段 {} 的值为空，已忽略", number, name);
				continue;
			}
			if (!isValidValue(value))
			{
				// The value is never logged: it is a credential-like user setting.
				log.warn("OSRSCN AI: 附加请求头第 {} 段 {} 的值非法（只能是 ASCII 可打印字符），已忽略", number, name);
				continue;
			}
			if (headers.size() >= MAX_HEADERS && !applied.containsKey(lowered))
			{
				if (!capped)
				{
					capped = true;
					log.warn("OSRSCN AI: 附加请求头最多 {} 项，多出的已忽略", MAX_HEADERS);
				}
				continue;
			}
			String previous = applied.remove(lowered);
			if (previous != null)
			{
				headers.remove(previous); // duplicate (case-insensitive): the last entry wins
			}
			headers.put(name, value);
			applied.put(lowered, name);
		}
		return Collections.unmodifiableMap(headers);
	}

	/** Only printable ASCII (0x20-0x7E) and tab are allowed in a header value. */
	private static boolean isValidValue(String value)
	{
		for (int i = 0; i < value.length(); i++)
		{
			char c = value.charAt(i);
			if (c == '\t')
			{
				continue;
			}
			if (c < 0x20 || c > 0x7e)
			{
				return false;
			}
		}
		return true;
	}

	/**
	 * Apply the parsed headers to a request builder. {@code header} (set semantics) is used so a custom
	 * entry replaces an existing header rather than adding a duplicate. A single entry that OkHttp
	 * rejects is skipped: one bad value must not fail the translation request.
	 */
	static void apply(Map<String, String> headers, Request.Builder builder)
	{
		if (headers == null || headers.isEmpty() || builder == null)
		{
			return;
		}
		for (Map.Entry<String, String> entry : headers.entrySet())
		{
			try
			{
				builder.header(entry.getKey(), entry.getValue());
			}
			catch (RuntimeException e)
			{
				// Type only: the exception message may quote the value.
				log.warn("OSRSCN AI: 附加请求头 {} 无法应用（{}）", entry.getKey(), e.getClass().getSimpleName());
			}
		}
	}
}
