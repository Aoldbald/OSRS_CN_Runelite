package com.osrscn.translate;

import com.osrscn.OsrscnConfig;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;

/**
 * Local-only collector for game text that the lookup table can't translate, so the maintainer can
 * batch-translate it later and feed it back into the tables. Writes one entry per unique line to
 * {@code ~/.runelite/osrscn/missing_<install id>.tsv}; the plugin never uploads it (the side panel
 * offers a manual clipboard-and-browser submission the user performs themselves).
 *
 * <p>The file carries a stable per-install id (see {@link #installId()}) so files from different
 * contributors never collide; a legacy {@code missing.tsv} is renamed on first access.
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
	// Non-content classes observed in collected files (2026-07-16 field audit): bare count labels
	// ("0 cannonballs") and widget-join artifacts that start with punctuation (real sentences never
	// do). News broadcasts (trailing "|p" link marker, "Click here...") stay collectable - maintainer
	// decision 2026-07-17: announcements are player-visible and worth translating.
	private static final Pattern NOISE = Pattern.compile("^\\d+\\s+\\S+$|^[.,;)\\]]");
	// Parameterized transaction messages: one instance per traded item would collect forever; the
	// synthetic GE lookup (Translator.syntheticLookup) composes them from the name table instead.
	private static final Pattern TEMPLATE_MSG = Pattern.compile(
			"(?i)^Grand Exchange: (Finished )?(buying|selling) |^(Buy|Sell): \\d+ x |^(Bought|Sold): ");
	private static final Pattern TAGS = Pattern.compile("<[^>]+>");
	private static final Pattern WS = Pattern.compile("\\s+");

	// --- word salad (mid-fill scramble) -------------------------------------------------------------
	// The new-style skill guide (group 860) fills a paragraph word by word across frames, so a snapshot
	// taken mid-fill reads like a sentence but is scrambled ("Range , such as the one in Lumbridge
	// Castle.", "...select an to Accurate"). Rows like that were translated and shipped, and the upstream
	// settle gate proved leaky, so the check sits here: record() is the one choke point every collection
	// path goes through. Only the cheap, high-confidence tells from tools/clean_inbox.py (stages 3-4) are
	// ported - containment, cross-row dedup and library comparison need the whole batch and stay in the
	// pipeline. Precision over recall: a false reject silently loses a real missing line, a false accept
	// is one junk row the pipeline still catches.

	// A space before a comma or full stop is a relayout join scar in reflowed prose. NOT a general rule:
	// 137 published rows match it, including ordinary dialogue ("Hey , what are you doing here?"), which
	// is why isSalad() only runs on the reflow surfaces. See REFLOW_SOURCE.
	private static final Pattern FLOAT_PUNCT = Pattern.compile("\\s[,.](?:\\s|$)");
	// Word pairs English never produces: doubled articles, doubled prepositions, or an article glued
	// straight onto a preposition ("select an to Accurate"). Case is load-bearing - the trailing word is
	// matched lower-case only so real text like "the On switch" survives, and the article+preposition
	// rule takes a lower-case article only so a capitalised letter label ("The A to Z") survives too.
	// The lookahead keeps hyphenated words ("the in-game clock") out.
	private static final Pattern SALAD_PAIR = Pattern.compile(
			"\\b(?:[Tt]he|[Aa]n?)\\s+(?:the|an?)\\b"
			+ "|\\b(?:the|an?)\\s+(?:to|of|in|on|for|with|and|or|at|by|from)(?=\\s|$)"
			+ "|\\b(to|of|in|on|for|with)\\s+\\1\\b");
	// Text that continues in lower case after a full stop is two pieces joined mid-fill ("...combat
	// skills. and"). Needs two letters before the stop, so "e.g." / "i.e." never match; the remaining
	// abbreviations are listed out. URLs and decimals are safe (no space after the stop / not letters).
	private static final Pattern MID_PERIOD = Pattern.compile("([A-Za-z]{2,})\\.\\s+[a-z]");
	private static final Set<String> ABBREV = new HashSet<>(Arrays.asList(
			"etc", "eg", "ie", "vs", "approx", "min", "max", "sec", "hr", "lvl", "mr", "mrs", "dr", "st", "no"));

	@Inject
	private OsrscnConfig config;
	@Inject
	private ScheduledExecutorService executor;

	private static final String ID_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789";

	private final File dir = new File(RuneLite.RUNELITE_DIR, "osrscn");
	private volatile String installId;
	private volatile File file; // missing_<installId>.tsv, resolved (and legacy file migrated) lazily
	private final java.util.Set<String> seen = ConcurrentHashMap.newKeySet();
	private final ConcurrentLinkedQueue<String> pending = new ConcurrentLinkedQueue<>();
	private final AtomicBoolean loaded = new AtomicBoolean();
	private final AtomicBoolean flusherStarted = new AtomicBoolean();
	private final AtomicBoolean sessionMarked = new AtomicBoolean();
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
		if (t.isEmpty() || t.length() > MAX_LEN || t.indexOf('\t') >= 0 || t.indexOf('\n') >= 0)
		{
			return;
		}
		// Test for prose on the tag-stripped text, never the raw string: our own output is a run of
		// <img=N> char-image tags, and "img" is three letters, so a fully translated widget passed the
		// prose test and our own translations were collected as missing words. The "[player name]" mask
		// is dropped too, so a widget that was only a name is rejected.
		String bare = TAGS.matcher(t).replaceAll("").trim();
		if (!WORDY.matcher(bare.replace("[player name]", "")).find())
		{
			return;
		}
		if (isFragment(bare) || DYNAMIC.matcher(t).find() || NOISE.matcher(t).find()
				|| TEMPLATE_MSG.matcher(t).find() || hasCjk(t)
				|| ((isReflowSource(source) || isReflowSource(subCategory)) && isSalad(bare)))
		{
			// fragment, live value label, join noise, per-item template instance, player CJK, mid-fill salad
			return;
		}
		ensureLoaded();
		if (seen.add(dedupKey(t)))
		{
			pending.add(t + "\t\t" + clean(category) + "\t" + clean(subCategory) + "\t" + clean(source));
			ensureFlusher();
		}
	}

	private static final Pattern DEDUP_NUM = Pattern.compile("\\d+(?:[.,]\\d+)*");
	private static final Pattern DEDUP_COL = Pattern.compile("(?i)<colnum\\d+>|</col>");

	/**
	 * Dedup key only: colour placeholders stripped and numbers templated, so the value/colour variants
	 * of one template don't each burn a row. The stored sample keeps its original text (translators
	 * need the real numbers as context, and names like "Rune 2h sword" must not lose their digits).
	 */
	private static String dedupKey(String t)
	{
		return TranslationStore.normalize(DEDUP_NUM.matcher(DEDUP_COL.matcher(t).replaceAll(" ")).replaceAll("#"));
	}

	/**
	 * A wrapped-line fragment (mid-sentence), which must not be collected as a translatable unit.
	 *
	 * @param bare the text with tags stripped
	 */
	private static boolean isFragment(String bare)
	{
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
		return !terminal && WS.split(bare).length >= 4 && FRAG_END.matcher(bare).find();
	}

	/**
	 * Word salad only comes from the surfaces that reflow prose frame by frame (the new-style skill guide
	 * and the journal reconstruction), and their provenance tag is knowledge only the client has. Scoping
	 * the check to them keeps the shape rules away from ordinary dialogue and UI text, where they draw
	 * false positives. A client-side reject is invisible and can never be hot-fixed, so it stays narrow.
	 */
	private static boolean isReflowSource(String tag)
	{
		return tag != null && (tag.startsWith("skillguide") || tag.startsWith("journal"));
	}

	/**
	 * Word salad: a paragraph snapshot taken while the client was still filling it word by word. Only
	 * mechanical, high-confidence tells (see the pattern comments above); anything ambiguous is accepted
	 * and left to the offline cleaner.
	 *
	 * @param bare the text with tags stripped
	 */
	private static boolean isSalad(String bare)
	{
		if (bare.isEmpty())
		{
			return false;
		}
		if (FLOAT_PUNCT.matcher(bare).find() || SALAD_PAIR.matcher(bare).find())
		{
			return true;
		}
		Matcher m = MID_PERIOD.matcher(bare);
		while (m.find())
		{
			if (!ABBREV.contains(m.group(1).toLowerCase(java.util.Locale.ROOT)))
			{
				return true;
			}
		}
		return false;
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

	/**
	 * Stable per-install 6-char id, created once and kept in {@code .reflow_id}. It names the missing
	 * file (and any submission), so files from different contributors never collide and repeat
	 * submissions from the same install are recognisable.
	 */
	public synchronized String installId()
	{
		if (installId != null)
		{
			return installId;
		}
		File idFile = new File(dir, ".reflow_id");
		try
		{
			if (idFile.exists())
			{
				String s = new String(Files.readAllBytes(idFile.toPath()), StandardCharsets.UTF_8).trim();
				if (s.matches("[a-z0-9]{4,16}"))
				{
					installId = s;
					return s;
				}
			}
		}
		catch (Exception e)
		{
			log.debug("OSRSCN: failed to read .reflow_id", e);
		}
		StringBuilder sb = new StringBuilder(6);
		SecureRandom rnd = new SecureRandom();
		for (int i = 0; i < 6; i++)
		{
			sb.append(ID_CHARS.charAt(rnd.nextInt(ID_CHARS.length())));
		}
		installId = sb.toString();
		try
		{
			//noinspection ResultOfMethodCallIgnored
			dir.mkdirs();
			Files.write(idFile.toPath(), installId.getBytes(StandardCharsets.UTF_8));
		}
		catch (Exception e)
		{
			log.debug("OSRSCN: failed to write .reflow_id", e); // id stays session-only, still usable
		}
		return installId;
	}

	/** The per-install missing file; a legacy {@code missing.tsv} is renamed to it on first access. */
	public synchronized File missingFile()
	{
		if (file != null)
		{
			return file;
		}
		File f = new File(dir, "missing_" + installId() + ".tsv");
		File legacy = new File(dir, "missing.tsv");
		if (legacy.exists() && !f.exists())
		{
			try
			{
				Files.move(legacy.toPath(), f.toPath());
			}
			catch (Exception e)
			{
				log.debug("OSRSCN: failed to migrate missing.tsv", e); // keep both; nothing is lost
			}
		}
		file = f;
		return f;
	}

	/** Flush queued rows to disk now, so a submission that reads the file right after sees everything. */
	public void flushPending()
	{
		flush();
	}

	private void ensureLoaded()
	{
		if (!loaded.compareAndSet(false, true))
		{
			return;
		}
		File f = missingFile();
		if (!f.exists())
		{
			return;
		}
		try (BufferedReader r = Files.newBufferedReader(f.toPath(), StandardCharsets.UTF_8))
		{
			String line;
			boolean first = true;
			while ((line = r.readLine()) != null)
			{
				if (line.startsWith("#"))
				{
					continue; // session markers
				}
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
					seen.add(dedupKey(en));
				}
			}
		}
		catch (Exception e)
		{
			log.debug("OSRSCN: failed to read missing file", e);
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
		File f = missingFile();
		//noinspection ResultOfMethodCallIgnored
		f.getParentFile().mkdirs();
		boolean newFile = !f.exists();
		try (FileWriter w = new FileWriter(f, StandardCharsets.UTF_8, true))
		{
			if (newFile)
			{
				w.write("english\ttranslation\tcategory\tsub_category\tsource\n");
			}
			if (sessionMarked.compareAndSet(false, true))
			{
				// one marker per session, so it's obvious when each block of rows was collected;
				// every consumer (loader below / uploader / relay / clean_inbox) skips '#' lines
				w.write("# session " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm")
						.format(new java.util.Date()) + "\n");
			}
			String line;
			while ((line = pending.poll()) != null)
			{
				w.write(line + "\n");
			}
		}
		catch (Exception e)
		{
			log.debug("OSRSCN: failed to append missing file", e);
		}
	}
}
