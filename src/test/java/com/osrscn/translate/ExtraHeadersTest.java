package com.osrscn.translate;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import okhttp3.Request;
import org.junit.Test;

/** Parser and applier for the user-supplied {@code apiExtraHeaders} setting. */
public class ExtraHeadersTest
{
	private static Request apply(Map<String, String> headers)
	{
		Request.Builder builder = new Request.Builder().url("http://127.0.0.1:1/fixture");
		ExtraHeaders.apply(headers, builder);
		return builder.get().build();
	}

	@Test public void nullAndBlankYieldEmptyMaps()
	{
		assertTrue(ExtraHeaders.parse(null).isEmpty());
		assertTrue(ExtraHeaders.parse("").isEmpty());
		assertTrue(ExtraHeaders.parse("   \t  ").isEmpty());
		assertTrue(ExtraHeaders.parse("; ;\r\n").isEmpty());
	}

	@Test public void nullAndEmptyHeadersApplyNothing()
	{
		assertTrue(apply(ExtraHeaders.parse(null)).headers().names().isEmpty());
		assertTrue(apply(ExtraHeaders.parse("")).headers().names().isEmpty());
	}

	@Test public void singleHeaderParses()
	{
		Map<String, String> headers = ExtraHeaders.parse("x-opencode-session: OSRSCN");
		assertEquals(1, headers.size());
		assertEquals("OSRSCN", headers.get("x-opencode-session"));
	}

	@Test public void semicolonSeparatesMultipleHeaders()
	{
		Map<String, String> headers = ExtraHeaders.parse("x-opencode-session: OSRSCN; User-Agent: OSRSCN");
		assertEquals(2, headers.size());
		assertEquals(List.of("x-opencode-session", "User-Agent"), new ArrayList<>(headers.keySet()));
		assertEquals("OSRSCN", headers.get("x-opencode-session"));
		assertEquals("OSRSCN", headers.get("User-Agent"));
	}

	@Test public void newlinesSeparateMultipleHeaders()
	{
		Map<String, String> headers = ExtraHeaders.parse("x-opencode-session: OSRSCN\r\nUser-Agent: OSRSCN\nx-extra: 1");
		assertEquals(3, headers.size());
		assertEquals("OSRSCN", headers.get("x-opencode-session"));
		assertEquals("OSRSCN", headers.get("User-Agent"));
		assertEquals("1", headers.get("x-extra"));
	}

	@Test public void nameAndValueAreTrimmed()
	{
		Map<String, String> headers = ExtraHeaders.parse("  x-opencode-session \t: \t OSRSCN  ");
		assertEquals(1, headers.size());
		assertEquals("OSRSCN", headers.get("x-opencode-session"));
	}

	@Test public void valueMayContainColons()
	{
		Map<String, String> headers = ExtraHeaders.parse("Referer: https://x/y; x-ref: a:b:c");
		assertEquals("https://x/y", headers.get("Referer"));
		assertEquals("a:b:c", headers.get("x-ref"));
	}

	@Test public void segmentWithoutColonIsSkipped()
	{
		Map<String, String> headers = ExtraHeaders.parse("no-colon-here; x-opencode-session: OSRSCN");
		assertEquals(1, headers.size());
		assertEquals("OSRSCN", headers.get("x-opencode-session"));
	}

	@Test public void invalidNamesAreSkipped()
	{
		Map<String, String> headers = ExtraHeaders.parse("Bad Header: x; 名称: 值; : empty-name; x-ok: 1");
		assertEquals(1, headers.size());
		assertEquals("1", headers.get("x-ok"));
	}

	@Test public void blockedNamesAreSkipped()
	{
		Map<String, String> headers = ExtraHeaders.parse(
				"Authorization: Bearer x; Host: evil.example; Accept-Encoding: gzip; Cookie: a=b; x-ok: 1");
		assertEquals(1, headers.size());
		assertEquals("1", headers.get("x-ok"));
	}

	@Test public void nonAsciiValuesAreSkipped()
	{
		Map<String, String> headers = ExtraHeaders.parse("x-zh: 中文; x-latin: café; x-ok: 1");
		assertEquals(1, headers.size());
		assertEquals("1", headers.get("x-ok"));
	}

