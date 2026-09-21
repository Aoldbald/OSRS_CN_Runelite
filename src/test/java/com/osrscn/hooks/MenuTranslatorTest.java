package com.osrscn.hooks;

import static org.junit.Assert.assertEquals;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import com.osrscn.glyph.GlyphService;
import com.osrscn.translate.TranslationStore.Category;
import com.osrscn.translate.Translator;
import net.runelite.api.Client;
import net.runelite.api.Menu;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Point;
import net.runelite.api.events.MenuOpened;
import org.junit.Test;

public class MenuTranslatorTest
{
	private static int nativeEntryAt(int menuX, int menuY, int menuWidth, int entryCount,
			int scroll, int mouseX, int mouseY)
	{
		try
		{
			Method method = MenuTranslator.class.getDeclaredMethod("nativeEntryAt", int.class, int.class,
					int.class, int.class, int.class, int.class, int.class);
			method.setAccessible(true);
			return (int) method.invoke(null, menuX, menuY, menuWidth, entryCount, scroll, mouseX, mouseY);
		}
		catch (ReflectiveOperationException ex)
		{
			throw new AssertionError("native menu hover geometry is not implemented", ex);
		}
	}

	@Test
	public void onlyNativeCurrentRowIsSelected()
	{
		assertEquals(1, nativeEntryAt(100, 200, 160, 3, 0, 120, 246));
		assertEquals(0, nativeEntryAt(100, 200, 160, 3, 0, 120, 261));
		assertEquals(2, nativeEntryAt(100, 200, 160, 3, 0, 120, 231));
	}

	@Test
	public void nativeScrollKeepsEntryIndexAligned()
	{
		assertEquals(1, nativeEntryAt(100, 200, 160, 5, 2, 120, 246));
		assertEquals(0, nativeEntryAt(100, 200, 160, 5, 2, 120, 261));
		assertEquals(-1, nativeEntryAt(100, 200, 160, 5, 2, 120, 276));
	}

	@Test
	public void submenuGeometryDoesNotSelectRootRow()
	{
		assertEquals(-1, nativeEntryAt(100, 200, 140, 3, 0, 310, 231));
		assertEquals(2, nativeEntryAt(260, 200, 140, 3, 0, 310, 231));
	}

	@Test
	public void nativeHitboxUsesStrictEdges()
	{
		assertEquals(-1, nativeEntryAt(100, 200, 160, 3, 0, 100, 231));
		assertEquals(-1, nativeEntryAt(100, 200, 160, 3, 0, 120, 218));
		assertEquals(1, nativeEntryAt(100, 200, 160, 3, 0, 120, 234));
	}

	@Test
	public void baseHoverBaseThenClickRestoresExactEnglish() throws Exception
	{
		MenuFixture f = new MenuFixture(100, 200, 160);
		MenuEntry entry = f.entry("Walk here", "Goblin");
		f.root(entry);
		MenuTranslator handler = f.handler();
		MenuOpened opened = new MenuOpened();
		opened.setMenuEntries(new MenuEntry[] { entry });
		handler.handleMenuOpened(opened);
		assertEquals(img("Walk here", 0xffffff), entry.getOption());
		assertEquals(img("Goblin", 0xffffff), entry.getTarget());

		f.mouse.set(new Point(120, 231));
		handler.retranslateOpenMenu();
		assertEquals(img("Walk here", 0xffff00), entry.getOption());
		assertEquals(img("Goblin", 0xffff00), entry.getTarget());

		f.mouse.set(new Point(20, 20));
		handler.retranslateOpenMenu();
		assertEquals(img("Walk here", 0xffffff), entry.getOption());
		assertEquals(img("Goblin", 0xffffff), entry.getTarget());

		handler.restoreForClick(entry);
		assertEquals("Walk here", entry.getOption());
		assertEquals("Goblin", entry.getTarget());
	}

	@Test
	public void submenuHoverDoesNotRecolourRoot() throws Exception
	{
		MenuFixture f = new MenuFixture(100, 200, 140);
		MenuEntry child0 = f.entry("Walk here", "Goblin");
		MenuEntry child1 = f.entry("Walk here", "Goblin");
		Menu sub = f.menu(260, 200, 140, child0, child1);
		MenuEntry rootEntry = f.entry("Walk here", "Goblin", sub);
		f.root(rootEntry);
		MenuTranslator handler = f.handler();
		MenuOpened opened = new MenuOpened();
		opened.setMenuEntries(new MenuEntry[] { rootEntry });
		handler.handleMenuOpened(opened);

		f.mouse.set(new Point(310, 231));
		handler.retranslateOpenMenu();
		assertEquals(img("Walk here", 0xffffff), rootEntry.getOption());
		assertEquals(img("Walk here", 0xffff00), child1.getOption());
		assertEquals(img("Walk here", 0xffffff), child0.getOption());
	}

	@Test
	public void readyGlyphsAreNotRerenderedUntilColorChanges() throws Exception
	{
		MenuFixture f = new MenuFixture(100, 200, 160);
		MenuEntry entry = f.entry("Walk here", "Goblin");
		f.root(entry);
		MenuTranslator handler = f.handler();
		MenuOpened opened = new MenuOpened();
		opened.setMenuEntries(new MenuEntry[] { entry });
		handler.handleMenuOpened(opened);
		int afterOpen = f.translator.calls.get();
		handler.retranslateOpenMenu();
		handler.retranslateOpenMenu();
		assertEquals(afterOpen, f.translator.calls.get());
		f.mouse.set(new Point(120, 231));
		handler.retranslateOpenMenu();
		assertEquals(afterOpen + 3, f.translator.calls.get());
		handler.retranslateOpenMenu();
		assertEquals(afterOpen + 3, f.translator.calls.get());
	}

