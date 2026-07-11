package com.osrscn.translate;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.regex.Pattern;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.inject.Inject;
import javax.inject.Singleton;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * In-memory English -> Simplified Chinese lookup, loaded from the community OSRS transcript TSVs.
 *
 * <p>Each TSV is a translation domain (dialogue, names, menu actions, interface text, examine,
 * etc.) and is kept in its own map so callers can look up in the right context. Files are read
 * from the local cache ({@code ~/.runelite/osrscn/zh/}); a missing file is downloaded once from
 * the configured base URL and cached.
 */
@Slf4j
@Singleton
public class TranslationStore
{
	/** Translation domains, each backed by one TSV file. */
	public enum Category
	{
		DIALOGUE("transcript_zh_dialogue.tsv"),
		DIALOGUE_EXPERIMENTAL("transcript_zh_dialogue_experimental.tsv"),
		NAME("transcript_zh_name.tsv"),
		ACTIONS("transcript_zh_actions.tsv"),
		INVENTORY_ACTIONS("transcript_zh_inventoryActions.tsv"),
		INTERFACE("transcript_zh_interface.tsv"),
		EXAMINE("transcript_zh_examine.tsv"),
		GAME_TEXT("transcript_zh_gameText.tsv"),
		LVL_UP("transcript_zh_lvl_up_msg.tsv"),
		// skill-guide prose/titles (group 860), regenerated wholesale from the cache-dump pipeline
		SKILL_GUIDE("transcript_zh_skillguide.tsv"),
		SUPPLEMENT("transcript_zh_supplement.tsv");

		final String file;

		Category(String file)
		{
			this.file = file;
		}
	}

	// Order tried by lookupAny() when the caller has no specific context. Every category is listed so a
	// translation never gets lost just because it was filed under one this path didn't try; the dialogue
	// tables stay last (experimental dead last) so curated tables win.
	private static final Category[] ANY_ORDER = {
			Category.DIALOGUE, Category.GAME_TEXT, Category.LVL_UP, Category.INTERFACE, Category.NAME,
			Category.EXAMINE, Category.SKILL_GUIDE, Category.SUPPLEMENT, Category.ACTIONS,
			Category.INVENTORY_ACTIONS, Category.DIALOGUE_EXPERIMENTAL,
	};

	// Categories that also get a case-insensitive index. The huge dialogue tables are
	// excluded to save memory - dialogue is matched verbatim.
	private static final java.util.EnumSet<Category> LOOSE = java.util.EnumSet.of(
			Category.NAME, Category.INTERFACE, Category.GAME_TEXT, Category.EXAMINE,
			Category.SKILL_GUIDE, Category.SUPPLEMENT, Category.LVL_UP, Category.ACTIONS,
			Category.INVENTORY_ACTIONS);

	private final File cacheDir = new File(RuneLite.RUNELITE_DIR, "osrscn/zh");
	private final Map<Category, Map<String, String>> maps = new EnumMap<>(Category.class);
	private final Map<Category, Map<String, String>> lowerMaps = new EnumMap<>(Category.class); // case-insensitive index
	private volatile boolean loaded;
	private volatile String baseUrl; // e.g. https://raw.githubusercontent.com/<user>/<repo>/<branch>/public/zh/

	@Inject
	private OkHttpClient httpClient;

	public TranslationStore()
	{
		for (Category c : Category.values())
		{
			maps.put(c, new ConcurrentHashMap<>());
		}
		for (Category c : LOOSE)
		{
			lowerMaps.put(c, new ConcurrentHashMap<>());
		}
	}

	public void setBaseUrl(String url)
	{
		this.baseUrl = (url == null || url.trim().isEmpty()) ? null : url.trim();
	}

	public boolean isLoaded()
	{
		return loaded;
	}

	public int size()
	{
		int n = 0;
		for (Map<String, String> m : maps.values())
		{
			n += m.size();
		}
		return n;
	}

	/**
	 * Distinct CJK codepoints across every loaded Chinese value, for glyph pre-warm (so a character's
	 * first appearance does not flash English while its sprite uploads). Call once {@link #isLoaded()};
	 * returns an empty set if nothing is loaded yet. Uses the same 0x2E80 threshold as the glyph renderer.
	 */
	public java.util.Set<Integer> chineseCodepoints()
	{
		java.util.Set<Integer> out = new java.util.HashSet<>(8192);
		for (Map<String, String> m : maps.values())
		{
			for (String v : m.values())
			{
				if (v == null)
				{
					continue;
				}
				int i = 0;
				int len = v.length();
				while (i < len)
				{
					int cp = v.codePointAt(i);
					if (cp >= 0x2E80)
					{
						out.add(cp);
					}
					i += Character.charCount(cp);
				}
			}
		}
		return out;
	}

