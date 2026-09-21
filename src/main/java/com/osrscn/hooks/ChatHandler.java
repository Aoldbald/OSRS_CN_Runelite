package com.osrscn.hooks;

import com.osrscn.OsrscnConfig;
import com.osrscn.AiBackend;
import com.osrscn.glyph.GlyphService;
import com.osrscn.text.Tags;
import com.osrscn.translate.Translator;
import com.osrscn.translate.AiTranslator;
import com.osrscn.translate.AiTranslator.RequestPermit;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import com.osrscn.PlayerChatMode;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.MessageNode;
import net.runelite.api.Point;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;

/**
 * Owns the scrolling chat box, which the interface walk deliberately skips (chat is message-node
 * driven, not widget driven).
 *
 * <ul>
 *   <li><b>Player chat</b> (public / friends chat / clan / guest clan / GIM): names are never translated.
 *       {@link PlayerChatMode#OFF} keeps English in the native channel colour;
 *       {@link PlayerChatMode#INLINE} translates the content in place;
 *       {@link PlayerChatMode#INSERT} leaves the English line and adds a separate Chinese line below
 *       it (e.g. {@code "OSRS_CN: Name: 你好"}).</li>
 *   <li><b>Game messages</b> (level-up / quest / system / examine description): translated to
 *       char-images, number-templated, with AI fallback.</li>
 *   <li>Everything else (private chat and channel system notices): left as the game drew it.</li>
 * </ul>
 *
 * <p>Applying a translation writes both {@link MessageNode#setValue} and, when present,
 * {@link MessageNode#setRuneLiteFormatMessage} - the latter is what the client renders for
 * plugin-formatted lines (e.g. the Examine plugin's vendor/GE price, set <i>after</i> our handler
 * runs). Such nodes stay in {@link #pending} and are re-asserted each tick. Translated nodes are
 * remembered so the language toggle can put the chat back to English ({@link #goEnglish()}) and back
 * to Chinese ({@link #goChinese()}).
 */
@Singleton
public class ChatHandler
{
	// Untagged messages are coloured by the engine per message type; baked glyphs must match.
	private static final int PUBLIC_OPAQUE_RGB = 0x0000ff;
	private static final int PUBLIC_TRANSPARENT_RGB = 0x9090ff;
	private static final int AUTOCHAT_OPAQUE_RGB = 0x2020ef;
	private static final int AUTOCHAT_TRANSPARENT_RGB = 0x4040ff;
	private static final int FRIENDSCHAT_OPAQUE_RGB = 0x7f0000;
	private static final int FRIENDSCHAT_TRANSPARENT_RGB = 0xef5050;
	private static final int CLANCHAT_RGB = 0x7f0000;
	private static final int GUESTCLAN_OPAQUE_RGB = 0x007a00;
	private static final int GUESTCLAN_TRANSPARENT_RGB = 0x00d300;
	private static final int GIMCHAT_RGB = 0x7f0000;
	private static final int DIDYOUKNOW_OPAQUE_RGB = 0x006600;
	private static final int DIDYOUKNOW_TRANSPARENT_RGB = 0xffff00;
	private static final int GAME_OPAQUE_RGB = 0x000000;
	private static final int GAME_TRANSPARENT_RGB = 0xffffff;
	private static final int CHAT_WIDTH_PX = 480; // approx chat line width, for wrapping
	private static final int TIMESTAMP_PAD_PX = 60;
	private static final long PENDING_TIMEOUT_MS = 15_000;
	private static final int PENDING_MAX = 96;
	private static final int TRACKED_MAX = 200;
	// RuneLite's BUILD_CHATBOX (216) is only a wrapper. Chat transmit and chatbox init invoke the
	// callback-owning rebuild proc directly, so lifecycle capture must follow the actual proc (84).
	private static final int CHATBOX_REBUILD_PROC = 84;

	@Inject
	private Client client;
	@Inject
	private Translator translator;
	@Inject
	private GlyphService glyph;
	@Inject
	private OsrscnConfig config;
	@Inject
	private com.osrscn.ToggleService toggle;

	private LongSupplier clock = System::currentTimeMillis;
	private long writeEpoch;
	private volatile RequestPermit publicRequests = newPlayerPermit(false);
	private volatile RequestPermit groupRequests = newPlayerPermit(true);
	private RequestPermit cleanedPublicRequests;
	private RequestPermit cleanedGroupRequests;
	private PlayerChatMode cleanedPublicMode;
	private PlayerChatMode cleanedGroupMode;

	private RequestPermit newPlayerPermit(boolean group)
	{
		return new RequestPermit(() -> toggle.isChineseEnabled()
				&& (group ? config.groupChatMode() : config.playerChatMode()) != PlayerChatMode.OFF);
	}

	/** Revoke both scopes for callers invalidating all player deliveries. */
	public synchronized void playerChatModeChanged()
	{
		publicRequests.revoke();
		groupRequests.revoke();
		publicRequests = newPlayerPermit(false);
		groupRequests = newPlayerPermit(true);
	}

