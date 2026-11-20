package com.osrscn.translate;

import static org.junit.Assert.*;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import java.io.IOException;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.junit.Test;

/**
 * Bing (Edge) free machine-translation request building and response parsing.
 *
 * <p>No network: every case inspects the {@link Request} object or a plain response string, which is
 * exactly what the production callback hands to {@link FreeMtClient#parse(Gson, String)}.
 */
public class FreeMtClientTest
{
	private static final Gson GSON = new Gson();
	private static final String ENDPOINT =
			"https://edge.microsoft.com/translate/translatetext?from=en&to=zh-Hans";

	private static String body(Request request) throws IOException
	{
		RequestBody post = request.body();
		assertNotNull(post);
		Buffer buffer = new Buffer();
		post.writeTo(buffer);
		return buffer.readUtf8();
	}

	private static JsonArray bodyArray(Request request) throws IOException
	{
		return GSON.fromJson(body(request), JsonArray.class);
	}

	/** Well-formed Bing success body carrying one translated array member. */
	private static String response(String text)
	{
		return "[{\"translations\":[{\"text\":" + GSON.toJson(text) + ",\"to\":\"zh-Hans\"}]}]";
	}

	/**
	 * Every malformed body must surface as {@link FreeMtParseException}. If a RuntimeException escaped
	 * instead, {@code assertThrows} fails the test with that type, so the assertion also proves the
	 * exception funnel.
	 */
	private static void assertParseFailure(String body)
	{
		FreeMtParseException failure =
				assertThrows(FreeMtParseException.class, () -> FreeMtClient.parse(GSON, body));
		assertNotNull(failure);
	}

	// --- Request -------------------------------------------------------------------------------

	@Test
	public void probeTextIsHelloWorld()
	{
		assertEquals("Hello world", FreeMtClient.PROBE_TEXT);
		assertEquals(FreeMtClient.PROBE_TEXT, FreeMtClient.probeText());
	}

	@Test
	public void buildUsesPost() throws Exception
	{
		Request request = FreeMtClient.build(GSON, "Hello world");
		assertEquals("POST", request.method());
	}

	@Test
	public void buildUsesExactBingEndpoint()
	{
		Request request = FreeMtClient.build(GSON, "Hello world");
		assertEquals(ENDPOINT, request.url().toString());
		assertEquals("https", request.url().scheme());
		assertEquals("edge.microsoft.com", request.url().host());
		assertEquals("/translate/translatetext", request.url().encodedPath());
		assertEquals("from=en&to=zh-Hans", request.url().encodedQuery());
	}

	@Test
	public void buildSendsJsonContentType() throws Exception
	{
		Request request = FreeMtClient.build(GSON, "Hello world");
		assertNotNull(request.body());
		// RequestBody.create(MediaType, String) appends "; charset=utf-8"; the media type must be JSON.
		// OkHttp's BridgeInterceptor turns body.contentType() into the Content-Type header on the wire.
		assertTrue(request.body().contentType().toString().startsWith("application/json"));
	}

	@Test
	public void buildBodyIsSingleElementArray() throws Exception
	{
		JsonArray body = bodyArray(FreeMtClient.build(GSON, "Hello world"));
		assertEquals(1, body.size());
		assertTrue(body.get(0).isJsonPrimitive());
		assertTrue(body.get(0).getAsJsonPrimitive().isString());
		assertEquals("Hello world", body.get(0).getAsString());
	}

	@Test
	public void buildEscapesTextAsJson() throws Exception
	{
		String text = "Say \"hi\" \\ now\n{{0}}占卜术";
		JsonArray body = bodyArray(FreeMtClient.build(GSON, text));
		assertEquals(1, body.size());
		assertEquals(text, body.get(0).getAsString());
	}

	@Test
	public void buildNeverEmitsNullMember() throws Exception
	{
		Request nullText = FreeMtClient.build(GSON, null);
		JsonArray nullBody = bodyArray(nullText);
		assertEquals(1, nullBody.size());
		assertTrue(nullBody.get(0).isJsonPrimitive());
		assertFalse(body(nullText).contains("null"));

		JsonArray emptyBody = bodyArray(FreeMtClient.build(GSON, ""));
		assertEquals(1, emptyBody.size());
		assertEquals("", emptyBody.get(0).getAsString());
	}

	// --- Parse ---------------------------------------------------------------------------------

	@Test
	public void parseReturnsTextFromArrayRoot() throws Exception
	{
		assertEquals("你好", FreeMtClient.parse(GSON, response("你好")));
	}

	@Test
	public void parseReturnsTextFromSingleObjectRoot() throws Exception
	{
		String body = "{\"translations\":[{\"text\":\"你好\",\"to\":\"zh-Hans\"}]}";
		assertEquals("你好", FreeMtClient.parse(GSON, body));
	}

