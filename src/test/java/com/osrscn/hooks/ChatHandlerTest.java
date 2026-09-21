package com.osrscn.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.awt.Rectangle;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.concurrent.atomic.AtomicReference;
import com.osrscn.OsrscnConfig;
import com.osrscn.OsrscnPlugin;
import com.osrscn.PlayerChatMode;
import com.osrscn.ToggleService;
import com.osrscn.glyph.GlyphService;
import com.osrscn.translate.AiTranslator;
import com.osrscn.translate.MissingCollector;
import com.osrscn.translate.TranslationStore;
import com.osrscn.translate.Translator;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.IterableHashTable;
import net.runelite.api.MessageNode;
import net.runelite.api.Point;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.GameState;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.widgets.Widget;
import org.junit.Test;

public class ChatHandlerTest
{
	private static Object call(String name, Class<?>[] types, Object... args)
	{
		try
		{
			Method method = ChatHandler.class.getDeclaredMethod(name, types);
			method.setAccessible(true);
			return method.invoke(null, args);
		}
		catch (ReflectiveOperationException ex)
		{
			throw new AssertionError("native chat hover identity is not implemented: " + name, ex);
		}
	}

	@Test
	public void callbackUidDistinguishesDuplicateTextMessages()
	{
		int[] first = { 7, 0, 101 };
		int[] second = { 7, 0, 102 };
		assertEquals(101, call("messageUid", new Class<?>[] { int[].class, int.class }, first, 3));
		assertEquals(102, call("messageUid", new Class<?>[] { int[].class, int.class }, second, 3));
	}

	@Test
	public void callbackOrderMapsToDistinctNativeLineIds()
	{
		Class<?>[] types = { int.class };
		assertEquals(InterfaceID.Chatbox.LINE0, call("chatLineId", types, 0));
		assertEquals(InterfaceID.Chatbox.LINE1, call("chatLineId", types, 1));
		assertEquals(InterfaceID.Chatbox.LINE499, call("chatLineId", types, 499));
		assertEquals(-1, call("chatLineId", types, 500));
	}

	@Test
	public void capturesTheCallbackOwningRebuildProcNotTheOuterWrapper()
	{
		assertTrue(ChatHandler.isChatBuildScript(84));
		assertFalse(ChatHandler.isChatBuildScript(net.runelite.api.ScriptID.BUILD_CHATBOX));
	}

	@Test
	public void onlyChatboxLineIdsAreAccepted()
	{
		Class<?>[] types = { int.class };
		assertTrue((boolean) call("isChatLineId", types, InterfaceID.Chatbox.LINE0));
		assertTrue((boolean) call("isChatLineId", types, InterfaceID.Chatbox.LINE499));
		assertFalse((boolean) call("isChatLineId", types, InterfaceID.Chatbox.SCROLLAREA));
		assertFalse((boolean) call("isChatLineId", types, InterfaceID.Chatbox.CHATSCROLLBAR));
	}

	@Test
	public void onlyMappedWidgetBoundsSupplyNativeColor()
	{
		Class<?>[] types = { Rectangle.class, Point.class, int.class, int.class, int.class, boolean.class };
		Rectangle bounds = new Rectangle(50, 100, 300, 15);
		assertEquals(0x707070,
				call("nativeLineColor", types, bounds, new Point(80, 108), 0x707070, 0xffffff, 0xffffff, true));
		assertEquals(0xffffff,
				call("nativeLineColor", types, bounds, new Point(20, 108), 0x707070, 0xffffff, 0x707070, true));
	}

	@Test
	public void ordinaryGameLineKeepsItsGreenBaseColorOnMouseover()
	{
		Class<?>[] types = { Rectangle.class, Point.class, int.class, int.class, int.class, boolean.class };
		Rectangle bounds = new Rectangle(50, 100, 300, 15);
		assertEquals(0x036602,
				call("nativeLineColor", types, bounds, new Point(80, 108), 0x000000,
						0x036602, 0x036602, false));
	}

	@Test
	public void clanPlayerChatUsesAiPathAndItsConfiguredColor() throws Exception
	{
		PlayerChatFixture f = new PlayerChatFixture();
		f.varps.put(VarPlayerID.OPTION_CHAT_COLOUR_CLANCHAT_OPAQUE, 0x7f0001);
		f.fire(ChatMessageType.CLAN_CHAT);

		assertEquals(img(0x7f0000), f.value.get());
		assertEquals("Alice", f.name.get());
		assertEquals(1, f.translator.calls.get());
		assertTrue(f.translator.lastAiFallback);
		assertFalse(f.translator.lastPersist);
	}

	@Test
	public void publicPlayerChatUsesTheConfiguredNativeColor() throws Exception
	{
		PlayerChatFixture f = new PlayerChatFixture();
		f.varps.put(VarPlayerID.OPTION_CHAT_COLOUR_PUBLIC_OPAQUE, 0x24579c);
		f.fire(ChatMessageType.PUBLICCHAT);

		assertEquals(img(0x24579b), f.value.get());
	}

	@Test
	public void didYouKnowUsesItsOwnCurrentColorSetting() throws Exception
	{
		PlayerChatFixture f = new PlayerChatFixture();
		f.varps.put(VarPlayerID.OPTION_CHAT_COLOUR_DIDYOUKNOW_OPAQUE, 0x036603);
		f.varps.put(VarPlayerID.OPTION_CHAT_COLOUR_BROADCAST_OPAQUE, 0x123457);
		f.fire(ChatMessageType.DIDYOUKNOW);

		assertEquals(img(0x036602), f.value.get());
	}

	@Test
	public void transparentDefaultsMatchCurrentClientStructures() throws Exception
	{
		PlayerChatFixture publicChat = new PlayerChatFixture();
		publicChat.transparent = true;
		publicChat.fire(ChatMessageType.PUBLICCHAT);
		assertEquals(img(0x9090ff), publicChat.value.get());

		PlayerChatFixture tip = new PlayerChatFixture();
		tip.transparent = true;
		tip.fire(ChatMessageType.DIDYOUKNOW);
		assertEquals(img(0xffff00), tip.value.get());
	}

	@Test
	public void colorStateChangesRerenderOnceAndThenStops()
	{
		Class<?>[] types = { int.class, int.class, boolean.class };
		assertTrue((boolean) call("needsNativeRerender", types, 0xffffff, 0x707070, true));
		assertFalse((boolean) call("needsNativeRerender", types, 0x707070, 0x707070, true));
		assertTrue((boolean) call("needsNativeRerender", types, 0x707070, 0x707070, false));
	}

	@Test
	public void duplicateTextRowsDoNotCrosstalkAndStableColorDoesNotRenderAgain() throws Exception
	{
		ChatFixture f = new ChatFixture();
		f.trackBoth();
		assertEquals(2, f.translator.calls.get());

		f.handler.beginChatBuild();
		f.handler.captureChatLine(new int[] { 0, 101 }, 2);
		f.handler.captureChatLine(new int[] { 0, 102 }, 2);
		f.handler.finishChatBuild();

		f.mouse.set(new Point(80, 108));
		f.colors[0].set(0x7f7f7f);
		f.handler.refreshNativeColors();
		assertEquals(img(0x7f7f7f), f.values[0].get());
		assertEquals(img(0x000000), f.values[1].get());
		assertEquals(3, f.translator.calls.get());

		f.handler.refreshNativeColors();
		assertEquals(3, f.translator.calls.get());

		f.mouse.set(new Point(20, 20));
		f.colors[0].set(0x000000);
		f.handler.refreshNativeColors();
		assertEquals(img(0x000000), f.values[0].get());
		assertEquals(img(0x000000), f.values[1].get());
		assertEquals(4, f.translator.calls.get());
	}

	@Test
	public void chatRebuildDoesNotDropHoverColorWhileMouseRemainsInside() throws Exception
	{
		ChatFixture f = new ChatFixture();
		f.trackBoth();
		f.handler.beginChatBuild();
		f.handler.captureChatLine(new int[] { 101 }, 1);
		f.handler.captureChatLine(new int[] { 102 }, 1);
		f.handler.finishChatBuild();

		f.mouse.set(new Point(80, 108));
		f.colors[0].set(0x7f7f7f);
		f.handler.refreshNativeColors();
		assertEquals(img(0x7f7f7f), f.values[0].get());
		assertEquals(3, f.translator.calls.get());

		// refreshChat rebuilds the native text child at its base colour without moving the mouse.
		f.colors[0].set(0x000000);
		f.handler.refreshNativeColors();
		assertEquals(img(0x7f7f7f), f.values[0].get());
		assertEquals(3, f.translator.calls.get());

		f.mouse.set(new Point(20, 20));
		f.handler.refreshNativeColors();
		assertEquals(img(0x000000), f.values[0].get());
		assertEquals(4, f.translator.calls.get());
	}

	@Test
	public void unavailableHoverGlyphRetriesWithoutRebuildingChat() throws Exception
	{
		ChatFixture f = new ChatFixture();
		f.trackBoth();
		f.handler.beginChatBuild();
		f.handler.captureChatLine(new int[] { 101 }, 1);
		f.handler.captureChatLine(new int[] { 102 }, 1);
		f.handler.finishChatBuild();
		f.mouse.set(new Point(80, 108));
		f.colors[0].set(0x7f7f7f);
		f.translator.pendingColor.set(0x7f7f7f);
		f.translator.pendingCalls.set(1);

		f.handler.refreshNativeColors();
		assertEquals(img(0x000000), f.values[0].get());
		f.handler.refreshNativeColors();
		assertEquals(img(0x7f7f7f), f.values[0].get());
	}

	@Test
	public void nativeFilterBannerOffsetsMessageRowsWithoutUsingText() throws Exception
	{
		ChatFixture f = new ChatFixture(true);
		f.trackBoth();
		f.handler.beginChatBuild();
		f.handler.captureChatLine(new int[] { 101 }, 1);
		f.handler.captureChatLine(new int[] { 102 }, 1);
		f.handler.finishChatBuild();
		f.mouse.set(new Point(80, 108));
		f.colors[0].set(0x7f7f7f);

		f.handler.refreshNativeColors();
		assertEquals(img(0x7f7f7f), f.values[0].get());
		assertEquals(img(0x000000), f.values[1].get());
	}

	@Test
	public void partialTranslationUpgradesOnTickAndThenStops() throws Exception
	{
		PartialFixture f = new PartialFixture();
		f.track(0);
		assertFalse(f.translator.last.complete);
		assertEquals(f.image(0, false), f.chat.values[0].get());
		f.chat.handler.tick(); // An unchanged partial image must still be retried.
		assertEquals(2, f.translator.calls);
		assertEquals(1, f.chat.refreshes.get());
		f.ai.ready = true;
		f.chat.handler.tick();
		assertTrue(f.translator.last.complete);
		assertEquals(f.image(0, true), f.chat.values[0].get());
		assertEquals(2, f.chat.refreshes.get());
		for (int i = 0; i < 20; i++) f.chat.handler.tick();
		assertEquals(3, f.translator.calls);
		assertEquals(2, f.chat.refreshes.get());
	}

	@Test
	public void nullGlyphBeforeAndDuringPartialTranslationCanRetry() throws Exception
	{
		PartialFixture f = new PartialFixture();
		f.glyph.pendingCalls = 1;
		f.track(0);
		assertNull(f.translator.last);
		assertEquals(PartialFixture.ENGLISH, f.chat.values[0].get());
		f.chat.handler.tick();
		assertFalse(f.translator.last.complete);
		assertEquals(f.image(0, false), f.chat.values[0].get());
		f.ai.ready = true;
		f.glyph.pendingCalls = 1;
		f.chat.handler.tick();
		assertNull(f.translator.last);
		assertEquals(f.image(0, false), f.chat.values[0].get());
		f.chat.handler.tick();
		assertTrue(f.translator.last.complete);
		assertEquals(f.image(0, true), f.chat.values[0].get());
		for (int i = 0; i < 20; i++) f.chat.handler.tick();
		assertEquals(4, f.translator.calls);
	}

