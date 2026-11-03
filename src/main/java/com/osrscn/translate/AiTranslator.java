package com.osrscn.translate;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.osrscn.AiBackend;
import com.osrscn.OsrscnConfig;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Dispatcher;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Asynchronous AI fallback for text not found in the lookup table. Two backends (see {@code aiBackend}):
 * local Ollama ({@code /api/chat}) and any OpenAI-compatible online API ({@code /chat/completions}).
 *
 * <p>{@link #translate} never blocks: on a miss it returns {@code null} and fires a background
 * request; the result lands in the cache and is picked up by a later call.
 */
@Slf4j
@Singleton
public class AiTranslator
{
	// Includes local model loading and inference; never an unlimited read or whole-call wait.
	public static final long OLLAMA_REQUEST_TIMEOUT_MS = 60_000;
	private static final MediaType JSON = MediaType.parse("application/json");
	private static final Pattern THINK = Pattern.compile("(?s)<think>.*?</think>");
	// Value labels (bank/GE totals "(3.6B)", "(500)") change with every gp move, so they never form a
	// stable cross-session cache hit - keep them out of the on-disk reflow cache. Doses like "(4)" pass.
	private static final Pattern CACHE_DYNAMIC = Pattern.compile("\\([\\d.,]+[KkMmBb]\\)|\\(\\d[\\d.,]{2,}\\)");
	// Number-templated messages (kill counts "1,290", prices, durations "4:58", charges) differ by a value
	// every time, so a cached instance never matches the next - persisting them only bloats the file. The
	// live in-memory cache still serves them within a session; we just don't write them to disk.
	private static final Pattern CACHE_NUMTEMPLATE = Pattern.compile(
			"\\d,\\d|\\d:\\d\\d|kill count is:|completion count is:|Actively traded price|charges remaining"
			+ "|Personal best|Buy limit:|Guide price|You received:|received a drop:|You healed"
			+ "|Delve level:|Total duration|Fight duration|Value:\\s*\\d|Level:\\s*\\d|Wave:?\\s*\\d|Rank\\s+\\d");

	private static final String SYSTEM_PROMPT =
			"You are translating Old School RuneScape (OSRS) game text to Simplified Chinese.\n"
			+ "Rules:\n"
			+ "1. Concise, natural game-style Chinese. This is in-game text, not literature.\n"
			+ "2. Keep proper nouns without established translations in English.\n"
			+ "3. Preserve ALL tags exactly as-is: <br>, <colNum0>, </col>, [player name], [player 1], "
			+ "<osrscn-name-0>, [milady/sirrah] etc. Keep every <colNumN> placeholder in place around the "
			+ "same words it wraps in the source. Never translate placeholders and never add "
			+ "tags that are not in the source.\n"
			+ "4. Output ONLY the translation, nothing else.";

	@Inject
	private OkHttpClient httpClient;
	private OkHttpClient ollamaHttpClient; // shares the private AI dispatcher and the injected connection pool
	// AI inference gets a dispatcher of its own: the injected client's per-host limit (5) belongs to
	// RuneLite and is shared with every other plugin, so translating through it caps this plugin at 5
	// concurrent requests no matter what aiConcurrency says. The private dispatcher reuses the injected
	// client's executor service, so it creates no threads of its own and needs no shutdown. Both fields
	// are lazily built under this monitor, like ollamaHttpClient.
	private static final int AI_MAX_REQUESTS_PER_HOST = 64;
	private OkHttpClient apiInferenceClient;
	private Dispatcher inferenceDispatcher;
	// Lazily derived from httpClient for connectivity checks. Volatile and deliberately unlocked:
	// testConnection runs on the EDT and must not take this monitor (ensureLoaded/persistToDisk hold it
	// across disk IO). A racing first call may build a second client, which is benign - both share the
	// injected dispatcher and connection pool, and the last instance written simply wins.
	private volatile OkHttpClient testHttpClient;

	@Inject
	private OsrscnConfig config;

	@Inject
	private Gson gson;

	@Inject
	private java.util.concurrent.ScheduledExecutorService executor;
	private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();
	private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
	// Keys known to be on disk. A cache hit re-requested with persist=true gets written on the spot:
	// the 860 settle gate fires its first requests persist=false (frame unsettled) and replays them
	// persist=true once stable - without this, guide translations would never survive a restart.
	private final Set<String> persisted = ConcurrentHashMap.newKeySet();
	private final File dir = new File(RuneLite.RUNELITE_DIR, "osrscn");
	private String loadedModel; // model whose cache is currently in memory (per-model on disk)
	private long generation; // guarded by this; model round trips never reuse request ownership
	private File cacheFile;
	private volatile long lastDispatch;

	// Custom request headers (apiExtraHeaders) re-parsed only when the configured text changes. The
	// pair is written map-then-spec and replaced wholesale, so no monitor is needed (testConnection
	// may read it from the EDT).
	private volatile String parsedHeadersSpec;
	private volatile Map<String, String> parsedHeaders = Collections.emptyMap();

	// Back off when the backend keeps failing (Ollama down, API unreachable) instead of
	// re-firing a request on every lookup miss.
	private static final int BACKOFF_AFTER_FAILURES = 3;
	private static final long BACKOFF_BASE_MS = 5_000;
	private static final long BACKOFF_MAX_MS = 300_000;
	private final java.util.concurrent.atomic.AtomicInteger failStreak = new java.util.concurrent.atomic.AtomicInteger();
	private volatile long backoffUntil;

	// monitor stats
	private final java.util.concurrent.atomic.AtomicInteger sessionCount = new java.util.concurrent.atomic.AtomicInteger();
	private final java.util.Deque<String> recent = new java.util.concurrent.ConcurrentLinkedDeque<>();

	/** Serializes player-mode revocation with admission of a fresh asynchronous request. */
	public static final class RequestPermit
	{
		private final java.util.function.BooleanSupplier allowed;
		private final RequestPermit owner;
		private boolean revoked;

		public RequestPermit(java.util.function.BooleanSupplier allowed)
		{
			this.allowed = allowed;
			owner = this;
		}

		/** Add a delivery's exact mode check while sharing its mode's cancellation boundary. */
		public RequestPermit(RequestPermit parent, java.util.function.BooleanSupplier allowed)
		{
			owner = parent.owner;
			this.allowed = () -> parent.allowed.getAsBoolean() && allowed.getAsBoolean();
		}

		public void revoke()
		{
			synchronized (owner) { owner.revoked = true; }
		}

		private void dispatch(Runnable action)
		{
			synchronized (owner)
			{
				if (!owner.revoked && allowed.getAsBoolean() && !owner.revoked) action.run();
			}
		}
	}

	/** @return cached translation, or null if disabled / still pending (request fired on miss). */
	public String translate(String english)
	{
		return translate(english, true);
	}

	/**
	 * Cache-only peek: no request is dispatched and nothing is written, so callers can skip expensive
	 * pre-processing when the answer is already known and re-ask through {@link #translate} for it.
	 *
	 * @return the cached translation, or null if this text has never been translated
	 */
	public synchronized String cached(String english)
	{
		if (english == null || english.isEmpty())
		{
			return null;
		}
		ensureLoaded();
		return cache.get(english);
	}

	/**
	 * @param persist whether a fresh translation is written to the on-disk cache. Player chat passes
	 *                {@code false}: player slang rarely recurs verbatim, so persisting it only bloats
	 *                the cache file and slows start-up loading. It is still cached in memory.
	 * @return cached translation (served even when AI is disabled), or null when not cached and AI is
	 *         disabled, or null while a fresh request is still pending (request fired on miss).
	 */
	public String translate(String english, boolean persist)
	{
		return translate(english, persist, null);
	}

	/** Player callers retain their mode's permit through preprocessing and every per-line request. */
	public String translate(String english, boolean persist, RequestPermit permit)
	{
		if (english == null || english.isEmpty())
		{
			return null;
		}
		final long requestedGeneration;
		synchronized (this)
		{
			ensureLoaded();
			String cached = cache.get(english);
			if (cached != null)
			{
				// AI off remains cache-only, including promotion of an existing memory-only entry.
				if (persist && isPersistable(english) && persisted.add(english))
				{
					persistToDisk(english, cached);
				}
				return cached;
			}
			if (!config.useLocalAi()) return null;
			requestedGeneration = generation;
		}
		// Never acquire a permit owner while holding this monitor. Admission order is owner -> this.
		if (permit == null) dispatch(english, persist, requestedGeneration);
		else permit.dispatch(() -> dispatch(english, persist, requestedGeneration));
		return null;
	}

	private synchronized void dispatch(String english, boolean persist, long requestedGeneration)
	{
		ensureLoaded();
		if (requestedGeneration != generation || !config.useLocalAi() || cache.containsKey(english))
		{
			return; // model/config changed or another callback filled this miss while acquiring admission
		}
		if (System.currentTimeMillis() < backoffUntil)
		{
			return; // backend keeps failing: wait out the backoff window
		}
		// rate limit so Ollama doesn't saturate the GPU and stall the game (both configurable)
		if (inFlight.size() >= Math.max(1, config.aiConcurrency())
				|| System.currentTimeMillis() - lastDispatch < config.aiPaceMs())
		{
			return;
		}
		if (!inFlight.add(english))
		{
			return; // already requested
		}
		lastDispatch = System.currentTimeMillis();
		RequestState state = new RequestState(generation, loadedModel);
		try
		{
			request(english, persist, state);
		}
		catch (RuntimeException e)
		{
			if (isCurrent(state))
			{
				recordFailure();
				finish(english, state);
			}
			if (persist) log.warn("OSRSCN AI dispatch failed", e);
			else log.warn("OSRSCN AI dispatch failed (non-persistent request): {}", e.getClass().getSimpleName());
		}
	}

	public int cacheSize()
	{
		return cache.size();
	}

	public int inFlightCount()
	{
		return inFlight.size();
	}

	public int sessionCount()
	{
		return sessionCount.get();
	}

	/** Most recent translations (newest first), for the monitor panel. */
	public java.util.List<String> recent()
	{
		return new java.util.ArrayList<>(recent);
	}

	/** Read the disk cache off the game thread so the first lookup miss doesn't stall the client. */
	public void preloadAsync()
	{
		executor.execute(this::ensureLoaded);
	}

	/** Each model keeps its own disk cache, so switching models doesn't return the old one's output. */
	private synchronized void ensureLoaded()
	{
		String model = activeModel();
		if (model.equals(loadedModel))
		{
			return;
		}
		configurationChanged();
		loadedModel = model;
		//noinspection ResultOfMethodCallIgnored
		dir.mkdirs();
		cacheFile = new File(dir, "ai_" + model.replaceAll("[^a-zA-Z0-9._-]", "_") + ".tsv");
		if (cacheFile.exists())
		{
			try (BufferedReader r = Files.newBufferedReader(cacheFile.toPath(), StandardCharsets.UTF_8))
			{
				String line;
				while ((line = r.readLine()) != null)
				{
					int t = line.indexOf('\t');
					if (t > 0 && t < line.length() - 1)
					{
						String k = line.substring(0, t);
						String v = line.substring(t + 1);
						cache.putIfAbsent(k, v);
						persisted.add(k);
						// Entries written before a normalize() change keep their old key; re-normalize on
						// load so the current query key still hits and the AI isn't re-run for them.
						String nk = TranslationStore.normalize(k);
						if (!nk.equals(k))
						{
							cache.putIfAbsent(nk, v);
							persisted.add(nk);
						}
					}
				}
				log.info("OSRSCN AI cache loaded: {} entries ({})", cache.size(), cacheFile.getName());
			}
			catch (Exception e)
			{
				log.warn("OSRSCN: failed to load AI cache", e);
			}
		}
	}

	/** Invalidate synchronously on model/connection events, even A -> B -> A without a lookup. */
	public synchronized void configurationChanged()
	{
		invalidateRequests();
		loadedModel = null;
		cacheFile = null;
		cache.clear();
		persisted.clear();
	}

	private void invalidateRequests()
	{
		generation++;
		inFlight.clear();
		failStreak.set(0);
		backoffUntil = 0;
		lastDispatch = 0;
	}

	/**
	 * Re-read the custom request headers on the next request. Headers are presentation, not identity:
	 * cached translations and in-flight requests stay valid, so only the parsed snapshot and a stale
	 * backoff (a previous 4xx may have been caused by the old headers) are cleared.
	 */
	public void requestHeadersChanged()
	{
		parsedHeadersSpec = null;
		parsedHeaders = Collections.emptyMap();
		failStreak.set(0);
		backoffUntil = 0;
	}

	private static final class RequestState
	{
		final long generation;
		final String model;
		boolean completed;

		RequestState(long generation, String model)
		{
			this.generation = generation;
			this.model = model;
		}
	}

	/** Called under this monitor, including a recheck after potentially reentrant config getters. */
	private boolean isCurrent(RequestState state)
	{
		return !state.completed && state.generation == generation && state.model.equals(loadedModel)
				&& state.model.equals(activeModel()) && state.generation == generation && !state.completed;
	}

	private void finish(String english, RequestState state)
	{
		if (isCurrent(state))
		{
			inFlight.remove(english);
			lastDispatch = System.currentTimeMillis();
		}
		state.completed = true;
	}

	/**
	 * Gate for the on-disk reflow cache only - never the in-memory {@code cache}, which always serves live
	 * on-screen text (cache.put is unconditional). Rejects the two categories that can never be a stable
	 * cross-session hit and only bloat the cache / pollute reflow submissions: player-authored CJK content
	 * (game English is never CJK), dynamic value labels, and number-templated messages that never recur.
	 * Deliberately does NOT apply MissingCollector's fragment / MAX_LEN / WORDY rules - those legitimately
	 * get translated live (e.g. wrapped info-box lines), and dropping them from disk would make AI-off
	 * users lose the translation next session.
	 */
	private static boolean isPersistable(String english)
	{
		if (english == null)
		{
			return false;
		}
		for (int i = 0; i < english.length(); i++)
		{
			if (english.charAt(i) >= 0x2E80)
			{
				return false; // CJK = player-created content (inventory-setup / bank-tab names)
			}
		}
		return !CACHE_DYNAMIC.matcher(english).find() && !CACHE_NUMTEMPLATE.matcher(english).find();
	}

	private synchronized void persistToDisk(String english, String zh)
	{
		if (cacheFile == null || english.indexOf('\t') >= 0 || english.indexOf('\n') >= 0
				|| zh.indexOf('\n') >= 0)
		{
			return; // skip entries that would corrupt the line-based file
		}
		try (FileWriter w = new FileWriter(cacheFile, StandardCharsets.UTF_8, true))
		{
			w.write(english + "\t" + zh + "\n");
		}
		catch (IOException e)
		{
			log.debug("OSRSCN: failed to append AI cache", e);
		}
	}

	public synchronized void clearCache()
	{
		ensureLoaded(); // configuration invalidation may have cleared the current file ownership
		invalidateRequests();
		cache.clear();
		persisted.clear();
		if (cacheFile != null)
		{
			//noinspection ResultOfMethodCallIgnored
			cacheFile.delete();
		}
	}

	/**
	 * Private dispatcher for AI inference. Reuses the injected client's executor service so no extra
	 * pool is created and nothing needs shutting down; only the per-host gate is raised, and only for
	 * this plugin. Called under this monitor.
	 */
	private Dispatcher inferenceDispatcher()
	{
		if (inferenceDispatcher == null)
		{
			inferenceDispatcher = new Dispatcher(httpClient.dispatcher().executorService());
			inferenceDispatcher.setMaxRequests(AI_MAX_REQUESTS_PER_HOST);
			inferenceDispatcher.setMaxRequestsPerHost(AI_MAX_REQUESTS_PER_HOST);
		}
		return inferenceDispatcher;
	}

	/** Called under this monitor; only local inference gets the cold-load budget. */
	private OkHttpClient inferenceClient(boolean api)
	{
		if (api)
		{
			if (apiInferenceClient == null)
			{
				apiInferenceClient = httpClient.newBuilder()
						.dispatcher(inferenceDispatcher())
						.build();
			}
			return apiInferenceClient;
		}
		if (ollamaHttpClient == null)
		{
			ollamaHttpClient = httpClient.newBuilder()
					.dispatcher(inferenceDispatcher())
					.readTimeout(OLLAMA_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
					.callTimeout(OLLAMA_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
					.build();
		}
		return ollamaHttpClient;
	}

	private void request(String english, boolean persist, RequestState state)
	{
		JsonArray messages = new JsonArray();
		messages.add(message("system", SYSTEM_PROMPT));
		messages.add(message("user", english));

		boolean api = config.aiBackend() == AiBackend.OPENAI;
		final String url = api ? apiEndpoint() : ollamaEndpoint();
		Request req = api ? buildApiRequest(url, messages, state.model) : buildOllamaRequest(url, messages, state.model);
		if (req == null)
		{
			finish(english, state); // misconfigured: no request, and no failure/backoff increment
			return;
		}

		Call pending = inferenceClient(api).newCall(req);
		if (!isCurrent(state)) return;
		pending.enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				synchronized (AiTranslator.this)
				{
					if (!isCurrent(state)) return;
					recordFailure();
					finish(english, state);
				}
				if (persist) log.warn("OSRSCN AI request failed ({}): {}", url, e.toString());
				else log.warn("OSRSCN AI request failed (non-persistent request): {}", e.getClass().getSimpleName());
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					if (!r.isSuccessful())
					{
						synchronized (AiTranslator.this)
						{
							if (!isCurrent(state)) return;
							recordFailure();
						}
						if (persist) log.warn("OSRSCN AI HTTP {} for model '{}'", r.code(), state.model);
						else log.warn("OSRSCN AI HTTP {} (non-persistent request)", r.code());
						return;
					}
					// Response IO/parse must not hold the model monitor: switching may happen mid-read.
					String content = r.body() == null ? null : parseContent(r.body().string());
					synchronized (AiTranslator.this)
					{
						if (!isCurrent(state)) return;
						recordSuccess();
						if (content != null && !content.isEmpty())
						{
							String zh = clean(content);
							cache.put(english, zh);
							if (persist && isPersistable(english) && persisted.add(english))
							{
								persistToDisk(english, zh);
							}
							sessionCount.incrementAndGet();
							recent.addFirst(english + "  →  " + zh);
							while (recent.size() > 40)
							{
								recent.pollLast();
							}
							if (persist) log.debug("OSRSCN AI: '{}' -> '{}'", english, zh);
							else log.debug("OSRSCN AI: non-persistent translation completed");
						}
					}
				}
				catch (Exception e)
				{
					// Parsing and close errors can contain response text, even for stale requests.
					if (persist) log.warn("OSRSCN AI parse failed", e);
					else log.warn("OSRSCN AI parse failed (non-persistent request): {}", e.getClass().getSimpleName());
				}
				finally
				{
					synchronized (AiTranslator.this) { finish(english, state); }
				}
			}
		});
	}

	private void recordFailure()
	{
		int n = failStreak.incrementAndGet();
		if (n >= BACKOFF_AFTER_FAILURES)
		{
			long delay = Math.min(BACKOFF_MAX_MS, BACKOFF_BASE_MS << Math.min(6, n - BACKOFF_AFTER_FAILURES));
			backoffUntil = System.currentTimeMillis() + delay;
			log.warn("OSRSCN AI: {} consecutive failures, backing off {}s", n, delay / 1000);
		}
	}

	private void recordSuccess()
	{
		failStreak.set(0);
		backoffUntil = 0;
	}

	/** The model name for the active backend; also names the per-model on-disk cache file. */
	private String activeModel()
	{
		return config.aiBackend() == AiBackend.OPENAI ? config.apiModel() : config.ollamaModel();
	}

	private String ollamaEndpoint()
	{
		return config.ollamaUrl().replaceAll("/+$", "") + "/api/chat";
	}

	/**
	 * Parsed {@code apiExtraHeaders}, re-parsed only when the configured text changes. Never null; an
	 * empty map means no extra headers. Names only are logged - values are user settings that may be
	 * sensitive (the config item is deliberately not masked, so it may hold a pasted key by mistake).
	 */
	private Map<String, String> extraHeaders()
	{
		String spec = config.apiExtraHeaders();
		if (spec == null)
		{
			spec = "";
		}
		if (!spec.equals(parsedHeadersSpec))
		{
			Map<String, String> parsed = ExtraHeaders.parse(spec);
			parsedHeaders = parsed;
			parsedHeadersSpec = spec;
			if (!parsed.isEmpty())
			{
				log.info("OSRSCN AI: applied custom request headers {}", parsed.keySet());
			}
		}
		return parsedHeaders;
	}

	private Request buildOllamaRequest(String url, JsonArray messages, String model)
	{
		JsonObject body = new JsonObject();
		body.addProperty("model", model);
		body.addProperty("stream", false);
		body.add("messages", messages);
		Request.Builder builder = new Request.Builder()
				.url(url)
				.post(RequestBody.create(JSON, gson.toJson(body)));
		ExtraHeaders.apply(extraHeaders(), builder);
		return builder.build();
	}

	/** Base API address (auto-filled from the provider preset) with {@code /chat/completions} appended. */
	private String apiEndpoint()
	{
		String base = config.apiUrl().trim().replaceAll("/+$", "");
		if (base.isEmpty())
		{
			return "";
		}
		return base.endsWith("/chat/completions") ? base : base + "/chat/completions";
	}

	private Request buildApiRequest(String url, JsonArray messages, String model)
	{
		String key = config.apiKey().trim();
		model = model.trim();
		if (url.isEmpty() || key.isEmpty() || model.isEmpty())
		{
			log.warn("OSRSCN AI: 在线 API 未配置完整（API 地址 / 密钥 / 模型）");
			return null;
		}
		JsonObject body = new JsonObject();
		body.addProperty("model", model);
		body.addProperty("stream", false);
		body.add("messages", messages);
		Request.Builder builder = new Request.Builder()
				.url(url)
				.post(RequestBody.create(JSON, gson.toJson(body)));
		ExtraHeaders.apply(extraHeaders(), builder);
		return builder.addHeader("Authorization", "Bearer " + key).build();
	}

	/**
	 * Check connectivity to the active backend. Ollama only lists tags ({@code /api/tags}). Online APIs
	 * use two steps: list models first (no tokens), then, when the provider does not serve
	 * {@code /models}, send one minimal completion (about one token). The result lands on an OkHttp
	 * thread, so the Swing caller must marshal {@code onResult} back to the EDT.
	 *
	 * @param onResult (ok, message): ok=true when the backend answered and the key/model are usable,
	 *                 with a short human-readable message.
	 */
	public void testConnection(java.util.function.BiConsumer<Boolean, String> onResult)
	{
		if (config.aiBackend() == AiBackend.OPENAI)
		{
			testApiConnection(onResult);
		}
		else
		{
			testOllamaConnection(onResult);
		}
	}

	/** Local backend: unchanged single-step tag listing. */
	private void testOllamaConnection(java.util.function.BiConsumer<Boolean, String> onResult)
	{
		String base = config.ollamaUrl().trim().replaceAll("/+$", "");
		if (base.isEmpty())
		{
			onResult.accept(false, "请先填写 Ollama 地址");
			return;
		}
		Request.Builder builder = new Request.Builder().url(base + "/api/tags");
		ExtraHeaders.apply(extraHeaders(), builder);
		Request req = builder.get().build();
		httpClient.newCall(req).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				onResult.accept(false, "连接失败：" + e.getMessage());
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					if (r.isSuccessful())
					{
						onResult.accept(true, "连接成功");
					}
					else if (r.code() == 401 || r.code() == 403)
					{
						onResult.accept(false, "密钥无效或无权限 (HTTP " + r.code() + ")");
					}
					else
					{
						onResult.accept(false, "连接失败 (HTTP " + r.code() + ")");
					}
				}
			}
		});
	}

	/**
	 * Online backend step 1: {@code GET {apiUrl}/models}. A key/auth/rate-limit answer is definitive and
	 * stops here; any other failure (or a 2xx that is not a model list) falls through to the one-token
	 * completion, because some OpenAI-compatible providers do not serve {@code /models} at all.
	 */
	private void testApiConnection(java.util.function.BiConsumer<Boolean, String> onResult)
	{
		String base = config.apiUrl().trim().replaceAll("/+$", "");
		String key = config.apiKey().trim();
		if (base.isEmpty() || key.isEmpty())
		{
			onResult.accept(false, "请先填写 API 地址和密钥");
			return;
		}
		Request.Builder builder = new Request.Builder().url(base + "/models");
		ExtraHeaders.apply(extraHeaders(), builder);
		Request req = builder.header("Authorization", "Bearer " + key).get().build();
		testClient().newCall(req).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				onResult.accept(false, "连接失败：" + e.getMessage());
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					if (r.isSuccessful())
					{
						Set<String> models = modelIds(r.body() == null ? "" : r.body().string());
						if (models == null)
						{
							testApiCompletion(onResult); // not a model list: probe with a real request
							return;
						}
						String message = "连接成功，密钥有效";
						String model = config.apiModel().trim();
						if (model.isEmpty())
						{
							message += "（未填写 API 模型）";
						}
						else if (!models.contains(model))
						{
							message += "（模型清单中未见 " + model + "，请确认模型名）";
						}
						onResult.accept(true, message);
					}
					else if (r.code() == 401 || r.code() == 403)
					{
						onResult.accept(false, "密钥无效或无权限 (HTTP " + r.code() + ")");
					}
					else if (r.code() == 429)
					{
						onResult.accept(false, "连接正常但被限流 (HTTP 429)");
					}
					else
					{
						testApiCompletion(onResult); // provider may not expose /models: verify with a request
					}
				}
				catch (IOException e)
				{
					onResult.accept(false, "连接失败：" + e.getMessage());
				}
			}
		});
	}

	/**
	 * Online backend step 2: one minimal completion against the same URL/auth/header rules as a real
	 * translation request. Only reached when the model list is missing or not a model list.
	 */
	private void testApiCompletion(java.util.function.BiConsumer<Boolean, String> onResult)
	{
		String url = apiEndpoint();
		String key = config.apiKey().trim();
		String model = config.apiModel().trim();
		if (url.isEmpty() || key.isEmpty())
		{
			onResult.accept(false, "请先填写 API 地址和密钥");
			return;
		}
		if (model.isEmpty())
		{
			onResult.accept(false, "请先填写 API 模型");
			return;
		}
		sendProbe(onResult, url, key, model, true);
	}

	/**
	 * Sends the probe completion. The first attempt caps the output at one token; a provider that
	 * rejects that parameter (400/422: newer OpenAI models want {@code max_completion_tokens}, some
	 * models require a minimum budget) gets exactly one retry with the cap omitted, which is legal for
	 * every chat-completions dialect. Any other failure is definitive and is never retried.
	 */
	private void sendProbe(java.util.function.BiConsumer<Boolean, String> onResult,
			String url, String key, String model, boolean capOutput)
	{
		JsonArray messages = new JsonArray();
		messages.add(message("user", "hi"));
		JsonObject body = new JsonObject();
		body.addProperty("model", model);
		body.add("messages", messages);
		if (capOutput)
		{
			body.addProperty("max_tokens", 1);
		}
		body.addProperty("stream", false);
		Request.Builder builder = new Request.Builder()
				.url(url)
				.post(RequestBody.create(JSON, gson.toJson(body)));
		ExtraHeaders.apply(extraHeaders(), builder);
		Request req = builder.addHeader("Authorization", "Bearer " + key).build();
		testClient().newCall(req).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				onResult.accept(false, "连接失败：" + e.getMessage());
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					if (r.code() == 200)
					{
						onResult.accept(true, "连接成功，模型可用");
						return;
					}
					if (r.code() == 401 || r.code() == 403)
					{
						onResult.accept(false, "密钥无效或无权限 (HTTP " + r.code() + ")");
						return;
					}
					if (r.code() == 429)
					{
						onResult.accept(false, "连接正常但被限流 (HTTP 429)");
						return;
					}
					if (capOutput && (r.code() == 400 || r.code() == 422))
					{
						sendProbe(onResult, url, key, model, false); // parameter rejected: retry uncapped
						return;
					}
					String detail = errorMessage(r.body() == null ? "" : r.body().string());
					onResult.accept(false, detail == null
							? "连接失败 (HTTP " + r.code() + ")"
							: "连接失败：" + detail);
				}
				catch (IOException e)
				{
					onResult.accept(false, "连接失败：" + e.getMessage());
				}
			}
		});
	}

	/** Lazily derived client for connectivity checks; never mutates the shared {@code httpClient}. */
	private OkHttpClient testClient()
	{
		OkHttpClient client = testHttpClient;
		if (client == null)
		{
			client = httpClient.newBuilder()
					.readTimeout(30_000, TimeUnit.MILLISECONDS)
					.build();
			testHttpClient = client;
		}
		return client;
	}

	/**
	 * @return the model ids from an OpenAI-style {@code /models} body, or null when the body is not a
	 *         model list at all (an error page, plain text, or another provider's schema).
	 */
	private Set<String> modelIds(String body)
	{
		try
		{
			JsonObject root = gson.fromJson(body, JsonObject.class);
			if (root == null || !root.has("data") || !root.get("data").isJsonArray())
			{
				return null;
			}
			JsonArray data = root.getAsJsonArray("data");
			Set<String> ids = new HashSet<>();
			for (int i = 0; i < data.size(); i++)
			{
				if (data.get(i).isJsonObject())
				{
					JsonObject entry = data.get(i).getAsJsonObject();
					if (entry.has("id") && entry.get("id").isJsonPrimitive())
					{
						ids.add(entry.get("id").getAsString());
					}
				}
			}
			return ids;
		}
		catch (RuntimeException e)
		{
			return null; // not JSON / not a model list: the caller falls back to a completion probe
		}
	}

	/** Cap for the server error detail shown in a dialog - providers may return whole paragraphs. */
	private static final int ERROR_DETAIL_MAX = 200;

	/**
	 * @return the {@code error.message} detail of an OpenAI-style error body, or null when absent.
	 *         Cleaned for a dialog: CR/LF collapse to spaces and the text is capped at
	 *         {@link #ERROR_DETAIL_MAX} characters with an ellipsis.
	 */
	private String errorMessage(String body)
	{
		try
		{
			JsonObject root = gson.fromJson(body, JsonObject.class);
			if (root != null && root.has("error") && root.get("error").isJsonObject())
			{
				JsonObject error = root.getAsJsonObject("error");
				if (error.has("message") && error.get("message").isJsonPrimitive())
				{
					String detail = error.get("message").getAsString().trim()
							.replaceAll("[\\r\\n]+", " ");
					if (!detail.isEmpty())
					{
						return detail.length() > ERROR_DETAIL_MAX
								? detail.substring(0, ERROR_DETAIL_MAX) + "…"
								: detail;
					}
				}
			}
		}
		catch (RuntimeException e)
		{
			return null;
		}
		return null;
	}

	/** Parse the assistant reply from either Ollama ({@code /api/chat}) or OpenAI-style responses. */
	private String parseContent(String json)
	{
		JsonObject root = gson.fromJson(json, JsonObject.class);
		if (root == null)
		{
			return null;
		}
		// Ollama /api/chat: { "message": { "content": ... } }
		if (root.has("message") && root.get("message").isJsonObject())
		{
			JsonObject msg = root.getAsJsonObject("message");
			if (msg.has("content"))
			{
				return msg.get("content").getAsString();
			}
		}
		// OpenAI-compatible /chat/completions: { "choices": [ { "message": { "content": ... } } ] }
		if (root.has("choices"))
		{
			JsonArray choices = root.getAsJsonArray("choices");
			if (choices != null && choices.size() > 0 && choices.get(0).isJsonObject())
			{
				JsonObject first = choices.get(0).getAsJsonObject();
				if (first.has("message") && first.get("message").isJsonObject())
				{
					JsonObject msg = first.getAsJsonObject("message");
					if (msg.has("content"))
					{
						return msg.get("content").getAsString();
					}
				}
			}
		}
		return null;
	}

	private static String clean(String s)
	{
		return THINK.matcher(s).replaceAll("").trim();
	}

	private JsonObject message(String role, String content)
	{
		JsonObject m = new JsonObject();
		m.addProperty("role", role);
		m.addProperty("content", content);
		return m;
	}
}
