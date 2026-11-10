package com.osrscn.translate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.Player;
import org.junit.Test;

/**
 * Guards the single AI dispatch funnel ({@code Translator.aiTranslate}): text with no Latin letter left
 * once every {@code <...>} tag / placeholder is removed must never reach the backend (and so never
 * poison the AI disk cache), while real prose - including prose that only survives around a protected
 * player name - must still be dispatched. The two guards tested here are the entry check on the query
 * and the re-check on the player-name-protected text, which is where a line consisting only of a
 * player name collapses to {@code <osrscn-name-0>}.
 */
public class TranslatorAiGuardTest
{
	/** Spy for the Translator -&gt; AiTranslator boundary: records dispatch attempts, never uses the net. */
	private static final class RecordingAi extends AiTranslator
	{
		final List<String> translated = new ArrayList<>();
		final List<String> cachedPeeked = new ArrayList<>();

		@Override
		public String translate(String english, boolean persist)
		{
			translated.add(english);
			return null;
		}

		@Override
		public synchronized String cached(String english)
		{
			cachedPeeked.add(english);
			return null;
		}
	}

	private static final class Fixture
	{
		final RecordingAi ai = new RecordingAi();
		final Translator translator = new Translator();

		Fixture(String... nearbyNames) throws Exception
		{
			inject(translator, "ai", ai);
			inject(translator, "store", new TranslationStore()); // empty maps, never loads the real tables
			inject(translator, "client", fakeClient(nearbyNames));
		}
	}

	private static void inject(Object object, String name, Object value) throws Exception
	{
		Field field = object.getClass().getDeclaredField(name);
		field.setAccessible(true);
		field.set(object, value);
	}

	/**
	 * Socket-free {@link Client}: fixed tick, the given nearby player names, no local player. Enough for
	 * the name-protection path ({@code nearbyNamePattern} / {@code protectDynamicPlayerNames}); a real
	 * client needs the game.
	 */
	private static Client fakeClient(String... nearbyNames) throws Exception
	{
		List<Player> players = new ArrayList<>();
		for (String name : nearbyNames)
		{
			final String playerName = name;
			players.add((Player) Proxy.newProxyInstance(TranslatorAiGuardTest.class.getClassLoader(),
					new Class<?>[] {Player.class}, (proxy, method, args) ->
					{
						if ("getName".equals(method.getName()))
						{
							return playerName;
						}
						return zero(method.getReturnType());
					}));
		}
		List<Player> nearby = Collections.unmodifiableList(players);
		return (Client) Proxy.newProxyInstance(TranslatorAiGuardTest.class.getClassLoader(),
				new Class<?>[] {Client.class}, (proxy, method, args) ->
				{
					switch (method.getName())
					{
						case "getTickCount":
							return 7;
						case "getPlayers":
							return nearby;
						case "getLocalPlayer":
							return null;
						default:
							return zero(method.getReturnType());
					}
				});
	}

	/** Default value for the interface methods the test does not care about (primitives must not be null). */
	private static Object zero(Class<?> type)
	{
		if (type == boolean.class)
		{
			return false;
		}
		if (type == int.class)
		{
			return 0;
		}
		if (type == long.class)
		{
			return 0L;
		}
		if (type == short.class)
		{
			return (short) 0;
		}
		if (type == byte.class)
		{
			return (byte) 0;
		}
		if (type == char.class)
		{
			return (char) 0;
		}
		if (type == float.class)
		{
			return 0f;
		}
		if (type == double.class)
		{
			return 0d;
		}
		return null;
	}

	@Test
	public void tagOnlyNumberIsNeverDispatched() throws Exception
	{
		Fixture f = new Fixture();
		assertNull(f.translator.plain("<colNum0>429</col>"));
		assertEquals("a tag-only number is not language", 0, f.ai.translated.size());
		assertTrue("the guard must run before any cache lookup", f.ai.cachedPeeked.isEmpty());

		// The player-less path (side-panel box) must be guarded too: it skips name protection entirely.
		assertNull(f.translator.plainNoPlayer("<colNum0>4/187</col>"));
		assertEquals(0, f.ai.translated.size());
	}

	/**
	 * The actual regression path: {@code renderUi} hands each table-miss line to {@code aiLine}, whose
	 * WORDY filter sees the placeholder letters ("colNum") and used to dispatch a pure number.
	 */
	@Test
	public void tagOnlyNumberOnTheLinePathIsNeverDispatched() throws Exception
	{
		Fixture f = new Fixture();
		assertNull(f.translator.renderUi("<colNum0>429</col>", 0xFFFFFF, 200, 14, true));
		assertEquals(0, f.ai.translated.size());
	}

	/** A line that is nothing but a nearby player's name becomes a lone {@code <osrscn-name-0>}. */
	@Test
	public void playerNameOnlyLineIsNeverDispatched() throws Exception
	{
		Fixture f = new Fixture("Zezima");
		assertNull(f.translator.plain("Zezima"));
		assertEquals("a name-only line has nothing to translate", 0, f.ai.translated.size());
	}

	@Test
	public void realSentenceIsStillDispatched() throws Exception
	{
		Fixture f = new Fixture();
		assertNull(f.translator.plain("The crystal gate awaits."));
		assertEquals(Arrays.asList("The crystal gate awaits."), f.ai.translated);
	}

	@Test
	public void sentenceAroundProtectedNameIsStillDispatched() throws Exception
	{
		Fixture f = new Fixture("Zezima");
		assertNull(f.translator.plain("Zezima was here."));
		assertEquals(Arrays.asList("<osrscn-name-0> was here."), f.ai.translated);

		// An already-placeholdered sentence has Latin words left, so it must still go out.
		Fixture g = new Fixture();
		assertNull(g.translator.plain("Name: <osrscn-name-0>"));
		assertEquals(Arrays.asList("Name: <osrscn-name-0>"), g.ai.translated);
	}

	/** The shared predicate unit-tested directly, so the rule itself is pinned down. */
	@Test
	public void latinLetterTestIgnoresTagsAndPlaceholders()
	{
		assertFalse(Translator.hasTranslatableText("<colNum0>429</col>"));
		assertFalse(Translator.hasTranslatableText("<colNum0>4/187</col>"));
		assertFalse(Translator.hasTranslatableText("<osrscn-name-0>"));
		assertFalse(Translator.hasTranslatableText("4/187"));
		assertFalse(Translator.hasTranslatableText(null));
		assertTrue(Translator.hasTranslatableText("Name: <osrscn-name-0>"));
		assertTrue(Translator.hasTranslatableText("<colNum0>Scarlet</col>"));
		assertTrue(Translator.hasTranslatableText("OK"));
	}
}