	/** Revoke the changed scope synchronously before client-thread cleanup is queued. */
	public synchronized void playerChatModeChanged(String key)
	{
		if ("groupChatMode".equals(key))
		{
			groupRequests.revoke();
			groupRequests = newPlayerPermit(true);
		}
		else if ("playerChatMode".equals(key))
		{
			publicRequests.revoke();
			publicRequests = newPlayerPermit(false);
		}
	}

	private RequestPermit requestsFor(ChatMessageType type)
	{
		return isGroupType(type) ? groupRequests : publicRequests;
	}

	private PlayerChatMode modeFor(ChatMessageType type)
	{
		return isGroupType(type) ? config.groupChatMode() : config.playerChatMode();
	}

	private boolean playerModeCurrent(Generation generation)
	{
		return generation.playerMode == null || (generation.playerRequests == requestsFor(generation.type)
				&& generation.playerMode != PlayerChatMode.OFF && generation.playerMode == modeFor(generation.type));
	}

	private boolean authorized(Generation generation)
	{
		return toggle.isChineseEnabled() && playerModeCurrent(generation) && current(generation);
	}

	/** Client-thread cleanup only; a delayed cleanup cannot remove a newer mode's deliveries. */
	public void discardRevokedPlayerMessages()
	{
		RequestPermit publicPermit = publicRequests;
		RequestPermit groupPermit = groupRequests;
		PlayerChatMode publicMode = config.playerChatMode();
		PlayerChatMode groupMode = config.groupChatMode();
		if (publicPermit == cleanedPublicRequests && groupPermit == cleanedGroupRequests
				&& publicMode == cleanedPublicMode && groupMode == cleanedGroupMode) return;
		cleanedPublicRequests = publicPermit;
		cleanedGroupRequests = groupPermit;
		cleanedPublicMode = publicMode;
		cleanedGroupMode = groupMode;
		boolean changed = false;
		for (Tracked t : new ArrayList<>(tracked.values()))
		{
			if (playerModeCurrent(t.generation)) continue;
			if (current(t.generation))
			{
				t.generation.node.setValue(t.origValue);
				t.generation.writtenValue = t.origValue;
				if (current(t.generation)) t.generation.node.setRuneLiteFormatMessage(t.origRlfm);
				changed = true;
			}
			t.generation.active = false;
			tracked.remove(t.generation.node, t);
		}
		for (Pending p : pending.values())
		{
			if (!playerModeCurrent(p.generation))
			{
				p.generation.active = false;
				pending.remove(p.generation.node, p);
			}
		}
		for (Insert in : inserts.values())
		{
			if (!playerModeCurrent(in.generation))
			{
				in.generation.active = false;
				inserts.remove(in.generation.node, in);
			}
		}
		if (changed) client.refreshChat();
	}

	// nodes we still re-assert each tick (AI may land late, or a plugin may overwrite our text)
	private final Map<MessageNode, Pending> pending = new ConcurrentHashMap<>();
	// Only the current explicit language switch waits here. Pending plus waiting owns at most
	// TRACKED_MAX switch members; each tick rotates at most PENDING_MAX of those same references.
	private final Map<MessageNode, Pending> toggleWaiting = new LinkedHashMap<>();
	// INSERT mode: player-chat lines waiting to have a separate Chinese line added once translated
	private final Map<MessageNode, Insert> inserts = new ConcurrentHashMap<>();
	// every node we've translated, with its original text + how to re-translate it, for the toggle (LRU)
	private final Map<MessageNode, Tracked> tracked = new LinkedHashMap<MessageNode, Tracked>(16, 0.75f, true)
	{
		@Override
		protected boolean removeEldestEntry(Map.Entry<MessageNode, Tracked> e)
		{
			return size() > TRACKED_MAX;
		}
	};
	// Current BUILD_CHATBOX identity map. RuneLite's patched script emits one UID callback for each
	// accepted message, in the same order as the contiguous Chatbox.LINE0..LINE499 hit boxes.
	private final Map<Generation, Integer> chatLines = new IdentityHashMap<>();
	private final List<Generation> buildingChatNodes = new ArrayList<>();
	private boolean chatBuildActive;
	private int chatBuildIndex;

	/** True only for the script that owns the chatMessageBuilding callbacks. */
	public static boolean isChatBuildScript(int scriptId)
	{
		return scriptId == CHATBOX_REBUILD_PROC;
	}

	/** A native message lifetime, not the reusable ChatLineBuffer object that happens to hold it. */
	private final class Generation
	{
		final MessageNode node;
		final int id;
		final ChatMessageType type;
		final String name;
		final String sender;
		final String originalValue;
		final PlayerChatMode playerMode;
		final RequestPermit playerRequests;
		final RequestPermit renderPermit;
		String writtenValue;
		boolean active = true;

		Generation(MessageNode node)
		{
			this.node = node;
			type = node.getType();
			playerMode = isPlayerType(type) ? modeFor(type) : null;
			playerRequests = requestsFor(type);
			renderPermit = playerMode == null ? null
					: new RequestPermit(playerRequests, () -> modeFor(type) == playerMode);
			id = node.getId();
			name = node.getName();
			sender = node.getSender();
			originalValue = node.getValue();
			writtenValue = originalValue;
		}
	}

