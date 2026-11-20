package com.osrscn.translate;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;

/**
 * Anonymous Bing (Edge) translation endpoint: request building and response parsing only.
 *
 * <p>Wire contract (measured 2026-11-19): {@code POST .../translatetext?from=en&to=zh-Hans} with a
 * single-element JSON array of strings, and a JSON answer whose {@code [0].translations[0].text}
 * carries the translation. Only one line is sent per request, so a JSON {@code null} member (which
 * makes the endpoint answer HTTP 500) can never be sent. The answer is served as
 * {@code text/plain; charset=utf-8}, so parsing must look at the body only and never at the header.
 */
final class FreeMtClient
{
	static final String ENDPOINT = "https://edge.microsoft.com/translate/translatetext?from=en&to=zh-Hans";
	static final String PROBE_TEXT = "Hello world";
	private static final MediaType JSON = MediaType.parse("application/json");

	private FreeMtClient()
	{
	}

	/** Text used by the "test connection" probe. */
	static String probeText()
	{
		return PROBE_TEXT;
	}

	/**
	 * Builds the POST request for one line. A {@code null} text becomes an empty member instead of a
	 * JSON {@code null} member, because the endpoint rejects an array containing null with HTTP 500.
	 */
	static Request build(Gson gson, String text)
	{
		JsonArray body = new JsonArray();
		body.add(text == null ? "" : text);
		return new Request.Builder()
				.url(ENDPOINT)
				.post(RequestBody.create(JSON, gson.toJson(body)))
				.build();
	}

	/**
	 * Extracts {@code [0].translations[0].text} from a Bing answer. The root may be the usual array or
	 * a single result object; the text must be a JSON primitive string, and {@code ""} is valid.
	 *
	 * <p>Every {@link RuntimeException} (Gson {@code JsonSyntaxException}, {@code IllegalStateException}
	 * from wrong element types, {@code IndexOutOfBoundsException}, {@code ClassCastException}, ...) is
	 * converted to {@link FreeMtParseException}. The caller catches only that type and treats the line
	 * as a P3 content failure; any other exception type would escape the per-line handling.
	 *
	 * @throws FreeMtParseException when the body is null/blank, malformed, or has an unusable shape.
	 */
	static String parse(Gson gson, String body) throws FreeMtParseException
	{
		if (body == null || body.trim().isEmpty())
		{
			throw new FreeMtParseException("empty response body");
		}
		try
		{
			JsonObject result = firstResult(gson.fromJson(body, JsonElement.class));
			JsonElement translations = result.get("translations");
			if (translations == null || !translations.isJsonArray()
					|| translations.getAsJsonArray().size() == 0)
			{
				throw new FreeMtParseException("response has no translations");
			}
			JsonElement translation = translations.getAsJsonArray().get(0);
			if (!translation.isJsonObject())
			{
				throw new FreeMtParseException("translation is not an object");
			}
			JsonElement text = translation.getAsJsonObject().get("text");
			if (text == null || !text.isJsonPrimitive() || !text.getAsJsonPrimitive().isString())
			{
				throw new FreeMtParseException("translation text is not a string");
			}
			return text.getAsString();
		}
		catch (RuntimeException e)
		{
			throw new FreeMtParseException("malformed translation response", e);
		}
	}

	/** Accepts either the documented array root or a single result object; other roots are unusable. */
	private static JsonObject firstResult(JsonElement root) throws FreeMtParseException
	{
		if (root == null || root.isJsonNull())
		{
			throw new FreeMtParseException("empty translation response");
		}
		if (root.isJsonObject())
		{
			return root.getAsJsonObject();
		}
		if (root.isJsonArray())
		{
			JsonArray results = root.getAsJsonArray();
			if (results.size() == 0)
			{
				throw new FreeMtParseException("no translation result");
			}
			JsonElement first = results.get(0);
			if (!first.isJsonObject())
			{
				throw new FreeMtParseException("translation result is not an object");
			}
			return first.getAsJsonObject();
		}
		throw new FreeMtParseException("unexpected response root");
	}
}
