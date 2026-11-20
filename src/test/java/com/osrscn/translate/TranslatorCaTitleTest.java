package com.osrscn.translate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import org.junit.Test;

/**
 * The combat-achievement boss detail page title is a fixed prefix plus the boss name the client read
 * from a struct param ("Combat Achievements - Shellbane Gryphon"). The transcript data only ever
 * carried the old bosses' full-title rows, so every newer boss missed the tables and fell through to
 * the AI (or stayed English when the AI was off / rejected the line). These tests pin the local
 * composition: prefix plus the boss name from the NAME table, with the game's optional "The " article
 * retried without it. A boss whose name is unknown must still miss, so the existing AI fallback - and
 * the missing-row collector - keep their behaviour.
 */
public class TranslatorCaTitleTest
{
	private final RecordingCollector collected = new RecordingCollector();
	private final List<String> aiRequests = new ArrayList<>();
	private final Translator translator = new Translator();

	public TranslatorCaTitleTest() throws Exception
	{
		set(translator, "client", client());
		set(translator, "store", new TranslationStore());
		set(translator, "missing", collected);
		set(translator, "ai", new AiTranslator()
		{
			@Override public synchronized String cached(String text) { return null; }

			@Override public String translate(String text, boolean persist)
			{
				aiRequests.add(text);
				return null;
			}
		});
		set(translator, "glyph", new com.osrscn.glyph.GlyphService()
		{
			@Override public String toImgTags(String text, int color, int maxChars, int size) { return text; }
		});
	}

	@Test
	public void composesTheTitleFromTheNameTable() throws Exception
	{
		// The interface table also carries a generalUI row for this boss with a different translation
		// (壳祸狮鹫); the CA page title must follow the curated NAME spelling used by the task rows.
		ui("Shellbane Gryphon", "壳祸狮鹫");
		name("Shellbane gryphon", "破壳狮鹫");
		assertEquals("战斗成就 - 破壳狮鹫", render("Combat Achievements - Shellbane Gryphon"));
		assertTrue("a table hit must not reach the AI", aiRequests.isEmpty());
		assertTrue("a table hit must not be collected", collected.keys.isEmpty());
	}

	/** The table stores "Shellbane gryphon"; the page capitalises the species name differently. */
	@Test
	public void bossNameLookupIsCaseInsensitive() throws Exception
	{
		name("Shellbane gryphon", "破壳狮鹫");
		assertEquals("战斗成就 - 破壳狮鹫", render("Combat Achievements - shellbane GRYPHON"));
	}

	@Test
	public void gameArticleIsRetriedWithoutThe() throws Exception
	{
		name("Maggot King", "蛆王");
		assertEquals("战斗成就 - 蛆王", render("Combat Achievements - The Maggot King"));
		assertEquals("战斗成就 - 蛆王", render("Combat Achievements - the maggot king"));
	}

	@Test
	public void articleIsNotStrippedWhenTheFullNameHits() throws Exception
	{
		name("The Fractured Archive", "破碎档案馆");
		assertEquals("战斗成就 - 破碎档案馆", render("Combat Achievements - The Fractured Archive"));
	}

	@Test
	public void unknownBossKeepsTheAiFallback() throws Exception
	{
		name("Maggot King", "蛆王");
		assertNull(render("Combat Achievements - Zzz Test Boss", true));
		assertEquals(java.util.Arrays.asList("Combat Achievements - Zzz Test Boss"), aiRequests);
	}

	@Test
	public void unknownBossStillCollectsForTheMissingFile() throws Exception
	{
		assertNull(render("Combat Achievements - Zzz Test Boss"));
		assertTrue(collected.keys.toString(), collected.keys.contains("Combat Achievements - Zzz Test Boss"));
	}

	/**
	 * A boss only present in the interface table (no NAME row) still composes, via the generic
	 * table-then-AI fallback that follows the NAME lookup.
	 */
	@Test
	public void interfaceOnlyBossFallsBackToTheGenericTableOrder() throws Exception
	{
		ui("Brutus", "布鲁图斯");
		assertEquals("战斗成就 - 布鲁图斯", render("Combat Achievements - Brutus"));
		assertTrue("the generic table hit must not reach the AI", aiRequests.isEmpty());
	}

	/** The 70 hand-translated rows are looked up before the composition and must keep winning. */
	@Test
	public void existingDataRowWinsOverComposition() throws Exception
	{
		ui("Combat Achievements - Abyssal Sire", "战斗成就 - 深渊领主");
		name("Abyssal Sire", "深渊魔主");
		assertEquals("战斗成就 - 深渊领主", render("Combat Achievements - Abyssal Sire"));
	}

