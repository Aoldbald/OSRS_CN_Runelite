package com.osrscn.hooks;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.FileAppender;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.osrscn.AiBackend;
import com.osrscn.OsrscnConfig;
import com.osrscn.PlayerChatMode;
import com.osrscn.ToggleService;
import com.osrscn.glyph.GlyphService;
import com.osrscn.translate.AiTranslator;
import com.osrscn.translate.MissingCollector;
import com.osrscn.translate.TranslationStore;
import com.osrscn.translate.Translator;
import com.sun.net.httpserver.HttpServer;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.IterableHashTable;
import net.runelite.api.MessageNode;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.RuneLite;
import okhttp3.OkHttpClient;
import org.junit.Test;
import org.slf4j.LoggerFactory;
import static org.junit.Assert.*;

/** Real translation and HTTP; only the native client and bitmap upload are simulated. */
public class PlayerChatPipelineTest
{
    private static final String EN = "Fictional violet otters gather near the moon gate.";
    private static final String ZH = "虚构紫獭聚集在月门附近。";
    private static final String NAME = "Fixture Alice";
    private static final ChatMessageType[] CHANNELS = {ChatMessageType.PUBLICCHAT, ChatMessageType.MODCHAT,
        ChatMessageType.AUTOTYPER, ChatMessageType.MODAUTOTYPER, ChatMessageType.CLAN_CHAT,
        ChatMessageType.CLAN_GUEST_CHAT, ChatMessageType.CLAN_GIM_CHAT, ChatMessageType.FRIENDSCHAT};

    @Test public void matrixCoversAllPlayerModesChannelsAndBackgrounds() throws Exception
    {
        int cells = 0;
        for (ChatMessageType channel : CHANNELS)
        for (PlayerChatMode mode : PlayerChatMode.values())
        for (boolean transparent : new boolean[] {false, true})
        for (boolean custom : new boolean[] {false, true})
        {
            try (Fixture f = new Fixture(mode, channel, transparent, custom))
            {
                f.fire();
                if (mode == PlayerChatMode.OFF)
                {
                    for (int i = 0; i < 5; i++) f.chat.tick();
                    f.assertRequests(0);
                    assertEquals(channel == ChatMessageType.PUBLICCHAT || channel == ChatMessageType.MODCHAT
                        || channel == ChatMessageType.AUTOTYPER || channel == ChatMessageType.MODAUTOTYPER
                        ? String.format("<col=%06x>%s</col>", f.color, EN) : EN, f.value);
                    assertTrue(f.glyphTexts.isEmpty());
                }
                else
                {
                    f.awaitResponse(); f.chat.tick();
                    f.assertRequests(1);
                    assertEquals(EN, f.requestText);
                    assertFalse(f.requestPayload.contains(NAME));
                    assertTrue(f.glyphTexts.contains(ZH));
                    assertTrue(f.glyphColors.stream().allMatch(c -> c == f.color));
                    if (mode == PlayerChatMode.INLINE) { assertTrue(f.value.contains("<img=")); assertTrue(f.inserted.isEmpty()); }
                    else { assertEquals(EN, f.value); assertEquals(Collections.singletonList("OSRS_CN: " + NAME + ": <img=" + f.color + ">"), f.inserted); }
                    for (int i = 0; i < 5; i++) f.chat.tick();
                    f.assertRequests(1);
                    assertEquals(ZH, f.ai.cached(EN));
                }
                assertEquals(NAME, f.node.getName()); f.assertPrivate(); cells++;
                System.out.println("R8_MATRIX channel=" + channel.name() + " mode=" + mode.name()
                    + " background=" + (transparent ? "transparent" : "opaque")
                    + " color=" + (custom ? "custom" : "default") + " layer=real-loopback-http result=PASS");
            }
        }
        assertEquals(96, cells);
    }

    @Test public void additionalChannelsTranslateIndependentlyWithPublicOff() throws Exception
    {
        for (ChatMessageType type : new ChatMessageType[] {ChatMessageType.FRIENDSCHAT, ChatMessageType.CLAN_CHAT,
            ChatMessageType.CLAN_GUEST_CHAT, ChatMessageType.CLAN_GIM_CHAT})
        for (PlayerChatMode mode : new PlayerChatMode[] {PlayerChatMode.INLINE, PlayerChatMode.INSERT})
        try (Fixture f = new Fixture(mode, type, false, false))
        {
            f.publicMode = PlayerChatMode.OFF;
            f.fire(); f.awaitResponse(); f.chat.tick();
            f.assertRequests(1); f.assertDelivered(); f.assertPrivate();
        }
    }

