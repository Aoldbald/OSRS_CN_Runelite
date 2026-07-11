package com.osrscn.translate;

import com.osrscn.OsrscnConfig;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;

/**
 * Local-only collector for game text that the lookup table can't translate, so the maintainer can
 * batch-translate it later and feed it back into the tables. Writes one entry per unique line to
 * {@code ~/.runelite/osrscn/missing.tsv} ({@code english\tsource}); nothing is ever uploaded.
 *
 * <p>Off unless {@link OsrscnConfig#collectMissing()} is on. Only real prose ({@code WORDY}) is kept,
 * and callers must pass game content only (dialogue / interface / menu) - never player chat.
 */
@Slf4j
@Singleton
public class MissingCollector
{
	private static final Pattern WORDY = Pattern.compile("[A-Za-z]{3,}");
	// Reconstructed quest-journal / achievement-diary sentences can run long; 300 dropped some, so allow
	// more. Still bounded to keep pathological strings out of the collected file.
	private static final int MAX_LEN = 500;
	// A wrapped interface line (a quest-journal line when whole-task reflow is off, a long tip's middle
	// row, ...) is only ever half a sentence, and translating half-sentences yields broken data. Reject
	// the tell-tale mid-sentence chunks: a lowercase-leading continuation, or a run of words that ends on
	// a linking word with no terminal punctuation. Whole labels ("More info", "Over the Mountains") and
	// full sentences (which end in punctuation) pass; only genuine fragments are dropped.
	private static final Pattern FRAG_END = Pattern.compile("(?i)\\b(the|a|an|to|of|for|in|on|at|with|from|by"
			+ "|and|or|as|your|my|his|her|its|their|our|that|which|you|we|they|is|are|was|were|be|been)$");
	// Live/dynamic labels that recur with a changing value (bank tab totals "Tab 2 (96.9K)", amount
	// buttons "Deposit-15000"): not translatable content, and they spam the file as the value ticks.
	private static final Pattern DYNAMIC = Pattern.compile(
			"\\([\\d.,]+[KkMmBb]\\)|^(?:Deposit|Withdraw)-\\d+$");

	@Inject
	private OsrscnConfig config;
	@Inject
	private ScheduledExecutorService executor;

	private final File file = new File(RuneLite.RUNELITE_DIR, "osrscn/missing.tsv");
	private final java.util.Set<String> seen = ConcurrentHashMap.newKeySet();
	private final ConcurrentLinkedQueue<String> pending = new ConcurrentLinkedQueue<>();
	private final AtomicBoolean loaded = new AtomicBoolean();
	private final AtomicBoolean flusherStarted = new AtomicBoolean();
	private volatile ScheduledFuture<?> flusherFuture;

	/**
	 * Record a table miss as a row matching the transcript TSV columns
	 * ({@code english<TAB>translation(empty)<TAB>category<TAB>sub_category<TAB>source}), so the collected
	 * file lines up with the data tables for later translating and merging. No-op when disabled, not real
	 * prose, or already seen this session / in the file. Safe on the client thread (disk I/O is deferred).
	 *
	 * @param english     game English that failed lookup (player names already substituted to placeholders)
	 * @param category    target table hint: {@code "dialogue"}, {@code "interface"}, {@code "name"}, ...
	 * @param subCategory entity context, e.g. the NPC name for dialogue (may be empty)
	 * @param source      speaker / origin, e.g. the NPC name or {@code "Player"} (may be empty)
	 */
	public void record(String english, String category, String subCategory, String source)
	{
		if (english == null || !config.collectMissing())
		{
			return;
		}
		String t = english.trim();
		if (t.isEmpty() || t.length() > MAX_LEN || t.indexOf('\t') >= 0 || t.indexOf('\n') >= 0
				|| !WORDY.matcher(t).find())
		{
			return;
		}
		if (isFragment(t) || DYNAMIC.matcher(t).find() || hasCjk(t))
		{
			return; // half a wrapped sentence, a live value label, or player-authored CJK content
		}
		ensureLoaded();
		if (seen.add(TranslationStore.normalize(t)))
		{
			pending.add(t + "\t\t" + clean(category) + "\t" + clean(subCategory) + "\t" + clean(source));
			ensureFlusher();
		}
	}

	/** A wrapped-line fragment (mid-sentence), which must not be collected as a translatable unit. */
	private static boolean isFragment(String t)
	{
		String bare = t.replaceAll("<[^>]+>", "").trim();
		if (bare.isEmpty())
		{
			return false;
		}
		if (Character.isLowerCase(bare.charAt(0)))
		{
			return true; // starts mid-sentence
		}
		char last = bare.charAt(bare.length() - 1);
		boolean terminal = last == '.' || last == '!' || last == '?' || last == ':' || last == ')' || last == '"';
		return !terminal && bare.split("\\s+").length >= 4 && FRAG_END.matcher(bare).find();
	}

	/** Game English is never CJK, so any CJK codepoint means player-authored content (setup / tab names). */
	private static boolean hasCjk(String s)
	{
		for (int i = 0; i < s.length(); i++)
		{
			if (s.charAt(i) >= 0x2E80)
			{
				return true;
			}
		}
		return false;
	}

	private static String clean(String s)
	{
		return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim();
	}

	private void ensureLoaded()
	{
		if (!loaded.compareAndSet(false, true))
		{
			return;
		}
		if (!file.exists())
		{
			return;
		}
		try (BufferedReader r = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8))
		{
			String line;
			boolean first = true;
			while ((line = r.readLine()) != null)
			{
				int tab = line.indexOf('\t');
				String en = tab == -1 ? line : line.substring(0, tab);
				if (first)
				{
					first = false;
					if (en.equalsIgnoreCase("english"))
					{
						continue; // skip header
					}
				}
				if (!en.isEmpty())
				{
					seen.add(TranslationStore.normalize(en));
				}
			}
		}
		catch (Exception e)
		{
			log.debug("OSRSCN: failed to read missing.tsv", e);
		}
	}

	private void ensureFlusher()
	{
		if (flusherStarted.compareAndSet(false, true))
		{
			// RuneLite's shared executor; plugin-hub disallows plugin-created threads / sleep / interrupt.
			flusherFuture = executor.scheduleWithFixedDelay(this::flush, 5, 5, TimeUnit.SECONDS);
		}
	}

	/** Final flush and cancel the periodic flusher (plugin shutdown); a re-enable restarts it lazily. */
	public void stop()
	{
		ScheduledFuture<?> f = flusherFuture;
		if (f != null)
		{
			f.cancel(false);
			flusherFuture = null;
		}
		flush();
		flusherStarted.set(false);
	}

	private synchronized void flush()
	{
		if (pending.isEmpty())
		{
			return;
		}
		//noinspection ResultOfMethodCallIgnored
		file.getParentFile().mkdirs();
		boolean newFile = !file.exists();
		try (FileWriter w = new FileWriter(file, StandardCharsets.UTF_8, true))
		{
			if (newFile)
			{
				w.write("english\ttranslation\tcategory\tsub_category\tsource\n");
			}
			String line;
			while ((line = pending.poll()) != null)
			{
				w.write(line + "\n");
			}
		}
		catch (Exception e)
		{
			log.debug("OSRSCN: failed to append missing.tsv", e);
		}
	}
}