	private static boolean isPlayerType(ChatMessageType type)
	{
		return type == ChatMessageType.PUBLICCHAT || type == ChatMessageType.MODCHAT
				|| type == ChatMessageType.AUTOTYPER || type == ChatMessageType.MODAUTOTYPER
				|| type == ChatMessageType.CLAN_CHAT || type == ChatMessageType.CLAN_GUEST_CHAT
				|| type == ChatMessageType.CLAN_GIM_CHAT || type == ChatMessageType.FRIENDSCHAT;
	}

	private static boolean isGroupType(ChatMessageType type)
	{
		return type == ChatMessageType.FRIENDSCHAT || type == ChatMessageType.CLAN_CHAT
				|| type == ChatMessageType.CLAN_GUEST_CHAT || type == ChatMessageType.CLAN_GIM_CHAT;
	}

	private boolean current(Generation generation)
	{
		MessageNode node = generation.node;
		// The current client publishes into getMessages before ChatMessage. Removal from chat history
		// unlinks that same table entry without changing the node's ID or body.
		boolean matches = generation.active && node.getId() == generation.id
				&& client.getMessages() != null && client.getMessages().get(generation.id) == node
				&& node.getType() == generation.type && Objects.equals(node.getName(), generation.name)
				&& Objects.equals(node.getSender(), generation.sender)
				&& (Objects.equals(node.getValue(), generation.originalValue)
						|| Objects.equals(node.getValue(), generation.writtenValue));
		if (!matches) generation.active = false;
		return matches;
	}

	private void forget(MessageNode node)
	{
		Tracked t = tracked.remove(node);
		Pending p = pending.remove(node);
		toggleWaiting.remove(node);
		Insert in = inserts.remove(node);
		if (t != null) t.generation.active = false;
		if (p != null) p.generation.active = false;
		if (in != null) in.generation.active = false;
	}

	private static final class Pending
	{
		final Generation generation;
		final String english;
		final int color;
		final int maxChars;
		final long deadline;
		final boolean persist;
		final long toggleEpoch;

		Pending(Generation generation, String english, int color, int maxChars, long deadline, boolean persist)
		{
			this(generation, english, color, maxChars, deadline, persist, -1);
		}

		Pending(Generation generation, String english, int color, int maxChars, long deadline, boolean persist,
				long toggleEpoch)
		{
			this.toggleEpoch = toggleEpoch;
			this.generation = generation;
			this.english = english;
			this.color = color;
			this.maxChars = maxChars;
			this.deadline = deadline;
			this.persist = persist;
		}
	}

	private static final class Tracked
	{
		final Generation generation;
		final String origValue;
		final String origRlfm;
		final String english;
		final int color;
		final boolean persist;
		final boolean nativeHoverColor;
		int appliedColor;
		String renderedText;
		boolean rendered;
		boolean complete;
		long retryDeadline = -1;

		Tracked(Generation generation, String origValue, String origRlfm, String english, int color, boolean persist,
				boolean nativeHoverColor)
		{
			this.generation = generation;
			this.origValue = origValue;
			this.origRlfm = origRlfm;
			this.english = english;
			this.color = color;
			this.persist = persist;
			this.nativeHoverColor = nativeHoverColor;
			this.appliedColor = color;
		}
	}

	private static final class Insert
	{
		final Generation generation;
		final String name;
		final String english;
		final int color;
		final long deadline;

		Insert(Generation generation, String name, String english, int color, long deadline)
		{
			this.generation = generation;
			this.name = name;
			this.english = english;
			this.color = color;
			this.deadline = deadline;
		}
	}

	/** Start a current-client BUILD_CHATBOX pass. The callback order is the native line order. */
	public void beginChatBuild()
	{
		buildingChatNodes.clear();
		chatBuildIndex = 0;
		chatBuildActive = true;
	}

	/** Capture one RuneLite chatMessageBuilding UID without comparing message text. */
	public void captureChatLine(int[] intStack, int intStackSize)
	{
		if (!chatBuildActive)
		{
			return;
		}
		chatBuildIndex++;
		int uid = messageUid(intStack, intStackSize);
		MessageNode node = uid < 0 || client.getMessages() == null ? null : client.getMessages().get(uid);
		Tracked t = node == null ? null : tracked.get(node);
		if (chatBuildIndex <= 500)
		{
			buildingChatNodes.add(t != null && t.generation.id == uid && current(t.generation)
					? t.generation : null);
		}
	}

	/** Publish only a complete build, so a partial or unrelated callback can never cross-wire rows. */
	public void finishChatBuild()
	{
		if (!chatBuildActive)
		{
			return;
		}
		chatBuildActive = false;
		chatLines.clear();
		int builtRows = 0;
		for (int i = 0; i < 500; i++)
		{
			Widget line = client.getWidget(chatLineId(i));
			if (line == null || line.getWidth() <= 0 || line.getHeight() <= 0)
			{
				break;
			}
			builtRows++;
		}
		// The current script may prepend exactly one non-message row for the public-chat text filter.
		// Infer that offset from native row allocation, never from row text.
		int offset = builtRows - buildingChatNodes.size();
		if (offset == 0 || offset == 1)
		{
			for (int i = 0; i < buildingChatNodes.size(); i++)
			{
				Generation generation = buildingChatNodes.get(i);
				if (generation != null && current(generation))
				{
					chatLines.put(generation, i + offset);
				}
			}
		}
		buildingChatNodes.clear();
	}

