package com.osrscn.translate;

import com.google.gson.Gson;
import com.osrscn.OsrscnConfig;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.MediaType;
import okio.Buffer;
import org.junit.Test;
import static org.junit.Assert.*;

/** Frozen inbox structures with fictional identities; no production files or HTTP. */
public class MissingPrivacyTest
{
    private static final String HEADER = "english\ttranslation\tcategory\tsub_category\tsource";
    private static final String RANK = "The top three crab crushers were Test\u00a0One, 12345, and Test Two!";
    private static final String SAFE = "You search the crate.";

    private static String[] channelIdentityFrames()
    {
        return new String[]{"Now talking in chat-channel River 42",
            "You are now a guest of Fictional Clan.",
            "You are now a guest of Fictional Clan.<br>To talk, start each line of chat with /// or /gc.",
            "<col=ff0000>You are now a guest of Fictional Clan.<br>To talk, start each line of chat with /// or /gc.</col>",
            "<col=ff0000>Now talking in chat-channel River 42</col>",
            "<col=ff0000>You are now a guest of Fictional Clan.</col>",
            "Now<br>talking in chat-channel River 42",
            "You are now<br/>a guest of Fictional Clan.",
            "Requesting access to chat as guest of Fictional Clan...",
            "<col=ef1020>Requesting access to chat as guest of Fictional Clan...</col>",
            "Now talking in chat-channel River 42\u0001",
            "You are now a guest of Fictional Clan.\u0001"};
    }

