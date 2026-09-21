package com.osrscn.hooks;

import com.osrscn.OsrscnConfig;
import com.osrscn.glyph.GlyphService;
import com.osrscn.translate.Translator;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import org.junit.Test;
import static org.junit.Assert.*;

public class InterfaceTranslatorLifecycleTest
{
    @Test public void groupingNamesNeverReachTranslationAcrossGenericEntrypoints() throws Exception
    {
        for (boolean aiEnabled : new boolean[] {false, true})
        {
            for (String entry : new String[] {"open", "redraw", "group", "component", "groupId", "slow"})
            {
                GroupingFixture f = new GroupingFixture(aiEnabled);
                switch (entry)
                {
                    case "open": f.handler.translateOpen(); break;
                    case "redraw": f.handler.translateRedraw(); break;
                    case "group": f.handler.translateGroup(InterfaceID.Grouping.UNIVERSE); break;
                    case "component": f.handler.translateComponent(InterfaceID.Grouping.PLAYERLIST); break;
                    case "groupId": f.handler.translateGroupId(InterfaceID.Grouping.UNIVERSE >>> 16); break;
                    case "slow": f.handler.translateSlowGroups(); break;
                    default: throw new AssertionError(entry);
                }
                assertEquals(entry + "/" + aiEnabled, "Logs", f.member.text);
                assertEquals(entry + "/" + aiEnabled, "Needle", f.nestedMember.text);
                assertFalse(entry, f.translated.contains("Logs"));
                assertFalse(entry, f.translated.contains("Needle"));
            }
        }
    }

    @Test public void groupingExclusionPreservesStaticAndUnrelatedInterfaceLabels() throws Exception
    {
        GroupingFixture f = new GroupingFixture(true);
        f.handler.translateRedraw();
        assertEquals("<img=998>", f.header.text);
        assertEquals("<img=998>", f.unrelated.text);
        assertEquals(2, f.translated.size());
        assertEquals("Logs", f.member.text);
        assertEquals("Needle", f.nestedMember.text);
    }

    @Test public void groupingRestoreAndRebuildDoNotAdmitPlayerNames() throws Exception
    {
        GroupingFixture f = new GroupingFixture(false);
        f.handler.translateOpen();
        f.handler.restore();
        assertEquals("Grouping", f.header.text);
        assertEquals("Grouping", f.unrelated.text);
        assertEquals("Logs", f.member.text);
        f.member.text = "Logs 2";
        f.translated.clear();
        f.handler.translateOpen();
        f.handler.translateRedraw();
        assertEquals("Logs 2", f.member.text);
        assertEquals("Needle", f.nestedMember.text);
        assertEquals(2, f.translated.size());
        assertEquals("<img=998>", f.header.text);
    }

    @Test public void uninitializedWidgetContainerSkipsEveryRootEnumeration() throws Exception
    {
        Fixture f = new Fixture();
        f.handler.translateRedraw();
        f.handler.translateOpen();
        f.handler.translateSlowGroups();
        f.handler.translateGroupId(200);
        f.handler.restore();
        assertEquals(0, f.rootReads);
    }

    @Test public void readyContainerResumesWithoutWaitingForLoggedIn() throws Exception
    {
        Fixture f = new Fixture();
        f.handler.translateRedraw();
        f.ready = true;
        for (GameState state : new GameState[] {GameState.LOGIN_SCREEN, GameState.LOADING,
                GameState.HOPPING, GameState.LOGGED_IN})
        {
            f.state = state;
            int before = f.rootVisits;
            f.handler.translateRedraw();
            f.handler.translateOpen();
            f.handler.translateSlowGroups();
            f.handler.translateGroupId(200);
            assertEquals(before + 4, f.rootVisits);
        }
        assertEquals(16, f.rootReads);
    }

    @Test public void restoreDuringLoadingRestoresParentAndDynamicChild() throws Exception
    {
        Fixture f = new Fixture();
        f.ready = true;
        f.state = GameState.LOADING;
        f.seedSavedEnglish();
        f.handler.restore();
        assertEquals("Parent label", f.rootText);
        assertEquals("Child label", f.childText);
        assertEquals(1, f.rootReads);
        f.assertStateCleared();
    }

