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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
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
			+ "2. Keep proper nouns without established translations in English. When a Terminology list "
			+ "is present, treat it as reference only: use an entry when the text clearly refers to that "
			+ "named thing, and translate ordinary words and function words by their normal meaning. "
			+ "Never translate or repeat the list itself.\n"
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

	// Terminology source (the local NAME table). Null in unit tests that build AiTranslator directly;
	// the prompt then carries no Terminology block, exactly as before this feature.
	@Inject
	private TranslationStore store;

	@Inject
	private Gson gson;

	@Inject
	private java.util.concurrent.ScheduledExecutorService executor;
	private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();
	private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
	// Lines whose reply was the terminology block instead of a translation. A miss keeps arriving on
	// every lookup, so without this the same doomed line would be re-sent for the rest of the session.
	private final Set<String> untranslatable = ConcurrentHashMap.newKeySet();
	// Terminology is only a hint: a model that copies the block on consecutive exhausted rounds keeps
	// doing it, so it stops being given one for the rest of this session. Both are reset when the
	// model/backend identity changes (configurationChanged) and by an explicit cache clear, which
	// restores the hints without a restart.
	private final Set<String> glossaryMutedModels = ConcurrentHashMap.newKeySet();
	private final ConcurrentHashMap<String, Integer> glossaryEchoStreak = new ConcurrentHashMap<>();
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

	// Some models (o1/o3/gpt-5, strict proxies) reject the optional temperature parameter with
	// 400/422. Once a retry without it succeeds, this configuration omits temperature from every
	// later request instead of burning a guaranteed 400/422 round trip per line. Only
	// configurationChanged() (model/backend/URL/key) clears it, because a new endpoint may accept it.
	private volatile boolean apiTemperatureUnsupported;

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
		if (requestedGeneration != generation || !config.useLocalAi() || cache.containsKey(english)
				|| untranslatable.contains(english))
		{
			return; // model/config changed, the miss is negative-cached, or another callback filled it
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
		state.withoutTemperature = apiTemperatureUnsupported;
		// Computed once per admitted request, never per lookup/tick. The index is built off-thread at
		// store load, so this is a token lookup plus a short word-boundary scan (~50us). The hint is
		// optional: a broken index must degrade to the plain message, not fail the request and leave
		// the line stuck in inFlight, so its exceptions stay local and are never counted as failures.
		// A store implementation may also answer null; that means "no hint", exactly like "".
		try
		{
			String hint = store == null || glossaryMutedModels.contains(state.model)
					? "" : store.glossaryPrompt(english);
			state.glossaryText = hint == null ? "" : hint;
		}
		catch (RuntimeException e)
		{
			state.glossaryText = "";
			log.debug("OSRSCN AI: terminology hint unavailable, continuing without it", e);
		}
		state.glossaryEchoRef = state.glossaryText;
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
		// Terminology bookkeeping is model identity too: a new model gets a clean echo record.
		glossaryMutedModels.clear();
		glossaryEchoStreak.clear();
		apiTemperatureUnsupported = false; // a new model/backend/URL/key may accept temperature again
	}

	private void invalidateRequests()
	{
		generation++;
		inFlight.clear();
		untranslatable.clear(); // a new model/endpoint may translate the lines the old one choked on
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
		boolean withoutTemperature; // this attempt omitted the optional temperature field
		boolean retryWithoutTemperature; // the provider rejected temperature: retry once without it
		boolean retryWithoutGlossary; // the model echoed the block: retry once without it
		// An echo was already detected in this round. The retry itself carries no block, but its reply
		// is still held to the translation acceptance rule, so a model that keeps repeating the source
		// line cannot have that repeat cached as a translation on the second attempt.
		boolean glossaryEchoed;
		// Terminology block for the next attempt ("" = plain user message). Cleared when the model
		// echoes it, so the retry sends the bare source line. Once cleared it stays empty: that is what
		// makes the echo retry a single shot instead of a loop.
		String glossaryText = "";
		// The block as first sent in this round. Kept after glossaryText is cleared so the echo guard
		// can still recognise and strip the reference lines the model copied; "" = no block this round.
		String glossaryEchoRef = "";

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
	 * on-screen text (every accepted translation is put regardless of this gate). Rejects the two
	 * categories that can never be a stable cross-session hit and only bloat the cache / pollute reflow
	 * submissions: player-authored CJK content (game English is never CJK), dynamic value labels, and
	 * number-templated messages that never recur.
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
		// An explicit clear is the user's "start over", so the terminology bookkeeping goes too:
		// a model muted for echoing the block gets the hint again. configurationChanged() clears
		// the same two because a new model identity also starts with a clean echo record.
		glossaryMutedModels.clear();
		glossaryEchoStreak.clear();
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
		// The terminology block is a prefix of the user message (not the system prompt) so the system
		// prompt keeps its stable prefix for provider-side prompt caching. Empty means the user message
		// is byte-identical to what this plugin sent before the glossary existed.
		messages.add(message("user", state.glossaryText.isEmpty()
				? english : state.glossaryText + "\n\n" + english));

		boolean api = config.aiBackend() == AiBackend.OPENAI;
		final String url = api ? apiEndpoint() : ollamaEndpoint();
		Request req = api
				? buildApiRequest(url, messages, state.model, !state.withoutTemperature)
				: buildOllamaRequest(url, messages, state.model);
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
						// Compatibility: providers/models that reject the optional temperature field with
						// 400/422 get exactly one retry without it. A rejected retry is a normal failure.
						boolean retry = api && !state.withoutTemperature
								&& (r.code() == 400 || r.code() == 422);
						synchronized (AiTranslator.this)
						{
							if (!isCurrent(state)) return;
							if (!retry)
							{
								recordFailure();
							}
							state.retryWithoutTemperature = retry;
						}
						if (retry)
						{
							if (persist) log.warn("OSRSCN AI HTTP {} for model '{}', retrying without temperature", r.code(), state.model);
							else log.warn("OSRSCN AI HTTP {} (non-persistent request), retrying without temperature", r.code());
						}
						else if (persist)
						{
							log.warn("OSRSCN AI HTTP {} for model '{}'", r.code(), state.model);
						}
						else
						{
							log.warn("OSRSCN AI HTTP {} (non-persistent request)", r.code());
						}
						return;
					}
					// Response IO/parse must not hold the model monitor: switching may happen mid-read.
					String content = r.body() == null ? null : parseContent(r.body().string());
					synchronized (AiTranslator.this)
					{
						if (!isCurrent(state)) return;
						recordSuccess();
						if (state.withoutTemperature)
						{
							// The provider accepted a request without temperature: remember that this
							// model rejects it so later lines skip the guaranteed 400/422 first attempt.
							apiTemperatureUnsupported = true;
						}
						if (content != null && !content.isEmpty())
						{
							String zh = clean(content);
							List<GlossaryRef> references = glossaryRefs(state.glossaryEchoRef);
							if (isGlossaryEcho(zh, english, state.glossaryEchoRef, references))
							{
								state.glossaryEchoed = true;
								zh = stripGlossaryEcho(zh, references);
							}
							if (state.glossaryEchoed && !isUsableTranslation(zh, english))
							{
								// The reply was the reference block itself, the source line repeated
								// back, or a stripped remainder made of nothing else: never a translation.
								// Retry once without terminology; a second failure means this line cannot
								// be translated, and repeating the round on every tick would never end.
								if (!state.glossaryText.isEmpty())
								{
									state.retryWithoutGlossary = true;
									state.glossaryText = "";
								}
								else
								{
									untranslatable.add(english);
									markGlossaryEcho(state.model);
									log.debug("OSRSCN AI: reply repeated the terminology block or the source"
											+ " line for model '{}'; line marked untranslatable", state.model);
								}
								return; // never cache/persist/display a reference block or the source line
							}
							glossaryEchoStreak.remove(state.model);
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
					synchronized (AiTranslator.this)
					{
						if (isCurrent(state) && (state.retryWithoutTemperature || state.retryWithoutGlossary))
						{
							// Retry in place: the request keeps its in-flight ownership and is not finished,
							// so callers still see it pending and no second slot is consumed.
							// Only one retry can be pending at a time and neither can loop: a temperature
							// retry is armed on a rejected HTTP response and cleared here before dispatch,
							// while a glossary retry is armed on a successful response whose block was
							// echoed, after glossaryText was cleared - so the bare retry cannot be re-armed.
							boolean withoutTemperature = state.retryWithoutTemperature;
							state.retryWithoutTemperature = false;
							if (withoutTemperature)
							{
								state.withoutTemperature = true;
							}
							else
							{
								// glossaryText was already cleared when the echo was detected.
								state.retryWithoutGlossary = false;
							}
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
								if (persist) log.warn("OSRSCN AI retry dispatch failed", e);
								else log.warn("OSRSCN AI retry dispatch failed (non-persistent request): {}", e.getClass().getSimpleName());
							}
						}
						else
						{
							finish(english, state);
						}
					}
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

	private Request buildApiRequest(String url, JsonArray messages, String model, boolean includeTemperature)
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
		if (includeTemperature)
		{
			// Fixed low temperature: it only narrows run-to-run sampling variance for the same line.
			// It does not by itself keep proper nouns in English or fix terminology drift; the prompt /
			// glossary has to do that.
			body.addProperty("temperature", 0.3);
		}
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

	/** Whitespace-normalized form, so an echoed reference line is recognised regardless of spacing. */
	private static String flatten(String s)
	{
		return s.trim().replaceAll("\\s+", " ").replaceAll("\\s*=>\\s*", " => ");
	}

	/**
	 * One {@code en => zh} pair of a terminology block, with the matcher that recognises the model
	 * copying it back. Detection and stripping share this matcher, so a reply can never be classified
	 * as an echo and then leave a reference line behind (or be accepted whole while one is inside).
	 */
	private static final class GlossaryRef
	{
		// The block's own "=>" plus the separators a model commonly retypes it with (Chinese output
		// often rewrites the arrow; a fullwidth colon needs no spaces at all).
		private static final String SEPARATOR = "=>|->|→|：|:";
		final String en;
		final String zh;
		final String flattened;
		private final Pattern line;

		GlossaryRef(String en, String zh)
		{
			this.en = en;
			this.zh = zh;
			flattened = flatten(en + " => " + zh);
			// Case-insensitive, with the word boundary in front of the English term made explicit:
			// a name may end in "'s", so \b is not reliable. The separator itself bounds the end.
			line = Pattern.compile("(?i)(?<![A-Za-z0-9_])" + Pattern.quote(en)
					+ "\\s*(?:" + SEPARATOR + ")");
		}

		/** @return the end index of the first {@code en + separator} occurrence in {@code text}, or -1. */
		int separatorEnd(String text)
		{
			Matcher matcher = line.matcher(text);
			return matcher.find() ? matcher.end() : -1;
		}
	}

	/**
	 * The reference pairs of one terminology block. The block is generated as one pair per line after
	 * {@link TermGlossary#HEADER}, so a line with an arrow is always a pair.
	 */
	private static List<GlossaryRef> glossaryRefs(String glossaryText)
	{
		List<GlossaryRef> references = new ArrayList<>();
		if (glossaryText == null || glossaryText.isEmpty())
		{
			return references;
		}
		for (String line : glossaryText.split("\n"))
		{
			String flat = flatten(line);
			int arrow = flat.indexOf("=>");
			if (arrow <= 0)
			{
				continue;
			}
			String en = flat.substring(0, arrow).trim();
			String zh = flat.substring(arrow + 2).trim();
			if (!en.isEmpty() && !zh.isEmpty())
			{
				references.add(new GlossaryRef(en, zh));
			}
		}
		return references;
	}

	/**
	 * True when a reply is the reference block instead of a translation: the header constant, a
	 * complete pair copied from the block, a retyped {@code en + separator} reference line (the model
	 * may rewrite "=>" as "->", "→" or "："), or - weaker - an arrow in a reply to a source line that
	 * has none (the block is the only reason the model sees "=>").
	 */
	private static boolean isGlossaryEcho(String zh, String english, String glossaryRef,
			List<GlossaryRef> references)
	{
		if (zh.contains(TermGlossary.HEADER))
		{
			return true;
		}
		if (!references.isEmpty())
		{
			String flat = flatten(zh);
			for (GlossaryRef reference : references)
			{
				if (flat.contains(reference.flattened))
				{
					return true;
				}
			}
			for (String line : zh.split("\n", -1))
			{
				if (findReference(line, references) != null)
				{
					return true;
				}
			}
		}
		return glossaryRef.indexOf("=>") >= 0 && zh.indexOf("=>") >= 0 && english.indexOf("=>") < 0;
	}

	/**
	 * Removes copied header and reference lines from a mixed reply, block-aware rather than line-exact:
	 * the English header, any line that retypes one of the block's pairs (any accepted separator, and
	 * a pair split as "en =>" + next line "zh" counts as one reference), and - once a reference line
	 * was seen - the translated block header. Every other line is kept in order.
	 *
	 * @return the translation left behind, or "" when the reply was nothing but the block
	 */
	private static String stripGlossaryEcho(String zh, List<GlossaryRef> references)
	{
		String[] lines = zh.split("\n", -1);
		boolean referenceSeen = false;
		for (String line : lines)
		{
			if (findReference(line, references) != null)
			{
				referenceSeen = true;
				break;
			}
		}
		StringBuilder kept = new StringBuilder();
		for (int i = 0; i < lines.length; i++)
		{
			String trimmed = lines[i].trim();
			if (trimmed.isEmpty() || trimmed.contains(TermGlossary.HEADER))
			{
				continue;
			}
			GlossaryRef reference = findReference(trimmed, references);
			if (reference != null)
			{
				// "crystal gate =>" with the Chinese on the following line: that split pair is one
				// reference, so consume the continuation line with it.
				if (i + 1 < lines.length && endsAtSeparator(trimmed, reference)
						&& lines[i + 1].trim().startsWith(reference.zh))
				{
					i++;
				}
				continue;
			}
			if (referenceSeen && isTranslatedHeader(trimmed))
			{
				continue;
			}
			if (kept.length() > 0)
			{
				kept.append('\n');
			}
			kept.append(trimmed);
		}
		return kept.toString();
	}

	/**
	 * @return the first pair that occurs in {@code line} - either retyped ({@code en + separator}) or
	 *         copied verbatim with different spacing (the flattened pair is contained in the flattened
	 *         line) - or null.
	 */
	private static GlossaryRef findReference(String line, List<GlossaryRef> references)
	{
		String flat = null;
		for (GlossaryRef reference : references)
		{
			if (reference.separatorEnd(line) >= 0)
			{
				return reference;
			}
			if (flat == null)
			{
				flat = flatten(line);
			}
			if (flat.contains(reference.flattened))
			{
				return reference;
			}
		}
		return null;
	}

	/** True when a reference line is only {@code en + separator}, with the Chinese on the next line. */
	private static boolean endsAtSeparator(String line, GlossaryRef reference)
	{
		int end = reference.separatorEnd(line);
		return end >= 0 && line.substring(end).trim().isEmpty();
	}

	/**
	 * True for a translated block header: a label-only line that names the terminology and ends at its
	 * colon ("术语参考翻译："). A line with content after the colon is a translation and is kept.
	 */
	private static boolean isTranslatedHeader(String line)
	{
		if (line.indexOf("术语") < 0 && line.indexOf("術語") < 0)
		{
			return false;
		}
		char last = line.charAt(line.length() - 1);
		return last == '：' || last == ':';
	}

	/**
	 * The acceptance rule for a reply that carried (or repeated) the reference block: only real
	 * Chinese content counts as a translation, never an empty remainder, the source line itself, or a
	 * remainder with no Chinese at all.
	 */
	private static boolean isUsableTranslation(String zh, String english)
	{
		if (zh.isEmpty() || !hasCjk(zh))
		{
			return false;
		}
		return !flatten(zh).equals(flatten(english));
	}

	private static boolean hasCjk(String text)
	{
		for (int i = 0; i < text.length(); i++)
		{
			char c = text.charAt(i);
			if (c >= 0x3400 && c <= 0x9FFF)
			{
				return true;
			}
		}
		return false;
	}

	/** One exhausted echo round for {@code model}; two in a row stop terminology for it this session. */
	private void markGlossaryEcho(String model)
	{
		int streak = glossaryEchoStreak.merge(model, 1, Integer::sum);
		if (streak >= 2 && glossaryMutedModels.add(model))
		{
			log.warn("OSRSCN AI: model '{}' keeps echoing the terminology block; terminology disabled for it", model);
		}
	}

	private JsonObject message(String role, String content)
	{
		JsonObject m = new JsonObject();
		m.addProperty("role", role);
		m.addProperty("content", content);
		return m;
	}
}