	/** A coloured boss name (colour placeholders) composes the same title, rendered single-colour. */
	@Test
	public void colouredBossNameStillComposes() throws Exception
	{
		name("Yama", "阎魔");
		assertEquals("战斗成就 - 阎魔", render("Combat Achievements - <col=ff0000>Yama</col>"));
	}

	@Test
	public void emptyBossNameIsNotACombatAchievementTitle() throws Exception
	{
		assertNull(render("Combat Achievements - "));
		assertNull(render("Combat Achievements - ."));
	}

	@Test
	public void unrelatedComposedLinesAreUntouched() throws Exception
	{
		// the neighbouring synthetic branches keep working (control for the new branch's placement)
		name("Yama", "阎魔");
		assertEquals("怪物：阎魔", render("Monster: Yama"));
		// a lookalike prefix is not a CA title
		assertNull(render("Combat Profile - Yama"));
		assertNull(render("Achievements - Yama"));
	}

	@Test
	public void titleWithoutMiddleSpaceIsNotComposed() throws Exception
	{
		name("Yama", "阎魔");
		assertNull(render("Combat Achievements: Yama"));
	}

	/** The page writes the prefix as a fixed cs2 string constant; only the boss name casing varies. */
	@Test
	public void lowercasePrefixIsNotTheGameTitle() throws Exception
	{
		name("Yama", "阎魔");
		assertNull(render("combat achievements - Yama"));
	}

	private String render(String text) throws Exception
	{
		return render(text, false);
	}

	private String render(String text, boolean aiFallback) throws Exception
	{
		collected.keys.clear();
		Translator.Rendered r = translator.renderUi(text, 0xFFFFFF, 0, 14, aiFallback);
		return r == null ? null : r.text;
	}

	private void name(String en, String zh) throws Exception
	{
		table(TranslationStore.Category.NAME, en, zh);
	}

	private void ui(String en, String zh) throws Exception
	{
		table(TranslationStore.Category.INTERFACE, en, zh);
	}

	@SuppressWarnings("unchecked")
	private void table(TranslationStore.Category category, String key, String value) throws Exception
	{
		Field storeField = Translator.class.getDeclaredField("store");
		storeField.setAccessible(true);
		TranslationStore store = (TranslationStore) storeField.get(translator);
		Field mapsField = TranslationStore.class.getDeclaredField("maps");
		mapsField.setAccessible(true);
		Map<TranslationStore.Category, Map<String, String>> maps =
				(Map<TranslationStore.Category, Map<String, String>>) mapsField.get(store);
		maps.get(category).put(TranslationStore.normalize(key), value);
		// the case-insensitive index is built when the real TSVs load; tests seed it alongside the map
		Field lowerField = TranslationStore.class.getDeclaredField("lowerMaps");
		lowerField.setAccessible(true);
		Map<TranslationStore.Category, Map<String, String>> lowerMaps =
				(Map<TranslationStore.Category, Map<String, String>>) lowerField.get(store);
		Map<String, String> lower = lowerMaps.get(category);
		if (lower != null)
		{
			lower.put(TranslationStore.normalize(key).toLowerCase(), value);
		}
	}

	private static final class RecordingCollector extends MissingCollector
	{
		final List<String> keys = new ArrayList<>();

		@Override
		public void record(String english, String category, String subCategory, String source)
		{
			keys.add(english);
		}
	}

	private static void set(Object target, String name, Object value) throws Exception
	{
		Class<?> type = target.getClass();
		Field field;
		while (true)
		{
			try
			{
				field = type.getDeclaredField(name);
				break;
			}
			catch (NoSuchFieldException e)
			{
				type = type.getSuperclass();
				if (type == null) throw e;
			}
		}
		field.setAccessible(true);
		field.set(target, value);
	}

	private static Client client()
	{
		return (Client) Proxy.newProxyInstance(TranslatorCaTitleTest.class.getClassLoader(),
				new Class<?>[] {Client.class}, (proxy, method, args) ->
				{
					switch (method.getName())
					{
						case "getTickCount": return 1;
						case "getPlayers": return Collections.emptyList();
						case "getLocalPlayer": return null;
						case "getGameState": return null;
						default: throw new AssertionError("Unexpected client call: " + method.getName());
					}
				});
	}
}