	/**
	 * Match char-image colour to the visible native chat row. Chatbox.LINE* is the row hit box; the
	 * actual text and colour live in SCROLLAREA's {@code lineIndex * 4} dynamic child.
	 */
	public void refreshNativeColors()
	{
		discardRevokedPlayerMessages();
		if (chatLines.isEmpty())
		{
			return;
		}
		Widget scroll = client.getWidget(InterfaceID.Chatbox.SCROLLAREA);
		Widget[] children = scroll == null ? null : scroll.getDynamicChildren();
		Point mouse = client.getMouseCanvasPosition();
		if (children == null || mouse == null)
		{
			return;
		}
		boolean changed = false;
		for (Map.Entry<Generation, Integer> e : new ArrayList<>(chatLines.entrySet()))
		{
			Generation generation = e.getKey();
			Tracked t = tracked.get(generation.node);
			int lineIndex = e.getValue();
			int textIndex = lineIndex * 4;
			if (t == null || t.generation != generation || !current(generation)
					|| textIndex < 0 || textIndex >= children.length)
			{
				continue;
			}
			Widget line = client.getWidget(chatLineId(lineIndex));
			Widget text = children[textIndex];
			if (line == null || text == null || line.isHidden() || text.isHidden())
			{
				continue;
			}
			int nativeColor = nativeLineColor(line.getBounds(), mouse, text.getTextColor(),
					t.color, t.appliedColor, t.nativeHoverColor);
			// Capacity-blocked partials use the existing visible-row pass to regain a retry slot.
			// Admission preserves the original deadline; translation remains on the tick path.
			if (!t.complete)
			{
				addPending(generation.node, t.english, nativeColor, t.persist);
			}
			Map<MessageNode, Pending> queue = pending.containsKey(generation.node) ? pending : toggleWaiting;
			Pending p = queue.get(generation.node);
			if (p != null && p.generation == generation && p.color != nativeColor)
			{
				queue.put(generation.node, new Pending(generation, p.english, nativeColor, p.maxChars,
						p.deadline, p.persist, p.toggleEpoch));
			}
			if (needsNativeRerender(t.appliedColor, nativeColor, t.rendered))
			{
				changed |= renderTracked(generation.node, t, nativeColor);
			}
		}
		if (changed)
		{
			client.refreshChat();
		}
	}

	private static int messageUid(int[] intStack, int intStackSize)
	{
		return intStack == null || intStackSize <= 0 || intStackSize > intStack.length
				? -1 : intStack[intStackSize - 1];
	}

	private static int chatLineId(int lineIndex)
	{
		return lineIndex < 0 || lineIndex > InterfaceID.Chatbox.LINE499 - InterfaceID.Chatbox.LINE0
				? -1 : InterfaceID.Chatbox.LINE0 + lineIndex;
	}

	private static boolean isChatLineId(int widgetId)
	{
		return widgetId >= InterfaceID.Chatbox.LINE0 && widgetId <= InterfaceID.Chatbox.LINE499;
	}

	private static int nativeLineColor(Rectangle bounds, Point mouse, int widgetColor,
			int fallbackColor, int appliedColor, boolean nativeHoverColor)
	{
		int fallback = fallbackColor & 0xffffff;
		if (!nativeHoverColor || bounds == null || mouse == null
				|| !bounds.contains(mouse.getX(), mouse.getY()))
		{
			return fallback;
		}
		int widget = widgetColor & 0xffffff;
		if (widget != fallback)
		{
			return widget;
		}
		// Writing new char-image tags calls refreshChat(), which rebuilds the native text child at its
		// base colour even though the pointer never left the row. Keep the last confirmed hover colour
		// latched until the bounds test observes a real mouse leave.
		int applied = appliedColor & 0xffffff;
		return applied != fallback ? applied : widget;
	}

	private static boolean needsNativeRerender(int appliedColor, int nativeColor, boolean rendered)
	{
		return !rendered || (appliedColor & 0xffffff) != (nativeColor & 0xffffff);
	}

	public void handle(ChatMessage event)
	{
		MessageNode node = event.getMessageNode();
		if (node == null) return;
		// A ChatMessage is a new delivery even when the buffer reuses an object, ID, or body.
		// Do this before OFF/disabled/unsupported/empty paths; those must not retain older work.
		forget(node);
		discardRevokedPlayerMessages();
		if (event.getType() != node.getType()) return;
		switch (event.getType())
		{
			case PUBLICCHAT:
			case MODCHAT:
			case AUTOTYPER:
			case MODAUTOTYPER:
			case CLAN_CHAT:
			case CLAN_GUEST_CHAT:
			case CLAN_GIM_CHAT:
			case FRIENDSCHAT:
				if (!isGroupType(event.getType()) || modeFor(event.getType()) != PlayerChatMode.OFF)
				{
					handlePlayer(node, event.getType());
				}
				break;
			case GAMEMESSAGE:
			case ENGINE:
			case SPAM:
			case WELCOME:
			case BROADCAST:
			case DIDYOUKNOW:
			case LEVELUPMESSAGE:
			case NPC_EXAMINE:
			case ITEM_EXAMINE:
			case OBJECT_EXAMINE:
				if (config.translateGameMessages())
				{
					handleGame(node, event.getType());
				}
				break;
			default:
				break; // private chat, channel system notices, dialogue: leave alone
		}
	}