	/** Loads the store on a background thread. Safe to call once at start-up. */
	public void loadAsync()
	{
		Thread t = new Thread(this::load, "osrscn-translation-load");
		t.setDaemon(true);
		t.start();
	}

	private void load()
	{
		try
		{
			//noinspection ResultOfMethodCallIgnored
			cacheDir.mkdirs();
			// Refresh stale tables against the published hash list; without this, existing
			// installs would keep their first-download copies forever. Any failure keeps the
			// cached copy - the check must never block startup.
			Map<String, String> remote = fetchRemoteHashes();
			for (Category c : Category.values())
			{
				File f = new File(cacheDir, c.file);
				if (f.exists() && remote != null)
				{
					String want = remote.get(c.file);
					if (want != null && !want.equalsIgnoreCase(sha256(f)))
					{
						try
						{
							download(baseUrl + c.file, f);
						}
						catch (Exception e)
						{
							log.debug("OSRSCN: refresh failed for {}, keeping cached copy: {}", c.file, e.getMessage());
						}
					}
				}
				if (!f.exists() && baseUrl != null)
				{
					try
					{
						download(baseUrl + c.file, f);
					}
					catch (Exception e)
					{
						log.debug("OSRSCN: optional file not downloaded {}: {}", c.file, e.getMessage());
					}
				}
				if (f.exists())
				{
					parse(f, c);
				}
			}
			loaded = true;
			log.info("OSRSCN translations loaded: {} entries", size());
		}
		catch (Exception e)
		{
			log.error("OSRSCN: failed to load translations", e);
		}
	}

	private void parse(File f, Category c) throws Exception
	{
		Map<String, String> into = maps.get(c);
		Map<String, String> lower = lowerMaps.get(c); // null for non-loose categories
		try (BufferedReader r = Files.newBufferedReader(f.toPath(), StandardCharsets.UTF_8))
		{
			String line = r.readLine(); // header
			while ((line = r.readLine()) != null)
			{
				int t1 = line.indexOf('\t');
				if (t1 <= 0)
				{
					continue;
				}
				int t2 = line.indexOf('\t', t1 + 1);
				String en = line.substring(0, t1);
				String zh = (t2 == -1) ? line.substring(t1 + 1) : line.substring(t1 + 1, t2);
				if (zh.isEmpty())
				{
					continue;
				}
				String key = normalize(en);
				into.putIfAbsent(key, zh);
				if (lower != null)
				{
					lower.putIfAbsent(key.toLowerCase(), zh);
				}
			}
		}
	}

	/** Published table hashes ({@code name|sha256} per line), or null when unavailable/offline. */
	private Map<String, String> fetchRemoteHashes()
	{
		if (baseUrl == null)
		{
			return null;
		}
		try
		{
			Request request = new Request.Builder().url(baseUrl + "hashList_zh.txt").build();
			try (Response response = httpClient.newCall(request).execute())
			{
				if (!response.isSuccessful() || response.body() == null)
				{
					return null;
				}
				Map<String, String> out = new java.util.HashMap<>();
				for (String line : response.body().string().split("\n"))
				{
					int p = line.indexOf('|');
					if (p > 0)
					{
						out.put(line.substring(0, p).trim(), line.substring(p + 1).trim());
					}
				}
				return out;
			}
		}
		catch (Exception e)
		{
			log.debug("OSRSCN: hash list not available: {}", e.getMessage());
			return null;
		}
	}

	private static String sha256(File f)
	{
		try (InputStream in = Files.newInputStream(f.toPath()))
		{
			java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
			byte[] buf = new byte[8192];
			int n;
			while ((n = in.read(buf)) > 0)
			{
				md.update(buf, 0, n);
			}
			StringBuilder sb = new StringBuilder();
			for (byte b : md.digest())
			{
				sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
			}
			return sb.toString();
		}
		catch (Exception e)
		{
			return "";
		}
	}

