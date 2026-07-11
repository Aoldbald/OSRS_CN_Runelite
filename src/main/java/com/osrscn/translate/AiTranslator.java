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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;
import okhttp3.Call;
import okhttp3.Callback;
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
	private static final MediaType JSON = MediaType.parse("application/json");
	private static final Pattern THINK = Pattern.compile("(?s)<think>.*?</think>");

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

	@Inject
	private OsrscnConfig config;

	@Inject
	private Gson gson;

	@Inject
	private java.util.concurrent.ScheduledExecutorService executor;
	private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();
	private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
	private final File dir = new File(RuneLite.RUNELITE_DIR, "osrscn");
	private String loadedModel; // model whose cache is currently in memory (per-model on disk)
	private File cacheFile;
	private volatile long lastDispatch;

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

	/** @return cached translation, or null if disabled / still pending (request fired on miss). */
	public String translate(String english)
	{
		return translate(english, true);
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
		if (english == null || english.isEmpty())
		{
			return null;
		}
		ensureLoaded();
		String cached = cache.get(english);
		if (cached != null)
		{
			// Serve already-cached translations even when AI is switched off: they cost nothing (no GPU,
			// no network), so turning AI off should only stop *new* translations, not hide finished ones.
			return cached;
		}
		if (!config.useLocalAi())
		{
			return null; // AI off: cache-only, never dispatch a fresh translation request
		}
		if (System.currentTimeMillis() < backoffUntil)
		{
			return null; // backend keeps failing: wait out the backoff window
		}
		// rate limit so Ollama doesn't saturate the GPU and stall the game (both configurable)
		if (inFlight.size() >= Math.max(1, config.aiConcurrency())
				|| System.currentTimeMillis() - lastDispatch < config.aiPaceMs())
		{
			return null;
		}
		if (!inFlight.add(english))
		{
			return null; // already requested
		}
		lastDispatch = System.currentTimeMillis();
		request(english, persist);
		return null;
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
		loadedModel = model;
		cache.clear();
		inFlight.clear();
		failStreak.set(0);
		backoffUntil = 0;
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
						// Entries written before a normalize() change keep their old key; re-normalize on
						// load so the current query key still hits and the AI isn't re-run for them.
						String nk = TranslationStore.normalize(k);
						if (!nk.equals(k))
						{
							cache.putIfAbsent(nk, v);
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

	public void clearCache()
	{
		cache.clear();
		inFlight.clear();
		if (cacheFile != null)
		{
			//noinspection ResultOfMethodCallIgnored
			cacheFile.delete();
		}
	}

	private void request(String english, boolean persist)
	{
		JsonArray messages = new JsonArray();
		messages.add(message("system", SYSTEM_PROMPT));
		messages.add(message("user", english));

		boolean api = config.aiBackend() == AiBackend.OPENAI;
		final String url = api ? apiEndpoint() : ollamaEndpoint();
		Request req = api ? buildApiRequest(url, messages) : buildOllamaRequest(url, messages);
		if (req == null)
		{
			inFlight.remove(english); // misconfigured (e.g. online API without key/model): don't dispatch
			return;
		}

		httpClient.newCall(req).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				inFlight.remove(english);
				lastDispatch = System.currentTimeMillis(); // gap measured from completion -> idle GPU window
				recordFailure();
				log.warn("OSRSCN AI request failed ({}): {}", url, e.toString());
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					if (!r.isSuccessful())
					{
						recordFailure();
						log.warn("OSRSCN AI HTTP {} for model '{}'", r.code(), activeModel());
						return;
					}
					recordSuccess();
					if (r.body() != null)
					{
						String content = parseContent(r.body().string());
						if (content != null && !content.isEmpty())
						{
							String zh = clean(content);
							cache.put(english, zh);
							if (persist)
							{
								persistToDisk(english, zh);
							}
							sessionCount.incrementAndGet();
							recent.addFirst(english + "  →  " + zh);
							while (recent.size() > 40)
							{
								recent.pollLast();
							}
							log.debug("OSRSCN AI: '{}' -> '{}'", english, zh);
						}
					}
				}
				catch (Exception e)
				{
					log.warn("OSRSCN AI parse failed", e);
				}
				finally
				{
					inFlight.remove(english);
					lastDispatch = System.currentTimeMillis(); // start the idle gap after completion
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

	private Request buildOllamaRequest(String url, JsonArray messages)
	{
		JsonObject body = new JsonObject();
		body.addProperty("model", config.ollamaModel());
		body.addProperty("stream", false);
		body.add("messages", messages);
		return new Request.Builder()
				.url(url)
				.post(RequestBody.create(JSON, gson.toJson(body)))
				.build();
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

	private Request buildApiRequest(String url, JsonArray messages)
	{
		String key = config.apiKey().trim();
		String model = config.apiModel().trim();
		if (url.isEmpty() || key.isEmpty() || model.isEmpty())
		{
			log.warn("OSRSCN AI: 在线 API 未配置完整（API 地址 / 密钥 / 模型）");
			return null;
		}
		JsonObject body = new JsonObject();
		body.addProperty("model", model);
		body.addProperty("stream", false);
		body.add("messages", messages);
		return new Request.Builder()
				.url(url)
				.addHeader("Authorization", "Bearer " + key)
				.post(RequestBody.create(JSON, gson.toJson(body)))
				.build();
	}

	/**
	 * Check connectivity to the active backend without spending any tokens: lists models
	 * (Ollama {@code /api/tags}, OpenAI {@code /models}) - no text is generated. The result lands on an
	 * OkHttp thread, so the Swing caller must marshal {@code onResult} back to the EDT.
	 *
	 * @param onResult (ok, message): ok=true on a 2xx reply, with a short human-readable message.
	 */
	public void testConnection(java.util.function.BiConsumer<Boolean, String> onResult)
	{
		boolean api = config.aiBackend() == AiBackend.OPENAI;
		Request req;
		if (api)
		{
			String base = config.apiUrl().trim().replaceAll("/+$", "");
			String key = config.apiKey().trim();
			if (base.isEmpty() || key.isEmpty())
			{
				onResult.accept(false, "请先填写 API 地址和密钥");
				return;
			}
			req = new Request.Builder().url(base + "/models").header("Authorization", "Bearer " + key).get().build();
		}
		else
		{
			String base = config.ollamaUrl().trim().replaceAll("/+$", "");
			if (base.isEmpty())
			{
				onResult.accept(false, "请先填写 Ollama 地址");
				return;
			}
			req = new Request.Builder().url(base + "/api/tags").get().build();
		}
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
						onResult.accept(true, api ? "连接成功，密钥有效" : "连接成功");
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