    @Test public void publicOffNeverDispatchesWhileAdditionalModeIsEnabled() throws Exception
    {
        for (ChatMessageType type : new ChatMessageType[] {ChatMessageType.PUBLICCHAT, ChatMessageType.MODCHAT,
            ChatMessageType.AUTOTYPER, ChatMessageType.MODAUTOTYPER})
        try (Fixture f = new Fixture(PlayerChatMode.INLINE, type, false, false))
        {
            f.publicMode = PlayerChatMode.OFF; f.fire(); f.chat.tick();
            f.assertRequests(0); assertFalse(f.value.contains("<img="));
            assertTrue(f.inserted.isEmpty()); f.assertPrivate();
        }
    }

    @Test public void publicModeChangePreservesGroupInFlightResponse() throws Exception
    {
        for (PlayerChatMode mode : new PlayerChatMode[] {PlayerChatMode.INLINE, PlayerChatMode.INSERT})
        try (Fixture f = new Fixture(mode, ChatMessageType.CLAN_CHAT, false, false))
        {
            f.publicMode = PlayerChatMode.INLINE;
            f.release = new CountDownLatch(1); f.fire(); await(() -> f.requests.get() == 1);
            f.publicMode = PlayerChatMode.OFF; f.modeChanged("playerChatMode");
            f.chat.discardRevokedPlayerMessages(); f.release.countDown(); f.awaitResponse(); f.chat.tick();
            f.assertRequests(1); f.assertDelivered(); f.assertPrivate();
        }
    }

    @Test public void groupModeChangePreservesPublicInFlightResponse() throws Exception
    {
        for (PlayerChatMode mode : new PlayerChatMode[] {PlayerChatMode.INLINE, PlayerChatMode.INSERT})
        try (Fixture f = new Fixture(mode, ChatMessageType.PUBLICCHAT, false, false))
        {
            f.publicMode = mode;
            f.release = new CountDownLatch(1); f.fire(); await(() -> f.requests.get() == 1);
            f.groupChannels = false; f.modeChanged("groupChatMode");
            f.chat.discardRevokedPlayerMessages(); f.release.countDown(); f.awaitResponse(); f.chat.tick();
            f.assertRequests(1); f.assertDelivered(); f.assertPrivate();
        }
    }

    @Test public void independentGroupModeRoundTripRejectsLateReplyWithPublicOff() throws Exception
    {
        for (PlayerChatMode mode : new PlayerChatMode[] {PlayerChatMode.INLINE, PlayerChatMode.INSERT})
        try (Fixture f = new Fixture(mode, ChatMessageType.FRIENDSCHAT, false, false))
        {
            f.publicMode = PlayerChatMode.OFF;
            f.release = new CountDownLatch(1); f.fire(); await(() -> f.requests.get() == 1);
            f.groupChannels = false; f.modeChanged("groupChatMode");
            f.groupChannels = true; f.modeChanged("groupChatMode");
            f.release.countDown(); f.awaitResponse(); f.chat.tick();
            f.chat.goEnglish(); f.chat.goChinese(); f.chat.tick();
            assertFalse(f.value.contains("<img=")); assertTrue(f.inserted.isEmpty());
            f.assertRequests(1); f.assertPrivate();
        }
    }

    @Test public void publicUsesItsOwnRenderingModeAndCachedTextCannotBypassOff() throws Exception
    {
        try (Fixture f = new Fixture(PlayerChatMode.INLINE, ChatMessageType.PUBLICCHAT, false, false))
        {
            f.publicMode = PlayerChatMode.INSERT; f.fire(); f.awaitResponse(); f.chat.tick();
            assertEquals(EN, f.value); assertEquals(1, f.inserted.size());
            f.publicMode = PlayerChatMode.OFF; f.modeChanged("playerChatMode");
            f.fire(); f.chat.tick();
            assertFalse(f.value.contains("<img=")); assertEquals(1, f.inserted.size());
            f.assertRequests(1); f.assertPrivate();
        }
    }

    @Test public void groupScopeDefaultsOffInRealConfig()
    {
        assertEquals(PlayerChatMode.OFF, new OsrscnConfig() {}.groupChatMode());
        assertEquals(PlayerChatMode.OFF, new OsrscnConfig() {}.playerChatMode());
    }