	private void handlePlayer(MessageNode node, ChatMessageType type)
	{
		if (node == null || !toggle.isChineseEnabled())
		{
			return;
		}
		String value = node.getValue();
		if (value == null || value.isEmpty() || value.contains("<img="))
		{
			return; // empty or already our char-image translation
		}
		int color = playerChatColor(type);
		switch (modeFor(type))
		{
			case INLINE:
				// player chat: memory-only (slang rarely recurs, don't bloat the disk cache)
				Tracked t = translateNode(node, Tags.stripCol(value), color, false, false);
				if (t != null && current(t.generation) && !isTranslated(node))
				{
					recolour(t.generation, value, color); // keep native-colour English until translation is ready
				}
				break;
			case INSERT:
				// leave the English line as the game drew it; add a separate Chinese line below it
				queueInsert(node, value, color);
				break;
			case OFF:
			default:
				recolour(new Generation(node), value, color); // not tracked: preserve the channel's native colour
				break;
		}
	}

	private long pendingTimeoutMs()
	{
		// Capture once when work is admitted. Ticks and colour refreshes never renew this budget.
		return config.useLocalAi() && config.aiBackend() == AiBackend.OLLAMA
				? AiTranslator.OLLAMA_REQUEST_TIMEOUT_MS + PENDING_TIMEOUT_MS : PENDING_TIMEOUT_MS;
	}

	/** INSERT mode: remember this player-chat line so {@link #tick()} can add a Chinese line for it. */
	private void queueInsert(MessageNode node, String value, int color)
	{
		if (inserts.containsKey(node) || inserts.size() >= PENDING_MAX)
		{
			return;
		}
		String name = node.getName();
		Generation generation = new Generation(node);
		if (!current(generation)) return;
		inserts.put(node, new Insert(generation, name == null ? "" : name, Tags.stripCol(value), color,
				clock.getAsLong() + pendingTimeoutMs()));
		tryInsert(node); // table hits land immediately; AI misses retry next tick
	}

	/**
	 * Add a "OSRS_CN: Name: 你好" line for one queued player message once its translation is ready.
	 * Returns true when handled (inserted, or nothing translatable) so the caller can stop retrying.
	 */
	private boolean tryInsert(MessageNode node)
	{
		Insert in = inserts.get(node);
		if (in == null)
		{
			return true;
		}
		if (!authorized(in.generation))
		{
			inserts.remove(node, in);
			return true;
		}
		long epoch = writeEpoch;
		Translator.Rendered r = renderChat(in.generation, in.english, in.color, chatMaxChars(), false);
		if (epoch != writeEpoch || !authorized(in.generation) || inserts.get(node) != in)
		{
			inserts.remove(node, in);
			return true;
		}
		if (r == null)
		{
			return false; // not translated yet
		}
		inserts.remove(node, in);
		String prefix = "OSRS_CN: " + (in.name.isEmpty() ? "" : in.name + ": ");
		client.addChatMessage(ChatMessageType.CONSOLE, "", prefix + r.text, null);
		return true;
	}

	private void handleGame(MessageNode node, ChatMessageType type)
	{
		if (node == null)
		{
			return;
		}
		String value = node.getValue();
		if (value == null || value.isEmpty() || value.contains("<img="))
		{
			return; // empty, our own message, or already translated
		}
		int fallback = engineDefaultColor(type);
		boolean nativeHoverColor = hasNativeHoverColor(type);
		if (!toggle.isChineseEnabled())
		{
			// English mode: track only, so goChinese() can translate messages that arrived
			// while the toggle was off.
			track(node, value, Tags.firstColor(value, fallback), true, nativeHoverColor);
			return;
		}
		translateNode(node, value, Tags.firstColor(value, fallback), true, nativeHoverColor);
	}

	private static boolean hasNativeHoverColor(ChatMessageType type)
	{
		return type == ChatMessageType.LEVELUPMESSAGE || type == ChatMessageType.BROADCAST;
	}

	/**
	 * Colour the engine would paint an untagged message of this type. Baked glyph images can't
	 * inherit the engine's per-type colouring, so we have to reproduce it. Broadcast and
	 * "Did you know?" have separate current-client colour settings; everything else is black on the
	 * opaque chat box and white on the transparent one.
	 */
	private int engineDefaultColor(ChatMessageType type)
	{
		boolean transparent = client.getVarbitValue(net.runelite.api.gameval.VarbitID.CHATBOX_TRANSPARENCY) != 0;
		if (type == ChatMessageType.DIDYOUKNOW)
		{
			return configuredChatColor(transparent,
					net.runelite.api.gameval.VarPlayerID.OPTION_CHAT_COLOUR_DIDYOUKNOW_OPAQUE,
					net.runelite.api.gameval.VarPlayerID.OPTION_CHAT_COLOUR_DIDYOUKNOW_TRANSPARENT,
					DIDYOUKNOW_OPAQUE_RGB, DIDYOUKNOW_TRANSPARENT_RGB);
		}
		if (type == ChatMessageType.BROADCAST)
		{
			return configuredChatColor(transparent,
					net.runelite.api.gameval.VarPlayerID.OPTION_CHAT_COLOUR_BROADCAST_OPAQUE,
					net.runelite.api.gameval.VarPlayerID.OPTION_CHAT_COLOUR_BROADCAST_TRANSPARENT,
					GAME_OPAQUE_RGB, GAME_TRANSPARENT_RGB);
		}
		return transparent ? GAME_TRANSPARENT_RGB : GAME_OPAQUE_RGB;
	}