    private static void set(Object target, String name, Object value) throws Exception
    {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true); field.set(target, value);
    }

    private static String row(String text) { return text + "\t\tgameText\t\t"; }

    private static final class Fixture
    {
        final Path root;
        final MissingCollector collector = new MissingCollector();
        final MissingUploader uploader = new MissingUploader();
        final List<Request> requests = new ArrayList<>();
        final List<Callback> callbacks = new ArrayList<>();
        boolean enabled = true;
        boolean throwOnEnqueue;

        Fixture() throws Exception
        {
            String base = System.getProperty("osrscn.test.fixtureRoot");
            assertNotNull(base);
            root = Files.createTempDirectory(Path.of(base), "missing-privacy-");
            OsrscnConfig config = (OsrscnConfig) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{OsrscnConfig.class}, (p,m,a) ->
                    m.getName().equals("collectMissing") || m.getName().equals("uploadMissing") ? enabled : null);
            ScheduledExecutorService executor = (ScheduledExecutorService) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{ScheduledExecutorService.class}, (p,m,a) -> null);
            set(collector, "dir", root.toFile()); set(collector, "installId", "test01");
            set(collector, "config", config); set(collector, "executor", executor);
            set(uploader, "config", config); set(uploader, "collector", collector); set(uploader, "gson", new Gson());
            set(uploader, "httpClient", new OkHttpClient() {
                @Override public Call newCall(Request request)
                {
                    requests.add(request);
                    return (Call) Proxy.newProxyInstance(Call.class.getClassLoader(), new Class<?>[]{Call.class},
                        (p,m,a) -> {
                            if (m.getName().equals("enqueue")) {
                                if (throwOnEnqueue) throw new IllegalStateException("fictional dispatch failure");
                                callbacks.add((Callback) a[0]);
                            }
                            return null;
                        });
                }
            });
        }
        Path file() { return collector.missingFile().toPath(); }
        Path state() { return root.resolve(".upload_state"); }
        void seed(String... rows) throws Exception
        {
            List<String> lines = new ArrayList<>(); lines.add(HEADER); lines.addAll(Arrays.asList(rows));
            Files.write(file(), lines, StandardCharsets.UTF_8);
            Files.writeString(state(), file().getFileName() + "\t1");
        }
        void tick() throws Exception
        {
            Method method = MissingUploader.class.getDeclaredMethod("tick"); method.setAccessible(true); method.invoke(uploader);
        }
        void respond(int code) throws Exception
        {
            callbacks.get(callbacks.size() - 1).onResponse(null, new Response.Builder()
                .request(requests.get(requests.size() - 1)).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
                .body(ResponseBody.create(MediaType.parse("text/plain"), "fixture")).build());
        }
        List<String> sent() throws Exception
        {
            Buffer buffer = new Buffer(); requests.get(requests.size() - 1).body().writeTo(buffer);
            com.google.gson.JsonObject body = new Gson().fromJson(buffer.readUtf8(), com.google.gson.JsonObject.class);
            assertEquals(1, body.get("v").getAsInt()); assertEquals(3, body.entrySet().size());
            List<String> rows = new ArrayList<>(); body.getAsJsonArray("rows").forEach(v -> rows.add(v.getAsString()));
            return rows;
        }
        int cursor() throws Exception { return Integer.parseInt(Files.readString(state()).split("\t")[1]); }
        long holdFiles() throws Exception
        {
            Path dir = root.resolve(".missing_privacy_holds");
            if (!Files.exists(dir)) return 0;
            try (java.util.stream.Stream<Path> files = Files.list(dir)) { return files.filter(p -> p.toString().endsWith(".tsv")).count(); }
        }
        List<String> data() throws Exception
        {
            if (!Files.exists(file())) return new ArrayList<>();
            List<String> result = new ArrayList<>();
            for (String line : Files.readAllLines(file())) if (!line.isBlank() && !line.startsWith("#") && !line.equals(HEADER)) result.add(line);
            return result;
        }
    }

    @Test public void completeRankingIsBlockedAtTheCommonCollector() throws Exception
    {
        Fixture f = new Fixture(); f.collector.record(RANK, "gameText", "", ""); f.collector.flushPending();
        assertTrue("Identity-bearing ranking must not enter the missing file", f.data().isEmpty());
    }

    @Test public void privateGroupIsBlockedEvenAfterPlayerMasking() throws Exception
    {
        Fixture f = new Fixture();
        f.collector.record("[player name]'s group is: Fictional Team. It has 3 members;", "interface", "", "");
        f.collector.flushPending(); assertTrue(f.data().isEmpty());
    }

    @Test public void channelIdentityFramesStayOutOfNewMissingRows() throws Exception
    {
        for (String text : channelIdentityFrames())
        {
            Fixture f = new Fixture();
            for (int column : new int[]{0, 3, 4})
                f.collector.record(column == 0 ? text : SAFE, "interface",
                    column == 3 ? text : "", column == 4 ? text : "");
            f.collector.flushPending();
            assertTrue("Identity frame or context must not be collected: " + text, f.data().isEmpty());
            f.collector.record(SAFE, "gameText", "", ""); f.collector.flushPending();
            assertEquals(Arrays.asList(row(SAFE)), f.data());
        }
    }

    @Test public void channelIdentityFramesStayOutOfManualAndAutomaticSubmissions() throws Exception
    {
        for (String text : channelIdentityFrames())
        {
            Fixture f = new Fixture(); String unsafe = row(text); String safe = row(SAFE);
            f.seed(MissingPrivacy.marker(unsafe), unsafe, MissingPrivacy.marker(safe), safe);
            byte[] sourceBefore = Files.readAllBytes(f.file());
            byte[] cursorBefore = Files.readAllBytes(f.state());
            MissingUploader.Submission manual = f.uploader.manualSubmission();
            assertEquals(1, manual.rows); assertEquals(1, manual.held);
            assertEquals(HEADER + "\n" + safe + "\n", manual.content);
            assertArrayEquals(sourceBefore, Files.readAllBytes(f.file()));
            assertArrayEquals(cursorBefore, Files.readAllBytes(f.state()));
            assertTrue(f.requests.isEmpty());

            f.tick();
            assertEquals(Arrays.asList(safe), f.sent());
            assertArrayEquals(sourceBefore, Files.readAllBytes(f.file()));
            assertArrayEquals(cursorBefore, Files.readAllBytes(f.state()));
            f.respond(200); assertEquals(5, f.cursor());
            assertArrayEquals(sourceBefore, Files.readAllBytes(f.file()));
        }
    }

    @Test public void invalidUnicodeIsRejectedButValidPairsPass() throws Exception
    {
        assertEquals("invalid_unicode", MissingPrivacy.rejection(row("Lone high \uD800 surrogate")));
        assertEquals("invalid_unicode", MissingPrivacy.rejection(row("Lone low \uDC00 surrogate")));
        assertEquals("invalid_unicode", MissingPrivacy.rejection(row("Trailing high \uD83C")));
        assertEquals("invalid_unicode", MissingPrivacy.rejection(SAFE + "\t\tgameText\tsub \uD800\t"));
        assertNull(MissingPrivacy.rejection(row("Valid pair 🌟 here")));
        Fixture f = new Fixture(); f.collector.record("Broken \uD800 text", "gameText", "", "");
        f.collector.flushPending(); assertTrue(f.data().isEmpty());
    }

    @Test public void legacyRowsRemainLocalEvenWithAnExistingWatermark() throws Exception
    {
        Fixture f = new Fixture(); f.seed(row(SAFE), row(RANK)); byte[] before = Files.readAllBytes(f.file());
        f.tick(); assertTrue("Unreviewed legacy rows must not be sent", f.requests.isEmpty());
        assertArrayEquals(before, Files.readAllBytes(f.file()));
    }

    @Test public void ordinaryGameTextStillCollects() throws Exception
    {
        Fixture f = new Fixture(); f.collector.record(SAFE, "gameText", "", ""); f.collector.flushPending();
        assertEquals(Arrays.asList(row(SAFE)), f.data());
    }

    @Test public void completeIdentityFamiliesAreBlockedAcrossAllColumns() throws Exception
    {
        for (String text : new String[]{RANK, "The top crab crusher was 12345!", "Trading With: Test One",
                "Test One has 12 free inventory slots.", "Test One offers:",
                "[player name]'s group is: Fictional Team. It has 3 members; [player name],"})
            for (int column : new int[]{0, 3, 4}) {
                Fixture f = new Fixture();
                f.collector.record(column == 0 ? text : SAFE, "interface", column == 3 ? text : "", column == 4 ? text : "");
                f.collector.flushPending(); assertTrue("Identity family/column " + column, f.data().isEmpty());
            }
    }

    @Test public void styledAndUnicodeRankingHeadersCannotBypassTheGate() throws Exception
    {
        for (String text : new String[]{"<col=ff0000>The top three crab crushers were</col> Test One, 123, and Test Two!",
                "The top\u00a0three crab crushers were Test One, 123, and Test Two!",
                "The top three crab crushers were [player name], [player name], and [player name]!",
                "The top three crab crushers were an incomplete list"}) {
            Fixture f = new Fixture(); f.collector.record(text, "gameText", "", "");
            f.collector.flushPending(); assertTrue(f.data().isEmpty());
        }
    }

    @Test public void ordinaryDialogueAndNpcContextRemainIntact() throws Exception
    {
        Fixture f = new Fixture();
        for (String text : new String[]{"Thank you so much, sir.", "Are you sure you want to do that?", "Hello, [player name]."})
            f.collector.record(text, "dialogue", "King Roald", "King Roald");
        f.collector.flushPending(); assertEquals(3, f.data().size());
        for (String line : f.data()) assertTrue(line.endsWith("\tdialogue\tKing Roald\tKing Roald"));
    }

    @Test public void collectorMarksEveryNewRowAndUploadCountsOnlyData() throws Exception
    {
        Fixture f = new Fixture(); f.seed(); f.collector.record(SAFE, "gameText", "", ""); f.collector.flushPending();
        assertEquals(1, f.uploader.pendingRows()); f.tick(); assertEquals(Arrays.asList(row(SAFE)), f.sent());
        f.respond(200); assertEquals(Files.readAllLines(f.file()).size(), f.cursor()); assertEquals(0, f.uploader.pendingRows());
    }

    @Test public void mixedBatchHoldsLegacyRowsBeforeSendingSafeRows() throws Exception
    {
        Fixture f = new Fixture(); String safe = row(SAFE);
        f.seed(MissingPrivacy.marker(safe), safe, row(RANK), MissingPrivacy.marker(row("You search the chest.")), row("You search the chest."));
        byte[] before = Files.readAllBytes(f.file()); f.tick();
        assertEquals(1, f.holdFiles()); assertEquals(2, f.sent().size()); assertFalse(f.sent().contains(row(RANK)));
        assertEquals(1, f.cursor()); f.respond(200); assertEquals(6, f.cursor()); assertArrayEquals(before, Files.readAllBytes(f.file()));
        try (java.util.stream.Stream<Path> files = Files.list(f.root.resolve(".missing_privacy_holds"))) {
            for (Path file : (Iterable<Path>) files::iterator) {
                String content = Files.readString(file); assertFalse(content.contains(RANK)); assertFalse(content.contains("Test One"));
            }
        }
    }

    @Test public void attestationNeverOverridesTheFinalIdentityGate() throws Exception
    {
        Fixture f = new Fixture(); String unsafe = row(RANK); f.seed(MissingPrivacy.marker(unsafe), unsafe);
        f.tick(); assertTrue(f.requests.isEmpty()); assertEquals(3, f.cursor()); assertEquals(1, f.holdFiles());
    }

    @Test public void changingAnyColumnInvalidatesTheReceipt() throws Exception
    {
        String original = row(SAFE);
        for (int i = 0; i < 5; i++) {
            Fixture f = new Fixture(); String[] fields = original.split("\t", -1); fields[i] += "changed";
            f.seed(MissingPrivacy.marker(original), String.join("\t", fields)); f.tick();
            assertTrue(f.requests.isEmpty()); assertEquals(1, f.holdFiles());
        }
    }

    @Test public void missingWrongVersionAndNonAdjacentReceiptsAreHeld() throws Exception
    {
        String safe = row(SAFE);
        for (String marker : new String[]{"# old-policy", MissingPrivacy.marker(safe).replace("privacy-1", "privacy-0"), "# session"}) {
            Fixture f = new Fixture(); f.seed(MissingPrivacy.marker(safe), marker, safe); f.tick(); assertTrue(f.requests.isEmpty());
        }
    }

    @Test public void controlsAndMalformedRowsAreNotRepairedIntoApproval() throws Exception
    {
        for (String invalid : new String[]{row(SAFE) + "\textra", row(SAFE).replace("gameText", "playerChat"),
                SAFE + "\ttranslated\tgameText\t\t", SAFE + "\t\tgameText\t\u0000\t"}) {
            Fixture f = new Fixture(); f.seed(MissingPrivacy.marker(invalid), invalid); f.tick(); assertTrue(f.requests.isEmpty());
        }
        Fixture f = new Fixture(); f.collector.record(SAFE, "gameText", "npc\rprivate", "");
        f.collector.flushPending(); assertTrue(f.data().isEmpty());
    }

    @Test public void failedHoldReceiptDoesNotSendOrAdvance() throws Exception
    {
        Fixture f = new Fixture(); String safe = row(SAFE); f.seed(MissingPrivacy.marker(safe), safe, row(RANK));
        Files.writeString(f.root.resolve(".missing_privacy_holds"), "blocked path");
        f.tick(); assertTrue(f.requests.isEmpty()); assertEquals(1, f.cursor());
    }

    @Test public void failedTransportResponseAndDispatchRetainTheWholeInterval() throws Exception
    {
        for (int failure : new int[]{0, 1, 2}) {
            Fixture f = new Fixture(); String safe = row(SAFE); f.seed(MissingPrivacy.marker(safe), safe, row(RANK));
            f.throwOnEnqueue = failure == 2; f.tick();
            if (failure == 0) f.callbacks.get(0).onFailure(null, new IOException("fictional failure"));
            if (failure == 1) f.respond(503);
            assertEquals(1, f.cursor()); assertEquals(1, f.holdFiles());
            f.throwOnEnqueue = false; f.tick(); assertEquals(2, f.requests.size()); assertEquals(1, f.holdFiles());
            assertEquals(Arrays.asList(safe), f.sent()); f.respond(200); assertEquals(4, f.cursor());
        }
    }

    @Test public void batchBoundaryDoesNotSeparateReceiptFromItsRow() throws Exception
    {
        Fixture f = new Fixture(); String safe = row(SAFE); List<String> rows = new ArrayList<>();
        for (int i = 0; i < 399; i++) rows.add("# fixture");
        rows.add(MissingPrivacy.marker(safe)); rows.add(safe); f.seed(rows.toArray(new String[0]));
        f.tick(); assertTrue(f.requests.isEmpty()); assertEquals(401, f.cursor());
        f.tick(); assertEquals(Arrays.asList(safe), f.sent()); f.respond(200); assertEquals(402, f.cursor());
    }

    @Test public void appendDuringRequestRemainsPendingAfterSuccess() throws Exception
    {
        Fixture f = new Fixture(); String safe = row(SAFE); f.seed(MissingPrivacy.marker(safe), safe); f.tick();
        f.collector.record("You search the chest.", "gameText", "", ""); f.collector.flushPending();
        f.respond(200); assertEquals(3, f.cursor()); assertEquals(1, f.uploader.pendingRows());
        f.tick(); assertEquals(Arrays.asList(row("You search the chest.")), f.sent());
    }

    @Test public void changedSourceOrStateMakesAStaleCallbackFailClosed() throws Exception
    {
        for (boolean state : new boolean[]{false, true}) {
            Fixture f = new Fixture(); String safe = row(SAFE); f.seed(MissingPrivacy.marker(safe), safe); f.tick();
            if (state) Files.writeString(f.state(), "another-file\t1");
            else Files.writeString(f.file(), HEADER + "\nreplacement\n");
            String before = Files.readString(f.state()); f.respond(200); assertEquals(before, Files.readString(f.state()));
        }
    }

    @Test public void boundCursorRejectsSameNameReplacementAndTruncation() throws Exception
    {
        for (boolean truncate : new boolean[]{false, true}) {
            Fixture f = new Fixture(); f.seed(row(SAFE)); f.tick(); assertEquals(2, f.cursor());
            String before = Files.readString(f.state());
            Files.writeString(f.file(), truncate ? HEADER + "\n" : HEADER + "\n" + row("You search the chest.") + "\n");
            f.tick(); assertTrue(f.requests.isEmpty()); assertEquals(before, Files.readString(f.state()));
        }
    }

    @Test public void initialUploadStillSkipsAllPreexistingRows() throws Exception
    {
        Fixture f = new Fixture(); String safe = row(SAFE); f.seed(MissingPrivacy.marker(safe), safe);
        Files.move(f.state(), f.root.resolve("prior-state-evidence"));
        f.tick(); assertTrue(f.requests.isEmpty()); assertEquals(3, f.cursor()); assertEquals(0, f.holdFiles());
    }

    @Test public void disabledUploadDoesNotWriteProgressOrDispatch() throws Exception
    {
        Fixture f = new Fixture(); f.seed(row(RANK)); f.enabled = false;
        f.tick(); assertTrue(f.requests.isEmpty()); assertEquals(1, f.cursor()); assertEquals(0, f.holdFiles());
    }

    @Test public void failedFlushKeepsQueuedRowsForRetry() throws Exception
    {
        Fixture f = new Fixture(); Files.createDirectory(f.file()); f.collector.record(SAFE, "gameText", "", "");
        f.collector.flushPending(); f.tick(); assertTrue(f.requests.isEmpty()); assertFalse(Files.exists(f.state()));
        Files.move(f.file(), f.root.resolve("blocked-file-evidence")); f.collector.flushPending();
        assertEquals(Arrays.asList(row(SAFE)), f.data());
    }

    @Test public void manualSubmissionUsesTheSameGateWithoutAdvancingUploadState() throws Exception
    {
        Fixture f = new Fixture(); String safe = row(SAFE);
        f.seed(MissingPrivacy.marker(safe), safe, row(RANK)); String before = Files.readString(f.state());
        MissingUploader.Submission submission = f.uploader.manualSubmission();
        assertEquals(1, submission.rows); assertEquals(1, submission.held);
        assertEquals(HEADER + "\n" + safe + "\n", submission.content);
        assertEquals(before, Files.readString(f.state())); assertTrue(f.requests.isEmpty()); assertEquals(1, f.holdFiles());
    }

    @Test public void manualSubmissionDoesNotExportAnythingWhenReceiptStorageFails() throws Exception
    {
        Fixture f = new Fixture(); String safe = row(SAFE); f.seed(MissingPrivacy.marker(safe), safe, row(RANK));
        Files.writeString(f.root.resolve(".missing_privacy_holds"), "blocked");
        try { f.uploader.manualSubmission(); fail("Export must fail before any content is returned"); }
        catch (IOException expected) { }
        assertEquals(1, f.cursor()); assertTrue(f.requests.isEmpty());
    }

    @Test public void manualSnapshotStaysFixedWhenMoreRowsArrive() throws Exception
    {
        Fixture f = new Fixture(); f.seed(); f.collector.record(SAFE, "gameText", "", "");
        MissingUploader.Submission first = f.uploader.manualSubmission();
        f.collector.record("You search the chest.", "gameText", "", "");
        MissingUploader.Submission second = f.uploader.manualSubmission();
        assertEquals(1, first.rows); assertEquals(2, second.rows); assertFalse(first.content.contains("chest"));
        assertEquals(1, f.cursor());
    }

    @Test public void allHeldManualRowsProduceNoClipboardPayload() throws Exception
    {
        Fixture f = new Fixture(); f.seed(row(SAFE), row(RANK));
        MissingUploader.Submission submission = f.uploader.manualSubmission();
        assertEquals("", submission.content); assertEquals(0, submission.rows); assertEquals(2, submission.held);
        assertEquals(1, f.cursor()); assertTrue(f.requests.isEmpty());
    }

    @Test public void unreadableManualSourceProducesAnErrorNotRawContent() throws Exception
    {
        Fixture f = new Fixture(); Files.createDirectory(f.file());
        try { f.uploader.manualSubmission(); fail("Unreadable input must fail closed"); }
        catch (IOException expected) { }
        assertFalse(Files.exists(f.state()));
    }

    @Test public void stateIdentityConflictIsVisibleAndDoesNotOverwriteTheCursor() throws Exception
    {
        Fixture f = new Fixture(); f.seed(row(SAFE)); Files.writeString(f.state(), "different-file\t1");
        assertEquals(-1, f.uploader.pendingRows()); f.tick();
        assertEquals("different-file\t1", Files.readString(f.state())); assertTrue(f.requests.isEmpty());
    }

    @Test public void emptyFileBaselineDoesNotSkipLaterCollectedRows() throws Exception
    {
        Fixture f = new Fixture(); Files.writeString(f.file(), ""); f.tick(); assertEquals(0, f.cursor());
        f.collector.record(SAFE, "gameText", "", ""); f.collector.flushPending();
        assertEquals(1, f.uploader.pendingRows()); f.tick(); assertEquals(Arrays.asList(row(SAFE)), f.sent());
        f.respond(200); assertEquals(Files.readAllLines(f.file()).size(), f.cursor());
    }

    @Test public void failedProgressCommitDoesNotReportSuccessOrLoseRetry() throws Exception
    {
        Fixture f = new Fixture(); String safe = row(SAFE); f.seed(MissingPrivacy.marker(safe), safe);
        List<Integer> successes = new ArrayList<>(); f.uploader.setUploadListener(successes::add); f.tick();
        Files.move(f.state(), f.root.resolve("original-state")); Files.createDirectory(f.state());
        f.respond(200); assertTrue(successes.isEmpty()); assertEquals(-1, f.uploader.pendingRows());
        Files.move(f.state(), f.root.resolve("blocked-state")); Files.move(f.root.resolve("original-state"), f.state());
        f.tick(); assertEquals(2, f.requests.size()); f.respond(200); assertEquals(Arrays.asList(1), successes);
    }


    private static final class RecordingAi extends AiTranslator
    {
        int reads;
        int requests;
        String result;
        @Override public synchronized String cached(String text) { reads++; return result; }
        @Override public String translate(String text, boolean persist) { requests++; return result; }
        @Override public String translate(String text, boolean persist, RequestPermit permit)
        { assertFalse(persist); requests++; return result; }
    }

    @SuppressWarnings("unchecked")
    private static void table(Translator translator, String key, String value) throws Exception
    {
        Field storeField = Translator.class.getDeclaredField("store"); storeField.setAccessible(true);
        Field mapsField = TranslationStore.class.getDeclaredField("maps"); mapsField.setAccessible(true);
        java.util.Map<TranslationStore.Category, java.util.Map<String, String>> maps =
            (java.util.Map<TranslationStore.Category, java.util.Map<String, String>>) mapsField.get(storeField.get(translator));
        maps.get(TranslationStore.Category.GAME_TEXT).put(TranslationStore.normalize(key), value);
    }

    private static RecordingAi recordingAi(Translator translator) throws Exception
    {
        RecordingAi ai = new RecordingAi(); set(translator, "ai", ai);
        set(translator, "glyph", new com.osrscn.glyph.GlyphService() {
            @Override public String toImgTags(String text, int color, int maxChars, int size) { return text; }
        });
        return ai;
    }

    @Test public void knownIdentityFramesNeverReachAutomaticAiOrItsCache() throws Exception
    {
        // Frozen structures; invented identities and synthetic break/color variants.
        for (String text : new String[]{RANK, RANK.replace("top three", "top<br>three"),
                RANK.replace("were ", "were<br>"), "Trading<br/>With: Test One",
                "<col=ff0000>Trading with:</col><br>Test One", "Test One's group is: Test Team."}) {
            Fixture f = new Fixture(); Translator t = translator(f); RecordingAi ai = recordingAi(t);
            assertNull(t.plain(text)); assertNull(t.plainCollect(text, "dialogue", "", ""));
            assertNull(t.plainColored(text, "dialogue", "", ""));
            assertNull(t.renderChat(text, 0, 100, 14, true, true));
            assertNull(t.renderUi(text, 0, 100, 14, true));
            assertNull(t.renderUiNoCollect(text, 0, 100, 14, true));
            assertNull(t.translateJournalSentence(text, true));
            assertEquals(0, ai.requests); assertEquals(0, ai.reads);
            f.collector.flushPending(); assertTrue(f.data().isEmpty());
        }
    }

    @Test public void identityTailsCannotBecomeIndependentDictionaryWords() throws Exception
    {
        Fixture f = new Fixture(); Translator t = translator(f); RecordingAi ai = recordingAi(t);
        table(t, "Logs", "原木"); String text = "Trading with:<br>Logs";
        assertNull(t.renderChat(text, 0, 100, 14, true, true));
        assertNull(t.renderUi(text, 0, 100, 14, false));
        assertNull(t.renderUiNoCollect(text, 0, 100, 14, true));
        assertEquals(0, ai.requests); assertEquals(0, ai.reads);
    }

    @Test public void oldAiIdentityCacheCannotBypassTheWholeInputHold() throws Exception
    {
        Fixture f = new Fixture(); Translator t = translator(f); RecordingAi ai = recordingAi(t);
        ai.result = "旧缓存错误译文";
        for (String text : new String[]{RANK, "Trading with:<br>Logs"}) {
            assertNull(t.plain(text)); assertNull(t.renderChatCached(text, 0, 100, 14));
            assertNull(t.renderChat(text, 0, 100, 14, true, true));
        }
        assertEquals(0, ai.requests); assertEquals(0, ai.reads);
    }

    @Test public void identityWholeTableHitsRemainFinalIncludingEnglishAndPendingGlyphs() throws Exception
    {
        Fixture f = new Fixture(); Translator t = translator(f); RecordingAi ai = recordingAi(t);
        String text = "Trading with:<br>Logs"; String zh = "正在交易：<br>Logs";
        table(t, text, zh);
        assertEquals(zh, t.renderChat(text, 0, 100, 14, true, true).text);
        assertEquals(zh, t.plain(text));
        set(t, "glyph", new com.osrscn.glyph.GlyphService() {
            @Override public String toImgTags(String value, int color, int maxChars, int size) { return null; }
        });
        assertNull(t.renderChat(text, 0, 100, 14, true, true));
        table(t, text, text); assertNull(t.renderChat(text, 0, 100, 14, true, true));
        assertNull(t.plain(text)); assertEquals(0, ai.requests); assertEquals(0, ai.reads);
    }

    @Test public void manualIdentityHoldDoesNotReadTheClient() throws Exception
    {
        Fixture f = new Fixture(); Translator t = translator(f); RecordingAi ai = recordingAi(t);
        set(t, "client", Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{net.runelite.api.Client.class}, (p,m,a) -> { throw new AssertionError("Swing client access"); }));
        assertNull(t.plainNoPlayer(RANK)); assertEquals(0, ai.requests); assertEquals(0, ai.reads);
        table(t, RANK, "排名 Test One"); assertEquals("排名 Test One", t.plainNoPlayer(RANK));
    }

    @Test public void identityRequirementHoldKeepsOriginalInsteadOfClaimingAiPending() throws Exception
    {
        Fixture f = new Fixture(); Translator t = translator(f); RecordingAi ai = recordingAi(t);
        for (String text : new String[]{RANK, " Started " + RANK + " ", "65 " + RANK})
            assertEquals(text, t.translateReqSegment(text, true));
        assertEquals(0, ai.requests); assertEquals(0, ai.reads);
    }

    @Test public void ordinaryMultilineAndOptedInPlayerTextStillUseAi() throws Exception
    {
        Fixture f = new Fixture(); Translator t = translator(f); RecordingAi ai = recordingAi(t);
        ai.result = "测试译文";
        assertNotNull(t.renderUi(SAFE + "<br>You search the chest.", 0, 100, 14, true));
        assertEquals(2, ai.requests);
        assertNotNull(t.renderChat("Trading with:<br>Logs", 0, 100, 14, true, false,
            new AiTranslator.RequestPermit(() -> true)));
        assertEquals(4, ai.requests);
        assertNotNull(t.renderChatCached(SAFE, 0, 100, 14)); assertEquals(4, ai.requests);
    }


    @Test public void taggedRequirementKeepsItsIdentityFrameBeforeSplitting() throws Exception
    {
        Fixture f = new Fixture(); Translator t = translator(f); RecordingAi ai = recordingAi(t);
        table(t, "Logs", "原木");
        com.osrscn.hooks.InterfaceTranslator handler = new com.osrscn.hooks.InterfaceTranslator();
        set(handler, "translator", t);
        Method method = handler.getClass().getDeclaredMethod("translateReqInline", String.class, boolean.class);
        method.setAccessible(true);
        String text = "Trading with:<col=ff0000>Logs</col>";
        assertEquals(text, method.invoke(handler, text, true));
        String zh = "正在交易：<col=ff0000>Logs</col>";
        table(t, com.osrscn.text.Tags.placeholdColors(text), com.osrscn.text.Tags.placeholdColors(zh));
        assertEquals(zh, method.invoke(handler, text, true));
        assertEquals(0, ai.requests); assertEquals(0, ai.reads);
    }

    @Test public void dialogueRecolourKeepsRawIdentityFrameWithoutCollection() throws Exception
    {
        Fixture f = new Fixture(); Translator t = translator(f); RecordingAi ai = recordingAi(t);
        com.osrscn.hooks.DialogueHandler handler = new com.osrscn.hooks.DialogueHandler();
        set(handler, "translator", t);
        set(handler, "original", new java.util.HashMap<>(java.util.Map.of("test", RANK.replace("top three", "top<br>three"))));
        set(handler, "lastSet", new java.util.HashMap<>(java.util.Map.of("test", "旧字图")));
        set(handler, "lastColor", new java.util.HashMap<>(java.util.Map.of("test", 1)));
        net.runelite.api.widgets.Widget widget = (net.runelite.api.widgets.Widget) Proxy.newProxyInstance(
            getClass().getClassLoader(), new Class<?>[]{net.runelite.api.widgets.Widget.class}, (p,m,a) -> {
                switch (m.getName()) {
                    case "isHidden": return false;
                    case "getText": return "旧字图";
                    case "getTextColor": return 2;
                    default: throw new AssertionError("Unexpected widget operation: " + m.getName());
                }
            });
        Method method = handler.getClass().getDeclaredMethod("translateWidget", net.runelite.api.widgets.Widget.class, String.class);
        method.setAccessible(true); method.invoke(handler, widget, "test");
        assertEquals(0, ai.requests); assertEquals(0, ai.reads);
        f.collector.flushPending(); assertTrue(f.data().isEmpty());
    }

    @Test public void colouredIdentityWholeTableStillRestoresColoursAndNames() throws Exception
    {
        Fixture f = new Fixture(); Translator t = translator(f); RecordingAi ai = recordingAi(t);
        String text = "<col=ff0000>Trading with:</col> Logs";
        String zh = "<col=ff0000>正在交易：</col> Logs";
        table(t, com.osrscn.text.Tags.placeholdColors(text), com.osrscn.text.Tags.placeholdColors(zh));
        assertEquals(zh, t.plainColored(text, "dialogue", "", ""));
        assertEquals(zh, t.plainColoredNoCollect(text));
        assertEquals(zh, t.renderUi(text, 0, 100, 14, true).text);
        assertEquals(0, ai.requests); assertEquals(0, ai.reads);
        f.collector.flushPending(); assertTrue(f.data().isEmpty());
    }


    @Test public void guideIdentityFrameCannotEnterStrippedDeferredQueue() throws Exception
    {
        Fixture f = new Fixture(); Translator t = translator(f); RecordingAi ai = recordingAi(t);
        com.osrscn.hooks.InterfaceTranslator handler = new com.osrscn.hooks.InterfaceTranslator(); set(handler, "translator", t);
        Method method = handler.getClass().getDeclaredMethod("plainZh", String.class, boolean.class); method.setAccessible(true);
        String text = "Trading<br>With: Test One";
        for (boolean settled : new boolean[]{false, true}) {
            set(handler, "collectOk", settled); assertNull(method.invoke(handler, text, true));
            Field pending = handler.getClass().getDeclaredField("pendingCollect"); pending.setAccessible(true);
            assertTrue(((java.util.Collection<?>) pending.get(handler)).isEmpty());
        }
        table(t, text, "正在交易：Test One"); assertEquals("正在交易：Test One", method.invoke(handler, text, true));
        assertEquals(0, ai.requests); assertEquals(0, ai.reads);
        f.collector.flushPending(); assertTrue(f.data().isEmpty());
    }


    @Test public void journalReconstructionKeepsHeaderAndProseIdentityBeforeTagRemoval() throws Exception
    {
        for (String text : new String[]{"Trading<br>With: Test One", "<col=ffff00>Trading<br>With: Test One</col>"}) {
            Fixture f = new Fixture(); Translator t = translator(f); RecordingAi ai = recordingAi(t);
            com.osrscn.hooks.InterfaceTranslator handler = new com.osrscn.hooks.InterfaceTranslator();
            set(handler, "translator", t); set(handler, "cfgAiInterface", true);
            set(handler, "glyph", new com.osrscn.glyph.GlyphService() {
                @Override public int uiSize() { return 14; }
                @Override public int wrapChars(int width, int size) { return 80; }
            });
            net.runelite.api.widgets.Widget widget = (net.runelite.api.widgets.Widget) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{net.runelite.api.widgets.Widget.class}, (p,m,a) -> {
                    switch (m.getName()) {
                        case "getText": return text;
                        case "isHidden": return false;
                        case "getWidth": return 400;
                        case "getId": return 741 << 16;
                        case "getIndex": case "getTextColor": return 0;
                        case "getStaticChildren": case "getDynamicChildren": case "getNestedChildren": return null;
                        default: throw new AssertionError("Unexpected widget operation: " + m.getName());
                    }
                });
            set(handler, "client", Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{net.runelite.api.Client.class}, (p,m,a) -> {
                    if (m.getName().equals("getWidget")) return widget;
                    throw new AssertionError("Unexpected client operation");
                }));
            Method method = handler.getClass().getDeclaredMethod("reconstructRoot", int.class); method.setAccessible(true);
            for (int tick = 0; tick < 5; tick++) method.invoke(handler, 741 << 16);
            assertEquals(0, ai.requests); assertEquals(0, ai.reads);
            f.collector.flushPending(); assertTrue(f.data().isEmpty());
        }
    }

    private static Translator translator(Fixture f) throws Exception
    {
        Translator translator = new Translator();
        net.runelite.api.Player local = (net.runelite.api.Player) Proxy.newProxyInstance(
            MissingPrivacyTest.class.getClassLoader(), new Class<?>[]{net.runelite.api.Player.class},
            (p,m,a) -> m.getName().equals("getName") ? "Test One" : null);
        net.runelite.api.Player nearby = (net.runelite.api.Player) Proxy.newProxyInstance(
            MissingPrivacyTest.class.getClassLoader(), new Class<?>[]{net.runelite.api.Player.class},
            (p,m,a) -> m.getName().equals("getName") ? "Test Two" : null);
        net.runelite.api.Client client = (net.runelite.api.Client) Proxy.newProxyInstance(
            MissingPrivacyTest.class.getClassLoader(), new Class<?>[]{net.runelite.api.Client.class},
            (p,m,a) -> {
                switch (m.getName()) {
                    case "getLocalPlayer": return local;
                    case "getPlayers": return Arrays.asList(local, nearby);
                    case "getTickCount": return 1;
                    default: throw new AssertionError("Unexpected client call: " + m.getName());
                }
            });
        set(translator, "client", client); set(translator, "store", new TranslationStore());
        set(translator, "missing", f.collector);
        set(translator, "ai", new AiTranslator() {
            @Override public synchronized String cached(String text) { return null; }
            @Override public String translate(String text, boolean persist) { return null; }
        });
        return translator;
    }

    @Test public void dialogueUsesTheSameKnownNameProtectionForAllThreeTextFields() throws Exception
    {
        Fixture f = new Fixture(); Translator translator = translator(f);
        translator.plainCollect("Test One greets Test Two.", "dialogue", "Test Two", "Test One");
        f.collector.flushPending();
        assertEquals(Arrays.asList("[player name] greets [player name].\t\tdialogue\t[player name]\t[player name]"), f.data());
    }

    @Test public void actualTranslationEntrypointsReachTheSameCollectionGate() throws Exception
    {
        Fixture f = new Fixture(); Translator translator = translator(f);
        translator.renderChat(RANK, 0, 100, 14, false, true);
        translator.renderUi(RANK, 0, 100, 14, false);
        translator.plainCollect(RANK, "dialogue", "", "");
        translator.lookupRenderMenuOption(RANK, 0, 14);
        translator.translateJournalSentence(RANK, false, "", true);
        f.collector.flushPending(); assertTrue(f.data().isEmpty());
        translator.renderUiNoCollect(SAFE, 0, 100, 14, false);
        translator.renderChat(SAFE, 0, 100, 14, false, false);
        f.collector.flushPending(); assertTrue(f.data().isEmpty());
        translator.renderChat(SAFE, 0, 100, 14, false, true);
        f.collector.flushPending(); assertEquals(Arrays.asList(row(SAFE)), f.data());
    }

    @Test public void lineBreaksCannotJoinIdentityHeadersAtEitherExit() throws Exception
    {
        for (String text : new String[]{RANK.replace("top three", "top<br>three"), "Trading<br/>With: Test One"}) {
            Fixture f = new Fixture(); f.collector.record(text, "interface", "", ""); f.collector.flushPending();
            assertTrue("Break tag must preserve the identity frame", f.data().isEmpty());
            String line = row(text); f.seed(MissingPrivacy.marker(line), line); f.tick();
            assertTrue(f.requests.isEmpty()); assertEquals("", f.uploader.manualSubmission().content);
        }
    }

    @Test public void splitRenderingDoesNotCollectTheIdentityBearingTail() throws Exception
    {
        for (String text : new String[]{RANK.replace("top three", "top<br>three"),
                RANK.replace("were ", "were<br>"), "Trading<br>With: Test One"}) {
            Fixture f = new Fixture(); Translator translator = translator(f);
            translator.renderUi(text, 0, 100, 14, false);
            translator.renderChat(text, 0, 100, 14, false, true);
            translator.plainColored(text, "dialogue", "", "");
            f.collector.flushPending(); assertTrue("Do not detach identity slots from their frame", f.data().isEmpty());
        }
        Fixture f = new Fixture(); translator(f).renderUi(SAFE + "<br>You search the chest.", 0, 100, 14, false);
        f.collector.flushPending(); assertEquals(2, f.data().size());
    }
}
