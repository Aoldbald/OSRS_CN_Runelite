package com.osrscn.hooks;

import com.osrscn.glyph.GlyphService;
import com.osrscn.translate.AiTranslator;
import com.osrscn.translate.MissingCollector;
import com.osrscn.translate.TranslationStore;
import com.osrscn.translate.Translator;
import com.osrscn.ui.DialogueHistory;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real Translator and DialogueHandler over in-memory tables; only client/glyph IO is fake. */
public class DialogueNumericTemplateTest
{
    private static void set(Object target, String name, Object value) throws Exception
    {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object fallback(Class<?> type)
    {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        return null;
    }

    private static class Fixture
    {
        final TranslationStore store = new TranslationStore();
        final Translator translator = new Translator();
        final DialogueHandler handler = new DialogueHandler();
        final DialogueHistory history = new DialogueHistory();
        final Map<Integer, Widget> widgets = new HashMap<>();
        final List<Integer> renderedColors = new ArrayList<>();
        int collected;
        int aiRequests;
        int aiTranslateCalls;

        Fixture() throws Exception
        {
            row(TranslationStore.Category.DIALOGUE, "Pay <Num0> coins.", "支付<Num0>金币。");
            row(TranslationStore.Category.INTERFACE, "Reward <Num0> points", "获得<Num0>点");
            Client client = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("getWidget")) return widgets.get((Integer) args[0]);
                        if (method.getName().equals("getLocalPlayer")) return null;
                        if (method.getName().equals("getTickCount")) return 0;
                        if (method.getName().equals("getPlayers")) return java.util.Collections.emptyList();
                        throw new AssertionError("Unexpected client call: " + method.getName());
                    });
            GlyphService glyph = new GlyphService() {
                @Override public int dialogueSize() { return 16; }
                @Override public int wrapChars(int width, int size) { return 100; }
                @Override public String toImgTags(String text, int color, int maxChars, int size) { renderedColors.add(color); return text; }
            };
            set(translator, "client", client); set(translator, "store", store); set(translator, "glyph", glyph);
            set(translator, "missing", new MissingCollector() {
                @Override public void record(String en, String cat, String sub, String source) {
                    throw new AssertionError("Table miss reached forbidden collector");
                }
            });
            set(translator, "ai", new AiTranslator() {
                @Override public synchronized String cached(String en) { return null; }
                @Override public String translate(String en) { throw new AssertionError("AI forbidden"); }
                @Override public String translate(String en, boolean persist) { throw new AssertionError("AI forbidden"); }
            });
            set(handler, "client", client); set(handler, "translator", translator);
            set(handler, "glyph", glyph); set(handler, "history", history);
        }

        AtomicReference<String> dialogue(int id, String text)
        {
            return dialogue(id, text, new AtomicInteger());
        }

        AtomicReference<String> dialogue(int id, String text, AtomicInteger color)
        {
            AtomicReference<String> value = new AtomicReference<>(text);
            Widget widget = (Widget) Proxy.newProxyInstance(Widget.class.getClassLoader(), new Class<?>[]{Widget.class},
                    (proxy, method, args) -> {
                        switch (method.getName())
                        {
                            case "getText": return value.get();
                            case "setText": value.set((String) args[0]); return proxy;
                            case "getWidth": return 500;
                            case "getTextColor": return color.get();
                            default: return fallback(method.getReturnType());
                        }
                    });
            widgets.put(id, widget);
            return value;
        }

        @SafeVarargs
        final void options(AtomicReference<String>... values)
        {
            Widget[] children = new Widget[values.length];
            for (int i = 0; i < values.length; i++)
            {
                AtomicReference<String> value = values[i];
                children[i] = (Widget) Proxy.newProxyInstance(Widget.class.getClassLoader(), new Class<?>[]{Widget.class},
                        (proxy, method, args) -> {
                            switch (method.getName())
                            {
                                case "getText": return value.get();
                                case "setText": value.set((String) args[0]); return proxy;
                                case "getWidth": return 500;
                                case "getTextColor": return 0;
                                default: return fallback(method.getReturnType());
                            }
                        });
            }
            Widget parent = (Widget) Proxy.newProxyInstance(Widget.class.getClassLoader(), new Class<?>[]{Widget.class},
                    (proxy, method, args) -> method.getName().equals("getDynamicChildren") ? children : fallback(method.getReturnType()));
            widgets.put(InterfaceID.Chatmenu.OPTIONS, parent);
        }

        void dialogueRow(String english, String chinese) throws Exception
        {
            row(TranslationStore.Category.DIALOGUE, english, chinese);
        }

        @SuppressWarnings("unchecked")
        void row(TranslationStore.Category category, String english, String chinese) throws Exception
        {
            Field field = TranslationStore.class.getDeclaredField("maps");
            field.setAccessible(true);
            Map<TranslationStore.Category, Map<String, String>> maps =
                    (Map<TranslationStore.Category, Map<String, String>>) field.get(store);
            maps.get(category).put(TranslationStore.normalize(english), chinese);
        }

        void countMissFallbacks() throws Exception
        {
            set(translator, "missing", new MissingCollector() {
                @Override public void record(String en, String cat, String sub, String source) { collected++; }
            });
            set(translator, "ai", new AiTranslator() {
                @Override public synchronized String cached(String en) { return null; }
                @Override public String translate(String en) { aiRequests++; return null; }
                @Override public String translate(String en, boolean persist) { aiRequests++; return null; }
            });
        }

        void cachedAi(String cached) throws Exception
        {
            set(translator, "missing", new MissingCollector() {
                @Override public void record(String en, String cat, String sub, String source) { }
            });
            set(translator, "ai", new AiTranslator() {
                @Override public synchronized String cached(String en) { return cached; }
                @Override public String translate(String en) { aiTranslateCalls++; return cached; }
                @Override public String translate(String en, boolean persist) { aiTranslateCalls++; return cached; }
            });
        }
    }

    @Test public void npcAmountTenTraversesRealDialogueHandler() throws Exception
    {
        Fixture f = new Fixture();
        AtomicReference<String> value = f.dialogue(InterfaceID.ChatLeft.TEXT, "Pay 10 coins.");
        f.handler.translateDialogues();
        assertEquals("支付10金币。", value.get());
        assertEquals("支付10金币。", f.history.snapshot().get(0).zh);
    }

    @Test public void playerAmountTwentyFiveTraversesRealDialogueHandler() throws Exception
    {
        Fixture f = new Fixture();
        AtomicReference<String> value = f.dialogue(InterfaceID.ChatRight.TEXT, "Pay 25 coins.");
        f.handler.translateDialogues();
        assertEquals("支付25金币。", value.get());
        f.handler.restore();
        assertEquals("Pay 25 coins.", value.get());
    }

    @Test public void playerOptionWithAmountUsesTheTemplateRow() throws Exception
    {
        Fixture f = new Fixture();
        f.dialogueRow("Yes, pay <Num0> coins.", "好的，支付<Num0>金币。");
        AtomicReference<String> option = new AtomicReference<>("Yes, pay 375 coins.");
        f.options(option);
        f.handler.translateDialogues();
        assertEquals("好的，支付375金币。", option.get());
    }

    @Test public void dialogueOnlyNumericTemplatesReachTextAndOptionBranchesWithoutPrivacyFallback() throws Exception
    {
        Fixture f = new Fixture();
        f.dialogueRow("Pay 25 coins.", "精确二十五金币。");
        f.dialogueRow("<colNum0>Pay <Num0> coins.</col>", "<colNum0>支付<Num0>金币。</col>");
        AtomicReference<String> left = f.dialogue(InterfaceID.ChatLeft.TEXT, "Pay 1 coins.");
        AtomicReference<String> right = f.dialogue(InterfaceID.ChatRight.TEXT, "Pay 42 coins.");
        AtomicReference<String> coloured = new AtomicReference<>("<col=ff0000>Pay 11 coins.</col>");
        AtomicReference<String> thousands = new AtomicReference<>("Pay 1,000 coins.");
        AtomicReference<String> decimal = new AtomicReference<>("Pay 2.5 coins.");
        f.options(coloured, thousands, decimal);
        f.handler.translateDialogues();
        assertEquals("支付1金币。", left.get());
        assertEquals("支付42金币。", right.get());
        assertEquals("<col=ff0000>支付11金币。</col>", coloured.get());
        assertEquals("支付1,000金币。", thousands.get());
        assertEquals("支付2.5金币。", decimal.get());

        Fixture exact = new Fixture();
        exact.dialogueRow("Pay 25 coins.", "精确二十五金币。");
        AtomicReference<String> exactValue = exact.dialogue(InterfaceID.ChatLeft.TEXT, "Pay 25 coins.");
        exact.handler.translateDialogues();
        assertEquals("精确二十五金币。", exactValue.get());

        Fixture identity = new Fixture(); identity.dialogueRow("Trading with: <Num0>", "正在交易：<Num0>");
        AtomicReference<String> protectedValue = identity.dialogue(InterfaceID.ChatLeft.TEXT, "Trading with: 10");
        identity.handler.translateDialogues();
        assertEquals("Trading with: 10", protectedValue.get());

        assertEquals("支付5金币。", f.translator.plainColoredDialogueNoCollect("Pay 5 coins."));
        assertNull(f.translator.plainColoredDialogueNoCollect("Unknown 5 words."));
    }

    @Test public void ordinaryColouredMissCollectsAndRequestsOnlyOnce() throws Exception
    {
        Fixture f = new Fixture(); f.countMissFallbacks();
        assertNull(f.translator.plainColored("<col=ff0000>Unmapped 5 dialogue words.</col>", "dialogue", "", ""));
        assertEquals(1, f.collected);
        assertEquals(1, f.aiRequests);
    }

    @Test public void dialogueTemplatesDoNotCrossCategoriesAndExperimentalStillConsumesNumbers() throws Exception
    {
        Fixture f = new Fixture(); f.countMissFallbacks();
        assertNull(f.translator.plainColoredDialogue("Reward 25 points", "dialogue", "", ""));
        assertEquals(1, f.collected); assertEquals(1, f.aiRequests);
        assertEquals("获得25点", f.translator.renderUi("Reward 25 points", 0, 100, 16, false).text);
        f.row(TranslationStore.Category.DIALOGUE_EXPERIMENTAL, "Experimental <Num0> points", "实验<Num0>点");
        f.row(TranslationStore.Category.DIALOGUE_EXPERIMENTAL, "Trade <Num0> for <Num1>", "用<Num1>换<Num0>");
        assertEquals("实验25点", f.translator.plainColoredDialogue("Experimental 25 points", "dialogue", "", ""));
        assertEquals("用9换3", f.translator.plainColoredDialogue("Trade 3 for 9", "dialogue", "", ""));
    }

    @Test public void dialogueMissAndCachedHoverRecolourHaveOneExitEach() throws Exception
    {
        Fixture miss = new Fixture(); miss.countMissFallbacks();
        assertNull(miss.translator.plainColoredDialogue("Missing 6 words.", "dialogue", "", ""));
        assertEquals(1, miss.collected); assertEquals(1, miss.aiRequests);

        Fixture cached = new Fixture(); cached.cachedAi("缓存悬停7");
        AtomicInteger color = new AtomicInteger(0x112233);
        AtomicReference<String> value = cached.dialogue(InterfaceID.ChatLeft.TEXT, "Cache-only hover 7", color);
        cached.handler.translateDialogues();
        assertEquals("缓存悬停7", value.get());
        int beforeRecolour = cached.aiTranslateCalls;
        color.set(0x445566); cached.handler.translateDialogues();
        assertEquals("缓存悬停7", value.get());
        assertEquals(java.util.Arrays.asList(0x112233, 0x445566), cached.renderedColors);
        assertEquals(beforeRecolour, cached.aiTranslateCalls);
    }
}