	private int playerChatColor(ChatMessageType type)
	{
		boolean transparent = client.getVarbitValue(net.runelite.api.gameval.VarbitID.CHATBOX_TRANSPARENCY) != 0;
		switch (type)
		{
			case AUTOTYPER:
			case MODAUTOTYPER:
				return configuredChatColor(transparent,
						net.runelite.api.gameval.VarPlayerID.OPTION_CHAT_COLOUR_AUTOCHAT_OPAQUE,
						net.runelite.api.gameval.VarPlayerID.OPTION_CHAT_COLOUR_AUTOCHAT_TRANSPARENT,
						AUTOCHAT_OPAQUE_RGB, AUTOCHAT_TRANSPARENT_RGB);
			case FRIENDSCHAT:
				return configuredChatColor(transparent,
						net.runelite.api.gameval.VarPlayerID.OPTION_CHAT_COLOUR_FRIENDSCHAT_OPAQUE,
						net.runelite.api.gameval.VarPlayerID.OPTION_CHAT_COLOUR_FRIENDSCHAT_TRANSPARENT,
						FRIENDSCHAT_OPAQUE_RGB, FRIENDSCHAT_TRANSPARENT_RGB);
			case CLAN_CHAT:
				return configuredChatColor(transparent,
						net.runelite.api.gameval.VarPlayerID.OPTION_CHAT_COLOUR_CLANCHAT_OPAQUE,
						net.runelite.api.gameval.VarPlayerID.OPTION_CHAT_COLOUR_CLANCHAT_TRANSPARENT,
						CLANCHAT_RGB, CLANCHAT_RGB);
			case CLAN_GUEST_CHAT:
				return configuredChatColor(transparent,
						net.runelite.api.gameval.VarPlayerID.OPTION_CHAT_COLOUR_GUESTCLAN_OPAQUE,
						net.runelite.api.gameval.VarPlayerID.OPTION_CHAT_COLOUR_GUESTCLAN_TRANSPARENT,
						GUESTCLAN_OPAQUE_RGB, GUESTCLAN_TRANSPARENT_RGB);
			case CLAN_GIM_CHAT:
				return configuredChatColor(transparent,
						net.runelite.api.gameval.VarPlayerID.OPTION_CHAT_COLOUR_GIMCHAT_OPAQUE,
						net.runelite.api.gameval.VarPlayerID.OPTION_CHAT_COLOUR_GIMCHAT_TRANSPARENT,
						GIMCHAT_RGB, GIMCHAT_RGB);
			case PUBLICCHAT:
			case MODCHAT:
			default:
				return configuredChatColor(transparent,
						net.runelite.api.gameval.VarPlayerID.OPTION_CHAT_COLOUR_PUBLIC_OPAQUE,
						net.runelite.api.gameval.VarPlayerID.OPTION_CHAT_COLOUR_PUBLIC_TRANSPARENT,
						PUBLIC_OPAQUE_RGB, PUBLIC_TRANSPARENT_RGB);
		}
	}

	private int configuredChatColor(boolean transparent, int opaqueVarp, int transparentVarp,
			int opaqueDefault, int transparentDefault)
	{
		int setting = client.getVarpValue(transparent ? transparentVarp : opaqueVarp);
		return setting > 0 ? (setting - 1) & 0xffffff
				: (transparent ? transparentDefault : opaqueDefault);
	}

	private Translator.Rendered renderChat(Generation generation, String text, int color, int maxChars, boolean persist)
	{
		if (!authorized(generation)) return null;
		return generation.playerMode == null
				? translator.renderChat(text, color, maxChars, glyph.uiSize(), true, persist)
				: translator.renderChat(text, color, maxChars, glyph.uiSize(), true, false, generation.renderPermit);
	}

	private Tracked translateNode(MessageNode node, String english, int color, boolean persist,
			boolean nativeHoverColor)
	{
		Tracked t = track(node, english, color, persist, nativeHoverColor);
		if (t == null || !authorized(t.generation)) return null;
		if (t.retryDeadline < 0)
		{
			t.retryDeadline = clock.getAsLong() + pendingTimeoutMs();
		}
		long epoch = writeEpoch;
		Translator.Rendered r = renderChat(t.generation, english, color, chatMaxChars(), persist);
		if (epoch != writeEpoch || !authorized(t.generation)) return null;
		if (r != null)
		{
			boolean changed = write(t.generation, r.text, epoch);
			markRendered(t, color, r);
			if (changed)
			{
				client.refreshChat();
			}
		}
		addPending(node, english, color, persist);
		return t;
	}

