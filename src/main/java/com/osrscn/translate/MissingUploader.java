package com.osrscn.translate;

import com.google.gson.Gson;
import com.osrscn.OsrscnConfig;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Voluntary uploader for the collected missing file. Off unless BOTH
 * {@link OsrscnConfig#collectMissing()} and {@link OsrscnConfig#uploadMissing()} are on (the latter
 * pops an explicit consent dialog when enabled). Periodically POSTs only the rows added since the
 * last successful upload - game English plus the anonymous install id, never chat or account data -
 * to the maintainer's relay, which files them into the private data repo's inbox.
 *
 * <p>A watermark file ({@code .upload_state}: {@code <missing file name>\t<lines sent>}) makes
 * uploads incremental; a failed local progress commit can retry an already received batch.
 */
@Slf4j
@Singleton
public class MissingUploader
{
	// Cloudflare Worker endpoint (see tools/reflow_worker/DEPLOY.md). Empty = feature dormant:
	// nothing is scheduled and the config toggle has no effect.
	static final String UPLOAD_URL = "https://osrscn-reflow.0shrug.workers.dev";

	private static final MediaType JSON = MediaType.parse("application/json");
	private static final int MAX_ROWS_PER_POST = 400;
	private static final long INITIAL_DELAY_S = 60;
	private static final long PERIOD_S = 30 * 60;

	@Inject
	private OsrscnConfig config;
	@Inject
	private MissingCollector collector;
	@Inject
	private ScheduledExecutorService executor;
	@Inject
	private OkHttpClient httpClient;
	@Inject
	private Gson gson;

	private volatile ScheduledFuture<?> future;
	private final AtomicBoolean inFlight = new AtomicBoolean();

	/** Schedule the periodic check (plugin startUp). No-op while no endpoint is configured. */
	public void start()
	{
		if (UPLOAD_URL.isEmpty() || future != null)
		{
			return;
		}
		future = executor.scheduleWithFixedDelay(this::tick, INITIAL_DELAY_S, PERIOD_S, TimeUnit.SECONDS);
	}

	// Told how many rows just went out, so the plugin can say so in chat. Data leaving the machine
	// should be visible to the person it belongs to, and a silent button looks broken besides.
	private volatile java.util.function.IntConsumer uploadListener;

	public void setUploadListener(java.util.function.IntConsumer listener)
	{
		uploadListener = listener;
	}

	/** Approved pending data rows; -1 means the source/progress could not be checked. */
	public int pendingRows()
	{
		try
		{
			File f = collector.missingFile();
			if (!f.exists())
			{
				return 0;
			}
			List<String> snapshot = collector.uploadSnapshot();
			int lines = snapshot.size();
			String state = stateText();
			int sent = readWatermark(f.getName(), snapshot, state);
			// No watermark yet: the first tick starts it at the current size (the backlog is never
			// sent), so nothing is actually pending.
			if (sent == 0 && (state.trim().isEmpty() || state.trim().split("\t", -1).length == 2)) return 0;
			int pending = 0;
			for (int i = sent; i < lines; i++)
			{
				String row = snapshot.get(i);
				if (i > 0 && MissingPrivacy.rejection(row) == null
						&& MissingPrivacy.attested(snapshot.get(i - 1), row)) pending++;
			}
			return pending;
		}
		catch (Exception e)
		{
			log.debug("OSRSCN: missing upload status unavailable (state_or_io)");
			return -1;
		}
	}

	/** Manual trigger from the side panel (quick test / impatient contributors). */
	public void uploadNow()
	{
		if (!UPLOAD_URL.isEmpty())
		{
			executor.execute(this::tick);
		}
	}

	/** Cancel the periodic check (plugin shutDown). */
	public void stop()
	{
		ScheduledFuture<?> f = future;
		if (f != null)
		{
			f.cancel(false);
			future = null;
		}
	}