    @Test public void restoreWithoutWidgetContainerStillClearsLocalState() throws Exception
    {
        Fixture f = new Fixture();
        f.seedSavedEnglish();
        f.handler.restore();
        assertEquals(0, f.rootReads);
        f.assertStateCleared();
    }

    @Test public void unexpectedRootFailureAfterInitializationIsNotSwallowed() throws Exception
    {
        Fixture f = new Fixture();
        f.ready = true;
        f.state = GameState.LOGGED_IN;
        f.failure = new IllegalStateException("fixture root failure");
        try
        {
            f.handler.translateRedraw();
            fail("Initialized-client failures must remain visible");
        }
        catch (IllegalStateException ex)
        {
            assertSame(f.failure, ex);
        }
    }

    private static final class GroupingFixture
    {
        final InterfaceTranslator handler = new InterfaceTranslator();
        final List<String> translated = new ArrayList<>();
        final TextWidget root = new TextWidget(InterfaceID.Grouping.UNIVERSE, -1, "");
        final TextWidget header = new TextWidget(InterfaceID.Grouping.HEADER, -1, "Grouping");
        final TextWidget list = new TextWidget(InterfaceID.Grouping.PLAYERLIST, -1, "");
        // Synthetic game words intentionally collide with translatable labels. No actual player data.
        final TextWidget member = new TextWidget(InterfaceID.Grouping.PLAYERLIST, 0, "Logs");
        final TextWidget nestedMember = new TextWidget(200 << 16, 0, "Needle");
        final TextWidget unrelated = new TextWidget(201 << 16, -1, "Grouping");

