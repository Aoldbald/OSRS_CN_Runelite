package com.osrscn.translate;

import com.google.gson.Gson;
import com.osrscn.OsrscnConfig;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
 * uploads incremental and idempotent; on any failure it simply retries next period.
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

	/** Rows past the upload watermark (display only - the batch builder re-reads on upload). */
	public int pendingRows()
	{
		try
		{
			File f = collector.missingFile();
			if (!f.exists())
			{
				return 0;
			}
			int lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8).size();
			return Math.max(0, lines - Math.max(1, readWatermark(f.getName())));
		}
		catch (Exception e)
		{
			return 0;
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
			log.debug("OSRSCN: missing upload failed", e);
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
		collector.flushPending();
		File f = collector.missingFile();
		if (!f.exists())
		{
			return false;
		}
		List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
		int sent = Math.max(1, readWatermark(f.getName())); // line 0 is the header, never sent
		if (lines.size() <= sent)
		{
			return false;
		}
		int end = Math.min(lines.size(), sent + MAX_ROWS_PER_POST);
		List<String> batch = new ArrayList<>();
		for (int i = sent; i < end; i++)
		{
			String l = lines.get(i);
			if (!l.trim().isEmpty() && !l.startsWith("#"))
			{
				batch.add(l); // '#' session markers stay local
			}
		}
		if (batch.isEmpty())
		{
			writeWatermark(f.getName(), end);
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
		final String fileName = f.getName();
		httpClient.newCall(req).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("OSRSCN: missing upload failed", e);
				inFlight.set(false);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					if (r.isSuccessful())
					{
						writeWatermark(fileName, newWatermark);
						log.debug("OSRSCN: uploaded missing rows up to line {}", newWatermark);
					}
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

	/** Lines already sent for this missing file; 0 when none / the file name changed (new id). */
	private int readWatermark(String fileName)
	{
		try
		{
			File s = stateFile();
			if (!s.exists())
			{
				return 0;
			}
			String[] c = new String(Files.readAllBytes(s.toPath()), StandardCharsets.UTF_8).trim().split("\t");
			if (c.length == 2 && c[0].equals(fileName))
			{
				return Integer.parseInt(c[1]);
			}
		}
		catch (Exception e)
		{
			log.debug("OSRSCN: failed to read .upload_state", e);
		}
		return 0;
	}

	private synchronized void writeWatermark(String fileName, int lines)
	{
		try
		{
			Files.write(stateFile().toPath(), (fileName + "\t" + lines).getBytes(StandardCharsets.UTF_8));
		}
		catch (Exception e)
		{
			log.debug("OSRSCN: failed to write .upload_state", e);
		}
	}
}