	@Test public void emptyValuesAreSkipped()
	{
		Map<String, String> headers = ExtraHeaders.parse("x-a:; x-b:    ; x-ok: 1");
		assertEquals(1, headers.size());
		assertEquals("1", headers.get("x-ok"));
	}

	@Test public void duplicateNamesAreCaseInsensitiveAndLastWins()
	{
		Map<String, String> headers = ExtraHeaders.parse("X-Test: one; x-test: two; X-TEST: three");
		assertEquals(1, headers.size());
		assertEquals("three", headers.get("X-TEST"));
		assertTrue(headers.containsKey("X-TEST"));
		assertFalse(headers.containsKey("X-Test"));
	}

	@Test public void userAgentIsAllowed()
	{
		Map<String, String> headers = ExtraHeaders.parse("User-Agent: OSRSCN");
		assertEquals("OSRSCN", headers.get("User-Agent"));
	}

	@Test public void semicolonInAValueStartsANewSegment()
	{
		// Documented limitation of the single-line format: ";" always separates segments.
		Map<String, String> headers = ExtraHeaders.parse("x-a: one;two");
		assertEquals(1, headers.size());
		assertEquals("one", headers.get("x-a"));
	}

	@Test public void moreThanThirtyTwoHeadersKeepTheFirstThirtyTwo()
	{
		StringBuilder spec = new StringBuilder();
		for (int i = 0; i < 40; i++)
		{
			if (i > 0) spec.append("; ");
			spec.append("x-h").append(i).append(": v").append(i);
		}
		Map<String, String> headers = ExtraHeaders.parse(spec.toString());
		assertEquals(32, headers.size());
		assertTrue(headers.containsKey("x-h0"));
		assertTrue(headers.containsKey("x-h31"));
		assertFalse(headers.containsKey("x-h32"));
		assertFalse(headers.containsKey("x-h39"));
	}

	@Test public void oversizedSpecIsTruncatedInsteadOfRejected()
	{
		StringBuilder spec = new StringBuilder("x-first: 1; ");
		while (spec.length() < 5000)
		{
			spec.append("x-pad").append(spec.length()).append(": value; ");
		}
		Map<String, String> headers = ExtraHeaders.parse(spec.toString());
		assertTrue(headers.size() <= 32);
		assertEquals("1", headers.get("x-first"));
	}

	@Test public void truncatedTailNeverLeaksAPartialValue()
	{
		// The 4096-character cut lands inside x-b's value: the half value must not be sent at all.
		Map<String, String> headers = ExtraHeaders.parse("x-a: 1; x-b: " + "v".repeat(5000));
		assertEquals(1, headers.size());
		assertEquals("1", headers.get("x-a"));
		assertFalse(headers.containsKey("x-b"));
	}

	@Test public void oversizedSpecWithoutAnySeparatorYieldsNothing()
	{
		// One segment longer than the cap and no separator: there is no complete header to keep.
		assertTrue(ExtraHeaders.parse("x-a: " + "v".repeat(5000)).isEmpty());
	}

	@Test public void parseResultIsUnmodifiable()
	{
		Map<String, String> headers = ExtraHeaders.parse("x-ok: 1");
		assertThrows(UnsupportedOperationException.class, () -> headers.put("x-other", "2"));
	}

	@Test public void applyUsesSetSemanticsAndKeepsOtherHeaders()
	{
		Request.Builder builder = new Request.Builder().url("http://127.0.0.1:1/fixture")
				.header("User-Agent", "default-agent").header("X-Keep", "1");
		ExtraHeaders.apply(ExtraHeaders.parse("User-Agent: OSRSCN"), builder);
		Request applied = builder.get().build();
		assertEquals("OSRSCN", applied.header("User-Agent"));
		assertEquals(1, applied.headers("User-Agent").size());
		assertEquals("1", applied.header("X-Keep"));
	}

	@Test public void applySkipsARejectedEntryAndKeepsTheRest()
	{
		// Names come here only via a manually built map: ExtraHeaders.parse would drop the bad name.
		Map<String, String> headers = new LinkedHashMap<>();
		headers.put("Bad Header", "x");
		headers.put("x-opencode-session", "OSRSCN");
		Request request = apply(headers);
		assertNull(request.header("Bad Header"));
		assertEquals("OSRSCN", request.header("x-opencode-session"));
	}
}