    @Test public void groupScopeOffLeavesAllAdditionalChannelsAndNamesUntouched() throws Exception
    {
        for (ChatMessageType type : new ChatMessageType[] {ChatMessageType.FRIENDSCHAT, ChatMessageType.CLAN_CHAT,
            ChatMessageType.CLAN_GUEST_CHAT, ChatMessageType.CLAN_GIM_CHAT})
        for (PlayerChatMode mode : new PlayerChatMode[] {PlayerChatMode.INLINE, PlayerChatMode.INSERT})
        try (Fixture f = new Fixture(mode, type, false, false))
        {
            f.groupChannels = false; f.fire(); f.chat.tick(); f.chat.refreshNativeColors();
            f.chat.goEnglish(); f.chat.goChinese(); f.chat.tick();
            f.assertRequests(0); assertEquals(EN, f.value); assertEquals(NAME, f.node.getName());
            assertTrue(f.inserted.isEmpty()); assertTrue(f.glyphTexts.isEmpty()); f.assertPrivate();
        }
    }

    @Test public void publicAndAutoStillTranslateWithGroupScopeOff() throws Exception
    {
        for (ChatMessageType type : new ChatMessageType[] {ChatMessageType.PUBLICCHAT, ChatMessageType.MODCHAT,
            ChatMessageType.AUTOTYPER, ChatMessageType.MODAUTOTYPER})
        for (PlayerChatMode mode : new PlayerChatMode[] {PlayerChatMode.INLINE, PlayerChatMode.INSERT})
        try (Fixture f = new Fixture(mode, type, false, false))
        {
            f.groupChannels = false; f.fire(); f.awaitResponse(); f.chat.tick();
            f.assertRequests(1); f.assertDelivered(); f.assertPrivate();
        }
    }

    @Test public void groupScopeOffAndRoundTripCannotReviveOldHttpReplies() throws Exception
    {
        for (ChatMessageType type : new ChatMessageType[] {ChatMessageType.FRIENDSCHAT, ChatMessageType.CLAN_CHAT,
            ChatMessageType.CLAN_GUEST_CHAT, ChatMessageType.CLAN_GIM_CHAT})
        for (PlayerChatMode mode : new PlayerChatMode[] {PlayerChatMode.INLINE, PlayerChatMode.INSERT})
        for (boolean enableAgain : new boolean[] {false, true})
        try (Fixture f = new Fixture(mode, type, false, false))
        {
            f.release = new CountDownLatch(1); f.fire(); await(() -> f.requests.get() == 1);
            f.groupChannels = false; f.chat.playerChatModeChanged();
            if (enableAgain) { f.groupChannels = true; f.chat.playerChatModeChanged(); }
            f.release.countDown(); f.awaitResponse();
            for (int i = 0; i < 5; i++) { f.chat.tick(); f.chat.refreshNativeColors(); }
            f.chat.goEnglish(); f.chat.goChinese(); f.chat.tick();
            assertFalse(f.value.contains("<img=")); assertTrue(f.inserted.isEmpty());
            assertEquals(NAME, f.node.getName()); f.assertRequests(1); f.assertPrivate();
        }
    }

    @Test public void httpFailureBacksOffAndExpires() throws Exception
    {
        for (PlayerChatMode mode : new PlayerChatMode[] {PlayerChatMode.INLINE, PlayerChatMode.INSERT})
        try (Fixture f = new Fixture(mode, CHANNELS[0], false, false))
        {
            f.status = 503; f.fire(); f.awaitFinished();
            for (int i = 1; i < 3; i++) { f.chat.tick(); f.awaitFinished(); }
            f.assertRequests(3);
            for (int i = 0; i < 30; i++) f.chat.tick();
            f.assertRequests(3);
            f.clock.set(75_001); f.chat.tick();
            inject(f.ai, "backoffUntil", 0L);
            for (int i = 0; i < 30; i++) f.chat.tick();
            f.assertRequests(3); assertTrue(f.inserted.isEmpty()); f.assertPrivate();
        }
    }

    @Test public void offRevokesPendingResponse() throws Exception
    {
        for (PlayerChatMode mode : new PlayerChatMode[] {PlayerChatMode.INLINE, PlayerChatMode.INSERT})
        {
            switchedResponse(mode, PlayerChatMode.OFF);
            try (Fixture f = new Fixture(mode, CHANNELS[4], false, false))
            {
                f.release = new CountDownLatch(1); f.fire(); await(() -> f.requests.get() == 1);
                f.enabled = false; f.release.countDown(); f.awaitResponse(); f.chat.tick();
                // Disabling the backend remains cache-only: an admitted response can still render.
                if (mode == PlayerChatMode.INLINE) assertTrue(f.value.contains("<img="));
                else assertEquals(1, f.inserted.size());
                f.value = EN + " Again."; f.fire();
                for (int i = 0; i < 5; i++) f.chat.tick();
                f.assertRequests(1); assertEquals(ZH, f.ai.cached(EN)); f.assertPrivate();
            }
        }
    }
    @Test public void inlineToInsertRejectsOldDelivery() throws Exception { switchedResponse(PlayerChatMode.INLINE, PlayerChatMode.INSERT); }
    @Test public void insertToInlineRejectsOldDelivery() throws Exception { switchedResponse(PlayerChatMode.INSERT, PlayerChatMode.INLINE); }