	private void tick()
	{
		if (!config.collectMissing() || !config.uploadMissing() || !inFlight.compareAndSet(false, true))
		{
			return;
		}
		boolean handedOff = false;
		try
		{
			handedOff = uploadNextBatch();
		}
		catch (Exception e)
		{
			log.debug("OSRSCN: missing upload failed");
		}
		finally
		{
			if (!handedOff)
			{
				inFlight.set(false); // async callback owns the flag once the call is enqueued
			}
		}
	}

	/** @return true if an async call was enqueued (the callback releases {@link #inFlight}) */
	private boolean uploadNextBatch() throws IOException
	{
		List<String> lines = collector.uploadSnapshot();
		File f = collector.missingFile();
		if (!f.exists())
		{
			return false;
		}
		String priorState = stateText();
		int sent = readWatermark(f.getName(), lines, priorState); // line 0 is the header, never sent
		if (sent == 0 && (priorState.trim().isEmpty() || priorState.trim().split("\t", -1).length == 2))
		{
			// First upload for this file: start the watermark at the current size. The backlog was
			// collected before the client-side quality gates existed and must never be replayed.
			commitWatermark(f, lines, lines.size(), priorState);
			return false;
		}
		if (lines.size() <= sent)
		{
			return false;
		}
		int end = Math.min(lines.size(), sent + MAX_ROWS_PER_POST);
		List<String> batch = approvedRows(f, lines, sent, end);
		if (batch.isEmpty())
		{
			commitWatermark(f, lines, end, priorState);
			return false;
		}
		Map<String, Object> body = new HashMap<>();
		body.put("v", 1);
		body.put("id", collector.installId());
		body.put("rows", batch);
		Request req = new Request.Builder()
				.url(UPLOAD_URL)
				.post(RequestBody.create(JSON, gson.toJson(body)))
				.build();
		final int newWatermark = end;
		final int rowsSent = batch.size(); // '#' markers advance the watermark but are never sent
		httpClient.newCall(req).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("OSRSCN: missing upload failed");
				inFlight.set(false);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					if (r.isSuccessful())
					{
						commitWatermark(f, lines, newWatermark, priorState);
						log.debug("OSRSCN: uploaded missing rows up to line {}", newWatermark);
						java.util.function.IntConsumer l = uploadListener;
						if (l != null)
						{
							l.accept(rowsSent);
						}
					}
				}
				catch (IOException e)
				{
					// HTTP may have succeeded; retain the old cursor rather than lose pending rows.
					log.debug("OSRSCN: missing upload progress not committed");
				}
				finally
				{
					inFlight.set(false);
				}
			}
		});
		return true;
	}

	private File stateFile()
	{
		return new File(collector.missingFile().getParentFile(), ".upload_state");
	}

	/** Filtered manual submission, sharing exactly the automatic upload boundary. */
	public static final class Submission
	{
		public final String content;
		public final int rows;
		public final int held;
		private Submission(List<String> safe, int held)
		{
			this.rows = safe.size(); this.held = held;
			this.content = safe.isEmpty() ? "" : "english\ttranslation\tcategory\tsub_category\tsource\n"
					+ String.join("\n", safe) + "\n";
		}
	}

	public Submission manualSubmission() throws IOException
	{
		List<String> lines = collector.uploadSnapshot();
		int start = Math.min(1, lines.size());
		List<String> safe = approvedRows(collector.missingFile(), lines, start, lines.size());
		int total = 0;
		for (int i = start; i < lines.size(); i++)
			if (!lines.get(i).trim().isEmpty() && !lines.get(i).startsWith("#")) total++;
		return new Submission(safe, total - safe.size());
	}

	private List<String> approvedRows(File f, List<String> lines, int sent, int end) throws IOException
	{
		List<String> batch = new ArrayList<>();
		StringBuilder held = new StringBuilder();
		for (int i = sent; i < end; i++)
		{
			String l = lines.get(i);
			if (l.trim().isEmpty() || l.startsWith("#")) continue;
			if (i == 0 && l.equals("english\ttranslation\tcategory\tsub_category\tsource")) continue;
			String reason = MissingPrivacy.rejection(l);
			if (reason == null && (i == 0 || !MissingPrivacy.attested(lines.get(i - 1), l)))
				reason = "unattested";
			if (reason == null) batch.add(l);
			else held.append(i + 1).append('\t').append(MissingPrivacy.hash(l))
					.append('\t').append(reason).append('\n');
		}
		// Save decisions before either HTTP or a watermark can acknowledge the scanned interval.
		if (held.length() > 0) recordHolds(f, lines, sent, end, held.toString());
		return batch;
	}

	private String stateText() throws IOException
	{
		return stateFile().exists() ? Files.readString(stateFile().toPath(), StandardCharsets.UTF_8) : "";
	}

	/** Accept the legacy cursor, but bind every new cursor to the inspected file prefix. */
	private int readWatermark(String fileName, List<String> lines, String rawState) throws IOException
	{
		String state = rawState.trim();
		if (state.isEmpty()) return 0;
		String[] c = state.split("\t", -1);
		if ((c.length != 2 && c.length != 3) || !c[0].equals(fileName))
			throw new IOException("Missing upload state identity mismatch");
		final int sent;
		try { sent = Integer.parseInt(c[1]); }
		catch (NumberFormatException e) { throw new IOException("Invalid missing upload cursor"); }
		if (sent < 0 || sent > lines.size()) throw new IOException("Missing upload file was truncated");
		if (c.length == 3 && !c[2].equals(prefixHash(lines, sent)))
			throw new IOException("Missing upload prefix changed");
		return sent;
	}

	private static String prefixHash(List<String> lines, int end)
	{
		return MissingPrivacy.hash(String.join("\n", lines.subList(0, end)) + "\n");
	}

	private void recordHolds(File f, List<String> lines, int start, int end, String held) throws IOException
	{
		String receipt = MissingPrivacy.VERSION + "\t" + MissingPrivacy.hash(f.getName()) + "\t"
				+ start + "\t" + end + "\t" + prefixHash(lines, end) + "\n" + held;
		Path directory = f.toPath().getParent().resolve(".missing_privacy_holds");
		Files.createDirectories(directory);
		Path target = directory.resolve(MissingPrivacy.hash(receipt) + ".tsv");
		if (Files.exists(target))
		{
			if (!receipt.equals(Files.readString(target, StandardCharsets.UTF_8)))
				throw new IOException("Missing hold receipt changed");
			return;
		}
		writeAtomic(target, receipt);
	}

	private void commitWatermark(File f, List<String> snapshot, int end, String priorState) throws IOException
	{
		synchronized (collector)
		{
			if (!f.equals(collector.missingFile()) || !priorState.equals(stateText()))
				throw new IOException("Missing upload state changed during request");
			List<String> current = collector.uploadSnapshot();
			if (current.size() < end || !prefixHash(current, end).equals(prefixHash(snapshot, end)))
				throw new IOException("Missing upload file changed during request");
			writeWatermark(f.getName(), end, snapshot);
		}
	}

	private void writeWatermark(String fileName, int end, List<String> lines) throws IOException
	{
		writeAtomic(stateFile().toPath(), fileName + "\t" + end + "\t" + prefixHash(lines, end));
	}

	/** Failure leaves the previous state and any temporary evidence intact. */
	private static void writeAtomic(Path target, String value) throws IOException
	{
		Path temporary = Files.createTempFile(target.getParent(), ".missing-write-", ".tmp");
		try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE))
		{
			ByteBuffer bytes = StandardCharsets.UTF_8.encode(value);
			while (bytes.hasRemaining()) channel.write(bytes);
			channel.force(true);
		}
		Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
	}
}