	@Test
	public void partialHoverCompletesWithoutCrossColorForDuplicateMessages() throws Exception
	{
		PartialFixture f = new PartialFixture();
		f.track(0);
		f.track(1);
		f.bindRows();
		f.hover();
		assertFalse(f.translator.last.complete);
		assertEquals(f.image(0x7f7f7f, false), f.chat.values[0].get());
		assertEquals(f.image(0, false), f.chat.values[1].get());
		f.ai.ready = true;
		f.chat.handler.tick();
		assertEquals(f.image(0x7f7f7f, true), f.chat.values[0].get());
		assertEquals(f.image(0, true), f.chat.values[1].get());
		assertEquals(5, f.translator.calls);
		f.chat.mouse.set(new Point(20, 20));
		f.chat.colors[0].set(0);
		f.chat.handler.refreshNativeColors();
		assertEquals(f.image(0, true), f.chat.values[0].get());
		assertEquals(f.image(0, true), f.chat.values[1].get());
		for (int i = 0; i < 20; i++)
		{
			f.chat.handler.refreshNativeColors();
			f.chat.handler.tick();
		}
		assertEquals(6, f.translator.calls);
	}

	@Test
	public void hoverRenderReplacesPreviouslyCompleteState() throws Exception
	{
		PartialFixture f = new PartialFixture();
		f.ai.ready = true;
		f.track(0);
		assertTrue(f.translator.last.complete);
		f.bindRows();
		// A later render must carry its own completeness, even after a prior cache hit.
		f.ai.ready = false;
		f.hover();
		assertFalse(f.translator.last.complete);
		f.ai.ready = true;
		f.chat.handler.tick();
		assertEquals(f.image(0x7f7f7f, true), f.chat.values[0].get());
		for (int i = 0; i < 20; i++) f.chat.handler.tick();
		assertEquals(3, f.translator.calls);
	}

	@Test
	public void partialTranslationRestoresEnglishAndCanResume() throws Exception
	{
		PartialFixture f = new PartialFixture();
		f.track(0);
		f.chat.handler.goEnglish();
		assertEquals(PartialFixture.ENGLISH, f.chat.values[0].get());
		f.chat.handler.tick();
		assertEquals(1, f.translator.calls);
		f.chat.handler.goChinese();
		assertFalse(f.translator.last.complete);
		f.ai.ready = true;
		f.chat.handler.tick();
		assertEquals(f.image(0, true), f.chat.values[0].get());
		for (int i = 0; i < 20; i++) f.chat.handler.tick();
		assertEquals(3, f.translator.calls);
	}

	@Test
	public void expiredMissDoesNotReviveOnNativeFrames() throws Exception
	{
		PartialFixture f = new PartialFixture();
		f.track(0, PartialFixture.SECOND_EN);
		f.bindRows();
		f.clock.set(15_001);
		f.chat.handler.tick();
		assertTrue(f.pending().isEmpty());
		int calls = f.translator.calls;
		int requests = f.ai.calls;
		for (int i = 0; i < 20; i++)
		{
			f.chat.mouse.set(new Point(i % 2 == 0 ? 80 : 20, i % 2 == 0 ? 108 : 20));
			f.chat.colors[0].set(0x7f7f7f);
			f.chat.handler.refreshNativeColors();
			f.chat.handler.tick();
		}
		assertTrue("Native frames must not recreate an expired retry", f.pending().isEmpty());
		assertEquals(requests, f.ai.calls);
		assertEquals(calls, f.translator.calls);
	}

	@Test
	public void expiredMissCannotRequestBeforeTickRemovesIt() throws Exception
	{
		PartialFixture f = new PartialFixture();
		f.track(0, PartialFixture.SECOND_EN);
		f.bindRows();
		f.clock.set(15_001);
		int requests = f.ai.calls;
		f.hover(); // BeforeRender may run before the next tick.
		assertEquals(requests, f.ai.calls);
		f.chat.handler.tick();
		assertTrue(f.pending().isEmpty());
	}

	@Test
	public void partialHoverDoesNotExtendTranslationDeadline() throws Exception
	{
		PartialFixture f = new PartialFixture();
		f.track(0);
		f.bindRows();
		f.clock.set(14_999);
		f.hover();
		assertEquals(15_000, f.deadline(0));
		f.clock.set(15_001);
		f.chat.handler.tick();
		int requests = f.ai.calls;
		int collected = f.collected;
		for (int i = 0; i < 5; i++)
		{
			f.chat.mouse.set(new Point(20, 20));
			f.chat.handler.refreshNativeColors();
			assertEquals(f.image(0, false), f.chat.values[0].get());
			f.hover();
			assertEquals(f.image(0x7f7f7f, false), f.chat.values[0].get());
		}
		assertTrue(f.pending().isEmpty());
		assertEquals(requests, f.ai.calls);
		assertEquals(collected, f.collected);
	}

	@Test
	public void cachedAiHoverGlyphCanFinishAcrossDeadline() throws Exception
	{
		PartialFixture f = new PartialFixture();
		f.ai.ready = true;
		f.track(0);
		f.bindRows();
		f.clock.set(15_000); // Preserve the existing inclusive last retry instant.
		f.glyph.pendingCalls = 2;
		f.hover();
		assertEquals(15_000, f.deadline(0));
		f.clock.set(15_001);
		f.chat.handler.tick();
		int requests = f.ai.calls;
		int collected = f.collected;
		f.chat.handler.refreshNativeColors();
		assertEquals(f.image(0, true), f.chat.values[0].get());
		f.chat.handler.refreshNativeColors();
		assertEquals(f.image(0x7f7f7f, true), f.chat.values[0].get());
		f.chat.mouse.set(new Point(20, 20));
		f.chat.handler.refreshNativeColors();
		assertEquals(f.image(0, true), f.chat.values[0].get());
		f.hover();
		assertEquals(f.image(0x7f7f7f, true), f.chat.values[0].get());
		assertTrue(f.pending().isEmpty());
		assertEquals(requests, f.ai.calls);
		assertEquals(collected, f.collected);
	}

	@Test
	public void firstGlyphCanCompleteAtOriginalDeadline() throws Exception
	{
		PartialFixture f = new PartialFixture();
		f.ai.ready = true;
		f.glyph.pendingCalls = 2;
		f.track(0);
		assertNull(f.translator.last);
		f.clock.set(14_999);
		f.chat.handler.tick();
		assertNull(f.translator.last);
		assertEquals(15_000, f.deadline(0));
		f.clock.set(15_000);
		f.chat.handler.tick();
		assertTrue(f.translator.last.complete);
		assertEquals(f.image(0, true), f.chat.values[0].get());
	}

	@Test
	public void newMessageAndLanguageToggleHaveFreshRetryWindows() throws Exception
	{
		PartialFixture f = new PartialFixture();
		f.track(0, PartialFixture.SECOND_EN);
		f.clock.set(15_001);
		f.chat.handler.tick();
		assertTrue(f.pending().isEmpty());
		f.track(1, PartialFixture.SECOND_EN);
		assertEquals(30_001, f.deadline(1));
		assertEquals(1, f.pending().size());
		f.chat.handler.goEnglish();
		f.clock.set(20_000);
		int requests = f.ai.calls;
		f.chat.handler.goChinese();
		assertEquals(35_000, f.deadline(0));
		assertEquals(35_000, f.deadline(1));
		assertEquals(requests + 2, f.ai.calls);
	}

	@Test
	public void firstGlyphBeyondDeadlineRequiresExistingExplicitRetry() throws Exception
	{
		PartialFixture f = new PartialFixture();
		f.ai.ready = true;
		f.glyph.pendingCalls = 1;
		f.track(0);
		f.bindRows();
		f.clock.set(15_001);
		f.chat.handler.tick();
		int calls = f.translator.calls;
		for (int i = 0; i < 20; i++) f.chat.handler.refreshNativeColors();
		assertEquals(calls, f.translator.calls);
		assertTrue(f.pending().isEmpty());
		f.chat.handler.goEnglish();
		f.chat.handler.goChinese();
		assertEquals(f.image(0, true), f.chat.values[0].get());
		assertEquals(30_001, f.deadline(0));
	}

