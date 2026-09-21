package com.osrscn.translate;

import static org.junit.Assert.*;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.FileAppender;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.osrscn.AiBackend;
import com.osrscn.OsrscnConfig;
import com.osrscn.OsrscnPlugin;
import com.osrscn.glyph.GlyphService;
import com.osrscn.ui.DialogueHistory;
import com.osrscn.ui.OsrscnPanel;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Consumer;
import java.util.stream.Stream;
import net.runelite.api.Client;
import net.runelite.client.RuneLite;
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
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.LoggerFactory;

/** Real production callbacks and file sinks; all content and configuration are fictional. */
public class AiTranslatorPrivacyTest
{
	private static final String EN = "Fictional violet otters whisper beside the moon gate.";
	private static final String ZH = "虚构紫獭在月门旁低语。";
	private static final String GAME = "The fictional crystal guardian opens the northern gate.";
	private static final String GAME_ZH = "虚构水晶守卫打开北门。";
	private static final String CANARY = "R24 DEBUG file sink is active";
	private Path root;
	private Path logFile;
	private Logger logger;
	private Level oldLevel;
	private boolean oldAdditive;
	private FileAppender<ILoggingEvent> appender;

	@Before public void isolateAndStartRealDebugFile() throws Exception
	{
		String base = System.getProperty("osrscn.test.fixtureRoot");
		assertNotNull("Test JVM must be isolated before class initialization", base);
		Path home = new File(base).toPath().toRealPath();
		assertEquals(home, new File(System.getProperty("user.home")).toPath().toRealPath());
		assertTrue(RuneLite.RUNELITE_DIR.toPath().toAbsolutePath().startsWith(home));
		assertTrue(new File(System.getProperty("logback.configurationFile")).toPath().startsWith(home));
		root = Files.createTempDirectory(home, "privacy-");
		logFile = root.resolve("plugin-debug.log");
		LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
		logger = context.getLogger("com.osrscn");
		oldLevel = logger.getLevel(); oldAdditive = logger.isAdditive();
		logger.setLevel(Level.DEBUG); logger.setAdditive(false);
		PatternLayoutEncoder encoder = new PatternLayoutEncoder();
		encoder.setContext(context); encoder.setCharset(StandardCharsets.UTF_8);
		encoder.setPattern("%level %logger %msg%n%ex{full}"); encoder.start();
		appender = new FileAppender<>(); appender.setContext(context); appender.setName("R24-file");
		appender.setFile(logFile.toString()); appender.setAppend(false);
		appender.setEncoder(encoder); appender.setImmediateFlush(true); appender.start();
		logger.addAppender(appender);
		assertTrue(appender.isStarted());
		assertTrue(LoggerFactory.getLogger(AiTranslator.class).isDebugEnabled());
		logger.debug(CANARY);
		assertTrue(logText().contains("DEBUG com.osrscn " + CANARY));
	}

	@After public void closeSink()
	{
		if (appender != null) { logger.detachAppender(appender); appender.stop(); }
		if (logger != null) { logger.setLevel(oldLevel); logger.setAdditive(oldAdditive); }
		// Keep fixture bytes, including failing logs, for the run's evidence archive.
	}

	private static void inject(Object object, String name, Object value) throws Exception
	{
		Field field = object.getClass().getDeclaredField(name);
		field.setAccessible(true); field.set(object, value);
	}

	private String logText() throws IOException { return Files.readString(logFile, StandardCharsets.UTF_8); }

	private void noPlayerBytes() throws IOException
	{
		assertTrue(logText().contains(CANARY));
		try (Stream<Path> paths = Files.walk(root))
		{
			for (Path path : (Iterable<Path>) paths.filter(Files::isRegularFile)::iterator)
			{
				String bytes = Files.readString(path, StandardCharsets.UTF_8);
				assertFalse("Source persisted in " + path.getFileName(), bytes.contains(EN));
				assertFalse("Normalized source persisted in " + path.getFileName(), bytes.contains(TranslationStore.normalize(EN)));
				assertFalse("Translation persisted in " + path.getFileName(), bytes.contains(ZH));
			}
		}
	}

	private final class Fixture
	{
		final File dir = Files.createTempDirectory(root, "data-").toFile();
		final AiTranslator ai = new AiTranslator();
		final MissingCollector collector = new MissingCollector();
		final Translator translator = new Translator();
		final OsrscnPlugin plugin = new OsrscnPlugin();
		final List<Pending> calls = new ArrayList<>();
		String model = "fictional-A";
		AiBackend backend = AiBackend.OLLAMA;
		boolean enabled = true;
		Consumer<Request> creation;
		Consumer<Pending> enqueue;

