package com.osrscn.translate;

import static org.junit.Assert.assertEquals;
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
 * A tooltip the client wraps with {@code <br>} is looked up line by line, so when only one line
 * hits the player sees half Chinese, half English. The lines themselves are half-sentences; only the
 * whole text is a usable table key, so a miss must collect the whole text with its {@code <br>} intact.
 */
public class TranslatorWrappedCollectTest
{
	private static final String LINE1 = "Complete a lap of the Draynor Village";
	private static final String LINE2 = "rooftop course.";
	private static final String WRAPPED = LINE1 + "<br>" + LINE2;

	private static final class RecordingCollector extends MissingCollector
	{
		final List<String> keys = new ArrayList<>();

		@Override
		public void record(String english, String category, String subCategory, String source)
		{
			keys.add(english);
		}
	}

	private final RecordingCollector collected = new RecordingCollector();
	private final Translator translator = new Translator();

	public TranslatorWrappedCollectTest() throws Exception
	{
		set(translator, "client", client());
		set(translator, "store", new TranslationStore());
		set(translator, "missing", collected);
		set(translator, "ai", new AiTranslator()
		{
			@Override public synchronized String cached(String text) { return null; }
			@Override public String translate(String text, boolean persist) { return null; }
		});
		set(translator, "glyph", new com.osrscn.glyph.GlyphService()
		{
			@Override public String toImgTags(String text, int color, int maxChars, int size) { return text; }
		});
	}

	@Test
	public void halfTranslatedWrappedTipCollectsTheWholeSentence() throws Exception
	{
		table(LINE1, "完成一圈德拉诺村");
		translator.renderUi(WRAPPED, 0xFFFFFF, 200, 14, false);
		assertTrue("collected " + collected.keys, collected.keys.contains(WRAPPED));
	}

	@Test
	public void untranslatedWrappedTipCollectsTheWholeSentence()
	{
		translator.renderUi(WRAPPED, 0xFFFFFF, 200, 14, false);
		assertTrue("collected " + collected.keys, collected.keys.contains(WRAPPED));
	}

	@Test
	public void wrapBeforeAProperNounCollectsTheWholeSentence()
	{
		String wrapped = "Pickpocket a Master Farmer in the<br>Draynor Village market.";
		translator.renderUi(wrapped, 0xFFFFFF, 200, 14, false);
		assertTrue("collected " + collected.keys, collected.keys.contains(wrapped));
	}

	/** Two complete stacked lines (item name + description) are independent entries. */
	@Test
	public void independentStackedLinesAreNotJoined()
	{
		translator.renderUi("Skillcape<br>See the Shilo Village Slayer Master", 0xFFFFFF, 200, 14, false);
		assertEquals(java.util.Arrays.asList("Skillcape", "See the Shilo Village Slayer Master"), collected.keys);
	}

	/** Stacked short labels (chat tab "Report<br>Abuse") are independent; joining them would make a key that later overrides both. */
	@Test
	public void stackedShortLabelsAreNotJoined()
	{
		translator.renderUi("Report<br>Abuse", 0xFFFFFF, 200, 14, false);
		assertEquals(java.util.Arrays.asList("Report", "Abuse"), collected.keys);
	}

	@Test
	public void fullyTranslatedWrappedTipCollectsNothing() throws Exception
	{
		table(LINE1, "完成一圈德拉诺村");
		table(LINE2, "屋顶跑酷路线。");
		translator.renderUi(WRAPPED, 0xFFFFFF, 200, 14, false);
		assertEquals(Collections.emptyList(), collected.keys);
	}

	@Test
	public void wholeSentenceHitCollectsNothing() throws Exception
	{
		table(LINE1 + " " + LINE2, "完成一圈德拉诺村屋顶跑酷路线。");
		translator.renderUi(WRAPPED, 0xFFFFFF, 200, 14, false);
		assertEquals(Collections.emptyList(), collected.keys);
	}

	@SuppressWarnings("unchecked")
	private void table(String key, String value) throws Exception
	{
		Field storeField = Translator.class.getDeclaredField("store");
		storeField.setAccessible(true);
		Field mapsField = TranslationStore.class.getDeclaredField("maps");
		mapsField.setAccessible(true);
		Map<TranslationStore.Category, Map<String, String>> maps =
				(Map<TranslationStore.Category, Map<String, String>>) mapsField.get(storeField.get(translator));
		maps.get(TranslationStore.Category.INTERFACE).put(TranslationStore.normalize(key), value);
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
		return (Client) Proxy.newProxyInstance(TranslatorWrappedCollectTest.class.getClassLoader(),
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