	@Test
	public void glyphUploadPendingRetriesWithoutReopening() throws Exception
	{
		MenuFixture f = new MenuFixture(100, 200, 160);
		f.translator.pendingCalls.set(4);
		MenuEntry entry = f.entry("Walk here", "Goblin");
		f.root(entry);
		MenuTranslator handler = f.handler();
		MenuOpened opened = new MenuOpened();
		opened.setMenuEntries(new MenuEntry[] { entry });
		handler.handleMenuOpened(opened);
		assertEquals("Walk here", entry.getOption());
		handler.retranslateOpenMenu();
		assertEquals(img("Walk here", 0xffffff), entry.getOption());
	}

	private static String img(String english, int color)
	{
		return "<img=" + english.hashCode() + "><col=" + Integer.toHexString(color) + ">";
	}

	private static void inject(Object target, String name, Object value) throws Exception
	{
		java.lang.reflect.Field field = target.getClass().getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}

	private static final class FakeGlyph extends GlyphService
	{
		@Override
		public int uiSize()
		{
			return 14;
		}
	}

	private static final class FakeTranslator extends Translator
	{
		final AtomicInteger calls = new AtomicInteger();
		final AtomicInteger pendingCalls = new AtomicInteger();

		@Override
		public String lookupRenderMenuOption(String option, int color, int size)
		{
			calls.incrementAndGet();
			return null;
		}

		@Override
		public String lookupRender(Category category, String english, int color, int maxChars, int size)
		{
			calls.incrementAndGet();
			if (pendingCalls.getAndUpdate(n -> Math.max(0, n - 1)) > 0)
			{
				return null;
			}
			if (category == Category.ACTIONS && "Walk here".equals(english))
			{
				return img(english, color);
			}
			if (category == Category.NAME && "Goblin".equals(english))
			{
				return img(english, color);
			}
			return null;
		}
	}

	private static final class MenuFixture
	{
		final AtomicReference<Point> mouse = new AtomicReference<>(new Point(20, 20));
		final FakeTranslator translator = new FakeTranslator();
		final int x;
		final int y;
		final int width;
		Menu root;
		MenuEntry[] rootEntries = new MenuEntry[0];

		MenuFixture(int x, int y, int width)
		{
			this.x = x;
			this.y = y;
			this.width = width;
		}

		MenuEntry entry(String option, String target)
		{
			return entry(option, target, null);
		}

		MenuEntry entry(String option, String target, Menu submenu)
		{
			AtomicReference<String> opt = new AtomicReference<>(option);
			AtomicReference<String> tgt = new AtomicReference<>(target);
			return (MenuEntry) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { MenuEntry.class },
					(proxy, method, args) ->
					{
						switch (method.getName())
						{
							case "getOption": return opt.get();
							case "setOption": opt.set((String) args[0]); return proxy;
							case "getTarget": return tgt.get();
							case "setTarget": tgt.set((String) args[0]); return proxy;
							case "getType": return MenuAction.WALK;
							case "getSubMenu": return submenu;
							case "hashCode": return System.identityHashCode(proxy);
							case "equals": return proxy == args[0];
							default: return defaultValue(method.getReturnType());
						}
					});
		}

		Menu menu(int mx, int my, int mw, MenuEntry... entries)
		{
			return (Menu) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { Menu.class },
					(proxy, method, args) ->
					{
						switch (method.getName())
						{
							case "getMenuEntries": return entries;
							case "getMenuX": return mx;
							case "getMenuY": return my;
							case "getMenuWidth": return mw;
							case "getMenuHeight": return 50;
							default: return defaultValue(method.getReturnType());
						}
					});
		}

		void root(MenuEntry... entries)
		{
			rootEntries = entries;
			root = menu(x, y, width, entries);
		}

		MenuTranslator handler() throws Exception
		{
			Client client = (Client) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { Client.class },
					(proxy, method, args) ->
					{
						switch (method.getName())
						{
							case "isMenuOpen": return true;
							case "getMenu": return root;
							case "getMenuEntries": return rootEntries;
							case "getMenuX": return x;
							case "getMenuY": return y;
							case "getMenuWidth": return width;
							case "getMenuHeight": return 50;
							case "getMenuScroll": return 0;
							case "getMouseCanvasPosition": return mouse.get();
							default: return defaultValue(method.getReturnType());
						}
					});
			MenuTranslator handler = new MenuTranslator();
			inject(handler, "client", client);
			inject(handler, "translator", translator);
			inject(handler, "glyph", new FakeGlyph());
			return handler;
		}
	}

	private static Object defaultValue(Class<?> type)
	{
		if (!type.isPrimitive()) return null;
		if (type == boolean.class) return false;
		if (type == byte.class) return (byte) 0;
		if (type == short.class) return (short) 0;
		if (type == int.class) return 0;
		if (type == long.class) return 0L;
		if (type == float.class) return 0f;
		if (type == double.class) return 0d;
		if (type == char.class) return '\0';
		return null;
	}
}
