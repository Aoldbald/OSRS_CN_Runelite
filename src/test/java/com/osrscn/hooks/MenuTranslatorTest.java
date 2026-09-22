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


	private static MenuTranslator opened(MenuFixture f, MenuEntry entry) throws Exception
	{
		f.root(entry); MenuTranslator handler = f.handler(); MenuOpened event = new MenuOpened();
		event.setMenuEntries(new MenuEntry[]{entry}); handler.handleMenuOpened(event); return handler;
	}

	@Test public void recycledNativeBankClickRetainsItsOptionForBankTags() throws Exception
	{
		MenuFixture f = new MenuFixture(100, 200, 160); MenuEntry entry = f.entry("Walk here", "Goblin");
		MenuTranslator handler = opened(f, entry);
		// The injected client reuses its entry object for a later native left-click without MenuOpened.
		entry.setOption("View tab 4").setTarget("").setType(MenuAction.CC_OP).setIdentifier(1).setParam0(4).setParam1(12 << 16);
		com.osrscn.OsrscnPlugin plugin = new com.osrscn.OsrscnPlugin();
		inject(plugin, "menuTranslator", handler); inject(plugin, "toggle", new com.osrscn.ToggleService());
		inject(plugin, "config", Proxy.newProxyInstance(getClass().getClassLoader(),
			new Class<?>[]{com.osrscn.OsrscnConfig.class}, (p,m,a) -> m.getName().equals("translateMenus") ? true : defaultValue(m.getReturnType())));
		net.runelite.api.events.MenuOptionClicked click = new net.runelite.api.events.MenuOptionClicked(entry);
		plugin.onMenuOptionClicked(click);
		// BankTags 1.12.39 uses this exact option prefix to close the active Inventory Setups filter.
		assertEquals(true, click.getMenuOption().startsWith("View tab"));
		assertEquals("", entry.getTarget()); assertEquals(MenuAction.CC_OP, entry.getType());
		assertEquals(4, entry.getParam0()); assertEquals(12 << 16, entry.getParam1()); assertEquals(false, click.isConsumed());
	}

	@Test public void recycledPluginActionAndCallbackAreUntouched() throws Exception
	{
		MenuFixture f = new MenuFixture(100, 200, 160); MenuEntry entry = f.entry("Walk here", "Goblin");
		MenuTranslator handler = opened(f, entry); AtomicReference<String> observed = new AtomicReference<>();
		entry.setType(MenuAction.RUNELITE).setOption("Open setup").setTarget("Test setup").onClick(e -> observed.set(e.getOption()));
		handler.restoreForClick(entry); entry.onClick().accept(entry);
		assertEquals("Open setup", observed.get()); assertEquals("Test setup", entry.getTarget());
	}

	@Test public void changedActionParametersInvalidateIdenticalRenderedText() throws Exception
	{
		for (String setter : new String[]{"setIdentifier", "setParam0", "setParam1", "setItemId", "setWorldViewId"}) {
			MenuFixture f = new MenuFixture(100, 200, 160); MenuEntry entry = f.entry("Walk here", "Goblin");
			MenuTranslator handler = opened(f, entry); String option = entry.getOption(); String target = entry.getTarget();
			MenuEntry.class.getMethod(setter, int.class).invoke(entry, 17);
			handler.restoreForClick(entry); assertEquals(setter, option, entry.getOption()); assertEquals(target, entry.getTarget());
		}
	}

	@Test public void foreignTextRewriteWithSameActionIsNotRestoredFromOldState() throws Exception
	{
		MenuFixture f = new MenuFixture(100, 200, 160); MenuEntry entry = f.entry("Walk here", "Goblin");
		MenuTranslator handler = opened(f, entry); entry.setOption("View all items").setTarget("New target");
		handler.restoreForClick(entry); assertEquals("View all items", entry.getOption()); assertEquals("New target", entry.getTarget());
	}

	@Test public void reusedEntryDuringOpenMenuDoesNotRepaintOldAction() throws Exception
	{
		MenuFixture f = new MenuFixture(100, 200, 160); MenuEntry entry = f.entry("Walk here", "Goblin");
		MenuTranslator handler = opened(f, entry); entry.setOption("View tab 4").setTarget("");
		f.mouse.set(new Point(120, 231)); handler.retranslateOpenMenu();
		assertEquals("View tab 4", entry.getOption()); assertEquals("", entry.getTarget());
	}

	@Test public void changedCallbackInvalidatesCachedOwnership() throws Exception
	{
		MenuFixture f = new MenuFixture(100, 200, 160); MenuEntry entry = f.entry("Walk here", "Goblin");
		MenuTranslator handler = opened(f, entry); String option = entry.getOption();
		entry.onClick(e -> { }); handler.restoreForClick(entry); assertEquals(option, entry.getOption());
	}

	@Test public void restoringOneFieldDoesNotOverwriteAnUntranslatedTarget() throws Exception
	{
		MenuFixture f = new MenuFixture(100, 200, 160); MenuEntry entry = f.entry("Walk here", "Unknown target");
		MenuTranslator handler = opened(f, entry); handler.restoreForClick(entry);
		assertEquals("Walk here", entry.getOption()); assertEquals("Unknown target", entry.getTarget());
		entry.setOption("View all items"); handler.restoreForClick(entry); assertEquals("View all items", entry.getOption());
	}

	@Test public void untranslatedOldEntryCannotOverwriteViewAllItems() throws Exception
	{
		MenuFixture f = new MenuFixture(100, 200, 160); MenuEntry entry = f.entry("Unknown option", "Unknown target");
		MenuTranslator handler = opened(f, entry);
		entry.setOption("View all items").setTarget("").setType(MenuAction.CC_OP);
		handler.restoreForClick(entry); assertEquals("View all items", entry.getOption()); assertEquals("", entry.getTarget());
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
			java.util.Map<String, Object> fields = new java.util.HashMap<>(); fields.put("Type", MenuAction.WALK);
			return (MenuEntry) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { MenuEntry.class },
					(proxy, method, args) ->
					{
						switch (method.getName())
						{
							case "getOption": return opt.get();
							case "setOption": opt.set((String) args[0]); return proxy;
							case "getTarget": return tgt.get();
							case "setTarget": tgt.set((String) args[0]); return proxy;
							case "onClick":
								if (args == null || args.length == 0) return fields.get("callback");
								fields.put("callback", args[0]); return proxy;
							case "getSubMenu": return submenu;
							case "hashCode": return System.identityHashCode(proxy);
							case "equals": return proxy == args[0];
							default:
								String name = method.getName();
								if (name.startsWith("set")) { fields.put(name.substring(3), args[0]); return proxy; }
								if (name.startsWith("get")) return fields.getOrDefault(name.substring(3), defaultValue(method.getReturnType()));
								return defaultValue(method.getReturnType());
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