	private void download(String url, File dest) throws Exception
	{
		log.info("OSRSCN: downloading {}", url);
		Request request = new Request.Builder().url(url).build();
		try (Response response = httpClient.newCall(request).execute())
		{
			if (!response.isSuccessful() || response.body() == null)
			{
				throw new java.io.IOException("HTTP " + response.code());
			}
			// Download to a temp file and swap it in atomically, so an interrupted download can
			// never leave a truncated table that the exists() check would treat as complete.
			Path tmp = dest.toPath().resolveSibling(dest.getName() + ".tmp");
			try
			{
				try (InputStream in = response.body().byteStream())
				{
					Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
				}
				try
				{
					Files.move(tmp, dest.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
				}
				catch (java.nio.file.AtomicMoveNotSupportedException e)
				{
					Files.move(tmp, dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
				}
			}
			finally
			{
				Files.deleteIfExists(tmp);
			}
		}
	}

	/** @return the translation for {@code english} in {@code category}, or {@code null}. */
	public String lookup(Category category, String english)
	{
		if (english == null || english.isEmpty())
		{
			return null;
		}
		return maps.get(category).get(normalize(english));
	}

	/** Lookup with an already-normalized key (avoids re-normalizing across many categories). */
	public String lookupNormalized(Category category, String normalizedKey)
	{
		return maps.get(category).get(normalizedKey);
	}

	/** Case-insensitive lookup; {@code lowerKey} must be an already-normalized, lower-cased key.
	 *  Only the {@link #LOOSE} categories have a lower-cased index (others return null). */
	public String lookupLower(Category category, String lowerKey)
	{
		Map<String, String> m = lowerMaps.get(category);
		return m == null ? null : m.get(lowerKey);
	}

	/** @return the translation from any domain (priority order), or {@code null}. */
	public String lookupAny(String english)
	{
		if (english == null || english.isEmpty())
		{
			return null;
		}
		String key = normalize(english);
		for (Category c : ANY_ORDER)
		{
			String zh = maps.get(c).get(key);
			if (zh != null)
			{
				return zh;
			}
		}
		String lower = key.toLowerCase();
		for (Category c : ANY_ORDER)
		{
			String zh = lookupLower(c, lower);
			if (zh != null)
			{
				return zh;
			}
		}
		return null;
	}

	// Precompiled: normalize() sits on the per-frame widget scan path, so its regexes must not be
	// recompiled on every call.
	private static final Pattern BR_TAG = Pattern.compile("(?i)<br\\s*/?>");
	private static final Pattern STR_TAG = Pattern.compile("(?i)</?str>");
	private static final Pattern ODD_SPACES = Pattern.compile("[\\u00A0\\u2000-\\u200B\\u202F\\u205F\\u3000\\uFEFF]");
	private static final Pattern WHITESPACE = Pattern.compile("\\s+");

	/** Canonicalize so game text and TSV keys compare equal. {@code <br>} becomes a space; {@code <str>}
	 *  is dropped so struck-through diary/quest lines match the plain key. Typographic variants are folded
	 *  to their ASCII form (curly quotes -&gt; straight, en/em dashes -&gt; '-', ellipsis -&gt; '...').
	 *  The apostrophe is kept as a real character (so "you've" never collapses onto "youve"). Unusual
	 *  Unicode spaces (NBSP, full-width, thin, zero-width) that Java's {@code \s} does NOT match are
	 *  turned into a normal space before whitespace is collapsed. */
	public static String normalize(String s)
	{
		s = BR_TAG.matcher(s).replaceAll(" ");
		s = STR_TAG.matcher(s).replaceAll("");
		s = s.replace((char) 0x2018, '\'').replace((char) 0x2019, '\'').replace((char) 0x201B, '\'')
				.replace((char) 0x00B4, '\'').replace('`', '\'')
				.replace((char) 0x201C, '"').replace((char) 0x201D, '"').replace((char) 0x201E, '"')
				.replace((char) 0x2013, '-').replace((char) 0x2014, '-').replace((char) 0x2012, '-').replace((char) 0x2015, '-')
				.replace(String.valueOf((char) 0x2026), "...");
		s = ODD_SPACES.matcher(s).replaceAll(" ");
		return WHITESPACE.matcher(s).replaceAll(" ").trim();
	}

	/** One English/Chinese name pair, for the panel's search results. */
	public static final class Match
	{
		public final String en;
		public final String zh;

		Match(String en, String zh)
		{
			this.en = en;
			this.zh = zh;
		}
	}

	/**
	 * Substring search over entity names (items / NPCs / objects), matching either the English or the
	 * Chinese side, for the side panel's lookup tool. Case-insensitive on the English side.
	 */
	public List<Match> searchNames(String query, int limit)
	{
		List<Match> out = new ArrayList<>();
		if (query == null)
		{
			return out;
		}
		String raw = query.trim();
		if (raw.isEmpty())
		{
			return out;
		}
		String lower = raw.toLowerCase();
		for (Map.Entry<String, String> e : maps.get(Category.NAME).entrySet())
		{
			String en = e.getKey();
			String zh = e.getValue();
			if (en.toLowerCase().contains(lower) || zh.contains(raw))
			{
				out.add(new Match(en, zh));
				if (out.size() >= limit)
				{
					break;
				}
			}
		}
		return out;
	}
}