	private boolean renderTracked(MessageNode node, Tracked t, int color)
	{
		if (!authorized(t.generation)) return false;
		long epoch = writeEpoch;
		boolean retryActive = t.retryDeadline >= 0 && clock.getAsLong() <= t.retryDeadline;
		if (!retryActive && !t.rendered)
		{
			return false; // Only a new message or the language toggle may start another retry window.
		}
		// A new colour may need glyph uploads after translation retries have ended. Read existing
		// translations without dispatching AI or collecting missing text; never renew the deadline.
		Translator.Rendered r = retryActive
				? renderChat(t.generation, t.english, color, chatMaxChars(), t.persist)
				: translator.renderChatCached(t.english, color, chatMaxChars(), glyph.uiSize());
		if (epoch != writeEpoch || !authorized(t.generation)) return false;
		if (r == null)
		{
			if (retryActive)
			{
				addPending(node, t.english, color, t.persist);
			}
			return false;
		}
		boolean changed = write(t.generation, r.text, epoch);
		markRendered(t, color, r);
		return changed;
	}

	/** Retry/re-assert chat translations; call each tick. */
	public void tick()
	{
		discardRevokedPlayerMessages();
		if (!toggle.isChineseEnabled()) return;
		long epoch = writeEpoch;
		long now = clock.getAsLong();
		if (!inserts.isEmpty())
		{
			for (Iterator<Map.Entry<MessageNode, Insert>> it = inserts.entrySet().iterator(); it.hasNext(); )
			{
				Map.Entry<MessageNode, Insert> e = it.next();
				if (!current(e.getValue().generation) || now > e.getValue().deadline)
				{
					it.remove();
				}
				else
				{
					tryInsert(e.getKey()); // removes itself from the map on success
					if (epoch != writeEpoch) return;
				}
			}
		}
		fillTogglePending();
		if (pending.isEmpty())
		{
			return;
		}
		boolean changed = false;
		// A fixed batch prevents newly admitted or reentrant switch work from running twice this tick.
		List<Map.Entry<MessageNode, Pending>> batch = new ArrayList<>(pending.entrySet());
		for (Map.Entry<MessageNode, Pending> e : batch)
		{
			if (epoch != writeEpoch) return;
			Pending p = e.getValue();
			if (pending.get(e.getKey()) != p) continue;
			if (!authorized(p.generation) || now > p.deadline)
			{
				pending.remove(e.getKey(), p);
				continue;
			}
			Tracked t = tracked.get(e.getKey());
			if (t != null && t.generation != p.generation) t = null;
			if (p.toggleEpoch >= 0 && (p.toggleEpoch != epoch || t == null))
			{
				pending.remove(e.getKey(), p);
				continue;
			}
			if (t != null && t.rendered && t.complete && t.appliedColor == p.color
					&& t.renderedText != null && t.renderedText.equals(displayed(e.getKey())))
			{
				continue;
			}
			Translator.Rendered r = renderChat(p.generation, p.english, p.color, p.maxChars, p.persist);
			if (epoch != writeEpoch) return;
			if (!authorized(p.generation) || pending.get(e.getKey()) != p) continue;
			if (r != null)
			{
				if (write(p.generation, r.text, epoch))
				{
					changed = true;
				}
				if (epoch != writeEpoch) return;
				if (t != null)
				{
					markRendered(t, p.color, r);
				}
			}
		}
		// Complete results keep their re-assertion opportunity, but cannot monopolize all 96 slots.
		// Natural retries retain their original queue membership and deadline.
		for (Map.Entry<MessageNode, Pending> e : batch)
		{
			Pending p = e.getValue();
			if (p.toggleEpoch == epoch && pending.remove(e.getKey(), p))
			{
				toggleWaiting.put(e.getKey(), p);
			}
		}
		fillTogglePending();
		if (changed)
		{
			client.refreshChat();
		}
	}

	/** Put the original English back on every translated chat line (switch to English). */
	public void goEnglish()
	{
		discardRevokedPlayerMessages();
		writeEpoch++;
		pending.clear();
		toggleWaiting.clear();
		for (Insert in : inserts.values()) in.generation.active = false;
		inserts.clear(); // stop adding new Chinese lines; already-inserted lines stay in chat history
		boolean changed = false;
		for (Map.Entry<MessageNode, Tracked> e : new ArrayList<>(tracked.entrySet()))
		{
			Tracked t = e.getValue();
			if (!current(t.generation)) continue;
			e.getKey().setValue(t.origValue);
			t.generation.writtenValue = t.origValue;
			if (!current(t.generation)) continue;
			e.getKey().setRuneLiteFormatMessage(t.origRlfm);
			t.retryDeadline = -1;
			t.rendered = false;
			t.complete = false;
			t.renderedText = null;
			changed = true;
		}
		if (changed)
		{
			client.refreshChat();
		}
	}