    private void switchedResponse(PlayerChatMode before, PlayerChatMode after) throws Exception
    {
        for (ChatMessageType channel : new ChatMessageType[] {ChatMessageType.CLAN_CHAT, ChatMessageType.FRIENDSCHAT})
        try (Fixture f = new Fixture(before, channel, false, false))
        {
            f.release = new CountDownLatch(1); f.fire();
            await(() -> f.requests.get() == 1);
            f.clock.set(37_000); f.chat.tick();
            f.mode = after; f.chat.playerChatModeChanged();
            f.release.countDown(); f.awaitResponse();
            for (int i = 0; i < 5; i++) f.chat.tick();
            assertFalse(f.value.contains("<img=")); assertTrue(f.inserted.isEmpty());
            f.assertRequests(1); assertEquals(NAME, f.node.getName()); f.assertPrivate();
        }
    }

    @Test public void unsupportedChannelsNeverDispatch() throws Exception
    {
        for (ChatMessageType type : new ChatMessageType[] {ChatMessageType.PRIVATECHAT, ChatMessageType.MODPRIVATECHAT, ChatMessageType.PRIVATECHATOUT,
            ChatMessageType.FRIENDSCHATNOTIFICATION, ChatMessageType.FRIENDNOTIFICATION, ChatMessageType.CHALREQ_FRIENDSCHAT,
            ChatMessageType.CLAN_MESSAGE, ChatMessageType.CLAN_GUEST_MESSAGE, ChatMessageType.CLAN_GIM_MESSAGE})
        try (Fixture f = new Fixture(PlayerChatMode.INLINE, type, false, false))
        {
            f.fire(); f.chat.tick(); assertEquals(EN, f.value); f.assertRequests(0); f.assertPrivate();
        }
    }

    @Test public void openAiCompatibleTransportUsesFixtureOnly() throws Exception
    {
        try (Fixture f = new Fixture(PlayerChatMode.INLINE, CHANNELS[6], true, true))
        {
            f.backend = AiBackend.OPENAI; f.fire(); f.awaitResponse(); f.chat.tick();
            assertEquals("/chat/completions", f.requestPath); assertEquals("Bearer fixture-only", f.authorization);
            assertTrue(f.value.contains("<img=")); f.assertRequests(1); assertEquals(ZH, f.ai.cached(EN)); f.assertPrivate();
        }
    }

    @Test public void coldOllamaSurvivesInheritedReadTimeout() throws Exception
    {
        for (PlayerChatMode mode : new PlayerChatMode[] {PlayerChatMode.INLINE, PlayerChatMode.INSERT})
        try (Fixture f = new Fixture(mode, ChatMessageType.FRIENDSCHAT, false, false))
        {
            f.responseDelayMs = 11_000;
            long start = System.nanoTime(); f.fire();
            assertTrue("Admission must not wait for model loading", System.nanoTime() - start < TimeUnit.SECONDS.toNanos(1));
            await(() -> f.requests.get() == 1);
            for (int i = 0; i < 30; i++) f.chat.tick();
            assertEquals(1, f.ai.inFlightCount()); assertEquals(1, f.requests.get());
            await(() -> ZH.equals(f.ai.cached(EN)), 15); f.awaitFinished();
            assertTrue(System.nanoTime() - start >= TimeUnit.SECONDS.toNanos(10));
            f.clock.set(37_000); f.chat.tick();
            f.assertDelivered(); f.assertRequests(1); f.assertPrivate();
            assertEquals(60_000, f.observedReadTimeoutMs);
            assertEquals(60_000, f.observedCallTimeoutMs);
            assertEquals(10_000, f.http.readTimeoutMillis());
            assertEquals(0, f.http.callTimeoutMillis());
        }
    }

    @Test public void coldResponseRemainsPendingPastOriginalChatWindow() throws Exception
    {
        for (PlayerChatMode mode : new PlayerChatMode[] {PlayerChatMode.INLINE, PlayerChatMode.INSERT})
        try (Fixture f = new Fixture(mode, CHANNELS[0], false, false))
        {
            f.release = new CountDownLatch(1); f.fire(); await(() -> f.requests.get() == 1);
            f.clock.set(37_000); f.chat.tick(); f.chat.refreshNativeColors();
            f.release.countDown(); f.awaitResponse(); f.chat.tick();
            f.assertDelivered(); f.assertRequests(1); f.assertPrivate();
        }
    }

