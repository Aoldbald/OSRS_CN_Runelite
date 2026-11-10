package com.osrscn.translate;

import static org.junit.Assert.*;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.osrscn.AiBackend;
import com.osrscn.OsrscnConfig;
import com.osrscn.OsrscnPlugin;
import com.osrscn.ui.DialogueHistory;
import com.osrscn.ui.OsrscnPanel;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.runelite.client.events.ConfigChanged;
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
import okio.ForwardingSource;
import okio.Okio;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Production lifecycle with fictional config, isolated files and a socket-free transport. */
public class AiTranslatorTest
{
	private static final String TEXT = "A fictional crystal gate awaits.";
	@Rule public TemporaryFolder temporary = new TemporaryFolder();

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

	/** OpenAI-style 400 body for a provider/model that rejects the optional temperature field. */
	private static ResponseBody temperatureRejected()
	{
		return ResponseBody.create(MediaType.parse("application/json"),
				"{\"error\":{\"message\":\"Unsupported value: temperature\"}}");
	}

	/** OpenAI-style success body with one translated message. */
	private static ResponseBody apiSuccess(String content)
	{
		return ResponseBody.create(MediaType.parse("application/json"),
				"{\"choices\":[{\"message\":{\"content\":\"" + content + "\"}}]}");
	}

	private final class Fixture
	{
		final File dir = temporary.newFolder();
		final AiTranslator ai = new AiTranslator();
		final OsrscnPlugin plugin = new OsrscnPlugin();
		final List<Pending> calls = new ArrayList<>();
		final List<Runnable> preload = new ArrayList<>();
		volatile String model = "fixture-A";
		volatile AiBackend backend = AiBackend.OLLAMA;
		volatile boolean enabled = true;
		volatile int pace;
		volatile int concurrency = 4;
		volatile String extraHeaders = "";
		final AtomicReference<Boolean> connected = new AtomicReference<>();
		final AtomicReference<String> connectMessage = new AtomicReference<>();
		final AtomicInteger connectResults = new AtomicInteger();
		Runnable newCallHook;
		Consumer<Pending> enqueueHook;
		Runnable modelReadHook;

