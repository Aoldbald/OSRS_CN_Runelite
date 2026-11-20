package com.osrscn.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.osrscn.OsrscnConfig;
import com.osrscn.glyph.GlyphService;
import com.osrscn.translate.Translator;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import org.junit.Test;

/**
 * The combat-achievement boss-detail page title ("Combat Achievements - &lt;boss&gt;") is written by the
 * interface's own onLoad script (4835) directly onto {@link InterfaceID.CaBoss#FRAME}'s text (the same
 * script then creates the page's child widgets). These tests pin the two targeted ways it reaches the
 * translator - the script hook (fire-and-translate before the frame draws) and the per-game-tick
 * backstop (a page already open when Chinese was toggled on) - and that both are idempotent once the
 * widget holds our char-images.
 */
public class SurfaceRegistryCaTitleTest
{
	@Test
	public void script4835IsTheCaBossTitleBuildScript()
	{
		SurfaceRegistry registry = SurfaceRegistry.build();
		List<SurfaceRegistry.Target> targets = registry.forScript(4835);
		assertNotNull("script 4835 must be registered", targets);
		assertEquals(1, targets.size());
		assertEquals(SurfaceRegistry.Reach.COMPONENT, targets.get(0).reach);
		assertEquals(InterfaceID.CaBoss.FRAME, targets.get(0).id);
	}

	@Test
	public void caBossFrameIsTheComponentTheWidgetInspectorReports()
	{
		// Widget inspector observation: id 46727170, group 713 (CaBoss), and the page title is the
		// frame's own text (script 4835 cc_settext's it before creating the page's children). Pin both
		// the group and the literal so a gameval update cannot silently move the hook.
		assertEquals(713, InterfaceID.CaBoss.FRAME >>> 16);
		assertEquals(46727170, InterfaceID.CaBoss.FRAME);
	}

	@Test
	public void caBossPageKeepsTheOrdinaryTickScanPolicy()
	{
		// group 713 is not excluded / no-AI / per-frame-excluded (unlike the CaTasks list, group 715),
		// which is why the generic walk is still a second line of defence beside the targeted paths.
		SurfaceRegistry registry = SurfaceRegistry.build();
		SurfaceRegistry.Surface caBoss = registry.forGroup(InterfaceID.CaBoss.FRAME >>> 16);
		assertFalse(caBoss.excluded);
		assertFalse(caBoss.noAi);
		assertFalse(caBoss.perFrameExcluded);
	}

	@Test
	public void unrelatedScriptsAreNotRegisteredForTheCaBossPage()
	{
		SurfaceRegistry registry = SurfaceRegistry.build();
		assertNull(registry.forScript(4836)); // helper script: only sets the boss-name tab label
		assertNull(registry.forScript(0));
	}

	@Test
	public void scriptHookTranslatesThePageTitle() throws Exception
	{
		CaBossFixture f = new CaBossFixture();
		f.handler.translateForScript(4835);
		assertEquals(java.util.Arrays.asList("Combat Achievements - Shellbane Gryphon"), f.renderCalls);
		assertEquals("<img=998>", f.frame.text);
	}

	@Test
	public void scriptHookIsIdempotentOnceTheTitleIsOurs() throws Exception
	{
		CaBossFixture f = new CaBossFixture();
		f.handler.translateForScript(4835);
		f.handler.translateForScript(4835);
		assertEquals(1, f.renderCalls.size());
		assertEquals("<img=998>", f.frame.text);
	}

	@Test
	public void scriptHookLeavesAClosedPageAlone() throws Exception
	{
		CaBossFixture f = new CaBossFixture();
		f.framePresent = false;
		f.handler.translateForScript(4835);
		assertTrue(f.renderCalls.isEmpty());
	}

	@Test
	public void scriptHookLeavesAHiddenFrameAlone() throws Exception
	{
		CaBossFixture f = new CaBossFixture();
		f.frame.hidden = true;
		f.handler.translateForScript(4835);
		assertTrue(f.renderCalls.isEmpty());
	}

	@Test
	public void gameTickBackstopTranslatesAPageOpenedBeforeChineseWasOn() throws Exception
	{
		CaBossFixture f = new CaBossFixture();
		f.handler.translateSlowGroups(); // what the plugin runs once per game tick
		assertEquals(1, f.renderCalls.size());
		assertEquals("<img=998>", f.frame.text);
	}

	@Test
	public void gameTickBackstopIsANoOpOnceTheTitleIsChinese() throws Exception
	{
		CaBossFixture f = new CaBossFixture();
		f.handler.translateSlowGroups();
		f.handler.translateSlowGroups();
		f.handler.translateSlowGroups();
		assertEquals(1, f.renderCalls.size());
		assertEquals("<img=998>", f.frame.text);
	}

	@Test
	public void gameTickBackstopLeavesAClosedPageAlone() throws Exception
	{
		CaBossFixture f = new CaBossFixture();
		f.framePresent = false;
		f.handler.translateSlowGroups();
		assertTrue(f.renderCalls.isEmpty());
	}

	private static final class CaBossFixture
	{
		final InterfaceTranslator handler = new InterfaceTranslator();
		final List<String> renderCalls = new ArrayList<>();
		// script 4835 writes the title onto CaBoss.FRAME itself; the frame then gets the page's children.
		final TextWidget frame = new TextWidget(InterfaceID.CaBoss.FRAME, 2,
				"Combat Achievements - Shellbane Gryphon");
		boolean framePresent = true;

		CaBossFixture() throws Exception
		{
			Client client = (Client) Proxy.newProxyInstance(getClass().getClassLoader(),
					new Class<?>[] {Client.class}, (p, m, a) -> {
						switch (m.getName())
						{
							case "getWidget":
								if (a.length == 1 && (int) a[0] == InterfaceID.CaBoss.FRAME)
								{
									return framePresent ? frame.widget : null;
								}
								return null;
							default: return defaultValue(m.getReturnType());
						}
					});
			Constructor<Translator.Rendered> rendered = Translator.Rendered.class
					.getDeclaredConstructor(String.class, boolean.class);
			rendered.setAccessible(true);
			Translator translator = new Translator()
			{
				@Override public Rendered renderUi(String text, int rgb, int width, int size, boolean ai)
				{
					renderCalls.add(text);
					try { return rendered.newInstance("<img=998>", true); }
					catch (ReflectiveOperationException ex) { throw new AssertionError(ex); }
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
							m.getName().equals("aiFillInterface") ? true : defaultValue(m.getReturnType())));
		}
	}

	private static final class TextWidget
	{
		final int id;
		final int index;
		String text;
		boolean hidden;
		Widget[] children, dynamic, nested;
		final Widget widget;

		TextWidget(int id, int index, String text)
		{
			this.id = id;
			this.index = index;
			this.text = text;
			widget = (Widget) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Widget.class},
					(p, m, a) -> {
						switch (m.getName())
						{
							case "getId": return id;
							case "getIndex": return index;
							case "getText": return this.text;
							case "setText": this.text = (String) a[0]; return null;
							case "isHidden":
							case "isSelfHidden": return hidden;
							case "getWidth": return 200;
							case "getHeight": return 20;
							case "getStaticChildren": return children;
							case "getDynamicChildren": return dynamic;
							case "getNestedChildren": return nested;
							default: return defaultValue(m.getReturnType());
						}
					});
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