	/** Re-translate every tracked chat line (switch back to Chinese). */
	public void goChinese()
	{
		discardRevokedPlayerMessages();
		writeEpoch++;
		long deadline = clock.getAsLong() + pendingTimeoutMs();
		pending.clear();
		toggleWaiting.clear();
		for (Map.Entry<MessageNode, Tracked> e : tracked.entrySet())
		{
			Tracked t = e.getValue();
			if (!current(t.generation)) continue;
			t.retryDeadline = deadline;
			toggleWaiting.put(e.getKey(), new Pending(t.generation, t.english, t.color, chatMaxChars(),
					deadline, t.persist, writeEpoch));
		}
		tick();
	}

	/** Forget everything without reverting (plugin shutdown). */
	public void clear()
	{
		writeEpoch++;
		for (Tracked t : tracked.values()) t.generation.active = false;
		for (Pending p : pending.values()) p.generation.active = false;
		for (Insert in : inserts.values()) in.generation.active = false;
		pending.clear();
		toggleWaiting.clear();
		inserts.clear();
		tracked.clear();
		chatLines.clear();
		buildingChatNodes.clear();
		chatBuildActive = false;
		chatBuildIndex = 0;
	}

	private Tracked track(MessageNode node, String english, int color, boolean persist,
			boolean nativeHoverColor)
	{
		Tracked t = tracked.get(node);
		if (t != null && !current(t.generation))
		{
			forget(node);
			t = null;
		}
		if (t == null)
		{
			Generation generation = new Generation(node);
			if (!current(generation)) return null;
			t = new Tracked(generation, node.getValue(), node.getRuneLiteFormatMessage(), english, color, persist,
					nativeHoverColor);
			tracked.put(node, t);
		}
		return t;
	}

	private static void markRendered(Tracked t, int color, Translator.Rendered result)
	{
		t.appliedColor = color & 0xffffff;
		t.renderedText = result.text;
		t.complete = result.complete;
		t.rendered = true;
	}

	/** Write our text to the node's value and (if it has one) its rlfm; returns true if it changed. */
	private boolean write(Generation generation, String text, long epoch)
	{
		if (epoch != writeEpoch || !authorized(generation)) return false;
		MessageNode node = generation.node;
		if (text.equals(displayed(node)))
		{
			return false;
		}
		node.setValue(text);
		generation.writtenValue = text;
		if (epoch == writeEpoch && authorized(generation) && node.getRuneLiteFormatMessage() != null)
		{
			node.setRuneLiteFormatMessage(text);
		}
		return true;
	}

	private void recolour(Generation generation, String value, int color)
	{
		if (!current(generation)) return;
		MessageNode node = generation.node;
		String wrapped = String.format("<col=%06x>%s</col>", color & 0xffffff, Tags.stripCol(value));
		if (wrapped.equals(node.getValue()))
		{
			return;
		}
		node.setValue(wrapped);
		generation.writtenValue = wrapped;
		client.refreshChat();
	}

	private boolean isTranslated(MessageNode node)
	{
		String shown = displayed(node);
		return shown != null && shown.contains("<img=");
	}

	private static String displayed(MessageNode node)
	{
		String rlfm = node.getRuneLiteFormatMessage();
		return rlfm != null ? rlfm : node.getValue();
	}

	private void addPending(MessageNode node, String english, int color, boolean persist)
	{
		// Reclaimed switch slots belong to the FIFO before new natural or visible-row admissions.
		fillTogglePending();
		Tracked t = tracked.get(node);
		if (t == null || !authorized(t.generation) || t.retryDeadline < 0 || clock.getAsLong() > t.retryDeadline)
		{
			return;
		}
		Pending current = pending.get(node);
		if (current != null)
		{
			if (current.color != color)
			{
				pending.put(node, new Pending(t.generation, current.english, color, current.maxChars,
						current.deadline, current.persist, current.toggleEpoch));
			}
			return;
		}
		Pending waiting = toggleWaiting.get(node);
		if (waiting != null)
		{
			if (waiting.color != color)
			{
				toggleWaiting.put(node, new Pending(t.generation, waiting.english, color, waiting.maxChars,
						waiting.deadline, waiting.persist, waiting.toggleEpoch));
			}
			return;
		}
		if (pending.size() >= PENDING_MAX)
		{
			return;
		}
		pending.put(node, new Pending(t.generation, english, color, chatMaxChars(), t.retryDeadline, persist));
	}

	private void fillTogglePending()
	{
		if (!toggle.isChineseEnabled()) return;
		long now = clock.getAsLong();
		for (Iterator<Map.Entry<MessageNode, Pending>> it = toggleWaiting.entrySet().iterator();
				it.hasNext() && pending.size() < PENDING_MAX; )
		{
			Map.Entry<MessageNode, Pending> e = it.next();
			Pending p = e.getValue();
			it.remove();
			Tracked t = tracked.get(e.getKey());
			if (p.toggleEpoch != writeEpoch || now > p.deadline || t == null
					|| t.generation != p.generation || !authorized(p.generation)) continue;
			pending.put(e.getKey(), p);
		}
	}

	private int chatMaxChars()
	{
		return Math.max(8, glyph.wrapChars(CHAT_WIDTH_PX - TIMESTAMP_PAD_PX, glyph.uiSize()));
	}

}