		Fixture() throws Exception
		{
			OsrscnConfig config = (OsrscnConfig) Proxy.newProxyInstance(getClass().getClassLoader(),
					new Class<?>[] { OsrscnConfig.class }, (p, m, args) ->
					{
						switch (m.getName())
						{
							case "useLocalAi": return enabled;
							case "aiBackend": return backend;
							case "apiModel":
							case "ollamaModel":
								String value = model;
								Runnable hook = modelReadHook; modelReadHook = null;
								if (hook != null) hook.run();
								return value;
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
			inject(ai, "dir", dir); // Before any load; never touch RuneLite's real cache directory.
			inject(ai, "config", config);
			inject(ai, "gson", new Gson());
			inject(ai, "executor", Proxy.newProxyInstance(getClass().getClassLoader(),
					new Class<?>[] { java.util.concurrent.ScheduledExecutorService.class }, (p, m, args) ->
					{
						if (!m.getName().equals("execute")) throw new AssertionError(m.getName());
						preload.add((Runnable) args[0]); return null;
					}));
			OkHttpClient injectedClient = transportClient(false);
			inject(ai, "httpClient", injectedClient);
			inject(ai, "ollamaHttpClient", injectedClient);
			// The API branch now returns its own derived client; pin it to the same socket-free
			// transport so the recorded Pending still proves which client issued a request.
			inject(ai, "apiInferenceClient", injectedClient);
			// testConnection must run through the derived test client, so it gets its own transport
			// instance: the recorded Pending then proves which client actually issued the request.
			inject(ai, "testHttpClient", transportClient(true));
			inject(plugin, "aiTranslator", ai);
			inject(plugin, "config", config);
			// Allocate only the unrelated Swing shell, without constructing UI/config stores or timers.
			// Its real onConfigChanged runs a no-change refresh against empty in-memory history.
			Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
			Field singleton = unsafeClass.getDeclaredField("theUnsafe"); singleton.setAccessible(true);
			Object panel = unsafeClass.getMethod("allocateInstance", Class.class)
					.invoke(singleton.get(null), OsrscnPanel.class);
			inject(panel, "config", config); inject(panel, "history", new DialogueHistory());
			inject(plugin, "panel", panel);
		}

		/** Socket-free transport; {@code viaTest} marks requests issued through the derived test client. */
		private OkHttpClient transportClient(final boolean viaTest)
		{
			return new OkHttpClient()
			{
				@Override public Call newCall(Request request)
				{
					Pending pending = new Pending(request);
					pending.viaTestClient = viaTest;
					Runnable hook = newCallHook; newCallHook = null;
					if (hook != null) hook.run();
					pending.call = (Call) Proxy.newProxyInstance(getClass().getClassLoader(),
							new Class<?>[] { Call.class }, (p, m, args) ->
							{
								if (m.getName().equals("request")) return request;
								if (!m.getName().equals("enqueue")) throw new AssertionError(m.getName());
								pending.callback = (Callback) args[0]; calls.add(pending);
								Consumer<Pending> action = enqueueHook; enqueueHook = null;
								if (action != null) action.accept(pending);
								return null;
							});
					return pending.call;
				}
			};
		}

		void event(String key)
		{
			ConfigChanged event = new ConfigChanged();
			event.setGroup(OsrscnConfig.GROUP); event.setKey(key);
			event.setOldValue(null); event.setNewValue(null); // reset does not need value parsing
			plugin.onConfigChanged(event);
		}

		void switchTo(String next)
		{
			model = next;
			event(backend == AiBackend.OPENAI ? "apiModel" : "ollamaModel");
		}

		/** Runs one connection probe and counts every completion, so a double report is observable. */
		void connect()
		{
			ai.testConnection((ok, msg) ->
			{
				connectResults.incrementAndGet();
				connected.set(ok); connectMessage.set(msg);
			});
		}

		Pending request(boolean persist)
		{
			int size = calls.size();
			assertNull(ai.translate(TEXT, persist));
			assertEquals(size + 1, calls.size());
			return calls.get(size);
		}

		File file(String model) { return new File(dir, "ai_" + model + ".tsv"); }
		int failures() throws Exception { return ((AtomicInteger) field(ai, "failStreak")).get(); }
		long value(String name) throws Exception { return (long) field(ai, name); }
		void backoff() throws Exception
		{
			((AtomicInteger) field(ai, "failStreak")).set(3);
			inject(ai, "backoffUntil", Long.MAX_VALUE);
		}
	}

	private static final class Pending
	{
		final Request request;
		Call call;
		Callback callback;
		boolean viaTestClient;
		Pending(Request request) { this.request = request; }
		void success(String content) throws Exception
		{
			JsonObject message = new JsonObject(); message.addProperty("content", content);
			JsonObject root = new JsonObject(); root.add("message", message);
			respond(200, ResponseBody.create(MediaType.parse("application/json"), root.toString()));
		}
		void failure() { callback.onFailure(call, new IOException("fictional offline failure")); }
		void httpFailure() throws Exception
		{
			respond(429, ResponseBody.create(MediaType.parse("application/json"), "fixture rate limit"));
		}
		void malformed() throws Exception
		{
			respond(200, ResponseBody.create(MediaType.parse("application/json"), "{"));
		}
		void respond(int status, ResponseBody body) throws Exception
		{
			callback.onResponse(call, new Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
					.code(status).message("fixture").body(body).build());
		}
		JsonObject json() throws Exception
		{
			Buffer buffer = new Buffer(); request.body().writeTo(buffer);
			return new Gson().fromJson(buffer.readUtf8(), JsonObject.class);
		}
		String model() throws Exception
		{
			return json().get("model").getAsString();
		}
	}

	@Test public void oldPlayerSuccessCannotContaminateCurrentMemoryOrFile() throws Exception
	{
		Fixture f = new Fixture(); Pending a = f.request(false); f.switchTo("fixture-B");
		Pending b = f.request(false); a.success("old player");
		assertNull(f.ai.cached(TEXT)); assertEquals(1, f.ai.inFlightCount());
		assertEquals(0, f.ai.sessionCount()); assertTrue(f.ai.recent().isEmpty());
		assertFalse(f.file("fixture-A").exists()); assertFalse(f.file("fixture-B").exists());
		b.success("new player"); assertEquals("new player", f.ai.cached(TEXT));
		assertFalse(f.file("fixture-B").exists());
	}

	@Test public void oldGameSuccessCannotWriteCurrentOrOldModelFiles() throws Exception
	{
		Fixture f = new Fixture(); Pending a = f.request(true); f.switchTo("fixture-B");
		Pending b = f.request(true); a.success("old game");
		assertNull(f.ai.cached(TEXT)); assertEquals(1, f.ai.inFlightCount());
		assertFalse(f.file("fixture-A").exists()); assertFalse(f.file("fixture-B").exists());
		b.success("new game");
		assertEquals(TEXT + "\tnew game\n", Files.readString(f.file("fixture-B").toPath()));
	}

	@Test public void lateOldSuccessCannotOverwriteCompletedNewSuccess() throws Exception
	{
		for (boolean persist : new boolean[] { false, true })
		{
			Fixture f = new Fixture(); Pending a = f.request(persist); f.switchTo("fixture-B");
			f.request(persist).success("new"); a.success("old");
			assertEquals("new", f.ai.cached(TEXT)); assertEquals(1, f.ai.sessionCount());
			assertEquals(1, f.ai.recent().size());
			if (persist) assertEquals(TEXT + "\tnew\n", Files.readString(f.file("fixture-B").toPath()));
		}
	}

	@Test public void oldFailureCannotClearSameKeyOrSetCurrentBackoff() throws Exception
	{
		Fixture f = new Fixture(); Pending a = f.request(false); f.switchTo("fixture-B"); f.request(false);
		a.failure(); assertEquals(1, f.ai.inFlightCount()); assertEquals(0, f.failures());
		assertEquals(0, f.value("backoffUntil"));
	}

	@Test public void oldHttpFailureCannotClearSameKeyOrSetCurrentBackoff() throws Exception
	{
		Fixture f = new Fixture(); Pending a = f.request(true); f.switchTo("fixture-B"); f.request(true);
		a.httpFailure(); assertEquals(1, f.ai.inFlightCount()); assertEquals(0, f.failures());
	}

	@Test public void oldParseFinallyCannotClearCurrentRequest() throws Exception
	{
		Fixture f = new Fixture(); Pending a = f.request(true); f.switchTo("fixture-B"); f.request(true);
		a.malformed(); assertEquals(1, f.ai.inFlightCount()); assertFalse(f.file("fixture-B").exists());
	}

	@Test public void oldSuccessCannotResetNewModelsFailuresAndBackoff() throws Exception
	{
		Fixture f = new Fixture(); Pending a = f.request(false); f.switchTo("fixture-B");
		f.ai.cached(TEXT); f.backoff(); a.success("old");
		assertEquals(3, f.failures()); assertEquals(Long.MAX_VALUE, f.value("backoffUntil"));
	}

	@Test public void oldCompletionCannotChangeCurrentPacingTimestamp() throws Exception
	{
		Fixture f = new Fixture(); Pending a = f.request(false); f.switchTo("fixture-B");
		f.ai.cached(TEXT); inject(f.ai, "lastDispatch", 123L); a.failure();
		assertEquals(123L, f.value("lastDispatch"));
	}

	@Test public void repeatedOldFailuresDoNotThrottleNewModel() throws Exception
	{
		Fixture f = new Fixture(); f.ai.translate(TEXT + " one", false); f.ai.translate(TEXT + " two", false);
		f.ai.translate(TEXT + " three", false); f.switchTo("fixture-B"); f.ai.cached(TEXT);
		for (Pending p : new ArrayList<>(f.calls)) p.failure();
		assertEquals(0, f.failures()); assertEquals(0, f.value("backoffUntil")); f.request(false);
	}

	@Test public void loadedAtoBtoACannotReuseFirstARequestGeneration() throws Exception
	{
		Fixture f = new Fixture(); Pending a = f.request(true); f.model = "fixture-B"; f.ai.cached(TEXT);
		f.model = "fixture-A"; Pending newer = f.request(true); a.success("obsolete A");
		assertNull(f.ai.cached(TEXT)); assertEquals(1, f.ai.inFlightCount()); newer.success("current A");
		assertEquals("current A", f.ai.cached(TEXT));
	}

	@Test public void configurationAtoBtoAWithoutLookupStillInvalidatesOldA() throws Exception
	{
		Fixture f = new Fixture(); Pending a = f.request(true);
		f.switchTo("fixture-B"); f.switchTo("fixture-A"); a.success("obsolete A");
		assertNull(f.ai.cached(TEXT)); assertFalse(f.file("fixture-A").exists()); f.request(true);
	}

	@Test public void backendChangeWithSameModelRevokesOldRequest() throws Exception
	{
		Fixture f = new Fixture(); Pending a = f.request(false); f.backend = AiBackend.OPENAI;
		f.event("aiBackend"); f.request(false); a.success("obsolete backend");
		assertNull(f.ai.cached(TEXT)); assertEquals(1, f.ai.inFlightCount());
		assertEquals("fixture-A", f.calls.get(1).model());
	}

	@Test public void everyRequestIdentityConfigKeyRevokesEvenNullResetEvents() throws Exception
	{
		for (String key : new String[] { "aiBackend", "ollamaModel", "apiModel", "ollamaUrl", "apiUrl", "apiKey" })
		{
			Fixture f = new Fixture(); Pending old = f.request(false); f.event(key);
			old.success("obsolete config"); assertNull(key, f.ai.cached(TEXT)); f.request(false);
		}
	}

	@Test public void unrelatedAndOtherGroupEventsDoNotDiscardCurrentWork() throws Exception
	{
		Fixture f = new Fixture(); Pending current = f.request(false); f.event("aiPaceMs");
		ConfigChanged ignored = new ConfigChanged(); ignored.setGroup("fictional-other"); ignored.setKey("apiModel");
		f.plugin.onConfigChanged(ignored); current.success("current"); assertEquals("current", f.ai.cached(TEXT));
	}

	@Test public void aiOffRetainsCompletedCacheButCannotDispatchMiss() throws Exception
	{
		Fixture f = new Fixture(); f.request(false).success("cached"); f.enabled = false; f.event("useLocalAi");
		assertEquals("cached", f.ai.translate(TEXT, false));
		assertNull(f.ai.translate("Another fictional gate.", false)); assertEquals(1, f.calls.size());
	}

	@Test public void clearCacheRevokesOldCompletionAndSameKeyCleanup() throws Exception
	{
		Fixture f = new Fixture(); Pending old = f.request(true); f.ai.clearCache(); Pending current = f.request(true);
		old.success("obsolete clear"); assertNull(f.ai.cached(TEXT)); assertEquals(1, f.ai.inFlightCount());
		current.success("current"); assertEquals("current", f.ai.cached(TEXT));
	}

	@Test public void modelSwitchDuringBodyReadCannotCommitSuccess() throws Exception
	{
		Fixture f = new Fixture(); Pending a = f.request(true);
		BufferedSource source = Okio.buffer(new ForwardingSource(new Buffer().writeUtf8("{\"message\":{\"content\":\"obsolete\"}}"))
		{
			boolean switched;
			@Override public long read(Buffer sink, long count) throws IOException
			{
				assertFalse("response IO must release the model monitor", Thread.holdsLock(f.ai));
				if (!switched) { switched = true; f.switchTo("fixture-B"); f.ai.cached(TEXT);
					try { f.backoff(); } catch (Exception e) { throw new AssertionError(e); } }
				return super.read(sink, count);
			}
		});
		a.respond(200, new ResponseBody()
		{
			@Override public MediaType contentType() { return MediaType.parse("application/json"); }
			@Override public long contentLength() { return -1; }
			@Override public BufferedSource source() { return source; }
		});
		assertNull(f.ai.cached(TEXT)); assertEquals(3, f.failures());
		assertEquals(Long.MAX_VALUE, f.value("backoffUntil")); assertFalse(f.file("fixture-B").exists());
	}

	@Test public void responseCloseAfterSuccessCannotClearNewSameKeyFinally() throws Exception
	{
		Fixture f = new Fixture(); Pending a = f.request(false);
		BufferedSource source = new Buffer().writeUtf8("{\"message\":{\"content\":\"old\"}}");
		a.respond(200, new ResponseBody()
		{
			@Override public MediaType contentType() { return MediaType.parse("application/json"); }
			@Override public long contentLength() { return -1; }
			@Override public BufferedSource source() { return source; }
			@Override public void close()
			{
				// ResponseBody.string closes its source before commit; Response.close occurs after it.
				assertFalse("response close must release the model monitor", Thread.holdsLock(f.ai));
				assertEquals("old", f.ai.cached(TEXT)); assertEquals(1, f.ai.sessionCount());
				f.switchTo("fixture-B"); f.request(false); super.close();
			}
		});
		assertEquals(1, f.ai.inFlightCount()); assertNull(f.ai.cached(TEXT));
	}

	@Test public void synchronousEnqueueCallbackIsReentrantWithoutDeadlock() throws Exception
	{
		Fixture f = new Fixture(); f.enqueueHook = pending ->
		{
			try { pending.success("inline callback"); } catch (Exception e) { throw new AssertionError(e); }
		};
		f.ai.translate(TEXT, false, new AiTranslator.RequestPermit(() -> true));
		assertEquals("inline callback", f.ai.cached(TEXT)); assertEquals(0, f.ai.inFlightCount());
	}

	@Test public void newCallReentryCannotEnqueueAnObsoleteGeneration() throws Exception
	{
		Fixture f = new Fixture(); f.newCallHook = () -> f.switchTo("fixture-B");
		f.ai.translate(TEXT, false, new AiTranslator.RequestPermit(() -> true));
		assertEquals(0, f.calls.size()); assertEquals(0, f.ai.inFlightCount()); f.request(false);
	}

	@Test public void enqueueExceptionCannotLeakInFlightOrEscapeToCaller() throws Exception
	{
		Fixture f = new Fixture(); f.enqueueHook = pending -> { throw new IllegalStateException("fixture enqueue failure"); };
		assertNull(f.ai.translate(TEXT, false)); assertEquals(0, f.ai.inFlightCount()); assertEquals(1, f.failures());
		f.request(false);
	}

	@Test public void oldEnqueueExceptionCannotRemoveNewSameKey() throws Exception
	{
		Fixture f = new Fixture(); f.enqueueHook = pending ->
		{
			f.switchTo("fixture-B"); f.request(false); throw new IllegalStateException("obsolete enqueue failure");
		};
		assertNull(f.ai.translate(TEXT, false)); assertEquals(1, f.ai.inFlightCount()); assertEquals(0, f.failures());
	}

	@Test public void requestBodyAndLoadedCacheUseTheSameCapturedModel() throws Exception
	{
		Fixture f = new Fixture(); f.ai.cached(TEXT); f.modelReadHook = () -> f.model = "fixture-B";
		f.ai.translate(TEXT, false);
		for (Pending pending : f.calls) assertEquals("fixture-B", pending.model());
		// Either reject the raced snapshot or dispatch using the current model; never bind B output to A.
		if (!f.calls.isEmpty()) f.calls.get(0).success("current B");
		assertFalse(f.file("fixture-A").exists());
	}

	@Test public void concurrentAdmissionRespectsSingleSlot() throws Exception
	{
		Fixture f = new Fixture(); f.concurrency = 1;
		CountDownLatch ready = new CountDownLatch(2); CountDownLatch start = new CountDownLatch(1);
		AtomicReference<Throwable> error = new AtomicReference<>();
		Runnable request = () ->
		{
			ready.countDown();
			try { if (!start.await(3, TimeUnit.SECONDS)) throw new AssertionError("start timeout");
				f.ai.translate(Thread.currentThread().getName(), false); }
			catch (Throwable e) { error.set(e); }
		};
		Thread first = new Thread(request, "fictional first"); Thread second = new Thread(request, "fictional second");
		first.start(); second.start(); assertTrue(ready.await(3, TimeUnit.SECONDS)); start.countDown();
		first.join(3000); second.join(3000); assertFalse(first.isAlive()); assertFalse(second.isAlive());
		if (error.get() != null) throw new AssertionError(error.get());
		assertEquals(1, f.calls.size()); assertEquals(1, f.ai.inFlightCount());
	}

	@Test public void revokedPermitCannotReviveAfterModelRoundTrip() throws Exception
	{
		Fixture f = new Fixture(); AiTranslator.RequestPermit permit = new AiTranslator.RequestPermit(() -> true);
		permit.revoke(); f.switchTo("fixture-B"); f.switchTo("fixture-A");
		f.ai.translate(TEXT, false, permit); assertEquals(0, f.calls.size());
		f.ai.translate(TEXT, false, new AiTranslator.RequestPermit(() -> true)); assertEquals(1, f.calls.size());
	}

	@Test public void currentFailuresStillBackOffAndModelSwitchResetsPacing() throws Exception
	{
		Fixture f = new Fixture(); for (int i = 0; i < 3; i++) f.request(false).failure();
		assertEquals(3, f.failures()); assertTrue(f.value("backoffUntil") > System.currentTimeMillis());
		assertNull(f.ai.translate(TEXT, false)); assertEquals(3, f.calls.size());
		f.pace = Integer.MAX_VALUE; f.switchTo("fixture-B"); f.request(false);
		assertEquals(0, f.failures()); assertEquals(0, f.value("backoffUntil"));
	}

	@Test public void memoryEntryCanStillBePromotedOnlyByPersistTrue() throws Exception
	{
		Fixture f = new Fixture(); f.request(false).success("memory"); assertFalse(f.file("fixture-A").exists());
		assertEquals("memory", f.ai.cached(TEXT)); assertFalse(f.file("fixture-A").exists());
		assertEquals("memory", f.ai.translate(TEXT, true));
		assertEquals(TEXT + "\tmemory\n", Files.readString(f.file("fixture-A").toPath()));
		f.ai.translate(TEXT, true); assertEquals(1, Files.readAllLines(f.file("fixture-A").toPath()).size());
	}

	@Test public void preloadAndCacheOnlyFollowActualConfigAndLoadOnlyFixtureFiles() throws Exception
	{
		Fixture f = new Fixture();
		Files.writeString(f.file("fixture-A").toPath(), TEXT + "\tstored A\n", StandardCharsets.UTF_8);
		Files.writeString(f.file("fixture-B").toPath(), TEXT + "\tstored B\n", StandardCharsets.UTF_8);
		f.ai.preloadAsync(); f.switchTo("fixture-B"); f.preload.get(0).run();
		assertEquals("stored B", f.ai.cached(TEXT)); assertEquals(0, f.calls.size());
		f.enabled = false; f.switchTo("fixture-A"); assertEquals("stored A", f.ai.translate(TEXT, false));
		assertEquals(0, f.calls.size());
	}

	@Test public void clearAfterConnectionEventDeletesCurrentDiskCacheBeforeAnotherLookup() throws Exception
	{
		Fixture f = new Fixture(); f.request(true).success("stored A"); f.event("apiKey");
		Translator translator = new Translator(); inject(translator, "ai", f.ai);
		translator.clearAiCache();
		assertFalse(f.file("fixture-A").exists()); assertNull(f.ai.cached(TEXT));
	}

	@Test public void clearAfterModelEventTargetsCurrentModelWithoutDeletingPreviousModel() throws Exception
	{
		Fixture f = new Fixture(); f.request(true).success("stored A");
		Files.writeString(f.file("fixture-B").toPath(), TEXT + "\tstored B\n", StandardCharsets.UTF_8);
		f.switchTo("fixture-B");
		Translator translator = new Translator(); inject(translator, "ai", f.ai);
		translator.clearAiCache();
		assertTrue(f.file("fixture-A").exists()); assertFalse(f.file("fixture-B").exists());
		assertEquals(TEXT + "\tstored A\n", Files.readString(f.file("fixture-A").toPath()));
		assertNull(f.ai.cached(TEXT));
	}

	@Test public void apiBackendRequestCarriesConfiguredCustomHeaders() throws Exception
	{
		Fixture f = new Fixture(); f.backend = AiBackend.OPENAI;
		f.extraHeaders = "x-opencode-session: OSRSCN; User-Agent: OSRSCN";
		Pending pending = f.request(false);
		assertEquals("OSRSCN", pending.request.header("x-opencode-session"));
		assertEquals("OSRSCN", pending.request.header("User-Agent"));
		assertEquals("Bearer fictional-not-a-key", pending.request.header("Authorization"));
	}

	@Test public void ollamaRequestCarriesConfiguredCustomHeaders() throws Exception
	{
		Fixture f = new Fixture();
		f.extraHeaders = "x-opencode-session: OSRSCN; User-Agent: OSRSCN";
		Pending pending = f.request(false);
		assertEquals("OSRSCN", pending.request.header("x-opencode-session"));
		assertEquals("OSRSCN", pending.request.header("User-Agent"));
		assertNull(pending.request.header("Authorization"));
	}

	@Test public void apiRequestCarriesFixedLowTemperature() throws Exception
	{
		Fixture f = new Fixture(); f.backend = AiBackend.OPENAI;
		JsonObject body = f.request(false).json();
		assertNotNull(body.get("temperature"));
		assertTrue(body.get("temperature").isJsonPrimitive());
		assertTrue(body.get("temperature").getAsJsonPrimitive().isNumber());
		assertEquals(0.3, body.get("temperature").getAsDouble(), 0.0);
	}

	@Test public void ollamaRequestHasNoTemperature() throws Exception
	{
		Fixture f = new Fixture();
		assertFalse(f.request(false).json().has("temperature"));
	}

	@Test public void rejectedTemperatureIsRetriedOnceWithoutIt() throws Exception
	{
		Fixture f = new Fixture(); f.backend = AiBackend.OPENAI;
		Pending first = f.request(false);
		assertTrue(first.json().has("temperature"));
		first.respond(400, temperatureRejected());
		assertEquals("exactly one automatic retry", 2, f.calls.size());
		Pending retry = f.calls.get(1);
		assertFalse("the retry must omit temperature", retry.json().has("temperature"));
		assertEquals("the retry stays in flight", 1, f.ai.inFlightCount());
		assertEquals("a retried temperature rejection is not a failure", 0, f.failures());
		retry.respond(200, apiSuccess("重送成功"));
		assertEquals("重送成功", f.ai.cached(TEXT));
		assertEquals(0, f.ai.inFlightCount());
		assertEquals(0, f.failures());
	}

	@Test public void failedRetryKeepsTemperatureForLaterRequests() throws Exception
	{
		Fixture f = new Fixture(); f.backend = AiBackend.OPENAI;
		f.request(false).respond(400, temperatureRejected());
		assertEquals(2, f.calls.size());
		f.calls.get(1).respond(400, temperatureRejected());
		assertEquals("a rejected retry is never retried again", 2, f.calls.size());
		assertEquals(1, f.failures());
		assertEquals(0, f.ai.inFlightCount());
		Pending next = f.request(false);
		assertTrue("a rejected retry must not disable temperature", next.json().has("temperature"));
	}

	@Test public void successfulRetryRemembersUnsupportedTemperature() throws Exception
	{
		Fixture f = new Fixture(); f.backend = AiBackend.OPENAI;
		f.request(false).respond(400, temperatureRejected());
		assertEquals(2, f.calls.size());
		f.calls.get(1).respond(200, apiSuccess("首次成功"));
		int before = f.calls.size();
		assertNull(f.ai.translate(TEXT + " later", false));
		assertEquals(before + 1, f.calls.size());
		assertFalse("later lines must skip the rejected field", f.calls.get(before).json().has("temperature"));
	}

	@Test public void configurationChangeRetriesTemperatureAgain() throws Exception
	{
		Fixture f = new Fixture(); f.backend = AiBackend.OPENAI;
		f.request(false).respond(400, temperatureRejected());
		assertEquals(2, f.calls.size());
		f.calls.get(1).respond(200, apiSuccess("首次成功"));
		f.switchTo("fixture-B");
		Pending next = f.request(false);
		assertTrue("a new model/endpoint must try temperature again", next.json().has("temperature"));
	}

	@Test public void ollamaHTTPErrorIsNotRetriedWithoutTemperature() throws Exception
	{
		Fixture f = new Fixture();
		Pending pending = f.request(false);
		assertFalse(pending.json().has("temperature"));
		pending.respond(400, temperatureRejected());
		assertEquals("Ollama never sends temperature, so there is nothing to retry", 1, f.calls.size());
		assertEquals(1, f.failures());
		assertEquals(0, f.ai.inFlightCount());
	}

	@Test public void blankCustomHeadersAddNothing() throws Exception
	{
		for (String spec : new String[] { "", "   ", null })
		{
			Fixture f = new Fixture(); f.extraHeaders = spec;
			Pending pending = f.request(false);
			assertNull(spec, pending.request.header("x-opencode-session"));
			assertNull(spec, pending.request.header("User-Agent"));
			assertTrue(spec, pending.request.headers().names().isEmpty());
		}
	}

	@Test public void apiTestConnectionCarriesConfiguredCustomHeaders() throws Exception
	{
		Fixture f = new Fixture(); f.backend = AiBackend.OPENAI;
		f.extraHeaders = "x-opencode-session: OSRSCN";
		AtomicReference<String> result = new AtomicReference<>();
		f.ai.testConnection((ok, msg) -> { if (ok) result.set(msg); });
		assertEquals(1, f.calls.size());
		Pending pending = f.calls.get(0);
		assertTrue(pending.request.url().toString().endsWith("/models"));
		assertEquals("OSRSCN", pending.request.header("x-opencode-session"));
		assertEquals("Bearer fictional-not-a-key", pending.request.header("Authorization"));
		pending.respond(200, ResponseBody.create(MediaType.parse("application/json"),
				"{\"data\":[{\"id\":\"fixture-A\"}]}"));
		assertEquals("连接成功，密钥有效", result.get());
	}

	@Test public void ollamaTestConnectionCarriesConfiguredCustomHeaders() throws Exception
	{
		Fixture f = new Fixture();
		f.extraHeaders = "x-opencode-session: OSRSCN";
		AtomicReference<String> result = new AtomicReference<>();
		f.ai.testConnection((ok, msg) -> { if (ok) result.set(msg); });
		assertEquals(1, f.calls.size());
		Pending pending = f.calls.get(0);
		assertTrue(pending.request.url().toString().endsWith("/api/tags"));
		assertEquals("OSRSCN", pending.request.header("x-opencode-session"));
		assertNull(pending.request.header("Authorization"));
		pending.respond(200, ResponseBody.create(MediaType.parse("application/json"), "{\"models\":[]}"));
		assertEquals("连接成功", result.get());
	}

	@Test public void apiTestConnectionFallsBackToMinimalCompletionWhenModelsUnavailable() throws Exception
	{
		Fixture f = new Fixture(); f.backend = AiBackend.OPENAI;
		f.extraHeaders = "x-opencode-session: OSRSCN; User-Agent: OSRSCN";
		f.connect();
		assertEquals(1, f.calls.size());
		Pending models = f.calls.get(0);
		assertEquals("GET", models.request.method());
		assertTrue(models.request.url().toString().endsWith("/models"));
		assertEquals("OSRSCN", models.request.header("x-opencode-session"));
		assertEquals("Bearer fictional-not-a-key", models.request.header("Authorization"));
		models.respond(404, ResponseBody.create(MediaType.parse("application/json"), "{\"error\":\"not found\"}"));
		// Step 2: a real one-token completion using the same URL/auth/header rules as translation.
		assertEquals(2, f.calls.size());
		Pending completion = f.calls.get(1);
		assertEquals("POST", completion.request.method());
		assertEquals("http://127.0.0.1:1/fixture/chat/completions", completion.request.url().toString());
		assertEquals("Bearer fictional-not-a-key", completion.request.header("Authorization"));
		assertEquals("OSRSCN", completion.request.header("x-opencode-session"));
		assertEquals("OSRSCN", completion.request.header("User-Agent"));
		JsonObject body = completion.json();
		assertEquals("fixture-A", body.get("model").getAsString());
		assertEquals(1, body.get("max_tokens").getAsInt());
		assertFalse(body.get("stream").getAsBoolean());
		assertEquals("hi", body.getAsJsonArray("messages").get(0).getAsJsonObject()
				.get("content").getAsString());
		completion.respond(200, ResponseBody.create(MediaType.parse("application/json"), "{\"choices\":[]}"));
		assertEquals(Boolean.TRUE, f.connected.get());
		assertEquals("连接成功，模型可用", f.connectMessage.get());
		assertEquals("the probe must report exactly once", 1, f.connectResults.get());
		// Isolation: a connectivity probe must not feed translation backoff / in-flight bookkeeping.
		assertEquals(0, f.failures());
		assertEquals(0, f.value("backoffUntil"));
		assertEquals(0, f.ai.inFlightCount());
	}

	@Test public void connectionProbesUseTheDerivedTestClientNotTheInjectedOne() throws Exception
	{
		Fixture f = new Fixture(); f.backend = AiBackend.OPENAI;
		f.connect();
		assertEquals(1, f.calls.size());
		assertTrue("step 1 must use the derived test client", f.calls.get(0).viaTestClient);
		f.calls.get(0).respond(404, ResponseBody.create(MediaType.parse("application/json"), "{}"));
		assertEquals(2, f.calls.size());
		assertTrue("step 2 must use the derived test client", f.calls.get(1).viaTestClient);
		f.calls.get(1).respond(200, ResponseBody.create(MediaType.parse("application/json"), "{}"));
		// Ordinary translation keeps the injected client, so the probe's isolation stays pinned.
		Pending translation = f.request(false);
		assertFalse("translation must keep the injected client", translation.viaTestClient);
	}

	@Test public void apiTestConnectionInvalidKeyStopsBeforeCompletionProbe() throws Exception
	{
		Fixture f = new Fixture(); f.backend = AiBackend.OPENAI;
		f.connect();
		f.calls.get(0).respond(401, ResponseBody.create(MediaType.parse("application/json"),
				"{\"error\":{\"message\":\"invalid key\"}}"));
		assertEquals(1, f.calls.size());
		assertEquals(Boolean.FALSE, f.connected.get());
		assertEquals("密钥无效或无权限 (HTTP 401)", f.connectMessage.get());
	}

	@Test public void apiTestConnectionNetworkFailureStopsBeforeCompletionProbe() throws Exception
	{
		Fixture f = new Fixture(); f.backend = AiBackend.OPENAI;
		f.connect();
		f.calls.get(0).failure();
		assertEquals(1, f.calls.size());
		assertEquals(Boolean.FALSE, f.connected.get());
		assertTrue(f.connectMessage.get().contains("fictional offline failure"));
	}

	@Test public void apiTestConnectionRateLimitStopsBeforeCompletionProbe() throws Exception
	{
		Fixture f = new Fixture(); f.backend = AiBackend.OPENAI;
		f.connect();
		f.calls.get(0).respond(429, ResponseBody.create(MediaType.parse("application/json"),
				"fixture rate limit"));
		assertEquals(1, f.calls.size());
		assertEquals(Boolean.FALSE, f.connected.get());
		assertEquals("连接正常但被限流 (HTTP 429)", f.connectMessage.get());
	}

	@Test public void apiTestConnectionCompletionErrorDetailIsReported() throws Exception
	{
		Fixture f = new Fixture(); f.backend = AiBackend.OPENAI;
		f.connect();
		f.calls.get(0).respond(404, ResponseBody.create(MediaType.parse("application/json"),
				"{\"error\":\"not found\"}"));
		assertEquals(2, f.calls.size());
		f.calls.get(1).respond(500, ResponseBody.create(MediaType.parse("application/json"),
				"{\"error\":{\"message\":\"fixture detail\"}}"));
		assertEquals("server errors are not retried", 2, f.calls.size());
		assertEquals(Boolean.FALSE, f.connected.get());
		assertTrue(f.connectMessage.get().contains("fixture detail"));
	}

	@Test public void apiTestConnectionWarnsWhenConfiguredModelIsNotListed() throws Exception
	{
		Fixture f = new Fixture(); f.backend = AiBackend.OPENAI;
		f.connect();
		f.calls.get(0).respond(200, ResponseBody.create(MediaType.parse("application/json"),
				"{\"data\":[{\"id\":\"some-other-model\"}]}"));
		assertEquals(1, f.calls.size());
		assertEquals(Boolean.TRUE, f.connected.get());
		assertEquals("连接成功，密钥有效（模型清单中未见 fixture-A，请确认模型名）", f.connectMessage.get());
	}

	@Test public void apiTestConnectionFallsBackWhenModelsReturnsNonListBody() throws Exception
	{
		Fixture f = new Fixture(); f.backend = AiBackend.OPENAI;
		f.connect();
		assertEquals(1, f.calls.size());
		f.calls.get(0).malformed(); // 200, but the body is not a model list ("{")
		assertEquals(2, f.calls.size());
		assertEquals("POST", f.calls.get(1).request.method());
		assertTrue(f.calls.get(1).request.url().toString().endsWith("/chat/completions"));
		f.calls.get(1).respond(200, ResponseBody.create(MediaType.parse("application/json"),
				"{\"choices\":[]}"));
		assertEquals(Boolean.TRUE, f.connected.get());
		assertEquals("连接成功，模型可用", f.connectMessage.get());
	}

	@Test public void apiTestConnectionCompletionRateLimitIsReported() throws Exception
	{
		Fixture f = new Fixture(); f.backend = AiBackend.OPENAI;
		f.connect();
		f.calls.get(0).respond(404, ResponseBody.create(MediaType.parse("application/json"),
				"{\"error\":\"not found\"}"));
		assertEquals(2, f.calls.size());
		f.calls.get(1).httpFailure(); // 429
		assertEquals(Boolean.FALSE, f.connected.get());
		assertEquals("连接正常但被限流 (HTTP 429)", f.connectMessage.get());
	}

	@Test public void apiTestConnectionCompletionErrorDetailIsCleanedAndCapped() throws Exception
	{
		Fixture f = new Fixture(); f.backend = AiBackend.OPENAI;
		f.connect();
		f.calls.get(0).respond(404, ResponseBody.create(MediaType.parse("application/json"),
				"{\"error\":\"not found\"}"));
		assertEquals(2, f.calls.size());
		StringBuilder detail = new StringBuilder("first line\\nsecond line\\n");
		while (detail.length() < 260)
		{
			detail.append('x');
		}
		f.calls.get(1).respond(500, ResponseBody.create(MediaType.parse("application/json"),
				"{\"error\":{\"message\":\"" + detail + "\"}}"));
		assertEquals("server errors are not retried", 2, f.calls.size());
		String message = f.connectMessage.get();
		assertEquals(Boolean.FALSE, f.connected.get());
		assertFalse(message.contains("\n"));
		assertFalse(message.contains("\r"));
		assertTrue(message.endsWith("…"));
		assertTrue(message.length() <= "连接失败：".length() + 200 + 1);
	}

	@Test public void apiTestCompletionRetriesWithoutTokenCapWhenProviderRejectsTheLimit() throws Exception
	{
		Fixture f = new Fixture(); f.backend = AiBackend.OPENAI;
		f.connect();
		f.calls.get(0).respond(404, ResponseBody.create(MediaType.parse("application/json"), "{}"));
		assertEquals(2, f.calls.size());
		assertEquals(1, f.calls.get(1).json().get("max_tokens").getAsInt());
		f.calls.get(1).respond(400, ResponseBody.create(MediaType.parse("application/json"),
				"{\"error\":{\"message\":\"Unsupported parameter: max_tokens\"}}"));
		assertEquals("a rejected output cap is retried exactly once", 3, f.calls.size());
		JsonObject retry = f.calls.get(2).json();
		assertFalse("the retry must omit the output cap", retry.has("max_tokens"));
		assertEquals("fixture-A", retry.get("model").getAsString());
		assertEquals("hi", retry.getAsJsonArray("messages").get(0).getAsJsonObject()
				.get("content").getAsString());
		f.calls.get(2).respond(200, ResponseBody.create(MediaType.parse("application/json"), "{}"));
		assertEquals(Boolean.TRUE, f.connected.get());
		assertEquals("连接成功，模型可用", f.connectMessage.get());
		assertEquals(1, f.connectResults.get());
	}

	@Test public void apiTestCompletionDoesNotRetryOnAuthFailure() throws Exception
	{
		Fixture f = new Fixture(); f.backend = AiBackend.OPENAI;
		f.connect();
		f.calls.get(0).respond(404, ResponseBody.create(MediaType.parse("application/json"), "{}"));
		f.calls.get(1).respond(401, ResponseBody.create(MediaType.parse("application/json"), "{}"));
		assertEquals("auth failures are definitive and never retried", 2, f.calls.size());
		assertEquals(Boolean.FALSE, f.connected.get());
		assertEquals("密钥无效或无权限 (HTTP 401)", f.connectMessage.get());
	}

	@Test public void apiTestCompletionRetriesOnlyOnceWhenTheUncappedProbeAlsoFails() throws Exception
	{
		Fixture f = new Fixture(); f.backend = AiBackend.OPENAI;
		f.connect();
		f.calls.get(0).respond(404, ResponseBody.create(MediaType.parse("application/json"), "{}"));
		f.calls.get(1).respond(400, ResponseBody.create(MediaType.parse("application/json"),
				"{\"error\":{\"message\":\"first attempt\"}}"));
		assertEquals(3, f.calls.size());
		f.calls.get(2).respond(400, ResponseBody.create(MediaType.parse("application/json"),
				"{\"error\":{\"message\":\"second attempt\"}}"));
		assertEquals("at most two probe attempts", 3, f.calls.size());
		assertEquals(Boolean.FALSE, f.connected.get());
		assertTrue(f.connectMessage.get().contains("second attempt"));
	}

	@Test public void apiTestConnectionSucceedsWithEmptyModelHint() throws Exception
	{
		Fixture f = new Fixture(); f.backend = AiBackend.OPENAI; f.model = "";
		f.connect();
		f.calls.get(0).respond(200, ResponseBody.create(MediaType.parse("application/json"),
				"{\"data\":[{\"id\":\"some-other-model\"}]}"));
		assertEquals(Boolean.TRUE, f.connected.get());
		assertEquals("连接成功，密钥有效（未填写 API 模型）", f.connectMessage.get());
	}

	@Test public void testClientDerivesThirtySecondReadTimeoutWithoutTouchingHttpClient() throws Exception
	{
		Fixture f = new Fixture();
		inject(f.ai, "testHttpClient", null);
		Method method = AiTranslator.class.getDeclaredMethod("testClient");
		method.setAccessible(true);
		OkHttpClient client = (OkHttpClient) method.invoke(f.ai);
		assertEquals(30_000, client.readTimeoutMillis());
		assertNotSame(field(f.ai, "httpClient"), client); // a derived copy, not the shared client
	}

	@Test public void invalidHeaderEntriesAreSkippedWithoutFailingTranslation() throws Exception
	{
		Fixture f = new Fixture();
		f.extraHeaders = "Bad Header: x; Authorization: y; x-opencode-session: OSRSCN";
		Pending pending = f.request(false);
		assertNull(pending.request.header("Bad Header"));
		assertNull(pending.request.header("Authorization"));
		assertEquals("OSRSCN", pending.request.header("x-opencode-session"));
		pending.success("header-safe");
		assertEquals("header-safe", f.ai.cached(TEXT));
		assertEquals(0, f.failures()); assertEquals(0, f.value("backoffUntil"));
	}

	@Test public void headerConfigChangeKeepsCacheAndResetsFailures() throws Exception
	{
		Fixture f = new Fixture(); f.extraHeaders = "x-opencode-session: OLD";
		f.request(false).success("cached translation");
		f.backoff();
		f.extraHeaders = "x-opencode-session: NEW";
		f.event("apiExtraHeaders");
		assertEquals(0, f.failures()); assertEquals(0, f.value("backoffUntil"));
		// Headers are not cache identity: the existing translation still hits without a new request.
		assertEquals("cached translation", f.ai.translate(TEXT, false));
		assertEquals(1, f.calls.size());
		String other = TEXT + " Another fictional gate.";
		assertNull(f.ai.translate(other, true));
		assertEquals(2, f.calls.size());
		assertEquals("NEW", f.calls.get(1).request.header("x-opencode-session"));
	}

	@Test public void aiInferenceUsesAPrivateDispatcherWithoutTouchingTheSharedClient() throws Exception
	{
		AiTranslator ai = new AiTranslator();
		// A real client, so the assertions see OkHttp's actual defaults (dispatcher 64 total / 5 per host).
		// One interceptor keeps the "chain preserved" assertion below meaningful (0 == 0 would be vacuous).
		OkHttpClient injected = new OkHttpClient.Builder()
				.addInterceptor(chain -> chain.proceed(chain.request()))
				.build();
		inject(ai, "httpClient", injected);
		Method inferenceClient = AiTranslator.class.getDeclaredMethod("inferenceClient", boolean.class);
		inferenceClient.setAccessible(true);
		OkHttpClient api = (OkHttpClient) inferenceClient.invoke(ai, true);
		OkHttpClient ollama = (OkHttpClient) inferenceClient.invoke(ai, false);
		// AI inference no longer rides RuneLite's dispatcher, so its 5-per-host cap cannot throttle it.
		assertNotSame(injected.dispatcher(), api.dispatcher());
		assertEquals(64, api.dispatcher().getMaxRequests());
		assertEquals(64, api.dispatcher().getMaxRequestsPerHost());
		// Both AI backends share that one private dispatcher, and it is cached rather than rebuilt.
		assertSame(api.dispatcher(), ollama.dispatcher());
		assertSame(api, inferenceClient.invoke(ai, true));
		// The injected client belongs to RuneLite: its dispatcher must come out untouched.
		assertEquals(5, injected.dispatcher().getMaxRequestsPerHost());
		assertEquals(64, injected.dispatcher().getMaxRequests());
		// Only the dispatcher differs: pool, executor and interceptor chain are still the injected ones.
		assertSame(injected.connectionPool(), api.connectionPool());
		assertSame(injected.connectionPool(), ollama.connectionPool());
		assertSame(injected.dispatcher().executorService(), api.dispatcher().executorService());
		assertSame(injected.dispatcher().executorService(), ollama.dispatcher().executorService());
		assertTrue(injected.interceptors().size() > 0);
		assertEquals(injected.interceptors().size(), api.interceptors().size());
		assertEquals(injected.interceptors().size(), ollama.interceptors().size());
		// Local inference keeps its cold-load budget while using the same private dispatcher.
		assertEquals(AiTranslator.OLLAMA_REQUEST_TIMEOUT_MS, ollama.readTimeoutMillis());
		assertEquals(AiTranslator.OLLAMA_REQUEST_TIMEOUT_MS, ollama.callTimeoutMillis());
	}
}