    @Test public void ollamaChatDeadlineNeverRenewsOnNaturalRetries() throws Exception
    {
        for (PlayerChatMode mode : new PlayerChatMode[] {PlayerChatMode.INLINE, PlayerChatMode.INSERT})
        try (Fixture f = new Fixture(mode, CHANNELS[0], false, false))
        {
            f.release = new CountDownLatch(1); f.fire(); await(() -> f.requests.get() == 1);
            for (long time : new long[] {15_001, 37_000, 74_999, 75_001})
            {
                f.clock.set(time); f.chat.tick(); f.chat.refreshNativeColors();
            }
            f.release.countDown(); f.awaitResponse();
            for (int i = 0; i < 5; i++) { f.chat.tick(); f.chat.refreshNativeColors(); }
            assertFalse(f.value.contains("<img=")); assertTrue(f.inserted.isEmpty());
            f.assertRequests(1); f.assertPrivate();
        }
    }

    @Test public void explicitChineseSwitchUsesOneBoundedOllamaWindow() throws Exception
    {
        try (Fixture f = new Fixture(PlayerChatMode.INLINE, CHANNELS[0], false, false))
        {
            f.release = new CountDownLatch(1); f.fire(); await(() -> f.requests.get() == 1);
            f.chat.goEnglish(); f.clock.set(1_000); f.chat.goChinese();
            f.clock.set(37_000); f.chat.tick();
            f.release.countDown(); f.awaitResponse(); f.chat.tick();
            f.assertDelivered(); f.assertRequests(1); f.assertPrivate();
            java.util.Map<?, ?> pending = (java.util.Map<?, ?>) field(f.chat, "pending");
            assertEquals(76_000L, field(pending.get(f.node), "deadline"));
            f.clock.set(76_001); f.chat.tick(); assertTrue(pending.isEmpty());
        }
    }

    @Test public void onlineAndDisabledAiKeepOriginalChatWindow() throws Exception
    {
        for (PlayerChatMode mode : new PlayerChatMode[] {PlayerChatMode.INLINE, PlayerChatMode.INSERT})
        {
            try (Fixture f = new Fixture(mode, CHANNELS[0], false, false))
            {
                f.backend = AiBackend.OPENAI; f.release = new CountDownLatch(1);
                f.fire(); await(() -> f.requests.get() == 1);
                f.clock.set(15_001); f.chat.tick(); f.release.countDown(); f.awaitResponse(); f.chat.tick();
                assertFalse(f.value.contains("<img=")); assertTrue(f.inserted.isEmpty());
                assertEquals(10_000, f.observedReadTimeoutMs); assertEquals(0, f.observedCallTimeoutMs);
                f.assertRequests(1); f.assertPrivate();
            }
            try (Fixture f = new Fixture(mode, CHANNELS[0], false, false))
            {
                f.enabled = false; f.fire(); f.clock.set(15_001); f.chat.tick();
                f.enabled = true; f.chat.tick(); f.chat.refreshNativeColors();
                f.assertRequests(0); f.assertPrivate();
            }
        }
    }

    @Test public void stalledOllamaRequestHasFiniteTotalBudget() throws Exception
    {
        try (Fixture f = new Fixture(PlayerChatMode.INLINE, CHANNELS[0], false, false))
        {
            f.release = new CountDownLatch(1); long start = System.nanoTime(); f.fire();
            await(() -> f.requests.get() == 1);
            await(() -> f.ai.inFlightCount() == 0, 66); f.awaitFinished();
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            assertTrue("Cold load must get its budget, then stop: " + elapsed, elapsed >= 59_000 && elapsed < 66_000);
            assertNull(f.ai.cached(EN)); assertEquals(1, ((AtomicInteger) field(f.ai, "failStreak")).get());
            f.clock.set(75_001); f.chat.tick(); f.assertRequests(1); f.assertPrivate();
        }
    }

    @Test public void cancelledOllamaCallReleasesSlotAndCanRecover() throws Exception
    {
        try (Fixture f = new Fixture(PlayerChatMode.INLINE, CHANNELS[0], false, false))
        {
            f.release = new CountDownLatch(1); f.fire(); await(() -> f.requests.get() == 1);
            f.http.dispatcher().cancelAll(); f.awaitFinished();
            assertNull(f.ai.cached(EN)); assertEquals(1, ((AtomicInteger) field(f.ai, "failStreak")).get());
            f.release.countDown(); f.release = null; f.chat.tick(); f.awaitResponse(); f.chat.tick();
            f.assertDelivered(); f.assertRequests(2); f.assertPrivate();
            assertEquals(0, ((AtomicInteger) field(f.ai, "failStreak")).get());
        }
    }

