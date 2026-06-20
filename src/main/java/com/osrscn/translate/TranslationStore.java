package com.osrscn.translate;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.inject.Singleton;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;

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
		MANUAL("transcript_zh_manual.tsv");

		final String file;

		Category(String file)
		{
			this.file = file;
		}
	}

	// Order tried by lookupAny() when the caller has no specific context.
	private static final Category[] ANY_ORDER = {
			Category.DIALOGUE, Category.GAME_TEXT, Category.LVL_UP, Category.INTERFACE, Category.NAME,
			Category.EXAMINE, Category.MANUAL, Category.DIALOGUE_EXPERIMENTAL,
	};

	// Categories that also get a case-insensitive index. The huge dialogue tables are
	// excluded to save memory - dialogue is matched verbatim.
	private static final java.util.EnumSet<Category> LOOSE = java.util.EnumSet.of(
			Category.NAME, Category.INTERFACE, Category.GAME_TEXT, Category.EXAMINE,
			Category.MANUAL, Category.LVL_UP, Category.ACTIONS, Category.INVENTORY_ACTIONS);

	private final File cacheDir = new File(RuneLite.RUNELITE_DIR, "osrscn/zh");
	private final Map<Category, Map<String, String>> maps = new EnumMap<>(Category.class);
	private final Map<Category, Map<String, String>> lowerMaps = new EnumMap<>(Category.class); // case-insensitive index
	private volatile boolean loaded;
	private volatile String baseUrl; // e.g. https://raw.githubusercontent.com/<user>/<repo>/<branch>/public/zh/

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
			for (Category c : Category.values())
			{
				File f = new File(cacheDir, c.file);
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

	private void download(String url, File dest) throws Exception
	{
		log.info("OSRSCN: downloading {}", url);
		HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
		conn.setInstanceFollowRedirects(true);
		conn.setRequestProperty("User-Agent", "osrscn-plugin");
		conn.setConnectTimeout(15000);
		conn.setReadTimeout(60000);
		if (conn.getResponseCode() / 100 != 2)
		{
			throw new java.io.IOException("HTTP " + conn.getResponseCode());
		}
		try (InputStream in = conn.getInputStream())
		{
			Files.copy(in, dest.toPath());
		}
		finally
		{
			conn.disconnect();
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

	/** Collapse whitespace and trim, so game text and TSV keys compare equal. {@code <br>} becomes a
	 *  space; {@code <str>}/{@code </str>} (strikethrough on completed diary/quest lines) is dropped so
	 *  struck-through text still matches the plain transcript key. */
	public static String normalize(String s)
	{
		return s.replaceAll("(?i)<br\\s*/?>", " ")
				.replaceAll("(?i)</?str>", "")
				.replaceAll("\\s+", " ").trim();
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