        GroupingFixture(boolean aiEnabled) throws Exception
        {
            root.children = new Widget[] {header.widget, list.widget};
            list.dynamic = new Widget[] {member.widget};
            list.nested = new Widget[] {nestedMember.widget};
            Class<?> cacheType = Client.class.getMethod("getWidgetSpriteCache").getReturnType();
            Object cache = Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {cacheType},
                    (p, m, a) -> defaultValue(m.getReturnType()));
            Client client = (Client) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] {Client.class}, (p, m, a) -> {
                        switch (m.getName())
                        {
                            case "getGameState": return GameState.LOGGED_IN;
                            case "isClientThread": return true;
                            case "getWidgetSpriteCache": return cache;
                            case "getWidgetRoots": return new Widget[] {root.widget, unrelated.widget};
                            case "getWidget":
                                if (a.length == 1)
                                    for (TextWidget w : new TextWidget[] {root, header, list, unrelated})
                                        if (w.id == (int) a[0]) return w.widget;
                                return null;
                            default: return defaultValue(m.getReturnType());
                        }
                    });
            Constructor<Translator.Rendered> rendered = Translator.Rendered.class
                    .getDeclaredConstructor(String.class, boolean.class);
            rendered.setAccessible(true);
            Translator translator = new Translator()
            {
                @Override public Rendered renderUi(String text, int rgb, int width, int size, boolean aiFallback)
                {
                    translated.add(text);
                    try { return rendered.newInstance("<img=998>", true); }
                    catch (ReflectiveOperationException ex) { throw new AssertionError(ex); }
                }
                @Override public Rendered renderUiNoCollect(String text, int rgb, int width, int size, boolean aiFallback)
                {
                    return renderUi(text, rgb, width, size, aiFallback);
                }
            };
            inject(handler, "client", client);
            inject(handler, "translator", translator);
            inject(handler, "glyph", new GlyphService()
            {
                @Override public int uiSize() { return 14; }
                @Override public int smallSize() { return 12; }
                @Override public int wrapChars(int width, int size) { return 40; }
            });
            inject(handler, "config", Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] {OsrscnConfig.class}, (p, m, a) ->
                            m.getName().equals("aiFillInterface") ? aiEnabled : defaultValue(m.getReturnType())));
        }
    }

    private static final class TextWidget
    {
        final int id;
        final Widget widget;
        String text;
        Widget[] children, dynamic, nested;

        TextWidget(int id, int index, String text)
        {
            this.id = id;
            this.text = text;
            widget = (Widget) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Widget.class},
                    (p, m, a) -> {
                        switch (m.getName())
                        {
                            case "getId": return id;
                            case "getIndex": return index;
                            case "getText": return this.text;
                            case "setText": this.text = (String) a[0]; return null;
                            case "getWidth": return 200;
                            case "getStaticChildren": return children;
                            case "getDynamicChildren": return dynamic;
                            case "getNestedChildren": return nested;
                            default: return defaultValue(m.getReturnType());
                        }
                    });
        }
    }

    private static final class Fixture
    {
        final InterfaceTranslator handler = new InterfaceTranslator();
        boolean ready;
        GameState state = GameState.STARTING;
        int rootReads;
        int rootVisits;
        String rootText = "<img=1>";
        String childText = "<img=2>";
        RuntimeException failure;

        Fixture() throws Exception
        {
            Widget child = (Widget) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] {Widget.class}, (p, m, a) -> {
                        switch (m.getName())
                        {
                            case "getId": return 200 << 16;
                            case "getIndex": return 0;
                            case "getText": return childText;
                            case "setText": childText = (String) a[0]; return null;
                            default: return defaultValue(m.getReturnType());
                        }
                    });
            Widget root = (Widget) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] {Widget.class}, (p, m, a) -> {
                        switch (m.getName())
                        {
                            case "getId": return 200 << 16;
                            case "getIndex": return -1;
                            case "isHidden": rootVisits++; return true;
                            case "getText": return rootText;
                            case "setText": rootText = (String) a[0]; return null;
                            case "getDynamicChildren": return new Widget[] {child};
                            default: return defaultValue(m.getReturnType());
                        }
                    });
            Class<?> cacheType = Client.class.getMethod("getWidgetSpriteCache").getReturnType();
            Object cache = Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {cacheType},
                    (p, m, a) -> defaultValue(m.getReturnType()));
            Client client = (Client) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] {Client.class}, (p, m, a) -> {
                        switch (m.getName())
                        {
                            case "getGameState": return state;
                            case "isClientThread": return true;
                            case "getWidgetSpriteCache": return ready ? cache : null;
                            case "getWidgetRoots":
                                rootReads++;
                                if (!ready) throw new NullPointerException("fixture widget definitions absent");
                                if (failure != null) throw failure;
                                return new Widget[] {root};
                            default: return defaultValue(m.getReturnType());
                        }
                    });
            inject(handler, "client", client);
            inject(handler, "config", Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] {OsrscnConfig.class}, (p, m, a) -> defaultValue(m.getReturnType())));
        }

        void seedSavedEnglish() throws Exception
        {
            long parent = ((long) (200 << 16) << 21) | 0x1FFFFF;
            long child = (long) (200 << 16) << 21;
            map("original").put(parent, "Parent label");
            map("original").put(child, "Child label");
            map("lastSet").put(parent, rootText);
            map("lastSet").put(child, childText);
            map("lastMiss").put(parent, "Untranslated fixture");
        }

        void assertStateCleared() throws Exception
        {
            for (String name : new String[] {"original", "lastSet", "lastMiss", "lastColor",
                    "movedX", "movedY", "movedW", "placedX", "placedY"})
                assertTrue(name, map(name).isEmpty());
        }

        @SuppressWarnings("unchecked")
        Map<Long, Object> map(String name) throws Exception
        {
            Field f = InterfaceTranslator.class.getDeclaredField(name);
            f.setAccessible(true);
            return (Map<Long, Object>) f.get(handler);
        }
    }

    private static void inject(Object target, String name, Object value) throws Exception
    {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Object defaultValue(Class<?> type)
    {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == long.class) return 0L;
        if (type == double.class) return 0D;
        if (type == float.class) return 0F;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == void.class) return null;
        return 0;
    }
}