		Fixture() throws Exception
		{
			OsrscnConfig config = (OsrscnConfig) Proxy.newProxyInstance(getClass().getClassLoader(),
					new Class<?>[] {OsrscnConfig.class}, (p, m, args) ->
					{
						switch (m.getName())
						{
							case "useLocalAi": return enabled;
							case "collectMissing": return true;
							case "aiBackend": return backend;
							case "ollamaModel": case "apiModel": return model;
							case "ollamaUrl": case "apiUrl": return "http://127.0.0.1:1/fictional";
							case "apiKey": return "fictional-placeholder";
							case "aiPaceMs": return 0;
							case "aiConcurrency": return 8;
							case "debugMonitor": return false;
							default: throw new AssertionError("Unexpected config read: " + m.getName());
						}
					});
			ScheduledExecutorService executor = (ScheduledExecutorService) Proxy.newProxyInstance(
					getClass().getClassLoader(), new Class<?>[] {ScheduledExecutorService.class}, (p,m,args) ->
					{
						if (!m.getName().equals("scheduleWithFixedDelay")) throw new AssertionError(m.getName());
						return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {ScheduledFuture.class},
								(f, method, values) -> { if (method.getName().equals("cancel")) return true;
									throw new AssertionError(method.getName()); });
					});
			inject(ai, "dir", dir); inject(ai, "config", config); inject(ai, "gson", new Gson());
			inject(ai, "httpClient", new OkHttpClient()
			{
				@Override public Call newCall(Request request)
				{
					if (creation != null) creation.accept(request);
					Pending pending = new Pending(request);
					pending.call = (Call) Proxy.newProxyInstance(getClass().getClassLoader(),
							new Class<?>[] {Call.class}, (p,m,args) ->
							{
								if (m.getName().equals("request")) return request;
								if (!m.getName().equals("enqueue")) throw new AssertionError("No real transport: " + m.getName());
								pending.callback = (Callback) args[0]; calls.add(pending);
								if (enqueue != null) enqueue.accept(pending);
								return null;
							});
					return pending.call;
				}
			});
			Field transport = AiTranslator.class.getDeclaredField("httpClient");
			transport.setAccessible(true); inject(ai, "ollamaHttpClient", transport.get(ai));
			inject(collector, "dir", dir); inject(collector, "config", config); inject(collector, "executor", executor);
			inject(translator, "ai", ai); inject(translator, "missing", collector);
			inject(translator, "store", new TranslationStore()); // Empty maps; never load real data.
			inject(translator, "glyph", new GlyphService()
			{
				@Override public String toImgTags(String text, int color, int width, int size) { return "<img=fixture>"; }
			});
			inject(translator, "client", Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Client.class},
					(p,m,args) ->
					{
						if (m.getName().equals("getPlayers")) return Collections.emptyList();
						if (m.getReturnType() == int.class) return 0;
						if (m.getReturnType() == boolean.class) return false;
						return null;
					}));
			inject(plugin, "aiTranslator", ai); inject(plugin, "config", config);
			Class<?> unsafe = Class.forName("sun.misc.Unsafe");
			Field singleton = unsafe.getDeclaredField("theUnsafe"); singleton.setAccessible(true);
			Object panel = unsafe.getMethod("allocateInstance", Class.class).invoke(singleton.get(null), OsrscnPanel.class);
			inject(panel, "config", config); inject(panel, "history", new DialogueHistory()); inject(plugin, "panel", panel);
		}

		void switchModel()
		{
			model = "fictional-B";
			ConfigChanged event = new ConfigChanged(); event.setGroup(OsrscnConfig.GROUP);
			event.setKey("ollamaModel"); plugin.onConfigChanged(event);
		}

		Pending request(String text, boolean persist)
		{
			int count = calls.size();
			assertNull(translator.renderChat(text, 0xffffff, 200, 14, true, persist));
			assertEquals(count + 1, calls.size()); return calls.get(count);
		}
		File file() { return new File(dir, "ai_" + model + ".tsv"); }
		void flush() { collector.flushPending(); collector.stop(); }
	}

	private static final class Pending
	{
		final Request request;
		Call call;
		Callback callback;
		Pending(Request request) { this.request = request; }
		void respond(int code, ResponseBody body) throws IOException
		{
			callback.onResponse(call, new Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
					.code(code).message("fictional").body(body).build());
		}
		void success(String zh) throws IOException { respond(200, json(reply(zh))); }
	}

	private static String reply(String zh)
	{
		JsonObject message = new JsonObject(); message.addProperty("content", zh);
		JsonObject root = new JsonObject(); root.add("message", message); return root.toString();
	}
	private static ResponseBody json(String text) { return ResponseBody.create(MediaType.parse("application/json"), text); }
	private static RuntimeException bodyException() { return new IllegalStateException(EN, new IOException(ZH)); }
	private static String malformed() { return "{\"" + EN + ZH + "\":[}"; }

	@Test public void playerSuccessAndCacheHitStayInMemory() throws Exception
	{
		Fixture f = new Fixture(); f.request(EN, false).success(ZH); f.flush();
		assertEquals(ZH, f.ai.cached(TranslationStore.normalize(EN)));
		assertEquals(1, f.ai.sessionCount()); assertTrue(f.ai.recent().get(0).contains(ZH));
		f.enabled = false;
		assertNotNull(f.translator.renderChat(EN, 0xffffff, 200, 14, true, false));
		assertNotNull(f.translator.renderChatCached(EN, 0, 200, 14));
		assertEquals(1, f.calls.size()); assertFalse(f.file().exists()); noPlayerBytes();
	}

	@Test public void openAiPlayerSuccessStaysInMemory() throws Exception
	{
		Fixture f = new Fixture(); f.backend = AiBackend.OPENAI;
		Pending p = f.request(EN, false);
		p.respond(200, json("{\"choices\":[" + reply(ZH) + "]}"));
		assertEquals(ZH, f.ai.cached(TranslationStore.normalize(EN))); f.flush(); noPlayerBytes();
	}

	@Test public void playerTransportFailureDoesNotLogExceptionBody() throws Exception
	{
		Fixture f = new Fixture(); Pending p = f.request(EN, false);
		p.callback.onFailure(p.call, new IOException(EN + ZH, new IOException(ZH)));
		assertEquals(0, f.ai.inFlightCount()); assertTrue(logText().contains("request failed")); f.flush(); noPlayerBytes();
	}

	@Test public void playerDispatchCreationFailureDoesNotLogRequestOrCause() throws Exception
	{
		Fixture f = new Fixture(); f.creation = request ->
		{
			Buffer buffer = new Buffer();
			try { request.body().writeTo(buffer); } catch (IOException e) { throw new AssertionError(e); }
			throw new IllegalArgumentException(buffer.readUtf8(), new IOException(ZH));
		};
		assertNull(f.translator.renderChat(EN, 0, 200, 14, true, false));
		assertEquals(0, f.ai.inFlightCount()); assertTrue(logText().contains("dispatch failed")); noPlayerBytes();
	}

	@Test public void playerEnqueueFailureDoesNotLogExceptionChain() throws Exception
	{
		Fixture f = new Fixture(); f.enqueue = pending -> { throw bodyException(); };
		f.request(EN, false); assertEquals(0, f.ai.inFlightCount()); noPlayerBytes();
	}

	@Test public void playerGsonSyntaxFailureDoesNotLogJsonPath() throws Exception
	{
		Fixture f = new Fixture(); f.request(EN, false).respond(200, json(malformed()));
		assertTrue(logText().contains("parse failed")); assertEquals(0, f.ai.inFlightCount()); noPlayerBytes();
	}

	@Test public void playerGsonSchemaFailureKeepsSafeDiagnostic() throws Exception
	{
		Fixture f = new Fixture(); f.request(EN, false).respond(200, json("{\"message\":{\"content\":{\"" + EN + "\":\"" + ZH + "\"}}}"));
		assertTrue(logText().contains("parse failed")); noPlayerBytes();
	}

	@Test public void playerReadFailureDoesNotLogBodyOrCause() throws Exception
	{
		Fixture f = new Fixture(); Pending p = f.request(EN, false);
		BufferedSource source = Okio.buffer(new ForwardingSource(new Buffer())
		{
			@Override public long read(Buffer sink, long byteCount) throws IOException
			{
				assertFalse(Thread.holdsLock(f.ai)); throw new IOException(EN, new IOException(ZH));
			}
		});
		p.respond(200, ResponseBody.create(null, -1, source));
		assertEquals(0, f.ai.inFlightCount()); noPlayerBytes();
	}

	private ResponseBody closeFailure(Fixture f, boolean committed)
	{
		return new ResponseBody()
		{
			final BufferedSource source = new Buffer().writeUtf8(reply(ZH));
			@Override public MediaType contentType() { return MediaType.parse("application/json"); }
			@Override public long contentLength() { return -1; }
			@Override public BufferedSource source() { return source; }
			@Override public void close()
			{
				assertFalse(Thread.holdsLock(f.ai));
				if (committed) assertEquals(ZH, f.ai.cached(TranslationStore.normalize(EN)));
				throw bodyException();
			}
		};
	}

	@Test public void playerCloseFailureAfterSuccessKeepsMemoryAndNoFile() throws Exception
	{
		Fixture f = new Fixture(); f.request(EN, false).respond(200, closeFailure(f, true));
		assertEquals(1, f.ai.sessionCount()); assertEquals(0, f.ai.inFlightCount()); noPlayerBytes();
	}

	@Test public void playerHttpErrorAndCloseFailureDoNotLogResponse() throws Exception
	{
		Fixture f = new Fixture(); f.request(EN, false).respond(429, closeFailure(f, false));
		assertTrue(logText().contains("HTTP 429")); assertEquals(0, f.ai.sessionCount()); noPlayerBytes();
	}

	@Test public void stalePlayerParseFailureCannotLeakOrReleaseCurrentKey() throws Exception
	{
		Fixture f = new Fixture(); Pending old = f.request(EN, false); f.switchModel();
		Pending current = f.request(EN, false); old.respond(200, json(malformed()));
		assertEquals(1, f.ai.inFlightCount()); assertNull(f.ai.cached(TranslationStore.normalize(EN)));
		current.success(ZH); assertEquals(1, f.ai.sessionCount()); noPlayerBytes();
	}

	@Test public void stalePlayerTransportFailureCannotLeakOrReleaseCurrentKey() throws Exception
	{
		Fixture f = new Fixture(); Pending old = f.request(EN, false); f.switchModel();
		Pending current = f.request(EN, false); old.callback.onFailure(old.call, new IOException(EN + ZH));
		assertEquals(1, f.ai.inFlightCount()); current.success(ZH); noPlayerBytes();
	}

	@Test public void stalePlayerSuccessAndCloseCannotLeak() throws Exception
	{
		Fixture f = new Fixture(); Pending old = f.request(EN, false); f.switchModel();
		Pending current = f.request(EN, false); old.respond(200, closeFailure(f, false));
		assertEquals(0, f.ai.sessionCount()); assertEquals(1, f.ai.inFlightCount());
		current.success(ZH); noPlayerBytes();
	}

	@Test public void gameSuccessStillLogsPersistsAndCollects() throws Exception
	{
		Fixture f = new Fixture(); f.request(GAME, true).success(GAME_ZH); f.flush();
		assertTrue(logText().contains(TranslationStore.normalize(GAME))); assertTrue(logText().contains(GAME_ZH));
		assertTrue(Files.readString(f.file().toPath()).contains(GAME_ZH));
		assertTrue(Files.readString(f.collector.missingFile().toPath()).contains(GAME));
		assertNotNull(f.translator.renderChat(GAME, 0, 200, 14, true, true)); noPlayerBytes();
	}

	@Test public void gameExceptionDiagnosticsStillIncludeDetails() throws Exception
	{
		Fixture f = new Fixture(); f.request(GAME, true).respond(200, json(malformed()));
		assertTrue(logText().contains(EN)); assertTrue(logText().contains(ZH));
		Fixture transport = new Fixture(); Pending p = transport.request(GAME, true);
		p.callback.onFailure(p.call, new IOException("fictional game network detail"));
		assertTrue(logText().contains("fictional game network detail"));
		Fixture dispatch = new Fixture(); dispatch.creation = request -> { throw bodyException(); };
		assertNull(dispatch.translator.renderChat(GAME, 0, 200, 14, true, true));
		assertTrue(logText().contains("Caused by: java.io.IOException: " + ZH));
	}

	@Test public void playerMissDoesNotCollectWhenAiDisabled() throws Exception
	{
		Fixture f = new Fixture(); f.enabled = false;
		assertNull(f.translator.renderChat(EN, 0, 200, 14, true, false));
		assertNull(f.translator.renderChat("1|" + EN, 0, 200, 14, true, false));
		f.flush(); assertEquals(0, f.calls.size()); noPlayerBytes();
	}

	@Test public void playerThenGameSameTextPromotesOnlyOnGameCall() throws Exception
	{
		Fixture f = new Fixture(); f.request(EN, false).success(ZH); f.flush(); noPlayerBytes();
		f.enabled = false;
		assertNotNull(f.translator.renderChat(EN, 0, 200, 14, true, true));
		assertTrue(Files.readString(f.file().toPath()).contains(ZH)); assertEquals(1, f.calls.size());
	}

	@Test public void gameThenPlayerCacheHitDoesNotChangePersistentFiles() throws Exception
	{
		Fixture f = new Fixture(); f.request(GAME, true).success(GAME_ZH); f.flush();
		byte[] cache = Files.readAllBytes(f.file().toPath());
		byte[] missing = Files.readAllBytes(f.collector.missingFile().toPath()); String logs = logText();
		assertNotNull(f.translator.renderChat(GAME, 0, 200, 14, true, false)); f.flush();
		assertArrayEquals(cache, Files.readAllBytes(f.file().toPath()));
		assertArrayEquals(missing, Files.readAllBytes(f.collector.missingFile().toPath())); assertEquals(logs, logText());
	}
}
