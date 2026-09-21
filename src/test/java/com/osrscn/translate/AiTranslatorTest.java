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
			inject(ai, "httpClient", new OkHttpClient()
			{
				@Override public Call newCall(Request request)
				{
					Pending pending = new Pending(request);
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
			});
			inject(ai, "ollamaHttpClient", field(ai, "httpClient"));
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
		String model() throws Exception
		{
			Buffer buffer = new Buffer(); request.body().writeTo(buffer);
			return new Gson().fromJson(buffer.readUtf8(), JsonObject.class).get("model").getAsString();
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
}