    private static void inject(Object target, String name, Object value) throws Exception
    {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
    private static Object field(Object target, String name) throws Exception
    {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    private static void await(BooleanSupplier condition) throws Exception { await(condition, 5); }
    private static void await(BooleanSupplier condition, int seconds) throws Exception
    {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(5);
        assertTrue("Timed out waiting for isolated HTTP callback", condition.getAsBoolean());
    }
    private static Object zero(Class<?> type)
    {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        return null;
    }
    private static int varp(ChatMessageType type, boolean transparent)
    {
        switch (type)
        {
            case AUTOTYPER: case MODAUTOTYPER: return transparent ? VarPlayerID.OPTION_CHAT_COLOUR_AUTOCHAT_TRANSPARENT : VarPlayerID.OPTION_CHAT_COLOUR_AUTOCHAT_OPAQUE;
            case FRIENDSCHAT: return transparent ? VarPlayerID.OPTION_CHAT_COLOUR_FRIENDSCHAT_TRANSPARENT : VarPlayerID.OPTION_CHAT_COLOUR_FRIENDSCHAT_OPAQUE;
            case CLAN_CHAT: return transparent ? VarPlayerID.OPTION_CHAT_COLOUR_CLANCHAT_TRANSPARENT : VarPlayerID.OPTION_CHAT_COLOUR_CLANCHAT_OPAQUE;
            case CLAN_GUEST_CHAT: return transparent ? VarPlayerID.OPTION_CHAT_COLOUR_GUESTCLAN_TRANSPARENT : VarPlayerID.OPTION_CHAT_COLOUR_GUESTCLAN_OPAQUE;
            case CLAN_GIM_CHAT: return transparent ? VarPlayerID.OPTION_CHAT_COLOUR_GIMCHAT_TRANSPARENT : VarPlayerID.OPTION_CHAT_COLOUR_GIMCHAT_OPAQUE;
            default: return transparent ? VarPlayerID.OPTION_CHAT_COLOUR_PUBLIC_TRANSPARENT : VarPlayerID.OPTION_CHAT_COLOUR_PUBLIC_OPAQUE;
        }
    }
    private static int defaultColor(ChatMessageType type, boolean transparent)
    {
        switch (type)
        {
            case AUTOTYPER: case MODAUTOTYPER: return transparent ? 0x4040ff : 0x2020ef;
            case FRIENDSCHAT: return transparent ? 0xef5050 : 0x7f0000;
            case CLAN_CHAT: case CLAN_GIM_CHAT: return 0x7f0000;
            case CLAN_GUEST_CHAT: return transparent ? 0x00d300 : 0x007a00;
            default: return transparent ? 0x9090ff : 0x0000ff;
        }
    }

    private static final class Fixture implements AutoCloseable
    {
        // This is deliberately the first initializer, before production objects touch RuneLite.
        final Path root = isolatedRoot();
        final ChatHandler chat = new ChatHandler();
        final AiTranslator ai = new AiTranslator();
        final MissingCollector collector = new MissingCollector();
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final AtomicInteger requests = new AtomicInteger();
        final AtomicLong clock = new AtomicLong();
        final List<String> inserted = new ArrayList<>();
        final List<String> glyphTexts = new ArrayList<>();
        final List<Integer> glyphColors = new ArrayList<>();
        final OkHttpClient http;
        final MessageNode node;
        final int color;
        final Logger logger;
        final Level oldLevel;
        final boolean oldAdditive;
        final FileAppender<ILoggingEvent> appender;
        final Path logFile;
        static final String CANARY = "R8 DEBUG fixture sink is active";
        volatile PlayerChatMode mode;
        volatile PlayerChatMode publicMode;
        volatile boolean enabled = true;
        volatile boolean groupChannels = true;
        volatile AiBackend backend = AiBackend.OLLAMA;
        volatile int status = 200;
        volatile long responseDelayMs, observedCallTimeoutMs;
        volatile int observedReadTimeoutMs;
        volatile CountDownLatch release;
        volatile String requestText, requestPayload, requestPath, authorization;
        String value = EN;

        Fixture(PlayerChatMode mode, ChatMessageType type, boolean transparent, boolean custom) throws Exception
        {
            this.mode = mode; color = custom ? (transparent ? 0x654321 : 0x123456) : defaultColor(type, transparent);
            logFile = root.resolve("plugin-debug.log");
            LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
            logger = context.getLogger("com.osrscn"); oldLevel = logger.getLevel(); oldAdditive = logger.isAdditive();
            logger.setLevel(Level.DEBUG); logger.setAdditive(false);
            PatternLayoutEncoder encoder = new PatternLayoutEncoder(); encoder.setContext(context);
            encoder.setCharset(StandardCharsets.UTF_8); encoder.setPattern("%level %logger %msg%n%ex{full}"); encoder.start();
            appender = new FileAppender<>(); appender.setContext(context); appender.setName("R8-file");
            appender.setFile(logFile.toString()); appender.setAppend(false); appender.setEncoder(encoder);
            appender.setImmediateFlush(true); appender.start(); logger.addAppender(appender);
            assertTrue(appender.isStarted()); assertTrue(LoggerFactory.getLogger(AiTranslator.class).isDebugEnabled());
            logger.debug(CANARY);
            int port = server.getAddress().getPort();
            http = new OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false)
                .retryOnConnectionFailure(false).addInterceptor(chain -> {
                    assertEquals("127.0.0.1", chain.request().url().host()); assertEquals(port, chain.request().url().port());
                    observedReadTimeoutMs = chain.readTimeoutMillis();
                    observedCallTimeoutMs = TimeUnit.NANOSECONDS.toMillis(chain.call().timeout().timeoutNanos());
                    return chain.proceed(chain.request());
                }).build();
            server.createContext("/", exchange -> {
                requests.incrementAndGet();
                try
                {
                    requestPath = exchange.getRequestURI().getPath(); authorization = exchange.getRequestHeaders().getFirst("Authorization");
                    requestPayload = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    JsonObject body = new Gson().fromJson(requestPayload, JsonObject.class);
                    requestText = body.getAsJsonArray("messages").get(1).getAsJsonObject().get("content").getAsString();
                    CountDownLatch gate = release;
                    if (gate != null && !gate.await(70, TimeUnit.SECONDS)) throw new AssertionError("Fixture response gate timed out");
                    if (responseDelayMs > 0) Thread.sleep(responseDelayMs);
                    String json = backend == AiBackend.OPENAI ? "{\"choices\":[{\"message\":{\"content\":\"" + ZH + "\"}}]}" : "{\"message\":{\"content\":\"" + ZH + "\"}}";
                    byte[] bytes = json.getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes);
                }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                finally { exchange.close(); }
            });
            OsrscnConfig config = (OsrscnConfig) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {OsrscnConfig.class}, (p,m,a) -> {
                switch (m.getName())
                {
                    case "playerChatMode": return publicMode == null ? this.mode : publicMode;
                    case "groupChatMode": return groupChannels ? this.mode : PlayerChatMode.OFF;
                    case "useLocalAi": return enabled;
                    case "collectMissing": return true;
                    case "aiBackend": return backend;
                    case "ollamaModel": case "apiModel": return "r8-fictional-model";
                    case "ollamaUrl": case "apiUrl": return "http://127.0.0.1:" + port;
                    case "apiKey": return "fixture-only";
                    case "aiConcurrency": return 1;
                    case "aiPaceMs": return 0;
                    default: return zero(m.getReturnType());
                }
            });
            node = (MessageNode) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {MessageNode.class}, (p,m,a) -> {
                switch (m.getName())
                {
                    case "getValue": return value;
                    case "setValue": value = (String) a[0]; return null;
                    case "getName": return NAME;
                    case "setName": throw new AssertionError("Player name must never be written");
                    case "getId": return 101;
                    case "getType": return type;
                    case "hashCode": return System.identityHashCode(p);
                    case "equals": return p == a[0];
                    default: return zero(m.getReturnType());
                }
            });
            Object table = Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {IterableHashTable.class}, (p,m,a) -> m.getName().equals("get") && ((Number) a[0]).longValue() == 101 ? node : null);
            Client client = (Client) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Client.class}, (p,m,a) -> {
                switch (m.getName())
                {
                    case "getMessages": return table;
                    case "getPlayers": return Collections.emptyList();
                    case "getVarbitValue": return transparent ? 1 : 0;
                    case "getVarpValue": return custom && (int) a[0] == varp(type, transparent) ? color + 1 : 0;
                    case "addChatMessage": assertEquals(ChatMessageType.CONSOLE, a[0]); inserted.add((String) a[2]); return null;
                    default: return zero(m.getReturnType());
                }
            });
            GlyphService glyph = new GlyphService() {
                @Override public int uiSize() { return 14; }
                @Override public int wrapChars(int width, int size) { return 200; }
                @Override public String toImgTags(String text, int rgb, int width, int size) {
                    glyphTexts.add(text); glyphColors.add(rgb); return "<img=" + rgb + ">";
                }
            };
            inject(ai, "dir", root.toFile()); inject(ai, "config", config); inject(ai, "gson", new Gson()); inject(ai, "httpClient", http);
            inject(collector, "dir", root.toFile()); inject(collector, "config", config);
            Translator translator = new Translator();
            inject(translator, "client", client); inject(translator, "store", new TranslationStore()); inject(translator, "ai", ai);
            inject(translator, "glyph", glyph); inject(translator, "missing", collector);
            inject(chat, "client", client); inject(chat, "translator", translator); inject(chat, "glyph", glyph);
            inject(chat, "config", config); inject(chat, "toggle", new ToggleService()); inject(chat, "clock", (LongSupplier) clock::get);
            server.start();
        }
        void modeChanged(String key) throws Exception
        {
            com.osrscn.OsrscnPlugin plugin = new com.osrscn.OsrscnPlugin();
            inject(plugin, "chatHandler", chat);
            inject(plugin, "clientThread", new net.runelite.client.callback.ClientThread() {
                @Override public void invoke(Runnable action) { action.run(); }
            });
            net.runelite.client.events.ConfigChanged event = new net.runelite.client.events.ConfigChanged();
            event.setGroup(OsrscnConfig.GROUP); event.setKey(key);
            plugin.onConfigChanged(event);
        }
        void assertDelivered()
        {
            if (mode == PlayerChatMode.INLINE) { assertTrue(value.contains("<img=")); assertTrue(inserted.isEmpty()); }
            else { assertEquals(EN, value); assertEquals(1, inserted.size()); }
            assertEquals(NAME, node.getName());
        }
        void fire() { ChatMessage event = new ChatMessage(); event.setMessageNode(node); event.setType(node.getType()); chat.handle(event); }
        void awaitResponse() throws Exception { await(() -> ZH.equals(ai.cached(EN))); awaitFinished(); }
        void assertRequests(int expected)
        {
            // Admission is synchronous. Check it before the asynchronous server count so a newly
            // queued request cannot pass the negative assertion while still waiting for transport.
            assertEquals("Unexpected request admitted before HTTP arrival", 0, ai.inFlightCount());
            assertEquals(expected, requests.get());
        }
        void awaitFinished() throws Exception
        {
            Field field = AiTranslator.class.getDeclaredField("inFlight"); field.setAccessible(true);
            Set<?> active = (Set<?>) field.get(ai); await(active::isEmpty);
            await(() -> http.dispatcher().runningCallsCount() == 0 && http.dispatcher().queuedCallsCount() == 0);
        }
        void assertPrivate() throws Exception
        {
            collector.flushPending(); collector.stop();
            assertTrue(Files.readString(logFile, StandardCharsets.UTF_8).contains(CANARY));
            try (java.util.stream.Stream<Path> paths = Files.walk(root))
            {
                for (Path path : (Iterable<Path>) paths.filter(Files::isRegularFile)::iterator)
                {
                    String bytes = Files.readString(path, StandardCharsets.UTF_8);
                    assertFalse(bytes.contains(EN)); assertFalse(bytes.contains(ZH)); assertFalse(bytes.contains(NAME));
                }
            }
        }
        @Override public void close() throws Exception
        {
            try
            {
                if (release != null) release.countDown(); server.stop(0); http.dispatcher().cancelAll();
                http.connectionPool().evictAll(); http.dispatcher().executorService().shutdownNow();
                assertTrue("HTTP fixture worker did not terminate", http.dispatcher().executorService().awaitTermination(5, TimeUnit.SECONDS));
                collector.stop();
            }
            finally
            {
                logger.detachAppender(appender); appender.stop(); logger.setLevel(oldLevel); logger.setAdditive(oldAdditive);
            }
        }
        private static Path isolatedRoot() throws Exception
        {
            String base = System.getProperty("osrscn.test.fixtureRoot");
            assertNotNull("Test JVM must be isolated before class initialization", base);
            Path home = Path.of(base).toRealPath();
            assertEquals(home, Path.of(System.getProperty("user.home")).toRealPath());
            assertTrue(RuneLite.RUNELITE_DIR.toPath().toAbsolutePath().startsWith(home));
            assertTrue(Path.of(System.getProperty("logback.configurationFile")).toAbsolutePath().startsWith(home));
            return Files.createTempDirectory(home, "r8-player-http-");
        }
    }
}