	@Test
	public void cachedChatPreservesProtectedNamesAndSkillLinkWithoutDispatch() throws Exception
	{
		PartialFixture f = new PartialFixture();
		// Treat a substring of the existing table fixture as a nearby player name.
		// This tests placeholder restoration, not a naturally occurring game message.
		net.runelite.api.Player player = (net.runelite.api.Player) Proxy.newProxyInstance(
				getClass().getClassLoader(), new Class<?>[] { net.runelite.api.Player.class },
				(p, method, args) -> method.getName().equals("getName") ? "Slayer Master" : defaultValue(method.getReturnType()));
		Client client = (Client) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { Client.class },
				(p, method, args) -> method.getName().equals("getLocalPlayer") ? player : defaultValue(method.getReturnType()));
		AiTranslator cacheOnly = new AiTranslator()
		{
			@Override
			public String cached(String english)
			{
				return english.equals(TranslationStore.normalize("See the Shilo Village <osrscn-name-0>"))
						? "前往希洛村拜访<osrscn-name-0>" : null;
			}

			@Override
			public String translate(String english, boolean persist)
			{
				throw new AssertionError("Cached repaint must not request or persist AI text");
			}
		};
		Field clientField = Translator.class.getDeclaredField("client");
		clientField.setAccessible(true);
		clientField.set(f.translator, client);
		Field aiField = Translator.class.getDeclaredField("ai");
		aiField.setAccessible(true);
		aiField.set(f.translator, cacheOnly);
		Translator.Rendered result = f.translator.renderChatCached("1|" + PartialFixture.SECOND_EN, 0x7f7f7f, 80, 14);
		assertTrue(result.complete);
		assertEquals("1|" + img(0x7f7f7f) + "前往希洛村拜访Slayer Master", result.text);
		assertEquals(0, f.collected);
	}

	@Test
	public void fullPendingQueueStillAllowsFirstGlyphWithinItsDeadline() throws Exception
	{
		PartialFixture f = new PartialFixture();
		f.ai.ready = true;
		f.fillPending();
		assertEquals(96, f.pending().size());
		f.glyph.pendingCalls = 1;
		f.track(0);
		f.bindRows();
		assertEquals(96, f.pending().size());
		f.clock.set(1);
		f.chat.handler.refreshNativeColors();
		assertEquals(f.image(0, true), f.chat.values[0].get());
		assertEquals(96, f.pending().size());
	}

	@Test
	public void queueAdmissionAfterCapacityFreesKeepsFirstAttemptDeadline() throws Exception
	{
		PartialFixture f = new PartialFixture();
		f.ai.ready = true;
		f.fillPending();
		f.clock.set(1_000);
		f.glyph.pendingCalls = 2;
		f.track(0);
		f.bindRows();
		f.clock.set(15_001);
		f.chat.handler.tick(); // Older entries expire while the latest line still has time.
		assertTrue(f.pending().isEmpty());
		f.chat.handler.refreshNativeColors();
		assertEquals(16_000, f.deadline(0));
		f.clock.set(16_000);
		f.chat.handler.tick();
		assertEquals(f.image(0, true), f.chat.values[0].get());
		f.clock.set(16_001);
		f.chat.handler.tick();
		assertTrue(f.pending().isEmpty());
	}


	@Test
	public void capacityBlockedFirstPartialResumesOnTickWithoutFrameTranslation() throws Exception
	{
		PartialFixture f = capacityPartial(false);
		int calls = f.translator.calls;
		for (int i = 0; i < 20; i++) f.chat.handler.refreshNativeColors();
		assertEquals(96, f.pending().size());
		assertEquals(calls, f.translator.calls);
		f.clock.set(15_001); f.chat.handler.tick();
		assertTrue(f.pending().isEmpty());
		f.chat.handler.refreshNativeColors();
		assertTrue(f.pending().containsKey(f.chat.nodes[0]));
		assertEquals(16_000, f.deadline(0));
		for (int i = 0; i < 20; i++) f.chat.handler.refreshNativeColors();
		assertEquals(calls, f.translator.calls);
		f.ai.ready = true; f.chat.handler.tick();
		assertEquals(f.image(0, true), f.chat.values[0].get());
		for (int i = 0; i < 20; i++) { f.chat.handler.refreshNativeColors(); f.chat.handler.tick(); }
		assertEquals(calls + 1, f.translator.calls);
	}

	@Test
	public void capacityBlockedNullToPartialAfterReleaseRetainsRetry() throws Exception
	{
		PartialFixture f = capacityPartial(true);
		assertNull(f.translator.last);
		f.clock.set(15_001); f.chat.handler.tick();
		f.chat.handler.refreshNativeColors();
		assertEquals(f.image(0, false), f.chat.values[0].get());
		assertTrue(f.pending().containsKey(f.chat.nodes[0]));
		assertEquals(16_000, f.deadline(0));
		f.ai.ready = true; f.chat.handler.tick();
		assertEquals(f.image(0, true), f.chat.values[0].get());
	}

	@Test
	public void capacityBlockedNullToPartialBeforeReleaseResumesLater() throws Exception
	{
		PartialFixture f = capacityPartial(true);
		f.chat.handler.refreshNativeColors();
		assertEquals(f.image(0, false), f.chat.values[0].get());
		assertFalse(f.pending().containsKey(f.chat.nodes[0]));
		f.clock.set(15_001); f.chat.handler.tick(); f.chat.handler.refreshNativeColors();
		assertTrue(f.pending().containsKey(f.chat.nodes[0]));
		f.ai.ready = true; f.chat.handler.tick();
		assertEquals(f.image(0, true), f.chat.values[0].get());
	}

	@Test
	public void capacityBlockedDuplicatesKeepSeparateOriginalDeadlines() throws Exception
	{
		PartialFixture f = capacityPartial(false);
		f.clock.set(2_000); firePartial(f, 1, ChatMessageType.LEVELUPMESSAGE); f.bindRows();
		f.clock.set(16_001); f.chat.handler.tick(); f.chat.handler.refreshNativeColors();
		assertFalse(f.pending().containsKey(f.chat.nodes[0]));
		assertTrue(f.pending().containsKey(f.chat.nodes[1]));
		assertEquals(17_000, f.deadline(1));
		f.ai.ready = true; f.chat.handler.tick();
		assertEquals(f.image(0, false), f.chat.values[0].get());
		assertEquals(f.image(0, true), f.chat.values[1].get());
	}

	@Test
	public void capacityBlockedPartialCanCompleteAtOriginalDeadline() throws Exception
	{
		PartialFixture f = capacityPartial(false);
		f.clock.set(16_000); f.chat.handler.tick(); f.chat.handler.refreshNativeColors();
		assertTrue(f.pending().containsKey(f.chat.nodes[0]));
		assertEquals(16_000, f.deadline(0));
		f.ai.ready = true; f.chat.handler.tick();
		assertEquals(f.image(0, true), f.chat.values[0].get());
		f.clock.set(16_001); f.chat.handler.tick();
		assertTrue(f.pending().isEmpty());
	}

	@Test
	public void capacityBlockedExpiredPartialNeverRequestsOrCollectsAgain() throws Exception
	{
		for (boolean firstNull : new boolean[] { false, true })
		{
			PartialFixture f = capacityPartial(firstNull);
			f.chat.handler.refreshNativeColors();
			assertEquals(f.image(0, false), f.chat.values[0].get());
			f.clock.set(16_001);
			int requests = f.ai.calls, collected = f.collected;
			f.chat.handler.refreshNativeColors(); // Before tick cleanup must also be safe.
			f.chat.handler.tick();
			for (int i = 0; i < 20; i++)
			{
				f.hover(); f.chat.mouse.set(new Point(20, 20));
				f.chat.handler.refreshNativeColors(); f.chat.handler.tick();
			}
			assertEquals(requests, f.ai.calls);
			assertEquals(collected, f.collected);
			assertTrue(f.pending().isEmpty());
		}
	}

	@Test
	public void capacityBlockedPartialsRespectSingleReleasedSlot() throws Exception
	{
		PartialFixture f = capacityPartial(false);
		firePartial(f, 1, ChatMessageType.LEVELUPMESSAGE); f.bindRows();
		MessageNode removed = (MessageNode) f.pending().keySet().iterator().next();
		f.chat.messages.remove((long) removed.getId()); f.chat.handler.tick();
		assertEquals(95, f.pending().size());
		f.chat.handler.refreshNativeColors();
		assertEquals(96, f.pending().size());
		assertTrue(f.pending().containsKey(f.chat.nodes[0]) ^ f.pending().containsKey(f.chat.nodes[1]));
		for (int i = 0; i < 20; i++) f.chat.handler.refreshNativeColors();
		assertEquals(96, f.pending().size());
		for (Object key : f.pending().keySet())
		{
			MessageNode node = (MessageNode) key;
			if (node != f.chat.nodes[0] && node != f.chat.nodes[1])
			{ f.chat.messages.remove((long) node.getId()); break; }
		}
		f.chat.handler.tick(); f.chat.handler.refreshNativeColors();
		assertEquals(96, f.pending().size());
		assertTrue(f.pending().containsKey(f.chat.nodes[0]));
		assertTrue(f.pending().containsKey(f.chat.nodes[1]));
		assertEquals(16_000, f.deadline(0)); assertEquals(16_000, f.deadline(1));
		f.ai.ready = true; f.chat.handler.tick();
		assertEquals(f.image(0, true), f.chat.values[0].get());
		assertEquals(f.image(0, true), f.chat.values[1].get());
	}

	@Test
	public void capacityBlockedPlayerPartialCannotResumeAfterOff() throws Exception
	{
		PartialFixture f = new PartialFixture(); f.fillPending(); f.clock.set(1_000);
		firePartial(f, 0, ChatMessageType.PUBLICCHAT); f.bindRows();
		f.chat.mode = PlayerChatMode.OFF; f.clock.set(15_001);
		int requests = f.ai.calls;
		f.chat.handler.refreshNativeColors(); f.chat.handler.tick();
		assertTrue(f.pending().isEmpty()); assertEquals(requests, f.ai.calls);
		f.chat.mode = PlayerChatMode.INLINE;
		f.chat.handler.refreshNativeColors(); f.chat.handler.tick();
		assertTrue(f.pending().isEmpty()); assertEquals(requests, f.ai.calls);
	}

	@Test
	public void capacityBlockedOldGenerationCannotReenterStaleRows() throws Exception
	{
		PartialFixture f = capacityPartial(false);
		f.chat.ids[0].set(103); f.chat.messages.remove(101L); f.chat.messages.put(103L, f.chat.nodes[0]);
		f.chat.values[0].set("replacement body"); f.clock.set(15_001);
		int requests = f.ai.calls;
		f.chat.handler.tick(); f.chat.handler.refreshNativeColors();
		assertTrue(f.pending().isEmpty()); assertEquals("replacement body", f.chat.values[0].get());
		assertEquals(requests, f.ai.calls);
	}

	@Test
	public void capacityBlockedPartialResumesWhenNativeMappingReturns() throws Exception
	{
		PartialFixture f = capacityPartial(false);
		f.chat.handler.beginChatBuild(); f.chat.handler.finishChatBuild();
		f.clock.set(15_001); f.chat.handler.tick(); f.chat.handler.refreshNativeColors();
		assertTrue(f.pending().isEmpty());
		f.bindRows(); f.chat.handler.refreshNativeColors();
		assertTrue(f.pending().containsKey(f.chat.nodes[0])); assertEquals(16_000, f.deadline(0));
		f.ai.ready = true; f.chat.handler.tick();
		assertEquals(f.image(0, true), f.chat.values[0].get());
	}

	private static PartialFixture capacityPartial(boolean firstNull) throws Exception
	{
		PartialFixture f = new PartialFixture(); f.fillPending();
		assertEquals(96, f.pending().size()); f.clock.set(1_000);
		f.glyph.pendingCalls = firstNull ? 1 : 0;
		firePartial(f, 0, ChatMessageType.LEVELUPMESSAGE); f.bindRows();
		assertEquals(96, f.pending().size()); assertFalse(f.pending().containsKey(f.chat.nodes[0]));
		return f;
	}

	private static void firePartial(PartialFixture f, int index, ChatMessageType type)
	{
		f.chat.values[index].set(PartialFixture.ENGLISH); f.chat.types[index].set(type);
		ChatMessage event = new ChatMessage(); event.setMessageNode(f.chat.nodes[index]); event.setType(type);
		f.chat.handler.handle(event);
	}

	@Test
	public void reusedNodeWithNewIdCannotReceiveOldAsyncResult() throws Exception
	{
		PartialFixture f = new PartialFixture();
		f.track(0, PartialFixture.SECOND_EN);
		f.chat.ids[0].incrementAndGet();
		f.chat.values[0].set("Skillcape");
		f.ai.ready = true;
		int requests = f.ai.calls;
		f.chat.handler.tick();
		assertEquals("Skillcape", f.chat.values[0].get());
		assertEquals(requests, f.ai.calls);
		assertTrue(f.pending().isEmpty());
	}

	@Test
	public void sameIdWithNewBodyCannotRestoreOldEnglish() throws Exception
	{
		PartialFixture f = new PartialFixture();
		f.track(0);
		f.chat.values[0].set("Skillcape");
		f.chat.handler.goEnglish();
		assertEquals("Skillcape", f.chat.values[0].get());
		f.chat.handler.goChinese();
		assertEquals("Skillcape", f.chat.values[0].get());
	}

	@Test
	public void newEventOnSameObjectGetsItsOwnOriginalAndDeadline() throws Exception
	{
		PartialFixture f = new PartialFixture();
		f.track(0);
		f.clock.set(7_000);
		f.chat.values[0].set("Skillcape"); // Also test a repeated native ID with changed content.
		f.fire(ChatMessageType.GAMEMESSAGE);
		assertEquals(22_000, f.deadline(0));
		f.chat.handler.goEnglish();
		assertEquals("Skillcape", f.chat.values[0].get());
	}

	@Test
	public void offAndSkippedEventsInvalidateWithoutTrackingOrRequests() throws Exception
	{
		for (ChatMessageType type : new ChatMessageType[] { ChatMessageType.PUBLICCHAT,
				ChatMessageType.PRIVATECHAT, ChatMessageType.GAMEMESSAGE })
		{
			PartialFixture f = new PartialFixture();
			f.track(0);
			f.chat.mode = PlayerChatMode.OFF;
			f.chat.translateGame = false;
			f.chat.values[0].set(PartialFixture.SECOND_EN);
			int requests = f.ai.calls;
			f.fire(type);
			String current = f.chat.values[0].get();
			f.chat.handler.tick();
			f.chat.handler.goEnglish();
			f.chat.handler.goChinese();
			assertEquals(current, f.chat.values[0].get());
			assertEquals(requests, f.ai.calls);
			assertTrue(state(f.chat.handler, "tracked").isEmpty());
			assertTrue(f.pending().isEmpty());
		}
	}

	@Test
	public void emptyAndImageEventsInvalidateOldGeneration() throws Exception
	{
		for (String value : new String[] { "", "<img=1>" })
		{
			PartialFixture f = new PartialFixture();
			f.track(0);
			f.chat.values[0].set(value);
			f.fire(ChatMessageType.GAMEMESSAGE);
			f.chat.handler.goEnglish();
			assertEquals(value, f.chat.values[0].get());
			assertTrue(state(f.chat.handler, "tracked").isEmpty());
		}
	}

	@Test
	public void insertResultCannotOutliveItsSourceGeneration() throws Exception
	{
		PartialFixture f = new PartialFixture();
		f.chat.mode = PlayerChatMode.INSERT;
		f.chat.values[0].set(PartialFixture.SECOND_EN);
		f.fire(ChatMessageType.PUBLICCHAT);
		assertEquals(1, state(f.chat.handler, "inserts").size());
		f.chat.ids[0].incrementAndGet();
		f.chat.values[0].set("Skillcape");
		f.ai.ready = true;
		f.chat.handler.tick();
		assertEquals(0, f.chat.inserted.get());
		assertTrue(state(f.chat.handler, "inserts").isEmpty());
	}

	@Test
	public void reusedInsertEventDoesNotInheritOldBodyOrName() throws Exception
	{
		PartialFixture f = new PartialFixture();
		f.chat.mode = PlayerChatMode.INSERT;
		f.chat.values[0].set(PartialFixture.SECOND_EN);
		f.fire(ChatMessageType.PUBLICCHAT);
		f.chat.values[0].set("Skillcape");
		f.fire(ChatMessageType.PUBLICCHAT);
		assertEquals(1, f.chat.inserted.get());
		assertTrue(f.chat.insertText.get().contains(PartialFixture.FIRST_ZH));
		assertFalse(f.chat.insertText.get().contains(PartialFixture.SECOND_ZH));
		assertTrue(state(f.chat.handler, "inserts").isEmpty());
	}

	@Test
	public void retiredChatMembershipCannotWriteOrRestore() throws Exception
	{
		PartialFixture f = new PartialFixture();
		f.track(0);
		String shown = f.chat.values[0].get();
		f.chat.messages.clear();
		f.ai.ready = true;
		int calls = f.translator.calls;
		f.chat.handler.tick();
		f.chat.handler.goEnglish();
		f.chat.handler.goChinese();
		assertEquals(shown, f.chat.values[0].get());
		assertEquals(calls, f.translator.calls);
	}

	@Test
	public void staleCompletedAndInProgressBuildsCannotColorNewGeneration() throws Exception
	{
		for (boolean completeBuild : new boolean[] { true, false })
		{
			PartialFixture f = new PartialFixture();
			f.track(0);
			f.chat.handler.beginChatBuild();
			f.chat.handler.captureChatLine(new int[] { 101 }, 1);
			f.chat.handler.captureChatLine(new int[] { 102 }, 1);
			if (completeBuild) f.chat.handler.finishChatBuild();
			f.chat.values[0].set("Skillcape");
			f.fire(ChatMessageType.LEVELUPMESSAGE);
			if (!completeBuild) f.chat.handler.finishChatBuild();
			String shown = f.chat.values[0].get();
			int calls = f.translator.calls;
			f.hover();
			assertEquals(shown, f.chat.values[0].get());
			assertEquals(calls, f.translator.calls);
		}
	}

	@Test
	public void legitimateFormattedOverwriteStillReassertsSameMessage() throws Exception
	{
		PartialFixture f = new PartialFixture();
		f.track(0);
		f.chat.rlfms[0].set("Skillcape");
		f.ai.ready = true;
		f.chat.handler.tick();
		assertEquals(f.image(0, true), f.chat.rlfms[0].get());
		f.chat.handler.goEnglish();
		assertEquals(PartialFixture.ENGLISH, f.chat.values[0].get());
		assertNull(f.chat.rlfms[0].get());
	}

	@Test
	public void realLogoutClearsChatButLoadingAndHoppingDoNot() throws Exception
	{
		PartialFixture f = new PartialFixture();
		f.track(0);
		OsrscnPlugin plugin = new OsrscnPlugin();
		inject(plugin, "chatHandler", f.chat.handler);
		GameStateChanged event = new GameStateChanged();
		for (GameState state : new GameState[] { GameState.LOADING, GameState.HOPPING })
		{
			event.setGameState(state);
			plugin.onGameStateChanged(event);
			assertEquals(1, f.pending().size());
		}
		event.setGameState(GameState.LOGIN_SCREEN);
		plugin.onGameStateChanged(event);
		assertTrue(f.pending().isEmpty());
		assertTrue(state(f.chat.handler, "tracked").isEmpty());
	}

	@Test
	public void inlineRecolourKeepsTheSameMessageRetryAlive() throws Exception
	{
		PartialFixture f = new PartialFixture();
		f.chat.values[0].set(PartialFixture.SECOND_EN);
		f.fire(ChatMessageType.PUBLICCHAT);
		assertTrue(f.chat.values[0].get().startsWith("<col="));
		f.ai.ready = true;
		f.chat.handler.tick();
		assertEquals(img(0xff) + PartialFixture.SECOND_ZH, f.chat.values[0].get());
		f.chat.handler.goEnglish();
		assertEquals(PartialFixture.SECOND_EN, f.chat.values[0].get());
	}

	@Test
	public void unavailableIdentityDoesNotTrackOrRequest() throws Exception
	{
		PartialFixture f = new PartialFixture();
		f.chat.messages.clear();
		f.chat.values[0].set(PartialFixture.SECOND_EN);
		f.fire(ChatMessageType.PUBLICCHAT);
		assertEquals(PartialFixture.SECOND_EN, f.chat.values[0].get());
		assertEquals(0, f.ai.calls);
		assertTrue(state(f.chat.handler, "tracked").isEmpty());
	}

	@Test
	public void reusedIdWithIdenticalBodyEventStartsANewDeadline() throws Exception
	{
		PartialFixture f = new PartialFixture();
		f.track(0, PartialFixture.SECOND_EN);
		f.clock.set(7_000);
		f.fire(ChatMessageType.GAMEMESSAGE);
		assertEquals(22_000, f.deadline(0));
		f.chat.messages.remove((long) f.chat.ids[0].get());
		f.chat.ids[0].set(901);
		f.chat.messages.put(901L, f.chat.nodes[0]);
		f.clock.set(9_000);
		f.fire(ChatMessageType.GAMEMESSAGE);
		assertEquals(24_000, f.deadline(0));
	}

	@Test
	public void resultReturningAfterNewEventCannotOverwriteIt() throws Exception
	{
		PartialFixture f = new PartialFixture();
		f.translator.afterRender = () ->
		{
			f.chat.values[0].set("Skillcape");
			f.fire(ChatMessageType.GAMEMESSAGE);
		};
		f.track(0);
		assertEquals(img(0) + PartialFixture.FIRST_ZH, f.chat.values[0].get());
		f.chat.handler.goEnglish();
		assertEquals("Skillcape", f.chat.values[0].get());
	}

	@Test
	public void clearInvalidatesAnOutstandingResultEvenWithIdenticalNativeFields() throws Exception
	{
		PartialFixture f = new PartialFixture();
		f.translator.afterRender = () ->
		{
			f.chat.handler.clear();
			f.chat.values[0].set(PartialFixture.ENGLISH);
			f.ai.ready = true;
			f.fire(ChatMessageType.GAMEMESSAGE);
		};
		f.track(0);
		assertEquals(f.image(0, true), f.chat.values[0].get());
		assertEquals(1, f.pending().size());
	}

	@Test
	public void englishToggleRejectsOutstandingRenderAndCanRetryExplicitly() throws Exception
	{
		PartialFixture f = new PartialFixture();
		f.translator.afterRender = f.chat.handler::goEnglish;
		f.track(0);
		assertEquals(PartialFixture.ENGLISH, f.chat.values[0].get());
		assertTrue(f.pending().isEmpty());
		f.ai.ready = true;
		f.chat.handler.goChinese();
		assertEquals(f.image(0, true), f.chat.values[0].get());
	}

	@Test
	public void oldInsertContinuationCannotRemoveTheNewQueueEntry() throws Exception
	{
		PartialFixture f = new PartialFixture();
		f.chat.mode = PlayerChatMode.INSERT;
		f.chat.values[0].set("Skillcape");
		f.translator.afterRender = () ->
		{
			f.chat.values[0].set(PartialFixture.SECOND_EN);
			f.fire(ChatMessageType.PUBLICCHAT);
		};
		f.fire(ChatMessageType.PUBLICCHAT);
		assertEquals(0, f.chat.inserted.get());
		assertEquals(1, state(f.chat.handler, "inserts").size());
		f.ai.ready = true;
		f.chat.handler.tick();
		assertEquals(1, f.chat.inserted.get());
		assertTrue(f.chat.insertText.get().contains(PartialFixture.SECOND_ZH));
	}

	@Test
	public void sameGenerationRebuildKeepsConfirmedHoverAndOriginalDeadline() throws Exception
	{
		PartialFixture f = new PartialFixture();
		f.track(0);
		f.bindRows();
		f.hover();
		f.clock.set(14_999);
		f.bindRows();
		f.chat.colors[0].set(0);
		f.chat.handler.refreshNativeColors();
		assertEquals(f.image(0x7f7f7f, false), f.chat.values[0].get());
		assertEquals(15_000, f.deadline(0));
	}

	@Test
	public void mismatchedEventTypeDoesNotTranslateCurrentPlayerMessage() throws Exception
	{
		PartialFixture f = new PartialFixture();
		f.track(0);
		f.chat.types[0].set(ChatMessageType.PUBLICCHAT);
		f.chat.values[0].set("Skillcape");
		ChatMessage event = new ChatMessage();
		event.setType(ChatMessageType.GAMEMESSAGE);
		event.setMessageNode(f.chat.nodes[0]);
		int calls = f.translator.calls;
		f.chat.handler.handle(event);
		assertEquals("Skillcape", f.chat.values[0].get());
		assertEquals(calls, f.translator.calls);
		assertTrue(state(f.chat.handler, "tracked").isEmpty());
	}

	private static Map<?, ?> state(ChatHandler handler, String name) throws Exception
	{
		Field field = ChatHandler.class.getDeclaredField(name);
		field.setAccessible(true);
		return (Map<?, ?>) field.get(handler);
	}

	private static String img(int color)
	{
		return "<img=same-" + Integer.toHexString(color) + ">";
	}

	private static void inject(Object target, String fieldName, Object value) throws Exception
	{
		Field field = target.getClass().getDeclaredField(fieldName);
		field.setAccessible(true);
		field.set(target, value);
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

	private static class FakeGlyph extends GlyphService
	{
		@Override
		public int uiSize()
		{
			return 14;
		}

		@Override
		public int wrapChars(int widthPx, int size)
		{
			return 80;
		}
	}

	/** Runs the production lookup/completeness path with only in-memory dependencies. */
	@Test
	public void inlineOffCancelsCapacityBlockedFirstDispatch() throws Exception
	{
		RevocationFixture f = new RevocationFixture(PlayerChatMode.INLINE);
		f.blockCapacity();
		f.fire();
		assertEquals(0, f.requests);
		f.base.chat.mode = PlayerChatMode.OFF;
		f.releaseCapacity();
		f.base.chat.handler.tick();
		assertEquals("OFF must cancel the first request", 0, f.requests);
	}

	@Test
	public void insertOffCancelsPaceBlockedFirstDispatch() throws Exception
	{
		RevocationFixture f = new RevocationFixture(PlayerChatMode.INSERT);
		f.blockPace();
		f.fire();
		assertEquals(0, f.requests);
		f.base.chat.mode = PlayerChatMode.OFF;
		f.releaseCapacity();
		f.base.chat.handler.tick();
		assertEquals("OFF must cancel the first INSERT request", 0, f.requests);
	}

	@Test
	public void inlineInFlightOffCannotWriteResponse() throws Exception
	{
		RevocationFixture f = new RevocationFixture(PlayerChatMode.INLINE);
		f.fire();
		assertEquals(1, f.requests);
		f.base.chat.mode = PlayerChatMode.OFF;
		f.respond();
		f.base.chat.handler.tick();
		assertFalse(f.base.chat.values[0].get().contains("<img="));
		assertEquals(1, f.requests);
	}

	@Test
	public void insertInFlightOffCannotInsertResponse() throws Exception
	{
		RevocationFixture f = new RevocationFixture(PlayerChatMode.INSERT);
		f.fire();
		assertEquals(1, f.requests);
		f.base.chat.mode = PlayerChatMode.OFF;
		f.respond();
		f.base.chat.handler.tick();
		assertEquals(0, f.base.chat.inserted.get());
	}

	@Test
	public void inlineToInsertDoesNotReuseOldBody() throws Exception
	{
		RevocationFixture f = new RevocationFixture(PlayerChatMode.INLINE);
		f.blockCapacity(); f.fire();
		f.base.chat.mode = PlayerChatMode.INSERT;
		f.releaseCapacity(); f.base.chat.handler.tick();
		assertEquals(0, f.requests);
	}

	@Test
	public void insertToInlineDoesNotReuseOldBody() throws Exception
	{
		RevocationFixture f = new RevocationFixture(PlayerChatMode.INSERT);
		f.blockCapacity(); f.fire();
		f.base.chat.mode = PlayerChatMode.INLINE;
		f.releaseCapacity(); f.base.chat.handler.tick();
		assertEquals(0, f.requests);
	}

	@Test
	public void offThenLanguageRoundTripCannotRequeueOldPlayerBody() throws Exception
	{
		RevocationFixture f = new RevocationFixture(PlayerChatMode.INLINE);
		f.blockCapacity(); f.fire();
		f.base.chat.mode = PlayerChatMode.OFF;
		f.toggle.toggle(); f.base.chat.handler.goEnglish();
		f.toggle.toggle(); f.base.chat.handler.goChinese();
		f.releaseCapacity(); f.base.chat.handler.tick();
		assertEquals(0, f.requests);
	}

	@Test
	public void chineseDisabledStopsRetryBeforeClientCleanup() throws Exception
	{
		RevocationFixture f = new RevocationFixture(PlayerChatMode.INLINE);
		f.blockCapacity(); f.fire();
		f.toggle.toggle();
		f.releaseCapacity(); f.base.chat.handler.tick();
		assertEquals(0, f.requests);
	}

	@Test
	public void aiDisabledStillServesMemoryCacheWithoutPersistingPlayerBody() throws Exception
	{
		RevocationFixture f = new RevocationFixture(PlayerChatMode.INLINE);
		f.fire(); f.respond(); f.aiEnabled = false;
		f.base.chat.handler.tick();
		assertTrue(f.base.chat.values[0].get().contains(PartialFixture.SECOND_ZH));
		assertEquals(1, f.requests);
		assertEquals(0, f.base.collected);
		assertTrue(((java.util.Set<?>) f.aiField("persisted")).isEmpty());
	}

	@Test
	public void aiDisabledMissCanRetryWhenEnabledInSamePlayerMode() throws Exception
	{
		RevocationFixture f = new RevocationFixture(PlayerChatMode.INSERT);
		f.aiEnabled = false; f.fire(); f.base.chat.handler.tick();
		assertEquals(0, f.requests);
		f.aiEnabled = true; f.base.chat.handler.tick();
		assertEquals(1, f.requests);
		f.respond(); f.base.chat.handler.tick();
		assertEquals(1, f.base.chat.inserted.get());
		assertEquals(0, f.base.collected);
	}

	@Test
	public void delayedOffOnConfigEventsRevokeEvenWhenCurrentModeMatches() throws Exception
	{
		RevocationFixture f = new RevocationFixture(PlayerChatMode.INLINE);
		f.blockCapacity(); f.fire();
		f.modeEvent(PlayerChatMode.OFF, "OFF"); f.modeEvent(PlayerChatMode.INLINE, "INLINE");
		assertEquals(2, f.clientQueue.size());
		f.releaseCapacity(); f.base.chat.handler.tick();
		assertEquals(0, f.requests);
		f.drain(); f.base.chat.handler.goEnglish(); f.base.chat.handler.goChinese();
		assertEquals(0, f.requests);
	}

	@Test
	public void delayedOldCleanupKeepsNewModeDelivery() throws Exception
	{
		RevocationFixture f = new RevocationFixture(PlayerChatMode.INLINE);
		f.blockCapacity(); f.fire();
		f.modeEvent(PlayerChatMode.INSERT, "INSERT");
		f.base.chat.values[0].set(PartialFixture.SECOND_EN); f.fire();
		f.drain(); f.releaseCapacity(); f.base.chat.handler.tick();
		assertEquals(1, f.requests);
		f.respond(); f.base.chat.handler.tick();
		assertEquals(1, f.base.chat.inserted.get());
		assertEquals(PartialFixture.SECOND_EN, f.base.chat.values[0].get());
	}

	@Test
	public void resetModeNullEventRevokesPlayerRequests() throws Exception
	{
		RevocationFixture f = new RevocationFixture(PlayerChatMode.INSERT);
		f.blockCapacity(); f.fire();
		f.modeEvent(PlayerChatMode.OFF, null);
		f.releaseCapacity(); f.base.chat.handler.tick(); f.drain();
		assertEquals(0, f.requests);
	}

	@Test
	public void foreignConfigGroupDoesNotRevokePlayerMode() throws Exception
	{
		RevocationFixture f = new RevocationFixture(PlayerChatMode.INLINE);
		f.blockCapacity(); f.fire();
		net.runelite.client.events.ConfigChanged e = new net.runelite.client.events.ConfigChanged();
		e.setGroup("other-plugin"); e.setKey("playerChatMode"); e.setNewValue("OFF");
		f.plugin.onConfigChanged(e);
		f.releaseCapacity(); f.base.chat.handler.tick();
		assertEquals(1, f.requests);
	}

	@Test
	public void revokeInsidePreprocessingPreventsFirstDispatch() throws Exception
	{
		for (PlayerChatMode mode : new PlayerChatMode[] { PlayerChatMode.INLINE, PlayerChatMode.INSERT })
		{
			RevocationFixture f = new RevocationFixture(mode);
			f.ai.beforeCached = () ->
			{
				f.modeEvent(PlayerChatMode.OFF, "OFF"); f.modeEvent(mode, mode.name());
			};
			f.fire();
			assertEquals("mid-render revocation must reach AI admission", 0, f.requests);
			assertFalse(f.base.chat.values[0].get().contains("<img="));
			assertEquals(0, f.base.chat.inserted.get());
		}
	}

	@Test
	public void revokedCompletedInlineRestoresOriginalWithoutReplayingOnLanguageToggle() throws Exception
	{
		RevocationFixture f = new RevocationFixture(PlayerChatMode.INLINE);
		f.fire(); f.respond(); f.base.chat.handler.tick();
		assertTrue(f.base.chat.values[0].get().contains("<img="));
		f.modeEvent(PlayerChatMode.OFF, "OFF"); f.drain();
		assertEquals(PartialFixture.SECOND_EN, f.base.chat.values[0].get());
		f.modeEvent(PlayerChatMode.INLINE, "INLINE"); f.drain();
		f.base.chat.handler.goEnglish(); f.base.chat.handler.goChinese();
		assertEquals(PartialFixture.SECOND_EN, f.base.chat.values[0].get());
		assertEquals(1, f.requests);
	}

	@Test
	public void playerModeChangePreservesGamePendingDeadline() throws Exception
	{
		PartialFixture f = new PartialFixture(); f.track(0);
		long deadline = f.deadline(0);
		f.chat.handler.playerChatModeChanged(); f.chat.handler.discardRevokedPlayerMessages();
		assertEquals(deadline, f.deadline(0));
		f.ai.ready = true; f.chat.handler.tick();
		assertTrue(f.chat.values[0].get().contains(PartialFixture.SECOND_ZH));
	}

	@Test
	public void queuedHotkeyChangesLanguageAndChatTogetherOnClientThread() throws Exception
	{
		RevocationFixture f = new RevocationFixture(PlayerChatMode.INLINE);
		f.blockCapacity(); f.fire();
		f.hotkey();
		assertTrue("EDT must not change the language ahead of cleanup", f.toggle.isChineseEnabled());
		f.drain();
		assertFalse(f.toggle.isChineseEnabled());
		f.releaseCapacity(); f.base.chat.handler.tick();
		assertEquals(0, f.requests);
		f.hotkey(); f.drain();
		assertTrue(f.toggle.isChineseEnabled());
		assertEquals("same mode retains the explicit language retry baseline", 1, f.requests);
	}

	@Test
	public void rapidHotkeysCannotRestoreRevokedPlayerHistory() throws Exception
	{
		RevocationFixture f = new RevocationFixture(PlayerChatMode.INLINE);
		f.blockCapacity(); f.fire();
		f.modeEvent(PlayerChatMode.OFF, "OFF");
		f.hotkey(); f.hotkey();
		f.modeEvent(PlayerChatMode.INLINE, "INLINE");
		f.releaseCapacity(); f.drain();
		assertTrue(f.toggle.isChineseEnabled());
		assertEquals(0, f.requests);
	}

	@Test
	public void modeValueChangeBeforeEventCannotDispatchOldModeDuringPreprocessing() throws Exception
	{
		for (PlayerChatMode mode : new PlayerChatMode[] { PlayerChatMode.INLINE, PlayerChatMode.INSERT })
		{
			RevocationFixture f = new RevocationFixture(mode);
			f.ai.beforeCached = () -> f.base.chat.mode = mode == PlayerChatMode.INLINE
					? PlayerChatMode.INSERT : PlayerChatMode.INLINE;
			f.fire();
			assertEquals(0, f.requests);
		}
	}

	@Test
	public void newDeliveryIsForgottenBeforeRevokedHistoryIsRestored() throws Exception
	{
		RevocationFixture f = new RevocationFixture(PlayerChatMode.INLINE);
		f.base.chat.rlfms[0].set("old-format");
		f.blockCapacity(); f.fire();
		f.modeEvent(PlayerChatMode.OFF, "OFF");
		// A fresh delivery may reuse every native field. Its formatted value is not ours to restore.
		f.base.chat.values[0].set(PartialFixture.SECOND_EN);
		f.base.chat.rlfms[0].set("new-format"); f.fire();
		assertEquals("new-format", f.base.chat.rlfms[0].get());
		assertEquals(0, f.requests);
	}

	@Test
	public void configurationEventOnAnotherThreadRevokesBeforeClientCleanup() throws Exception
	{
		RevocationFixture f = new RevocationFixture(PlayerChatMode.INLINE);
		AtomicReference<Throwable> error = new AtomicReference<>();
		f.ai.beforeCached = () ->
		{
			Thread eventThread = new Thread(() ->
			{
				try { f.modeEvent(PlayerChatMode.OFF, "OFF"); f.modeEvent(PlayerChatMode.INLINE, "INLINE"); }
				catch (Throwable t) { error.set(t); }
			}, "r22-isolated-config-event");
			eventThread.start();
			try { eventThread.join(5_000); }
			catch (InterruptedException e) { throw new AssertionError(e); }
			assertFalse("config event must not wait for client cleanup", eventThread.isAlive());
			assertNull(error.get());
		};
		f.fire();
		assertEquals(2, f.clientQueue.size());
		assertEquals(0, f.requests);
		f.drain(); f.base.chat.handler.tick();
		assertEquals(0, f.requests);
	}

	@Test
	public void groupScopeValueChangeBeforeEventPreventsDispatchInsidePreprocessing() throws Exception
	{
		for (PlayerChatMode mode : new PlayerChatMode[] {PlayerChatMode.INLINE, PlayerChatMode.INSERT})
		{
			RevocationFixture f = new RevocationFixture(mode);
			f.ai.beforeCached = () -> f.base.chat.groupChannels = false;
			f.fire();
			assertEquals("Scope must reach the final request permit before its config event", 0, f.requests);
			assertFalse(f.base.chat.values[0].get().contains("<img="));
			assertEquals(0, f.base.chat.inserted.get());
		}
	}

	@Test
	public void groupScopeConfigEventRevokesSynchronouslyAndOnlyNewMessagesResume() throws Exception
	{
		for (PlayerChatMode mode : new PlayerChatMode[] {PlayerChatMode.INLINE, PlayerChatMode.INSERT})
		{
			RevocationFixture f = new RevocationFixture(mode);
			f.blockCapacity(); f.fire();
			f.scopeEvent(false); f.scopeEvent(true);
			assertEquals("Cleanup stays queued while synchronous permits are already revoked", 2, f.clientQueue.size());
			f.releaseCapacity(); f.base.chat.handler.tick();
			assertEquals(0, f.requests);
			f.drain(); f.base.chat.handler.goEnglish(); f.base.chat.handler.goChinese();
			assertEquals("Old history must not regain permission", 0, f.requests);
			f.base.chat.values[0].set(PartialFixture.SECOND_EN); f.fire();
			assertEquals("A new explicitly authorized delivery may translate", 1, f.requests);
			f.respond(); f.base.chat.handler.tick();
		}
	}

	@Test
	public void groupScopeValueChangeBeforeEventRevokesPendingCachedDisplay() throws Exception
	{
		for (PlayerChatMode mode : new PlayerChatMode[] {PlayerChatMode.INLINE, PlayerChatMode.INSERT})
		{
			RevocationFixture f = new RevocationFixture(mode);
			f.fire(); f.base.chat.groupChannels = false; f.respond(); f.base.chat.handler.tick();
			assertFalse(f.base.chat.values[0].get().contains("<img="));
			assertEquals(0, f.base.chat.inserted.get());
			assertEquals(1, f.requests);
		}
	}

	private static final class HookAi extends AiTranslator
	{
		Runnable beforeCached;
		@Override
		public String cached(String english)
		{
			Runnable action = beforeCached; beforeCached = null;
			if (action != null) action.run();
			return super.cached(english);
		}
	}

	/** Real ChatHandler -> Translator -> AiTranslator, with a transport that never opens a socket. */
	private static final class RevocationFixture
	{
		final PartialFixture base = new PartialFixture();
		final HookAi ai = new HookAi();
		final ToggleService toggle = new ToggleService();
		final OsrscnPlugin plugin = new OsrscnPlugin();
		final java.util.List<Runnable> clientQueue = new java.util.ArrayList<>();
		boolean aiEnabled = true;
		int pace;
		int requests;
		okhttp3.Callback callback;
		okhttp3.Request request;

		RevocationFixture(PlayerChatMode mode) throws Exception
		{
			base.chat.mode = mode;
			inject(plugin, "chatHandler", base.chat.handler);
			inject(plugin, "clientThread", new net.runelite.client.callback.ClientThread()
			{
				@Override public void invoke(Runnable action) { clientQueue.add(action); }
			});
			base.chat.values[0].set(PartialFixture.SECOND_EN);
			inject(base.chat.handler, "toggle", toggle);
			Field field = Translator.class.getDeclaredField("ai");
			field.setAccessible(true); field.set(base.translator, ai);
			setAi("loadedModel", "r22-isolated-model"); // bypass every disk-cache load
			setAi("gson", new com.google.gson.Gson());
			setAi("config", Proxy.newProxyInstance(getClass().getClassLoader(),
					new Class<?>[] { OsrscnConfig.class }, (p, m, a) ->
					{
						switch (m.getName())
						{
							case "useLocalAi": return aiEnabled;
							case "aiBackend": return com.osrscn.AiBackend.OLLAMA;
							case "ollamaModel": return "r22-isolated-model";
							case "ollamaUrl": return "http://127.0.0.1:1";
							case "aiConcurrency": return 1;
							case "aiPaceMs": return pace;
							default: return defaultValue(m.getReturnType());
						}
					}));
			setAi("httpClient", new okhttp3.OkHttpClient()
			{
				@Override
				public okhttp3.Call newCall(okhttp3.Request req)
				{
					request = req;
					return (okhttp3.Call) Proxy.newProxyInstance(getClass().getClassLoader(),
							new Class<?>[] { okhttp3.Call.class }, (p, m, a) ->
							{
								if (m.getName().equals("enqueue"))
								{
									requests++; callback = (okhttp3.Callback) a[0];
									System.out.println("R22 isolated first dispatch count=" + requests);
									return null;
								}
								if (m.getName().equals("request")) return req;
								throw new AssertionError("Unexpected transport operation: " + m.getName());
							});
				}
			});
			setAi("ollamaHttpClient", aiField("httpClient"));
		}

		void scopeEvent(boolean enabled)
		{
			boolean old = base.chat.groupChannels; base.chat.groupChannels = enabled;
			net.runelite.client.events.ConfigChanged event = new net.runelite.client.events.ConfigChanged();
			event.setGroup(OsrscnConfig.GROUP); event.setKey("groupChatMode");
			event.setOldValue((old ? base.chat.mode : PlayerChatMode.OFF).name());
			event.setNewValue((enabled ? base.chat.mode : PlayerChatMode.OFF).name());
			plugin.onConfigChanged(event);
		}

		void modeEvent(PlayerChatMode mode, String newValue)
		{
			PlayerChatMode old = base.chat.mode; base.chat.mode = mode;
			net.runelite.client.events.ConfigChanged e = new net.runelite.client.events.ConfigChanged();
			e.setGroup(OsrscnConfig.GROUP); e.setKey("groupChatMode");
			e.setOldValue(old.name()); e.setNewValue(newValue);
			plugin.onConfigChanged(e);
		}

		void drain()
		{
			for (Runnable r : new java.util.ArrayList<>(clientQueue)) r.run();
			clientQueue.clear();
		}

		void hotkey() throws Exception
		{
			inject(plugin, "toggle", toggle);
			inject(plugin, "dialogueHandler", new DialogueHandler() { @Override public void restore() {} });
			inject(plugin, "interfaceTranslator", new InterfaceTranslator() { @Override public void restore() {} });
			inject(plugin, "overheadHandler", new OverheadHandler());
			Constructor<com.osrscn.ui.ToggleOverlay> ctor = com.osrscn.ui.ToggleOverlay.class.getDeclaredConstructor();
			ctor.setAccessible(true); inject(plugin, "toggleOverlay", ctor.newInstance());
			Field f = OsrscnPlugin.class.getDeclaredField("toggleHotkey"); f.setAccessible(true);
			((net.runelite.client.util.HotkeyListener) f.get(plugin)).hotkeyPressed();
		}

		Object aiField(String name) throws Exception
		{
			Field f = AiTranslator.class.getDeclaredField(name); f.setAccessible(true); return f.get(ai);
		}

		void setAi(String name, Object value) throws Exception
		{
			Field f = AiTranslator.class.getDeclaredField(name); f.setAccessible(true); f.set(ai, value);
		}

		@SuppressWarnings("unchecked")
		void blockCapacity() throws Exception { ((java.util.Set<String>) aiField("inFlight")).add("fixture-capacity"); }
		void blockPace() throws Exception { pace = Integer.MAX_VALUE; setAi("lastDispatch", System.currentTimeMillis()); }
		void releaseCapacity() throws Exception
		{
			((java.util.Set<?>) aiField("inFlight")).clear(); pace = 0; setAi("lastDispatch", 0L);
		}
		void fire() { base.fire(ChatMessageType.CLAN_CHAT); }
		void respond() throws Exception
		{
			callback.onResponse(null, new okhttp3.Response.Builder().request(request)
					.protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("fixture")
					.body(okhttp3.ResponseBody.create(okhttp3.MediaType.parse("application/json"),
							"{\"message\":{\"content\":\"" + PartialFixture.SECOND_ZH + "\"}}" )).build());
		}
	}

	@Test
	public void bulkToggle192NeverExceedsPendingBoundDuringAdmission() throws Exception
	{
		BulkFixture b = new BulkFixture(192);
		b.switchChinese();
		b.assertBound();
		assertEquals(192, b.tracked().size());
		for (int i = 0; i < 5; i++) { b.tick(); b.assertBound(); }
	}

	@Test
	public void bulkToggle200NeverExceedsPendingBoundDuringAdmission() throws Exception
	{
		BulkFixture b = new BulkFixture(200);
		b.switchChinese();
		b.assertBound();
		assertEquals(200, b.tracked().size());
		for (int i = 0; i < 5; i++) { b.tick(); b.assertBound(); }
	}

	@Test
	public void bulkTogglePartialGetsTurnWhileAllEarlierMessagesRemainValid() throws Exception
	{
		BulkFixture b = new BulkFixture(200);
		b.switchChinese();
		b.base.bindRows();
		b.base.chat.handler.refreshNativeColors();
		assertFalse(b.complete(199));
		b.base.ai.ready = true;
		for (int i = 0; i < 3; i++) b.tick();
		for (int i = 0; i < 200; i++) assertTrue("No retry opportunity at " + i, b.complete(i));
		assertEquals(200, b.tracked().size());
		assertEquals(200, b.base.chat.messages.size());
		b.assertBound();
	}

	@Test
	public void bulkToggleCompleteCacheRemainsReachableAndReassertsOverwrite() throws Exception
	{
		BulkFixture b = new BulkFixture(200);
		b.base.ai.ready = true;
		b.switchChinese();
		for (int i = 0; i < 3; i++) b.tick();
		for (int i = 0; i < 200; i++) assertTrue(b.complete(i));
		b.nodes.get(199).setRuneLiteFormatMessage("fixture plugin formatting");
		for (int i = 0; i < 3; i++) b.tick();
		assertTrue(b.nodes.get(199).getRuneLiteFormatMessage().contains(PartialFixture.SECOND_ZH));
		b.assertBound();
	}

	@Test
	public void bulkToggleFirstNullReceivesLaterGlyphAndPartialCompletion() throws Exception
	{
		BulkFixture b = new BulkFixture(192);
		b.base.glyph.pendingCalls = 10_000;
		b.switchChinese();
		for (int i = 0; i < 3; i++) b.tick();
		assertEquals(PartialFixture.ENGLISH, b.nodes.get(191).getValue());
		b.base.glyph.pendingCalls = 0;
		b.tick();
		b.base.ai.ready = true;
		for (int i = 0; i < 3; i++) b.tick();
		for (int i = 0; i < 192; i++) assertTrue(b.complete(i));
		b.assertBound();
	}

	@Test
	public void bulkToggleSingleAndMultipleVacanciesKeepSharedDeadline() throws Exception
	{
		for (int slots : new int[] { 1, 4 })
		{
			BulkFixture b = new BulkFixture(200);
			b.switchChinese();
			b.base.clock.set(6_000);
			b.base.bindRows();
			for (Object key : new java.util.ArrayList<>(b.peak.keySet()).subList(0, slots)) b.peak.remove(key);
			b.base.chat.handler.refreshNativeColors();
			b.tick();
			for (Object t : b.tracked().values()) assertEquals(20_000L, longField(t, "retryDeadline"));
			for (Object p : b.peak.values()) assertEquals(20_000L, longField(p, "deadline"));
			b.assertBound();
		}
	}

	@Test
	public void bulkToggleMappingCanReturnWithinOriginalSwitchWindow() throws Exception
	{
		BulkFixture b = new BulkFixture(200);
		b.switchChinese();
		b.base.chat.handler.beginChatBuild();
		b.base.chat.handler.finishChatBuild();
		b.base.clock.set(20_000);
		b.base.ai.ready = true;
		b.base.bindRows();
		b.base.chat.handler.refreshNativeColors();
		for (int i = 0; i < 3; i++) b.tick();
		assertTrue(b.complete(198)); assertTrue(b.complete(199));
		b.assertBound();
	}

	@Test
	public void bulkToggleExpiredWindowNeverDispatchesOrCollectsOnAutomaticPaths() throws Exception
	{
		for (boolean firstNull : new boolean[] { false, true })
		{
			BulkFixture b = new BulkFixture(200);
			if (firstNull) b.base.glyph.pendingCalls = 10_000;
			b.switchChinese(); b.base.bindRows();
			b.base.clock.set(20_001);
			int calls = b.base.ai.calls, collected = b.base.collected;
			b.base.glyph.pendingCalls = 0;
			for (int i = 0; i < 8; i++)
			{
				b.base.chat.colors[0].set(i % 2 == 0 ? 0x7f7f7f : 0);
				b.base.chat.handler.refreshNativeColors(); b.tick();
			}
			assertEquals(calls, b.base.ai.calls);
			assertEquals(collected, b.base.collected);
			assertEquals(0, b.peak.size());
			for (Object t : b.tracked().values()) assertEquals(20_000L, longField(t, "retryDeadline"));
			b.assertBound();
		}
	}

	@Test
	public void bulkToggleStablePartialFramesDoNotIncreaseTranslationFrequency() throws Exception
	{
		BulkFixture b = new BulkFixture(200);
		b.switchChinese(); b.base.bindRows();
		b.base.chat.handler.refreshNativeColors();
		int calls = b.base.translator.calls;
		for (int i = 0; i < 30; i++) b.base.chat.handler.refreshNativeColors();
		assertEquals(calls, b.base.translator.calls);
		for (int i = 0; i < 3; i++) b.tick();
		b.assertBound();
	}

	@Test
	public void bulkToggleRepeatedWindowsDoNotRenewNaturalRetry() throws Exception
	{
		BulkFixture b = new BulkFixture(200);
		for (int round = 0; round < 3; round++)
		{
			b.base.clock.set(5_000 + round * 1_000); b.switchChineseAtCurrentTime();
			long deadline = 20_000 + round * 1_000;
			for (int i = 0; i < 3; i++) b.tick();
			for (Object t : b.tracked().values()) assertEquals(deadline, longField(t, "retryDeadline"));
			b.assertBound();
		}
		PartialFixture natural = new PartialFixture();
		natural.track(0); natural.clock.set(7_000); natural.bindRows();
		for (int i = 0; i < 5; i++) { natural.chat.handler.tick(); natural.chat.handler.refreshNativeColors(); }
		assertEquals(15_000L, natural.deadline(0));
	}

	@Test
	public void bulkToggleOldGenerationAndRevokedModesCannotReenter() throws Exception
	{
		for (PlayerChatMode mode : new PlayerChatMode[] { PlayerChatMode.OFF, PlayerChatMode.INSERT })
		{
			BulkFixture b = new BulkFixture(200, 96);
			b.switchChinese();
			b.base.chat.mode = mode; b.base.chat.handler.playerChatModeChanged();
			b.base.chat.ids[1].addAndGet(10_000);
			b.base.chat.values[1].set("fixture replacement");
			b.base.ai.ready = true;
			for (int i = 0; i < 4; i++) b.tick();
			assertEquals("fixture replacement", b.nodes.get(199).getValue());
			for (int i = 0; i < 96; i++) assertEquals(PartialFixture.ENGLISH, b.nodes.get(i).getValue());
			assertEquals(0, b.base.chat.inserted.get());
			b.assertBound();
		}
	}

	@Test
	public void bulkToggleModeReleaseCannotLetNewNaturalMessagesStarveWaitingWork() throws Exception
	{
		BulkFixture b = new BulkFixture(200, 96);
		b.switchChinese();
		b.base.chat.mode = PlayerChatMode.OFF; b.base.chat.handler.playerChatModeChanged();
		b.base.chat.handler.discardRevokedPlayerMessages();
		b.base.clock.set(6_000);
		for (int i = 0; i < 96; i++) b.addNatural();
		b.base.ai.ready = true;
		for (int i = 0; i < 3; i++) b.tick();
		for (int i = 96; i < 200; i++) assertTrue("Waiting work starved " + i, b.complete(i));
		b.assertBound();
	}

	@Test
	public void bulkToggleReentrantLanguageChangeRejectsOldTickRotation() throws Exception
	{
		BulkFixture b = new BulkFixture(200);
		b.switchChinese();
		b.base.translator.afterRender = () ->
		{
			b.toggle.toggle(); b.base.chat.handler.goEnglish();
			b.base.clock.set(9_000); b.toggle.toggle(); b.base.chat.handler.goChinese();
		};
		b.tickWithoutBudgetAssertion();
		b.base.ai.ready = true;
		for (int i = 0; i < 3; i++) b.tick();
		for (int i = 0; i < 200; i++) assertTrue(b.complete(i));
		for (Object p : b.peak.values()) assertEquals(24_000L, longField(p, "deadline"));
		for (Object t : b.tracked().values()) assertEquals(24_000L, longField(t, "retryDeadline"));
		b.assertBound();
	}

	@Test
	public void bulkToggleEvictedPendingAndClearCannotRetainOldSwitchWork() throws Exception
	{
		BulkFixture b = new BulkFixture(200);
		b.switchChinese(); b.base.bindRows();
		for (int i = 0; i < 96; i++) b.addNatural();
		b.base.ai.ready = true;
		for (int i = 0; i < 3; i++) b.tick();
		assertTrue(b.complete(199)); b.assertBound();
		b.base.chat.handler.clear();
		int calls = b.base.translator.calls;
		for (int i = 0; i < 3; i++) b.tick();
		assertEquals(calls, b.base.translator.calls);
		assertTrue(b.tracked().isEmpty()); assertTrue(b.peak.isEmpty());
	}

	@Test
	public void bulkToggleWaitingCompleteKeepsVisibleNativeColorAcrossRotations() throws Exception
	{
		BulkFixture b = new BulkFixture(200);
		b.base.ai.ready = true; b.switchChinese(); b.tick(); b.tick();
		assertTrue(b.complete(198));
		Field field = ChatHandler.class.getDeclaredField("toggleWaiting"); field.setAccessible(true);
		assertTrue(((Map<?, ?>) field.get(b.base.chat.handler)).containsKey(b.nodes.get(198)));
		b.base.bindRows(); b.base.hover();
		assertEquals(b.base.image(0x7f7f7f, true), b.nodes.get(198).getValue());
		for (int i = 0; i < 6; i++)
		{
			b.tick();
			assertEquals(b.base.image(0x7f7f7f, true), b.nodes.get(198).getValue());
		}
		b.assertBound();
	}

	@Test
	public void bulkToggleInsideInsertStopsTheOuterTickBeforeTheNewBatch() throws Exception
	{
		BulkFixture b = new BulkFixture(200); b.switchChinese();
		b.base.chat.mode = PlayerChatMode.INSERT; b.base.chat.handler.playerChatModeChanged();
		MessageNode insert = ChatFixture.message(new AtomicReference<>(PartialFixture.SECOND_EN),
				new AtomicInteger(ChatFixture.nextId.incrementAndGet()), new AtomicReference<>(),
				new AtomicReference<>(ChatMessageType.PUBLICCHAT));
		b.base.chat.messages.put((long) insert.getId(), insert); b.fire(insert);
		b.base.translator.afterRender = () ->
		{
			b.toggle.toggle(); b.base.chat.handler.goEnglish();
			b.base.clock.set(9_000); b.toggle.toggle(); b.base.chat.handler.goChinese();
		};
		int calls = b.base.translator.calls;
		b.tickWithoutBudgetAssertion();
		assertEquals("One INSERT attempt plus the explicitly nested switch batch", 97, b.base.translator.calls - calls);
		assertEquals(0, b.base.chat.inserted.get());
		b.assertBound();
	}

	private static long longField(Object value, String name) throws Exception
	{
		Field field = value.getClass().getDeclaredField(name); field.setAccessible(true);
		return field.getLong(value);
	}

	private static final class PeakPending extends java.util.concurrent.ConcurrentHashMap<MessageNode, Object>
	{
		int peak;
		@Override
		public Object put(MessageNode node, Object pending)
		{
			Object old = super.put(node, pending);
			peak = Math.max(peak, size());
			return old;
		}
	}

	private static final class BulkFixture
	{
		final PartialFixture base = new PartialFixture();
		final ToggleService toggle = new ToggleService();
		final PeakPending peak = new PeakPending();
		final java.util.List<MessageNode> nodes = new java.util.ArrayList<>();

		BulkFixture(int count) throws Exception { this(count, 0); }

		BulkFixture(int count, int players) throws Exception
		{
			inject(base.chat.handler, "toggle", toggle);
			inject(base.chat.handler, "pending", peak);
			for (int i = 0; i < count; i++)
			{
				MessageNode node;
				if (i >= count - 2)
				{
					int index = i - count + 2;
					base.chat.values[index].set(PartialFixture.ENGLISH);
					node = base.chat.nodes[index];
				}
				else
				{
					node = ChatFixture.message(new AtomicReference<>(PartialFixture.ENGLISH),
							new AtomicInteger(ChatFixture.nextId.incrementAndGet()), new AtomicReference<>(),
							new AtomicReference<>(i < players ? ChatMessageType.PUBLICCHAT : ChatMessageType.LEVELUPMESSAGE));
				}
				base.chat.messages.put((long) node.getId(), node);
				nodes.add(node); fire(node);
			}
			assertEquals(count, tracked().size()); assertEquals(96, peak.size());
		}

		void fire(MessageNode node)
		{
			ChatMessage event = new ChatMessage(); event.setMessageNode(node); event.setType(node.getType());
			base.chat.handler.handle(event);
		}

		void addNatural()
		{
			MessageNode node = ChatFixture.message(new AtomicReference<>(PartialFixture.ENGLISH));
			base.chat.messages.put((long) node.getId(), node); fire(node);
		}

		void switchChinese() { base.clock.set(5_000); switchChineseAtCurrentTime(); }
		void switchChineseAtCurrentTime()
		{
			toggle.toggle(); base.chat.handler.goEnglish();
			toggle.toggle(); base.chat.handler.goChinese();
		}

		void tick()
		{
			int calls = base.translator.calls;
			base.chat.handler.tick();
			assertTrue("More than 96 render calls in one tick", base.translator.calls - calls <= 96);
		}

		void tickWithoutBudgetAssertion() { base.chat.handler.tick(); }
		void assertBound() { assertTrue("Observed pending peak " + peak.peak, peak.peak <= 96); }

		Map<?, ?> tracked() throws Exception
		{
			Field field = ChatHandler.class.getDeclaredField("tracked"); field.setAccessible(true);
			return (Map<?, ?>) field.get(base.chat.handler);
		}

		boolean complete(int index) throws Exception
		{
			Object t = tracked().get(nodes.get(index));
			if (t == null) return false;
			Field field = t.getClass().getDeclaredField("complete"); field.setAccessible(true);
			return field.getBoolean(t) && nodes.get(index).getValue().contains(PartialFixture.SECOND_ZH);
		}
	}

	private static final class PartialFixture
	{
		// local_data/zh/transcript_zh_interface.tsv, row 7226 at 45b82d5.
		// This is a real table input, not evidence of a naturally occurring chat message.
		static final String ENGLISH = "Skillcape<br>See the Shilo Village Slayer Master";
		static final String FIRST_ZH = "技能斗篷";
		static final String SECOND_EN = "See the Shilo Village Slayer Master";
		static final String SECOND_ZH = "前往希洛村拜访杀戮大师";
		final ChatFixture chat = new ChatFixture();
		final CountingTranslator translator = new CountingTranslator();
		final DelayedAi ai = new DelayedAi();
		final DelayedGlyph glyph = new DelayedGlyph();
		final AtomicLong clock = new AtomicLong();
		int collected;

		@SuppressWarnings("unchecked")
		PartialFixture() throws Exception
		{
			TranslationStore store = new TranslationStore();
			Field mapsField = TranslationStore.class.getDeclaredField("maps");
			mapsField.setAccessible(true);
			Map<TranslationStore.Category, Map<String, String>> maps =
					(Map<TranslationStore.Category, Map<String, String>>) mapsField.get(store);
			// Deliberately omit the combined row to exercise the real per-line fallback.
			maps.get(TranslationStore.Category.INTERFACE).put(TranslationStore.normalize("Skillcape"), FIRST_ZH);
			Client client = (Client) Proxy.newProxyInstance(getClass().getClassLoader(),
					new Class<?>[] { Client.class }, (p, method, args) -> defaultValue(method.getReturnType()));
			Map<String, Object> dependencies = new HashMap<>();
			dependencies.put("client", client);
			dependencies.put("store", store);
			dependencies.put("ai", ai);
			dependencies.put("glyph", glyph);
			dependencies.put("missing", new MissingCollector()
			{
				@Override
				public void record(String english, String category, String subCategory, String source)
				{
					assertEquals(SECOND_EN, english);
					collected++;
				}
			});
			for (Map.Entry<String, Object> e : dependencies.entrySet())
			{
				Field field = Translator.class.getDeclaredField(e.getKey());
				field.setAccessible(true);
				field.set(translator, e.getValue());
			}
			inject(chat.handler, "translator", translator);
			inject(chat.handler, "glyph", glyph);
			inject(chat.handler, "clock", (LongSupplier) clock::get);
		}

		void track(int index) throws Exception
		{
			track(index, ENGLISH);
		}

		void fire(ChatMessageType type)
		{
			chat.types[0].set(type);
			ChatMessage event = new ChatMessage();
			event.setMessageNode(chat.nodes[0]);
			event.setType(type);
			chat.handler.handle(event);
		}

		void track(int index, String english) throws Exception
		{
			chat.values[index].set(english);
			Method method = ChatHandler.class.getDeclaredMethod("translateNode",
					MessageNode.class, String.class, int.class, boolean.class, boolean.class);
			method.setAccessible(true);
			method.invoke(chat.handler, chat.nodes[index], english, 0, true, true);
		}

		void fillPending() throws Exception
		{
			Method method = ChatHandler.class.getDeclaredMethod("translateNode",
					MessageNode.class, String.class, int.class, boolean.class, boolean.class);
			method.setAccessible(true);
			for (int i = 0; i < 96; i++)
			{
				MessageNode node = ChatFixture.message(new AtomicReference<>(ENGLISH));
				chat.messages.put((long) node.getId(), node);
				method.invoke(chat.handler, node, ENGLISH, 0, true, true);
			}
		}

		Map<?, ?> pending() throws Exception
		{
			Field field = ChatHandler.class.getDeclaredField("pending");
			field.setAccessible(true);
			return (Map<?, ?>) field.get(chat.handler);
		}

		long deadline(int index) throws Exception
		{
			Object pending = pending().get(chat.nodes[index]);
			Field field = pending.getClass().getDeclaredField("deadline");
			field.setAccessible(true);
			return field.getLong(pending);
		}

		void bindRows()
		{
			chat.handler.beginChatBuild();
			chat.handler.captureChatLine(new int[] { 101 }, 1);
			chat.handler.captureChatLine(new int[] { 102 }, 1);
			chat.handler.finishChatBuild();
		}

		void hover()
		{
			chat.mouse.set(new Point(80, 108));
			chat.colors[0].set(0x7f7f7f);
			chat.handler.refreshNativeColors();
		}

		String image(int color, boolean complete)
		{
			return img(color) + FIRST_ZH + "<br>" + (complete ? SECOND_ZH : SECOND_EN);
		}
	}

	private static final class CountingTranslator extends Translator
	{
		int calls;
		Runnable afterRender;
		Rendered last;

		@Override
		public Rendered renderChat(String text, int color, int maxChars, int size, boolean fallback,
				boolean persist, AiTranslator.RequestPermit permit)
		{
			calls++;
			Rendered result = super.renderChat(text, color, maxChars, size, fallback, persist, permit);
			last = result;
			Runnable action = afterRender;
			afterRender = null;
			if (action != null) action.run();
			return result;
		}

		@Override
		public Rendered renderChat(String text, int color, int maxChars, int size, boolean fallback, boolean persist)
		{
			calls++;
			Rendered result = super.renderChat(text, color, maxChars, size, fallback, persist);
			last = result;
			Runnable action = afterRender;
			afterRender = null;
			if (action != null) action.run();
			return result;
		}

		@Override
		public Rendered renderChatCached(String text, int color, int maxChars, int size)
		{
			calls++;
			last = super.renderChatCached(text, color, maxChars, size);
			return last;
		}
	}

	private static final class DelayedAi extends AiTranslator
	{
		boolean ready;
		int calls;

		@Override
		public String translate(String english, boolean persist, AiTranslator.RequestPermit permit)
		{
			return translate(english, persist);
		}

		@Override
		public String cached(String english)
		{
			return ready ? PartialFixture.SECOND_ZH : null;
		}

		@Override
		public String translate(String english, boolean persist)
		{
			calls++;
			assertEquals(TranslationStore.normalize(PartialFixture.SECOND_EN), english);
			return ready ? PartialFixture.SECOND_ZH : null;
		}
	}

	private static final class DelayedGlyph extends FakeGlyph
	{
		int pendingCalls;

		@Override
		public String toImgTags(String text, int color, int maxChars, int size)
		{
			if (pendingCalls > 0)
			{
				pendingCalls--;
				return null;
			}
			return img(color) + text;
		}
	}

	private static final class FakeTranslator extends Translator
	{
		final AtomicInteger calls = new AtomicInteger();
		final AtomicInteger pendingColor = new AtomicInteger(-1);
		final AtomicInteger pendingCalls = new AtomicInteger();
		int lastColor;
		boolean lastAiFallback;
		boolean lastPersist;

		@Override
		public Rendered renderChat(String text, int color, int maxChars, int size,
				boolean aiFallback, boolean persist, AiTranslator.RequestPermit permit)
		{
			return renderChat(text, color, maxChars, size, aiFallback, persist);
		}

		@Override
		public Rendered renderChat(String text, int color, int maxChars, int size,
				boolean aiFallback, boolean persist)
		{
			calls.incrementAndGet();
			lastColor = color;
			lastAiFallback = aiFallback;
			lastPersist = persist;
			if (pendingColor.get() == color && pendingCalls.getAndUpdate(n -> Math.max(0, n - 1)) > 0)
			{
				return null;
			}
			try
			{
				Constructor<Rendered> c = Rendered.class.getDeclaredConstructor(String.class, boolean.class);
				c.setAccessible(true);
				return c.newInstance(img(color), true);
			}
			catch (ReflectiveOperationException ex)
			{
				throw new AssertionError(ex);
			}
		}
	}

	private static final class PlayerChatFixture
	{
		final ChatHandler handler = new ChatHandler();
		final FakeTranslator translator = new FakeTranslator();
		final Map<Integer, Integer> varps = new HashMap<>();
		final AtomicReference<String> value = new AtomicReference<>("hello clan");
		final AtomicReference<String> name = new AtomicReference<>("Alice");
		final MessageNode node;
		ChatMessageType type;
		boolean transparent;

		PlayerChatFixture() throws Exception
		{
			node = (MessageNode) Proxy.newProxyInstance(getClass().getClassLoader(),
					new Class<?>[] { MessageNode.class }, (proxy, method, args) ->
					{
						switch (method.getName())
						{
							case "getValue": return value.get();
							case "setValue": value.set((String) args[0]); return null;
							case "getName": return name.get();
							case "getId": return 101;
							case "getType": return type;
							case "getRuneLiteFormatMessage": return null;
							case "hashCode": return System.identityHashCode(proxy);
							case "equals": return proxy == args[0];
							default: return defaultValue(method.getReturnType());
						}
					});
			Client client = (Client) Proxy.newProxyInstance(getClass().getClassLoader(),
					new Class<?>[] { Client.class }, (proxy, method, args) ->
					{
						if ("getMessages".equals(method.getName()))
						{
							return Proxy.newProxyInstance(getClass().getClassLoader(),
									new Class<?>[] { IterableHashTable.class }, (p, m, a) ->
											m.getName().equals("get") && ((Number) a[0]).longValue() == 101 ? node : null);
						}
						if ("getVarbitValue".equals(method.getName()))
						{
							return transparent ? 1 : 0;
						}
						if ("getVarpValue".equals(method.getName()))
						{
							return varps.getOrDefault((int) args[0], 0);
						}
						return defaultValue(method.getReturnType());
					});
			OsrscnConfig config = (OsrscnConfig) Proxy.newProxyInstance(getClass().getClassLoader(),
					new Class<?>[] { OsrscnConfig.class }, (proxy, method, args) ->
					{
						switch (method.getName())
						{
							case "playerChatMode": return PlayerChatMode.INLINE;
							case "groupChatMode": return PlayerChatMode.INLINE;
							case "translateGameMessages": return true;
							default: return defaultValue(method.getReturnType());
						}
					});
			inject(handler, "client", client);
			inject(handler, "translator", translator);
			inject(handler, "glyph", new FakeGlyph());
			inject(handler, "config", config);
			inject(handler, "toggle", new ToggleService());
		}

		void fire(ChatMessageType type)
		{
			this.type = type;
			ChatMessage event = new ChatMessage();
			event.setMessageNode(node);
			event.setType(type);
			handler.handle(event);
		}
	}

	private static final class ChatFixture
	{
		final ChatHandler handler = new ChatHandler();
		final FakeTranslator translator = new FakeTranslator();
		final AtomicReference<Point> mouse = new AtomicReference<>(new Point(20, 20));
		final AtomicInteger refreshes = new AtomicInteger();
		final AtomicInteger inserted = new AtomicInteger();
		final AtomicReference<String> insertText = new AtomicReference<>();
		final Map<Long, MessageNode> messages = new HashMap<>();
		boolean groupChannels = true;
		PlayerChatMode mode = PlayerChatMode.INLINE;
		boolean translateGame = true;
		final AtomicReference<String>[] values = new AtomicReference[]
		{
			new AtomicReference<>("same"), new AtomicReference<>("same")
		};
		final AtomicInteger[] colors = { new AtomicInteger(), new AtomicInteger() };
		final AtomicInteger[] ids = { new AtomicInteger(101), new AtomicInteger(102) };
		final AtomicReference<ChatMessageType>[] types = new AtomicReference[]
		{
			new AtomicReference<>(ChatMessageType.LEVELUPMESSAGE), new AtomicReference<>(ChatMessageType.LEVELUPMESSAGE)
		};
		final AtomicReference<String>[] rlfms = new AtomicReference[]
		{
			new AtomicReference<>(), new AtomicReference<>()
		};
		final MessageNode[] nodes = { message(values[0], ids[0], rlfms[0], types[0]), message(values[1], ids[1], rlfms[1], types[1]) };
		private static final AtomicInteger nextId = new AtomicInteger(1_000);

		ChatFixture() throws Exception
		{
			this(false);
		}

		ChatFixture(boolean filterBanner) throws Exception
		{
			messages.put(101L, nodes[0]);
			messages.put(102L, nodes[1]);
			IterableHashTable<MessageNode> table = (IterableHashTable<MessageNode>) Proxy.newProxyInstance(
					getClass().getClassLoader(), new Class<?>[] { IterableHashTable.class },
					(proxy, method, args) -> "get".equals(method.getName())
							? messages.get(((Number) args[0]).longValue()) : defaultValue(method.getReturnType()));

			int offset = filterBanner ? 1 : 0;
			Widget[] dynamic = new Widget[(2 + offset) * 4];
			dynamic[offset * 4] = textWidget(colors[0]);
			dynamic[(offset + 1) * 4] = textWidget(colors[1]);
			Widget scroll = widget(null, dynamic, null);
			Widget[] lines = filterBanner
					? new Widget[]
					{
						widget(new Rectangle(50, 80, 300, 15), null, null),
						widget(new Rectangle(50, 100, 300, 15), null, null),
						widget(new Rectangle(50, 120, 300, 15), null, null)
					}
					: new Widget[]
					{
						widget(new Rectangle(50, 100, 300, 15), null, null),
						widget(new Rectangle(50, 120, 300, 15), null, null)
					};
			Client client = (Client) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { Client.class },
					(proxy, method, args) ->
					{
						switch (method.getName())
						{
							case "getMessages": return table;
							case "getMouseCanvasPosition": return mouse.get();
							case "refreshChat": refreshes.incrementAndGet(); return null;
							case "addChatMessage": inserted.incrementAndGet(); insertText.set((String) args[2]); return null;
							case "getWidget":
								int id = (int) args[0];
								if (id == InterfaceID.Chatbox.SCROLLAREA) return scroll;
								int lineIndex = id - InterfaceID.Chatbox.LINE0;
								if (lineIndex >= 0 && lineIndex < lines.length) return lines[lineIndex];
								return null;
							default: return defaultValue(method.getReturnType());
						}
					});
			inject(handler, "client", client);
			inject(handler, "translator", translator);
			inject(handler, "glyph", new FakeGlyph());
			inject(handler, "toggle", new ToggleService());
			inject(handler, "config", Proxy.newProxyInstance(getClass().getClassLoader(),
					new Class<?>[] { OsrscnConfig.class }, (p, method, args) ->
							method.getName().equals("groupChatMode") ? (groupChannels ? mode : PlayerChatMode.OFF)
							: method.getName().equals("playerChatMode") ? mode
							: method.getName().equals("translateGameMessages") ? translateGame
							: defaultValue(method.getReturnType())));
		}

		void trackBoth() throws Exception
		{
			Method method = ChatHandler.class.getDeclaredMethod("translateNode",
					MessageNode.class, String.class, int.class, boolean.class, boolean.class);
			method.setAccessible(true);
			method.invoke(handler, nodes[0], "same", 0x000000, true, true);
			method.invoke(handler, nodes[1], "same", 0x000000, true, true);
		}

		private static MessageNode message(AtomicReference<String> value)
		{
			return message(value, new AtomicInteger(nextId.incrementAndGet()), new AtomicReference<>(),
					new AtomicReference<>(ChatMessageType.LEVELUPMESSAGE));
		}

		private static MessageNode message(AtomicReference<String> value, AtomicInteger id,
				AtomicReference<String> rlfm, AtomicReference<ChatMessageType> type)
		{
			return (MessageNode) Proxy.newProxyInstance(ChatHandlerTest.class.getClassLoader(),
					new Class<?>[] { MessageNode.class }, (proxy, method, args) ->
					{
						switch (method.getName())
						{
							case "getValue": return value.get();
							case "setValue": value.set((String) args[0]); return null;
							case "getId": return id.get();
							case "getType": return type.get();
							case "getRuneLiteFormatMessage": return rlfm.get();
							case "setRuneLiteFormatMessage": rlfm.set((String) args[0]); return null;
							case "hashCode": return System.identityHashCode(proxy);
							case "equals": return proxy == args[0];
							default: return defaultValue(method.getReturnType());
						}
					});
		}

		private static Widget textWidget(AtomicInteger color)
		{
			return widget(null, null, color);
		}

		private static Widget widget(Rectangle bounds, Widget[] dynamic, AtomicInteger color)
		{
			return (Widget) Proxy.newProxyInstance(ChatHandlerTest.class.getClassLoader(),
					new Class<?>[] { Widget.class }, (proxy, method, args) ->
					{
						switch (method.getName())
						{
							case "getBounds": return bounds;
							case "getWidth": return bounds == null ? 0 : bounds.width;
							case "getHeight": return bounds == null ? 0 : bounds.height;
							case "getDynamicChildren": return dynamic;
							case "getTextColor": return color == null ? 0 : color.get();
							case "isHidden": return false;
							default: return defaultValue(method.getReturnType());
						}
					});
		}
	}
}