	@Test
	public void parseUsesFirstResultOfMany() throws Exception
	{
		String body = "[{\"translations\":[{\"text\":\"你好\"}]},"
				+ "{\"translations\":[{\"text\":\"再见\"}]}]";
		assertEquals("你好", FreeMtClient.parse(GSON, body));
	}

	@Test
	public void parseUsesFirstTranslation() throws Exception
	{
		String body = "[{\"translations\":[{\"text\":\"第一\"},{\"text\":\"第二\"}]}]";
		assertEquals("第一", FreeMtClient.parse(GSON, body));
	}

	@Test
	public void parseAcceptsEmptyText() throws Exception
	{
		assertEquals("", FreeMtClient.parse(GSON, response("")));
	}

	@Test
	public void parseUnescapesJsonEscapes() throws Exception
	{
		String body = "[{\"translations\":[{\"text\":\"\\u4f60\\u597d \\\"世界\\\" {{0}}\"}]}]";
		assertEquals("你好 \"世界\" {{0}}", FreeMtClient.parse(GSON, body));
	}

	/** The endpoint answers with {@code text/plain}; parse only ever looks at the body string. */
	@Test
	public void parseIgnoresPlainTextContentType() throws Exception
	{
		String json = response("你好");
		Response response = new Response.Builder()
				.request(new Request.Builder().url(ENDPOINT).build())
				.protocol(Protocol.HTTP_1_1)
				.code(200)
				.message("OK")
				.body(ResponseBody.create(MediaType.parse("text/plain; charset=utf-8"), json))
				.build();
		try (Response closed = response)
		{
			assertTrue(closed.body().contentType().toString().startsWith("text/plain"));
			assertEquals("你好", FreeMtClient.parse(GSON, closed.body().string()));
		}
	}

	// --- Parse failures: all of these must be FreeMtParseException, never a RuntimeException ----

	@Test
	public void parseRejectsMissingTranslations() throws Exception
	{
		assertParseFailure("[{\"foo\":1}]");
		assertParseFailure("{\"foo\":1}");
		assertParseFailure("{}");
	}

	@Test
	public void parseRejectsEmptyTranslations() throws Exception
	{
		assertParseFailure("[{\"translations\":[]}]");
		assertParseFailure("{\"translations\":[]}");
	}

	@Test
	public void parseRejectsMissingText() throws Exception
	{
		assertParseFailure("[{\"translations\":[{\"to\":\"zh-Hans\"}]}]");
	}

	@Test
	public void parseRejectsNullText() throws Exception
	{
		assertParseFailure("[{\"translations\":[{\"text\":null}]}]");
	}

	@Test
	public void parseRejectsNonStringText() throws Exception
	{
		assertParseFailure("[{\"translations\":[{\"text\":123}]}]");
		assertParseFailure("[{\"translations\":[{\"text\":true}]}]");
		assertParseFailure("[{\"translations\":[{\"text\":[\"你好\"]}]}]");
		assertParseFailure("[{\"translations\":[{\"text\":{\"v\":\"你好\"}}]}]");
	}

	@Test
	public void parseRejectsEmptyArray() throws Exception
	{
		assertParseFailure("[]");
	}

	@Test
	public void parseRejectsUnexpectedRootShape() throws Exception
	{
		assertParseFailure("\"just text\"");
		assertParseFailure("123");
		assertParseFailure("null");
	}

	@Test
	public void parseRejectsNonObjectResult() throws Exception
	{
		assertParseFailure("[123]");
		assertParseFailure("[null]");
		assertParseFailure("[{\"translations\":[123]}]");
		assertParseFailure("[{\"translations\":[[\"text\"]]}]");
	}

	@Test
	public void parseRejectsMalformedJson() throws Exception
	{
		assertParseFailure("not json");
		assertParseFailure("[{\"translations\":[{\"text\":\"你好\"}]}");
		assertParseFailure("{\"translations\":");
	}

	@Test
	public void parseRejectsNullAndBlankBody() throws Exception
	{
		assertParseFailure(null);
		assertParseFailure("");
		assertParseFailure("   \n\t");
	}

	@Test
	public void parseNeverLetsRuntimeExceptionEscape() throws Exception
	{
		String[] bodies = {
				"[{\"translations\":\"nope\"}]",
				"{\"translations\":null}",
				"[{\"translations\":[[]]}]",
				"[[\"translations\"]]",
				"[{\"translations\":[{\"text\":null}]}]",
		};
		for (String body : bodies)
		{
			assertParseFailure(body);
		}
	}
}
