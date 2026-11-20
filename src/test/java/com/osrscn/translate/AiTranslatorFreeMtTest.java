package com.osrscn.translate;

import static org.junit.Assert.*;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.osrscn.AiBackend;
import com.osrscn.OsrscnConfig;
import com.osrscn.text.Tags;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * FREE_MT (Bing) integration of {@link AiTranslator}: request routing, the failure taxonomy
 * (P1/P2 endpoint failures vs P2'/P3/P4/C content failures), the 429 backoff floor and its ordering,
 * the one-shot unavailable notice, the debug line, and the test-connection probe.
 *
 * <p>No network and no Swing: every request goes to a recording fake transport, and the unavailable
 * notifier is replaced by a counter, so a dialog can never block a test.
 */
public class AiTranslatorFreeMtTest
{
	private static final String TEXT = "A fictional crystal gate awaits.";
	private static final String ZH = "一座虚构的水晶门在等待。";
	private static final Gson GSON = new Gson();

	@Rule public TemporaryFolder temporary = new TemporaryFolder();

	// ---- reflection and fixture helpers -------------------------------------------------------

	private static Object field(Object object, String name) throws Exception
	{
		Field field = object.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.get(object);
	}

	private static void inject(Object object, String name, Object value) throws Exception
	{
		Field field = object.getClass().getDeclaredField(name);
		field.setAccessible(true);
		field.set(object, value);
	}

	private static Object invoke(Object object, String name) throws Exception
	{
		Method method = object.getClass().getDeclaredMethod(name);
		method.setAccessible(true);
		return method.invoke(object);
	}

	private static String requestBody(Pending pending) throws IOException
	{
		Buffer buffer = new Buffer();
		pending.request.body().writeTo(buffer);
		return buffer.readUtf8();
	}

	private static JsonArray requestArray(Pending pending) throws Exception
	{
		return GSON.fromJson(requestBody(pending), JsonArray.class);
	}

	/** Serialized form of a well-formed Bing success body. */
	private static String bingJson(String text)
	{
		JsonObject translation = new JsonObject();
		translation.addProperty("text", text);
		JsonArray translations = new JsonArray();
		translations.add(translation);
		JsonObject result = new JsonObject();
		result.add("translations", translations);
		JsonArray root = new JsonArray();
		root.add(result);
		return root.toString();
	}

	/** Well-formed Bing success body: {@code [{"translations":[{"text":...}]}]}. */
	private static ResponseBody bing(String text)
	{
		return ResponseBody.create(MediaType.parse("text/plain"), bingJson(text));
	}

	/**
	 * A body that DECLARES the given Content-Length but only carries {@code actual}: a caller that
	 * reads first and length-checks afterwards would use it normally, so only a guard that looks at
	 * {@code contentLength()} before {@link ResponseBody#string()} can reject it.
	 */
	private static ResponseBody declaredLength(long declared, String actual)
	{
		Buffer buffer = new Buffer().writeUtf8(actual);
		return new ResponseBody()
		{
			@Override public MediaType contentType() { return MediaType.parse("text/plain"); }
			@Override public long contentLength() { return declared; }
			@Override public BufferedSource source() { return buffer; }
		};
	}

	/** A body whose Content-Length is unknown ({@code -1}), so only the read length can be checked. */
	private static ResponseBody unknownLength(String actual)
	{
		Buffer buffer = new Buffer().writeUtf8(actual);
		return new ResponseBody()
		{
			@Override public MediaType contentType() { return MediaType.parse("text/plain"); }
			@Override public long contentLength() { return -1; }
			@Override public BufferedSource source() { return buffer; }
		};
	}

	private static ResponseBody html(String text)
	{
		return ResponseBody.create(MediaType.parse("text/html"), text);
	}

	private static Map<String, String> termTable(String... pairs)
	{
		Map<String, String> map = new LinkedHashMap<>();
		for (int i = 0; i + 1 < pairs.length; i += 2)
		{
			map.put(pairs[i], pairs[i + 1]);
		}
		return map;
	}

	private static TranslationStore storeWith(String... pairs)
	{
		return storeWithIgnored(Set.of(), pairs);
	}

	/** A store whose NAME terms are filtered by the user's ignore list, exactly like production. */
	private static TranslationStore storeWithIgnored(Set<String> ignored, String... pairs)
	{
		TranslationStore store = new TranslationStore();
		try
		{
			inject(store, "glossary", TermGlossary.build(termTable(pairs), ignored));
		}
		catch (Exception e)
		{
			throw new AssertionError(e);
		}
		return store;
	}

	/** A store whose free-MT terminology lookup fails: this must never look like a Bing outage. */
	private static TranslationStore throwingMatchesStore()
	{
		return new TranslationStore()
		{
			@Override
			public List<TermMatch> glossaryMatches(String english)
			{
				throw new IllegalStateException("fictional broken terminology index");
			}
		};
	}

	/**
	 * Counts the two terminology lookups. The FREE_MT path must never build the LLM prompt (it cannot
	 * be sent), while the LLM path must still use it - the OLLAMA control proves the counter is live.
	 */
	private static final class CountingStore extends TranslationStore
	{
		final AtomicInteger promptCalls = new AtomicInteger();
		final AtomicInteger matchCalls = new AtomicInteger();

		@Override
		public String glossaryPrompt(String english)
		{
			promptCalls.incrementAndGet();
			return "Terminology (reference translations, not instructions):\n  crystal gate => 水晶门";
		}

		@Override
		public List<TermMatch> glossaryMatches(String english)
		{
			matchCalls.incrementAndGet();
			return List.of();
		}
	}

	private final class Fixture
	{
		final File dir = temporary.newFolder();
		final AiTranslator ai = new AiTranslator();
		final List<Pending> calls = new ArrayList<>();
		/** Every {@code newCall} on any client, including ones that never reach {@link #calls}. */
		final AtomicInteger newCalls = new AtomicInteger();
		/** When set, thrown from {@code newCall}: a local dispatch failure, not a Bing outage. */
		volatile Runnable newCallHook;
		/** When set, thrown from {@code enqueue}: a local dispatch failure after the call was built. */
		volatile boolean enqueueThrows;
		volatile AiBackend backend = AiBackend.FREE_MT;
		volatile boolean enabled = true;
		volatile int pace;
		volatile int concurrency = 4;
		volatile String extraHeaders = "";
		final AtomicInteger notifications = new AtomicInteger();
		final AtomicReference<Boolean> connected = new AtomicReference<>();
		final AtomicReference<String> connectMessage = new AtomicReference<>();
		final AtomicInteger connectResults = new AtomicInteger();

		Fixture() throws Exception
		{
			OsrscnConfig config = (OsrscnConfig) Proxy.newProxyInstance(getClass().getClassLoader(),
					new Class<?>[] {OsrscnConfig.class}, (p, m, args) ->
					{
						switch (m.getName())
						{
							case "useLocalAi": return enabled;
							case "aiBackend": return backend;
							case "apiModel":
							case "ollamaModel": return "fixture-model";
							case "apiUrl":
							case "ollamaUrl": return "http://127.0.0.1:1/fixture";
							case "apiKey": return "fictional-not-a-key";
							case "aiConcurrency": return concurrency;
							case "aiPaceMs": return pace;
							case "apiExtraHeaders": return extraHeaders;
							case "debugMonitor": return false;
							default: throw new AssertionError("Unexpected config read: " + m.getName());
						}
					});
			inject(ai, "dir", dir); // before any load; never touch RuneLite's real cache directory
			inject(ai, "config", config);
			inject(ai, "gson", new Gson());
			// One transport per client field: which client the translator picks must be observable.
			inject(ai, "httpClient", transport("shared"));
			inject(ai, "ollamaHttpClient", transport("ollama"));
			inject(ai, "apiInferenceClient", transport("api"));
			inject(ai, "testHttpClient", transport("test"));
			inject(ai, "freeMtUnavailableNotifier", (Runnable) notifications::incrementAndGet);
		}

		/**
		 * Socket-free transport; {@code kind} records which client field issued the call, so a test
		 * can tell the 15 s API client from the local/Ollama client and the probe client.
		 */
		private OkHttpClient transport(final String kind)
		{
			return new OkHttpClient()
			{
				@Override public Call newCall(Request request)
				{
					newCalls.incrementAndGet();
					Runnable hook = newCallHook;
					if (hook != null)
					{
						hook.run();
					}
					Pending pending = new Pending(request);
					pending.clientKind = kind;
					pending.viaTestClient = "test".equals(kind);
					pending.call = (Call) Proxy.newProxyInstance(getClass().getClassLoader(),
							new Class<?>[] {Call.class}, (p, m, args) ->
							{
								if (m.getName().equals("request")) return request;
								if (!m.getName().equals("enqueue")) throw new AssertionError(m.getName());
								if (enqueueThrows)
								{
									throw new IllegalStateException("fictional enqueue failure");
								}
								pending.callback = (Callback) args[0];
								calls.add(pending);
								return null;
							});
					return pending.call;
				}
			};
		}

		/** One admitted translation miss: returns the single request the backend produced. */
		Pending request(String english, boolean persist)
		{
			int size = calls.size();
			assertNull(ai.translate(english, persist));
			assertEquals(size + 1, calls.size());
			return calls.get(size);
		}

		/** Asserts the line is negative-cached: no request is produced for it any more. */
		void assertNoRequest(String english)
		{
			int size = calls.size();
			assertNull(ai.translate(english, false));
			assertEquals("negative-cached line must not reach the network", size, calls.size());
		}

		int failStreak() throws Exception { return ((AtomicInteger) field(ai, "failStreak")).get(); }
		long backoffUntil() throws Exception { return (long) field(ai, "backoffUntil"); }
		long rateLimitUntil() throws Exception { return (long) field(ai, "freeMtRateLimitUntil"); }
		int contentFailStreak() throws Exception { return ((AtomicInteger) field(ai, "contentFailStreak")).get(); }
		long contentBackoffUntil() throws Exception { return (long) field(ai, "contentBackoffUntil"); }
		int lineStreak() throws Exception { return ((Integer) field(ai, "freeMtUnusableLineStreak")).intValue(); }
		boolean notified() throws Exception { return (boolean) field(ai, "freeMtUnavailableNotified"); }
		boolean degraded() throws Exception { return (boolean) field(ai, "freeMtDegraded"); }
		File cacheFile() { return new File(dir, "ai_free-mt.tsv"); }

		@SuppressWarnings("unchecked")
		Map<String, Integer> contentFailAttempts() throws Exception
		{
			return (Map<String, Integer>) field(ai, "contentFailAttempts");
		}

		void clearContentBackoff() throws Exception { inject(ai, "contentBackoffUntil", 0L); }
		void clearBackoff() throws Exception { inject(ai, "backoffUntil", 0L); }

		/** Runs one connection probe and counts every completion, so a double report is observable. */
		void connect()
		{
			ai.testConnection((ok, msg) ->
			{
				connectResults.incrementAndGet();
				connected.set(ok);
				connectMessage.set(msg);
			});
		}
	}

	private static final class Pending
	{
		final Request request;
		Call call;
		Callback callback;
		String clientKind; // "shared" / "ollama" / "api" / "test"
		boolean viaTestClient;
		Pending(Request request) { this.request = request; }

		void respond(int status, ResponseBody body) throws Exception
		{
			callback.onResponse(call, new Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
					.code(status).message("fixture").body(body).build());
		}

		void bing(String text) throws Exception { respond(200, AiTranslatorFreeMtTest.bing(text)); }

		void failure() { callback.onFailure(call, new IOException("fictional offline failure")); }
	}

	// ---- request routing ----------------------------------------------------------------------

	@Test public void freeMtRequestIsTheBingTranslationEndpoint() throws Exception
	{
		Fixture f = new Fixture();
		Pending pending = f.request(TEXT, false);
		assertEquals("POST", pending.request.method());
		assertEquals("edge.microsoft.com", pending.request.url().host());
		assertEquals("/translate/translatetext", pending.request.url().encodedPath());
		assertEquals("from=en&to=zh-Hans", pending.request.url().encodedQuery());
		assertTrue(pending.request.body().contentType().toString().startsWith("application/json"));
		JsonArray body = requestArray(pending);
		assertEquals("one line per request: a single-element array", 1, body.size());
		assertEquals(TEXT, body.get(0).getAsString());
		assertFalse("inference client, not the probe client", pending.viaTestClient);
	}

	@Test public void freeMtUsesTheApiInferenceClientNotTheOllamaOne() throws Exception
	{
		Fixture f = new Fixture();
		Pending pending = f.request(TEXT, false);
		// Even though both backends "share" httpClient in production, the free-MT request must be
		// issued through the 15 s API client: the local client carries a shorter call timeout.
		assertEquals("the 15 s API client owns free-MT requests", "api", pending.clientKind);
		assertFalse("never the local inference client", "ollama".equals(pending.clientKind));
		assertFalse("never the bare shared client", "shared".equals(pending.clientKind));
		assertFalse(pending.viaTestClient);
	}

	@Test public void freeMtRequestCarriesPreparedTextAndNoGlossaryPrompt() throws Exception
	{
		Fixture f = new Fixture();
		inject(f.ai, "store", storeWith("crystal gate", "水晶门"));
		Pending pending = f.request(TEXT, false);
		String raw = requestBody(pending);
		assertFalse("free MT has no terminology prompt", raw.contains(TermGlossary.HEADER));
		assertFalse("free MT is not a chat-completions request", raw.contains("messages"));
		assertFalse("free MT has no temperature field", raw.contains("temperature"));
		JsonArray body = requestArray(pending);
		assertEquals(1, body.size());
		assertEquals("the NAME term is substituted before the request",
				"A fictional 水晶门 awaits.", body.get(0).getAsString());
	}

	@Test public void freeMtNeverBuildsTheLlmGlossaryPrompt() throws Exception
	{
		CountingStore freeStore = new CountingStore();
		Fixture free = new Fixture();
		inject(free.ai, "store", freeStore);
		free.request(TEXT, false);
		assertEquals("FREE_MT cannot send a prompt, so it must never build one",
				0, freeStore.promptCalls.get());
		assertEquals("it uses the raw-offset term lookup instead", 1, freeStore.matchCalls.get());

		// Control: the same counting store on the LLM path proves the counter actually fires.
		CountingStore ollamaStore = new CountingStore();
		Fixture ollama = new Fixture();
		ollama.backend = AiBackend.OLLAMA;
		inject(ollama.ai, "store", ollamaStore);
		ollama.request(TEXT, false);
		assertTrue("the LLM path still builds the terminology prompt",
				ollamaStore.promptCalls.get() > 0);
		assertEquals("the LLM path does not use the free-MT term lookup",
				0, ollamaStore.matchCalls.get());
	}

	@Test public void freeMtSuccessRestoresProtectedTagsAndCaches() throws Exception
	{
		Fixture f = new Fixture();
		String line = "Hello <br> wide world";
		Pending pending = f.request(line, false);
		assertEquals("Hello {{0}} wide world", requestArray(pending).get(0).getAsString());
		pending.bing("你好 {{0}} 广阔世界");
		assertEquals("你好 <br> 广阔世界", f.ai.cached(line));
		assertEquals(1, f.ai.sessionCount());
		assertEquals(0, f.ai.inFlightCount());
		assertTrue("the debug list shows the restored text, not the {{n}} tokens",
				f.ai.recent().get(0).contains("你好 <br> 广阔世界"));
		assertFalse(f.ai.recent().get(0).contains("{{0}}"));
		assertEquals("Bing", f.ai.freeMtDebugLine());
		assertFalse(f.degraded());
	}

	@Test public void freeMtRecentShowsRestoredPlaceholdersNotRawTokens() throws Exception
	{
		Fixture f = new Fixture();
		inject(f.ai, "store", storeWith("Betty", "贝蒂"));
		String line = "Hello <colNum0>Betty</col>";
		Pending pending = f.request(line, false);
		// Wire v2: the already-Chinese name run is a locally anchored token pair, so the colour
		// markers travel with the words Bing keeps instead of sitting at fixed positions.
		assertEquals("Hello {{0}}贝蒂{{1}}", requestArray(pending).get(0).getAsString());
		pending.bing("你好 {{0}}贝蒂{{1}}");
		String entry = f.ai.recent().get(0);
		assertTrue("the monitor must show the restored tags",
				entry.contains("你好 <colNum0>贝蒂</col>"));
		assertFalse("the monitor must not show a {{n}} token", entry.contains("{{"));
		assertEquals("the cached value keeps the source colour markers",
				"你好 <colNum0>贝蒂</col>", f.ai.cached(line));
	}

	@Test public void freeMtSuccessPersistsToTheFreeMtCacheFile() throws Exception
	{
		Fixture f = new Fixture();
		f.request(TEXT, true).bing(ZH);
		File file = f.cacheFile();
		assertTrue("the free backend owns ai_free-mt.tsv", file.exists());
		assertEquals(TEXT + "\t" + ZH + "\n",
				Files.readString(file.toPath(), StandardCharsets.UTF_8));
		// Served from memory afterwards: no second request for the same line.
		assertEquals(ZH, f.ai.translate(TEXT, true));
		assertEquals(1, f.calls.size());
	}

	@Test public void freeMtSuccessClearsContentFailureCountersAndDegradedFlag() throws Exception
	{
		Fixture f = new Fixture();
		Pending pending = f.request(TEXT, false);
		f.contentFailAttempts().put(TEXT, 1);
		((AtomicInteger) field(f.ai, "contentFailStreak")).set(4);
		inject(f.ai, "freeMtUnusableLineStreak", 7);
		inject(f.ai, "freeMtDegraded", true);
		// Preset a real endpoint-failure state: a success must clear it, which only an assertion on a
		// non-zero starting value can prove (0 -> 0 would pass even without recordSuccess()).
		((AtomicInteger) field(f.ai, "failStreak")).set(2);
		inject(f.ai, "backoffUntil", Long.MAX_VALUE);
		pending.bing(ZH);
		assertTrue("the accepted line leaves no attempt record", f.contentFailAttempts().isEmpty());
		assertEquals("a success ends the content-failure streak", 0, f.contentFailStreak());
		assertEquals("a success ends the unusable-line streak", 0, f.lineStreak());
		assertFalse("a success restores the debug state", f.degraded());
		assertEquals("a usable reply also ends the endpoint failure streak", 0, f.failStreak());
		assertEquals("a usable reply clears the endpoint backoff too", 0L, f.backoffUntil());
		assertEquals(ZH, f.ai.cached(TEXT));
	}

	@Test public void freeMtSuccessKeepsTheUnavailableNoticeForThisRun() throws Exception
	{
		Fixture f = new Fixture();
		Pending pending = f.request(TEXT, false);
		inject(f.ai, "freeMtUnavailableNotified", true);
		pending.bing(ZH);
		assertTrue("one notice per RuneLite run, even after recovery", f.notified());
		assertFalse(f.degraded());
		assertEquals("Bing", f.ai.freeMtDebugLine());
	}

	@Test public void freeMtReplyCarryingOnlySubstitutedTermsIsRejected() throws Exception
	{
		Fixture f = new Fixture();
		inject(f.ai, "store", storeWith("crystal gate", "水晶门"));
		Pending pending = f.request(TEXT, false);
		assertEquals("A fictional 水晶门 awaits.", requestArray(pending).get(0).getAsString());
		// The only Chinese here is the substituted term: not a translation (C1 excludes it).
		pending.bing("A fictional 水晶门 awaits.");
		assertNull("a reply must not be accepted as a translation on substituted CJK alone",
				f.ai.cached(TEXT));
		assertEquals(Integer.valueOf(1), f.contentFailAttempts().get(TEXT));
		// A real translation of the same line is still accepted.
		f.request(TEXT, false).bing("一座虚构的水晶门在等待。");
		assertEquals("一座虚构的水晶门在等待。", f.ai.cached(TEXT));
	}

	// ---- content failures (line level, never endpoint level) ----------------------------------

	@Test public void freeMtRequestErrorsAreLineLevelNotEndpointFailures() throws Exception
	{
		for (int status : new int[] {400, 413, 414, 422})
		{
			Fixture f = new Fixture();
			f.request(TEXT, false).respond(status, html("request rejected"));
			assertEquals("P2' must never be retried", 1, f.calls.size());
			assertEquals("P2' is not an endpoint failure", 0, f.failStreak());
			assertEquals(Integer.valueOf(1), f.contentFailAttempts().get(TEXT));
			assertEquals(1, f.contentFailStreak());
			assertEquals(0, f.lineStreak());
			assertEquals(0, f.notifications.get());
			assertFalse(f.notified());

			f.request(TEXT, false).respond(status, html("request rejected"));
			assertTrue("after two content failures the attempt record is retired",
					f.contentFailAttempts().isEmpty());
			assertEquals("the line is now unusable for this run", 1, f.lineStreak());
			assertEquals(0, f.failStreak());
			f.assertNoRequest(TEXT);
			f.assertNoRequest(TEXT);
		}
	}

	@Test public void freeMtRequestRejectionIsNotRetriedWithoutTemperatureOrGlossary() throws Exception
	{
		Fixture f = new Fixture();
		f.request(TEXT, false).respond(400, html("request rejected"));
		// FREE_MT sends no temperature and no terminology block, so the LLM path's temperature/echo
		// retries must never fire: one request, one content failure, no endpoint failure.
		assertEquals("FREE_MT does no parameter/echo retry", 1, f.calls.size());
		assertEquals(Integer.valueOf(1), f.contentFailAttempts().get(TEXT));
		assertEquals(1, f.contentFailStreak());
		assertEquals(0, f.failStreak());
		assertEquals(0, f.notifications.get());
		assertEquals(0, f.ai.inFlightCount());
	}

	@Test public void freeMtBadJsonIsLineLevelAndCountsAsEndpointSuccess() throws Exception
	{
		Fixture f = new Fixture();
		Pending pending = f.request(TEXT, false);
		((AtomicInteger) field(f.ai, "failStreak")).set(2);
		pending.respond(200, html("{"));
		assertEquals("a 2xx parse failure still proves the endpoint alive", 0, f.failStreak());
		assertEquals(Integer.valueOf(1), f.contentFailAttempts().get(TEXT));
		assertEquals(0, f.lineStreak());
		assertEquals(0, f.notifications.get());
	}

	@Test public void freeMtEmptyTranslationIsLineLevel() throws Exception
	{
		Fixture f = new Fixture();
		Pending pending = f.request(TEXT, false);
		((AtomicInteger) field(f.ai, "failStreak")).set(2);
		pending.bing("");
		assertEquals("P4 still records an endpoint success", 0, f.failStreak());
		assertEquals(Integer.valueOf(1), f.contentFailAttempts().get(TEXT));
		f.request(TEXT, false).bing("");
		assertEquals("two P4 replies make the line unusable", 1, f.lineStreak());
		assertEquals(0, f.failStreak());
	}

	@Test public void freeMtReplyWithoutChineseIsLineLevel() throws Exception
	{
		Fixture f = new Fixture();
		f.request(TEXT, false).bing("A fictional crystal gate awaits.");
		assertEquals("C1 (no CJK) must not look like a transport failure", 0, f.failStreak());
		assertEquals(Integer.valueOf(1), f.contentFailAttempts().get(TEXT));
		f.request(TEXT, false).bing("A fictional crystal gate awaits.");
		assertEquals(1, f.lineStreak());
		assertEquals("both attempts count in the global content streak", 2, f.contentFailStreak());
	}

	@Test public void freeMtMissingPairTokenIsLineLevel() throws Exception
	{
		Fixture f = new Fixture();
		String line = "Hello <colNum0>100</col> wide";
		Pending pending = f.request(line, false);
		assertEquals("Hello {{0}}100{{1}} wide", requestArray(pending).get(0).getAsString());
		// The reply dropped the pair tokens: C4 is a content failure, never a 2xx echo.
		pending.bing("你好 100 广阔");
		assertEquals("C4 is a content failure", 0, f.failStreak());
		assertEquals(1, f.contentFailStreak());
		assertNull(f.ai.cached(line));
		f.request(line, false).bing("你好 100 广阔");
		assertEquals(1, f.lineStreak());
		assertEquals(0, f.failStreak());
	}

	@Test public void freeMtCloseBeforeOpenPairIsLineLevel() throws Exception
	{
		Fixture f = new Fixture();
		String line = "Hello <colNum0>100</col> wide";
		Pending pending = f.request(line, false);
		pending.bing("{{1}}你好 100{{0}} 广阔");
		assertEquals("a close token before its open token is a content failure", 0, f.failStreak());
		assertEquals(Integer.valueOf(1), f.contentFailAttempts().get(line));
		assertNull(f.ai.cached(line));
	}

	@Test public void freeMtOpaqueTokensMayReorderInAValidReply() throws Exception
	{
		Fixture f = new Fixture();
		String line = "Hello <br> wide <i> world";
		Pending pending = f.request(line, false);
		assertEquals("Hello {{0}} wide {{1}} world", requestArray(pending).get(0).getAsString());
		// Tokens that travel with reordered colour sections may come out of order; C3 only requires
		// each token exactly once. The restore must still put each original span back.
		pending.bing("你好 {{1}} 广阔世界 {{0}}");
		assertEquals("你好 <i> 广阔世界 <br>", f.ai.cached(line));
		assertEquals(1, f.ai.sessionCount());
		assertEquals(0, f.ai.inFlightCount());
	}

	@Test public void freeMtMultiColorSegmentsSurviveBingReorder() throws Exception
	{
		Fixture f = new Fixture();
		inject(f.ai, "store", storeWith("Dragon scimitar", "龙金弯刀"));
		String line = "You have <colNum0>Dragon scimitar</col> and <colNum1>100</col> coins";
		Pending pending = f.request(line, false);
		// Wire v2: each run whose substituted content is already Chinese is locally anchored; the
		// English between the runs is plain text for Bing to translate.
		assertEquals("You have {{0}}龙金弯刀{{1}} and {{2}}100{{3}} coins",
				requestArray(pending).get(0).getAsString());
		// Simulated Bing reply: the token pairs kept, the two anchored spans in Chinese word order.
		pending.bing("你有 {{2}}100{{3}} 枚硬币 {{0}}龙金弯刀{{1}} 还有");
		assertEquals("each pair restores its own colour markers",
				"你有 <colNum1>100</col> 枚硬币 <colNum0>龙金弯刀</col> 还有",
				f.ai.cached(line));
		assertEquals(1, f.ai.sessionCount());
		assertEquals(0, f.ai.inFlightCount());
		assertEquals(0, f.failStreak());
	}

	@Test public void freeMtIgnoreListWordIsProtectedWithItsColour() throws Exception
	{
		Fixture f = new Fixture();
		// "Preserve" has a NAME row but the user ignored it, so the store offers no TermMatch for it.
		// It is still a proper noun: the span is protected with a token pair so its colour survives,
		// and Bing may either keep the English word or translate it inside the pair.
		inject(f.ai, "store", storeWithIgnored(Set.of("preserve"), "Preserve", "保留"));
		String line = "<colNum0>Preserve</col> this item";
		Pending pending = f.request(line, false);
		assertEquals("an ignored term is never substituted; its colour span is still protected",
				"{{0}}Preserve{{1}} this item", requestArray(pending).get(0).getAsString());
		// First outcome: Bing keeps the English word inside the pair - the colour stays on it.
		pending.bing("保留 {{0}}Preserve{{1}} 此物品");
		assertEquals("the marker restores around the kept English word",
				"保留 <colNum0>Preserve</col> 此物品", f.ai.cached(line));
		assertEquals(1, f.ai.sessionCount());
		assertEquals(0, f.ai.inFlightCount());
		assertEquals(0, f.failStreak());

		// Second outcome: Bing translates inside the pair - the colour still wraps the translation.
		Fixture translated = new Fixture();
		inject(translated.ai, "store", storeWithIgnored(Set.of("preserve"), "Preserve", "保留"));
		translated.request(line, false).bing("保留 {{0}}保护{{1}} 此物品");
		assertEquals("the marker restores around the translation Bing produced",
				"保留 <colNum0>保护</col> 此物品", translated.ai.cached(line));
		assertEquals(1, translated.ai.sessionCount());
		assertEquals(0, translated.failStreak());
	}

	@Test public void freeMtCloserStyleSentenceRunKeepsItsColour() throws Exception
	{
		Fixture f = new Fixture();
		// User bug report: <col=7f0000>A Shell of a Friend</col> translated but lost its colour,
		// because the run was not proper-noun shaped. Every closer-style run is protected now.
		String line = "<colNum0>A Shell of a Friend</col>";
		Pending pending = f.request(line, false);
		assertEquals("a closer-style sentence run is protected by a non-strict pair",
				"{{0}}A Shell of a Friend{{1}}", requestArray(pending).get(0).getAsString());
		pending.bing("{{0}}朋友的空壳{{1}}");
		String cached = f.ai.cached(line);
		assertEquals("the translated sentence keeps its colour marker",
				"<colNum0>朋友的空壳</col>", cached);
		assertEquals("exactly one colour placeholder for the downstream colour guard",
				1, Tags.placeholderCount(cached));
		assertEquals(1, f.ai.sessionCount());
		assertEquals(0, f.ai.inFlightCount());
		assertEquals(0, f.failStreak());
	}

	@Test public void freeMtCloserStyleTrailingSpanKeepsItsColour() throws Exception
	{
		Fixture f = new Fixture();
		// User bug report: Description: <col=ffffff>Kill the Shellbane Gryphon 25 times.</col>
		String line = "Description: <colNum0>Kill the Shellbane Gryphon 25 times.</col>";
		Pending pending = f.request(line, false);
		assertEquals("the trailing closer-style span is protected",
				"Description: {{0}}Kill the Shellbane Gryphon 25 times.{{1}}",
				requestArray(pending).get(0).getAsString());
		pending.bing("描述：{{0}}击杀壳毒狮鹫25次。{{1}}");
		String cached = f.ai.cached(line);
		assertEquals("描述：<colNum0>击杀壳毒狮鹫25次。</col>", cached);
		assertEquals("exactly one colour placeholder restores the span colour",
				1, Tags.placeholderCount(cached));
		assertEquals(1, f.ai.sessionCount());
		assertEquals(0, f.failStreak());
	}

	@Test public void freeMtMissingTokenIsLineLevel() throws Exception
	{
		Fixture f = new Fixture();
		String line = "Hello <br> wide world";
		f.request(line, false).bing("你好 广阔世界");
		assertEquals("C3 is a content failure", Integer.valueOf(1), f.contentFailAttempts().get(line));
		f.request(line, false).bing("你好 广阔世界");
		assertEquals(1, f.lineStreak());
		assertEquals(0, f.failStreak());
	}

	@Test public void freeMtOversizedBodyIsLineLevel() throws Exception
	{
		Fixture f = new Fixture();
		Pending pending = f.request(TEXT, false);
		((AtomicInteger) field(f.ai, "failStreak")).set(2);
		// A VALID, parseable Bing answer above 1 MB. With the size guard removed this would be
		// parsed, validated and cached, so (unlike an invalid-JSON body) this really pins the guard.
		String hugeTranslation = "你好" + "。".repeat(1_048_600);
		assertTrue("fixture body must exceed the guard", hugeTranslation.length() > 1_048_576);
		pending.respond(200, bing(hugeTranslation));
		assertNull("the oversized reply must never be cached", f.ai.cached(TEXT));
		assertEquals("an oversized 2xx reply is a content failure, not an outage", 0, f.failStreak());
		assertEquals(Integer.valueOf(1), f.contentFailAttempts().get(TEXT));
		assertEquals(0, f.lineStreak());
		assertEquals(0, f.notifications.get());
	}

	@Test public void freeMtDeclaredOversizedContentLengthIsRejectedBeforeTheBodyIsRead() throws Exception
	{
		Fixture f = new Fixture();
		Pending pending = f.request(TEXT, false);
		((AtomicInteger) field(f.ai, "failStreak")).set(2);
		// Declares 2 MB but carries a SHORT, valid Bing answer. Reading first and checking the read
		// length afterwards would parse and cache it (and would already have buffered a real 2 MB
		// reply), so only a Content-Length check made before the body is consumed can reject it.
		pending.respond(200, declaredLength(2_000_000L, bingJson(ZH)));
		assertNull("a reply declaring over 1 MB must never be parsed or cached", f.ai.cached(TEXT));
		assertEquals("a declared-oversized 2xx is a content failure, not an outage", 0, f.failStreak());
		assertEquals(Integer.valueOf(1), f.contentFailAttempts().get(TEXT));
		assertEquals(0, f.lineStreak());
		assertEquals(0, f.notifications.get());
	}

	@Test public void freeMtOversizedBodyWithUnknownLengthIsLineLevel() throws Exception
	{
		Fixture f = new Fixture();
		Pending pending = f.request(TEXT, false);
		((AtomicInteger) field(f.ai, "failStreak")).set(2);
		String hugeTranslation = "你好" + "。".repeat(1_048_600);
		assertTrue(hugeTranslation.length() > 1_048_576);
		// Content-Length is unknown (-1): the Content-Length guard cannot decide, so the read-length
		// fallback must still reject the body as a P3 line failure.
		pending.respond(200, unknownLength(bingJson(hugeTranslation)));
		assertNull("the oversized reply must never be cached", f.ai.cached(TEXT));
		assertEquals("an oversized 2xx reply is a content failure, not an outage", 0, f.failStreak());
		assertEquals(Integer.valueOf(1), f.contentFailAttempts().get(TEXT));
		assertEquals(0, f.lineStreak());
		assertEquals(0, f.notifications.get());
	}

	@Test public void freeMtContentFailuresAccumulatePerLine() throws Exception
	{
		Fixture f = new Fixture();
		f.request("fictional line one", false).respond(400, html("x"));
		f.request("fictional line two", false).respond(422, html("x"));
		Map<String, Integer> attempts = f.contentFailAttempts();
		assertEquals(2, attempts.size());
		assertEquals(Integer.valueOf(1), attempts.get("fictional line one"));
		assertEquals(Integer.valueOf(1), attempts.get("fictional line two"));
		assertEquals(2, f.contentFailStreak());
		assertEquals(0, f.lineStreak());
	}

	// ---- endpoint failures, backoff and the one-shot notice -----------------------------------

	@Test public void freeMtTwoEndpointFailuresDoNotNotify() throws Exception
	{
		Fixture f = new Fixture();
		f.request("fictional offline line one", false).failure();
		f.request("fictional offline line two", false).failure();
		assertEquals(2, f.failStreak());
		assertEquals("under three endpoint failures there is no dialog", 0, f.notifications.get());
		assertFalse(f.notified());
		assertFalse(f.degraded());
		assertEquals("Bing", f.ai.freeMtDebugLine());
		assertEquals("a failed line stays retryable", 0, f.contentFailAttempts().size());
	}

	@Test public void freeMtThirdEndpointFailureNotifiesOnce() throws Exception
	{
		Fixture f = new Fixture();
		for (int i = 0; i < 3; i++)
		{
			f.request("fictional offline line " + i, false).failure();
		}
		assertEquals(3, f.failStreak());
		assertEquals("exactly one dialog for three consecutive failures", 1, f.notifications.get());
		assertTrue(f.notified());
		assertTrue(f.degraded());
		assertEquals("不可用（已提示）", f.ai.freeMtDebugLine());
		assertEquals(0, f.ai.inFlightCount());
	}

	@Test public void freeMtHttp429AppliesSixtySecondFloorAfterRecordFailure() throws Exception
	{
		Fixture f = new Fixture();
		// The first admission runs the one-time load/reset, so preset the streak afterwards.
		Pending pending = f.request(TEXT, false);
		((AtomicInteger) field(f.ai, "failStreak")).set(2);
		long before = System.currentTimeMillis();
		pending.respond(429, html("rate limited"));
		assertEquals("429 is an endpoint failure", 3, f.failStreak());
		assertTrue("the 60 s floor must not be overwritten by the 5 s step",
				f.backoffUntil() >= before + 60_000L);
		assertEquals(1, f.notifications.get());
	}

	@Test public void freeMtHttp429WithoutPriorFailuresStillAppliesTheFloor() throws Exception
	{
		Fixture f = new Fixture();
		long before = System.currentTimeMillis();
		f.request(TEXT, false).respond(429, html("rate limited"));
		assertEquals(1, f.failStreak());
		assertTrue(f.backoffUntil() >= before + 60_000L);
		assertEquals("one 429 is below the dialog threshold", 0, f.notifications.get());
	}

	@Test public void freeMt429FloorSurvivesSuccessFromAnAlreadyInFlightRequest() throws Exception
	{
		Fixture f = new Fixture();
		Pending limited = f.request(TEXT, false);
		Pending concurrent = f.request("a fictional concurrent line", false);
		long before = System.currentTimeMillis();
		limited.respond(429, html("rate limited"));
		assertTrue("the 429 arms the 60 s floor", f.rateLimitUntil() >= before + 60_000L);

		// The second request was dispatched before the 429 and answers afterwards successfully. With
		// aiConcurrency > 1 that is the normal case, and its success must not reopen the endpoint:
		// recordSuccess() clears failStreak/backoffUntil, which is not the rate-limit window.
		concurrent.bing("并发翻译成功");
		assertEquals("the success still ends the failure streak", 0, f.failStreak());
		assertEquals("and still clears the shared backoff", 0L, f.backoffUntil());
		assertTrue("but not the 429 rate-limit floor", f.rateLimitUntil() >= before + 60_000L);

		int calls = f.calls.size();
		assertNull(f.ai.translate("a fictional line after the 429", false));
		assertEquals("a new line stays undispatched while the floor is active", calls, f.calls.size());
	}

	@Test public void freeMt429FloorSurvivesTheFailureBackoffStep() throws Exception
	{
		Fixture f = new Fixture();
		Pending limited = f.request(TEXT, false);
		Pending second = f.request("a fictional concurrent line one", false);
		Pending third = f.request("a fictional concurrent line two", false);
		long before = System.currentTimeMillis();
		limited.respond(429, html("rate limited"));
		// Two more requests fail after the 429: the third consecutive failure assigns its own 5 s
		// step to backoffUntil, which must not shorten the 60 s rate-limit window either.
		second.respond(500, html("server error"));
		third.respond(500, html("server error"));
		assertEquals(3, f.failStreak());
		assertTrue("the rate-limit floor outlives the 5 s step", f.rateLimitUntil() >= before + 60_000L);

		f.clearBackoff(); // simulate the 5 s step elapsing: only the floor may keep the endpoint closed
		int calls = f.calls.size();
		assertNull(f.ai.translate("a fictional line after the concurrent failures", false));
		assertEquals("the shorter failure step must not reopen a rate-limited endpoint", calls, f.calls.size());
	}

	@Test public void freeMt429FloorExpiresWithTime() throws Exception
	{
		Fixture f = new Fixture();
		f.request(TEXT, false).respond(429, html("rate limited"));
		inject(f.ai, "freeMtRateLimitUntil", System.currentTimeMillis() - 1L);
		f.clearBackoff();
		int calls = f.calls.size();
		assertNull(f.ai.translate("a fictional line after the floor expired", false));
		assertEquals("once both windows have elapsed dispatch resumes", calls + 1, f.calls.size());
	}

	@Test public void freeMt429FloorIsClearedByAConfigurationChange() throws Exception
	{
		Fixture f = new Fixture();
		f.request(TEXT, false).respond(429, html("rate limited"));
		assertTrue("the 429 floor is armed", f.rateLimitUntil() > 0);
		f.ai.configurationChanged();
		assertEquals("a new model/backend identity resets the floor with the other backoff state",
				0L, f.rateLimitUntil());
		f.request("a fictional line after the reconfigure", false);
	}

	@Test public void freeMtServerErrorIsEndpointFailureAndRetryable() throws Exception
	{
		Fixture f = new Fixture();
		f.request(TEXT, false).respond(500, html("server error"));
		assertEquals(1, f.failStreak());
		assertEquals("a 5xx does not negative-cache the line", 0, f.contentFailAttempts().size());
		assertEquals(0, f.lineStreak());
		f.request(TEXT, false); // the next lookup may retry immediately
		assertEquals(2, f.calls.size());
	}

	@Test public void freeMtTenUntranslatableLinesNotifyOnce() throws Exception
	{
		Fixture f = new Fixture();
		long before = System.currentTimeMillis();
		for (int i = 0; i < 10; i++)
		{
			String line = "fictional unusable line " + i;
			// The 30 s content pause (10 attempts) is a different trigger; clear it so this test can
			// exercise ten distinct unusable LINES rather than waiting in real time.
			f.clearContentBackoff();
			f.request(line, false).respond(400, html("rejected"));
			f.clearContentBackoff();
			f.request(line, false).respond(400, html("rejected"));
		}
		assertEquals(10, f.lineStreak());
		assertEquals("ten attempts also arm the existing global content pause",
				true, f.contentBackoffUntil() >= before + 30_000L);
		assertEquals("the attempt streak resets after that pause is armed", 0, f.contentFailStreak());
		assertEquals("ten unusable lines show the dialog exactly once", 1, f.notifications.get());
		assertTrue(f.notified());
		assertTrue(f.degraded());

		String extra = "fictional unusable line extra";
		f.clearContentBackoff();
		f.request(extra, false).respond(413, html("rejected"));
		f.clearContentBackoff();
		f.request(extra, false).respond(413, html("rejected"));
		assertEquals(11, f.lineStreak());
		assertEquals("the notice is one per RuneLite run", 1, f.notifications.get());
	}

	@Test public void freeMtNineUnusableLinesStaySilent() throws Exception
	{
		Fixture f = new Fixture();
		for (int i = 0; i < 9; i++)
		{
			String line = "fictional unusable line " + i;
			// The 30 s content pause is a different trigger; clear it to exercise nine distinct LINES.
			f.clearContentBackoff();
			f.request(line, false).respond(400, html("rejected"));
			f.clearContentBackoff();
			f.request(line, false).respond(400, html("rejected"));
		}
		assertEquals("nine unusable lines are one below the notice threshold", 9, f.lineStreak());
		assertEquals("the dialog must not appear at nine lines", 0, f.notifications.get());
		assertFalse(f.notified());
		assertFalse(f.degraded());
		assertEquals("Bing", f.ai.freeMtDebugLine());
	}

	@Test public void freeMtSuccessResetsTheUnusableLineStreak() throws Exception
	{
		Fixture f = new Fixture();
		for (int i = 0; i < 3; i++)
		{
			String line = "fictional unusable " + i;
			f.request(line, false).respond(400, html("rejected"));
			f.request(line, false).respond(400, html("rejected"));
		}
		assertEquals(3, f.lineStreak());
		f.request(TEXT, false).bing(ZH);
		assertEquals("a success resets the consecutive-line count", 0, f.lineStreak());
		assertEquals(0, f.contentFailStreak());

		f.clearContentBackoff();
		String next = "fictional unusable next";
		f.request(next, false).respond(400, html("rejected"));
		f.request(next, false).respond(400, html("rejected"));
		assertEquals("the streak is consecutive, not cumulative", 1, f.lineStreak());
	}

	@Test public void freeMtGlossaryMatchFailureSkipsTheLineWithoutEndpointFailure() throws Exception
	{
		Fixture f = new Fixture();
		inject(f.ai, "store", throwingMatchesStore());
		assertNull(f.ai.translate(TEXT, false));
		assertEquals("a broken terminology index must not send anything", 0, f.calls.size());
		assertEquals("and must never be counted as a Bing failure", 0, f.failStreak());
		assertEquals(0, f.notifications.get());
		assertFalse(f.notified());
		assertEquals(0, f.ai.inFlightCount());
		f.assertNoRequest(TEXT); // marked untranslatable, never retried every tick
	}

	@Test public void freeMtDispatchFailureSkipsTheLineWithoutEndpointFailure() throws Exception
	{
		Fixture f = new Fixture();
		f.newCallHook = () -> { throw new IllegalStateException("fictional dispatch failure"); };
		assertNull(f.ai.translate(TEXT, false));
		assertEquals("nothing reached the fake transport", 0, f.calls.size());
		assertEquals("a local dispatch error must not count against Bing", 0, f.failStreak());
		assertEquals("and must never show the outage dialog", 0, f.notifications.get());
		assertFalse(f.notified());
		assertFalse(f.degraded());
		assertEquals(0, f.ai.inFlightCount());
		assertEquals("exactly one dispatch was attempted", 1, f.newCalls.get());
		f.newCallHook = null;
		f.assertNoRequest(TEXT); // negative-cached: the broken dispatch is not retried every tick
		assertEquals("a dispatch failure must not be retried", 1, f.newCalls.get());
		assertNull(f.ai.cached(TEXT));
	}

	@Test public void freeMtEnqueueFailureSkipsTheLineWithoutEndpointFailure() throws Exception
	{
		Fixture f = new Fixture();
		f.enqueueThrows = true;
		assertNull(f.ai.translate(TEXT, false));
		assertEquals("nothing reached the fake transport", 0, f.calls.size());
		assertEquals("a local enqueue error must not count against Bing", 0, f.failStreak());
		assertEquals("and must never show the outage dialog", 0, f.notifications.get());
		assertFalse(f.notified());
		assertFalse(f.degraded());
		assertEquals(0, f.ai.inFlightCount());
		assertEquals("the request was built before enqueue failed", 1, f.newCalls.get());
		f.enqueueThrows = false;
		f.assertNoRequest(TEXT); // negative-cached: the broken dispatch is not retried every tick
		assertEquals("an enqueue failure must not be retried", 1, f.newCalls.get());
		assertNull(f.ai.cached(TEXT));
	}

	@Test public void freeMtSkipConditionsNeverRequest() throws Exception
	{
		Fixture f = new Fixture();
		String longLine = "A ".repeat(1001);
		assertTrue(longLine.length() > FreeMtText.MAX_TEXT_CHARS);
		f.assertNoRequest(longLine);
		f.assertNoRequest("A line with {{0}} markers");
		f.assertNoRequest("12345");
		assertEquals("S1 lines never reach the network", 0, f.calls.size());
		assertEquals(0, f.failStreak());
		assertEquals(0, f.notifications.get());
	}

	@Test public void freeMtMaybeNotifyGuardRequiresEnabledFreeMtBackend() throws Exception
	{
		Fixture ollama = new Fixture();
		ollama.backend = AiBackend.OLLAMA;
		invoke(ollama.ai, "maybeNotifyFreeMtUnavailable");
		assertEquals("the dialog belongs to FREE_MT only", 0, ollama.notifications.get());

		Fixture openai = new Fixture();
		openai.backend = AiBackend.OPENAI;
		invoke(openai.ai, "maybeNotifyFreeMtUnavailable");
		assertEquals(0, openai.notifications.get());

		Fixture disabled = new Fixture();
		disabled.enabled = false;
		invoke(disabled.ai, "maybeNotifyFreeMtUnavailable");
		assertEquals("never notify while AI is off", 0, disabled.notifications.get());

		Fixture free = new Fixture();
		invoke(free.ai, "maybeNotifyFreeMtUnavailable");
		assertEquals(1, free.notifications.get());
		assertTrue(free.notified());
		assertTrue(free.degraded());
	}

	@Test public void freeMtDegradedIsSetEvenAfterTheNoticeWasShown() throws Exception
	{
		Fixture f = new Fixture();
		for (int i = 0; i < 3; i++)
		{
			f.request("fictional offline " + i, false).failure();
		}
		assertEquals(1, f.notifications.get());
		assertTrue(f.notified());
		assertTrue(f.degraded());

		f.clearBackoff();
		f.request(TEXT, false).bing(ZH);
		assertFalse("a success clears degraded", f.degraded());
		assertTrue("but the run keeps its one-shot notice state", f.notified());
		assertEquals("Bing", f.ai.freeMtDebugLine());

		f.clearBackoff();
		for (int i = 0; i < 3; i++)
		{
			f.request("fictional offline again " + i, false).failure();
		}
		assertEquals("no second dialog in the same run", 1, f.notifications.get());
		assertTrue("degraded is set before the notified early return", f.degraded());
	}

	// ---- state lifecycle and debug line -------------------------------------------------------

	@Test public void freeMtDebugLineOnlyForEnabledFreeMt()
	{
		try
		{
			Fixture f = new Fixture();
			assertEquals("Bing", f.ai.freeMtDebugLine());
			inject(f.ai, "freeMtDegraded", true);
			assertEquals("不可用（已提示）", f.ai.freeMtDebugLine());
			f.backend = AiBackend.OLLAMA;
			assertNull("the line is hidden for other backends", f.ai.freeMtDebugLine());
			f.backend = AiBackend.FREE_MT;
			f.enabled = false;
			assertNull("the line is hidden while AI is off", f.ai.freeMtDebugLine());
		}
		catch (Exception e)
		{
			throw new AssertionError(e);
		}
	}

	@Test public void freeMtDebugLineNeverWaitsForTheTranslatorMonitor() throws Exception
	{
		Fixture f = new Fixture();
		CountDownLatch held = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		Thread holder = new Thread(() ->
		{
			synchronized (f.ai)
			{
				held.countDown();
				try
				{
					release.await();
				}
				catch (InterruptedException e)
				{
					Thread.currentThread().interrupt();
				}
			}
		});
		holder.setDaemon(true);
		holder.start();
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try
		{
			assertTrue("fixture failed to hold the translator monitor", held.await(5, TimeUnit.SECONDS));
			// The panel refresh calls this every second from the EDT: it must answer without ever
			// waiting for the monitor a long translation request holds. Generous timeout: a monitor
			// wait would time out anyway, while a slow CI must not make this flaky.
			Future<String> result = executor.submit(f.ai::freeMtDebugLine);
			assertEquals("Bing", result.get(5, TimeUnit.SECONDS));
		}
		finally
		{
			release.countDown();
			executor.shutdownNow();
			holder.join(2000);
		}
	}

	@Test public void freeMtConfigurationChangedResetsUnavailableState() throws Exception
	{
		Fixture f = new Fixture();
		inject(f.ai, "freeMtUnavailableNotified", true);
		inject(f.ai, "freeMtDegraded", true);
		inject(f.ai, "freeMtUnusableLineStreak", 4);
		f.ai.configurationChanged();
		assertFalse(f.notified());
		assertFalse(f.degraded());
		assertEquals(0, f.lineStreak());
	}

	@Test public void freeMtClearCacheResetsUnavailableState() throws Exception
	{
		Fixture f = new Fixture();
		inject(f.ai, "freeMtUnavailableNotified", true);
		inject(f.ai, "freeMtDegraded", true);
		inject(f.ai, "freeMtUnusableLineStreak", 4);
		f.ai.clearCache();
		assertFalse(f.notified());
		assertFalse(f.degraded());
		assertEquals(0, f.lineStreak());
	}

	@Test public void freeMtRequestHeadersChangeDoesNotResetUnavailableState() throws Exception
	{
		Fixture f = new Fixture();
		inject(f.ai, "freeMtUnavailableNotified", true);
		inject(f.ai, "freeMtDegraded", true);
		inject(f.ai, "freeMtUnusableLineStreak", 5);
		f.ai.requestHeadersChanged();
		// apiExtraHeaders is an online-API setting FREE_MT never uses (plan 4.2 / 7.8): changing it
		// must not re-arm the notice or clear the free backend's degraded/line state.
		assertTrue(f.notified());
		assertTrue(f.degraded());
		assertEquals(5, f.lineStreak());
	}

	@Test public void freeMtStaleReplyAfterBackendSwitchIsIgnored() throws Exception
	{
		Fixture f = new Fixture();
		Pending stale = f.request(TEXT, true);
		f.backend = AiBackend.OLLAMA;
		f.ai.configurationChanged();
		stale.bing(ZH);
		assertNull("a reply for the previous backend must not be cached", f.ai.cached(TEXT));
		assertEquals("and must not count as a session translation", 0, f.ai.sessionCount());
		assertEquals(0, f.ai.inFlightCount());
		assertFalse("and must not be written to the free-MT cache file", f.cacheFile().exists());
	}

	// ---- test connection (Bing probe on the test client) -------------------------------------

	@Test public void freeMtTestConnectionSuccessResetsFlagsOnTheTestClient() throws Exception
	{
		Fixture f = new Fixture();
		inject(f.ai, "freeMtUnavailableNotified", true);
		inject(f.ai, "freeMtDegraded", true);
		f.connect();
		assertEquals(1, f.calls.size());
		Pending probe = f.calls.get(0);
		assertTrue("the probe must use the EDT-safe test client", probe.viaTestClient);
		assertEquals("edge.microsoft.com", probe.request.url().host());
		assertEquals(FreeMtClient.probeText(), requestArray(probe).get(0).getAsString());
		probe.bing("你好世界");
		assertEquals(1, f.connectResults.get());
		assertEquals(Boolean.TRUE, f.connected.get());
		assertEquals("免费机器翻译可用（Bing）", f.connectMessage.get());
		assertFalse("a successful probe re-arms the notice", f.notified());
		assertFalse(f.degraded());
		assertEquals("the probe never writes the cache", 0, f.ai.cacheSize());
		assertEquals("the probe never counts as a session translation", 0, f.ai.sessionCount());
		assertFalse(f.cacheFile().exists());
	}

	@Test public void freeMtTestConnectionSuccessDoesNotResetAnotherBackendsFailureState() throws Exception
	{
		Fixture f = new Fixture();
		inject(f.ai, "freeMtUnavailableNotified", true);
		inject(f.ai, "freeMtDegraded", true);
		f.connect();
		assertEquals(1, f.calls.size());
		// The user switches backend while the probe callback is still in flight. The late success
		// must not clear the NEW backend's failure streak/backoff (the Ollama/API probes never do).
		f.backend = AiBackend.OLLAMA;
		((AtomicInteger) field(f.ai, "failStreak")).set(2);
		inject(f.ai, "backoffUntil", Long.MAX_VALUE);
		f.calls.get(0).bing("你好世界");
		assertTrue("the probe must not re-arm the notice", f.notified());
		assertTrue("nor clear the debug flag", f.degraded());
		assertEquals("nor clear the new backend's failure streak", 2, f.failStreak());
		assertEquals("nor clear its backoff", Long.MAX_VALUE, f.backoffUntil());
		assertEquals("but it still reports its own result", Boolean.TRUE, f.connected.get());
		assertEquals("免费机器翻译可用（Bing）", f.connectMessage.get());
		assertEquals(1, f.connectResults.get());
	}

	@Test public void freeMtTestConnectionHttpError() throws Exception
	{
		Fixture f = new Fixture();
		inject(f.ai, "freeMtDegraded", true);
		f.connect();
		f.calls.get(0).respond(500, html("boom"));
		assertEquals(Boolean.FALSE, f.connected.get());
		assertEquals("免费机器翻译不可用：Bing HTTP 500", f.connectMessage.get());
		assertTrue("a failed probe does not clear the degraded state", f.degraded());
	}

	@Test public void freeMtTestConnectionBadJson() throws Exception
	{
		Fixture f = new Fixture();
		f.connect();
		f.calls.get(0).respond(200, html("{"));
		assertEquals(Boolean.FALSE, f.connected.get());
		assertEquals("Bing 回傳格式錯誤", f.connectMessage.get());
	}

	@Test public void freeMtTestConnectionUnusableContent() throws Exception
	{
		Fixture f = new Fixture();
		f.connect();
		f.calls.get(0).bing("Hello world");
		assertEquals(Boolean.FALSE, f.connected.get());
		assertEquals("Bing 回傳內容無法使用", f.connectMessage.get());
	}

	@Test public void freeMtTestConnectionTransportFailure() throws Exception
	{
		Fixture f = new Fixture();
		f.connect();
		f.calls.get(0).failure();
		assertEquals(Boolean.FALSE, f.connected.get());
		assertEquals("免费机器翻译不可用：Bing 连接失败", f.connectMessage.get());
	}
}
