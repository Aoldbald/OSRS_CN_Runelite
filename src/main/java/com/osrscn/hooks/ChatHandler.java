package com.osrscn.hooks;

import com.osrscn.OsrscnConfig;
import com.osrscn.glyph.GlyphService;
import com.osrscn.text.Tags;
import com.osrscn.translate.Translator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import com.osrscn.PlayerChatMode;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.MessageNode;
import net.runelite.api.events.ChatMessage;

/**
 * Owns the scrolling chat box, which the interface walk deliberately skips (chat is message-node
 * driven, not widget driven).
 *
 * <ul>
 *   <li><b>Player public chat</b>: names are never translated. {@link PlayerChatMode#OFF} recolours to
 *       OSRS blue; {@link PlayerChatMode#INLINE} translates the content in place (blue);
 *       {@link PlayerChatMode#INSERT} leaves the English line and adds a separate Chinese line below
 *       it (e.g. {@code "OSRS_CN: Name: 你好"}).</li>
 *   <li><b>Game messages</b> (level-up / quest / system / examine description): translated to
 *       char-images, number-templated, with AI fallback.</li>
 *   <li>Everything else (private / clan / friends chat): left as the game drew it.</li>
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
	private static final String BLUE_HEX = "0000ff"; // classic OSRS public-chat blue
	private static final int BLUE_RGB = 0x0000ff;
	private static final int GAME_DEFAULT_RGB = 0x000000; // fallback; most game messages carry a <col>
	private static final int CHAT_WIDTH_PX = 480; // approx chat line width, for wrapping
	private static final int TIMESTAMP_PAD_PX = 60;
	private static final long PENDING_TIMEOUT_MS = 15_000;
	private static final int PENDING_MAX = 96;
	private static final int TRACKED_MAX = 200;

	@Inject
	private Client client;
	@Inject
	private Translator translator;
	@Inject
	private GlyphService glyph;
	@Inject
	private OsrscnConfig config;

	// nodes we still re-assert each tick (AI may land late, or a plugin may overwrite our text)
	private final Map<MessageNode, Pending> pending = new ConcurrentHashMap<>();
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

	private static final class Pending
	{
		final String english;
		final int color;
		final int maxChars;
		final long deadline;
		final boolean persist;

		Pending(String english, int color, int maxChars, long deadline, boolean persist)
		{
			this.english = english;
			this.color = color;
			this.maxChars = maxChars;
			this.deadline = deadline;
			this.persist = persist;
		}
	}

	private static final class Tracked
	{
		final String origValue;
		final String origRlfm;
		final String english;
		final int color;
		final boolean persist;

		Tracked(String origValue, String origRlfm, String english, int color, boolean persist)
		{
			this.origValue = origValue;
			this.origRlfm = origRlfm;
			this.english = english;
			this.color = color;
			this.persist = persist;
		}
	}

	private static final class Insert
	{
		final String name;
		final String english;
		final long deadline;

		Insert(String name, String english, long deadline)
		{
			this.name = name;
			this.english = english;
			this.deadline = deadline;
		}
	}

	public void handle(ChatMessage event)
	{
		MessageNode node = event.getMessageNode();
		switch (event.getType())
		{
			case PUBLICCHAT:
			case MODCHAT:
			case AUTOTYPER:
			case MODAUTOTYPER:
				handlePublic(node);
				break;
			case GAMEMESSAGE:
			case ENGINE:
			case SPAM:
			case WELCOME:
			case NPC_EXAMINE:
			case ITEM_EXAMINE:
			case OBJECT_EXAMINE:
				if (config.translateGameMessages())
				{
					handleGame(node);
				}
				break;
			default:
				break; // private/clan/friends chat, dialogue: leave alone
		}
	}

	private void handlePublic(MessageNode node)
	{
		if (node == null)
		{
			return;
		}
		String value = node.getValue();
		if (value == null || value.isEmpty() || value.contains("<img="))
		{
			return; // empty or already our char-image translation
		}
		switch (config.playerChatMode())
		{
			case INLINE:
				// player chat: memory-only (slang rarely recurs, don't bloat the disk cache)
				translateNode(node, Tags.stripCol(value), BLUE_RGB, false);
				if (!isTranslated(node))
				{
					recolour(node, value); // show blue English until the translation is ready
				}
				break;
			case INSERT:
				// leave the English line as the game drew it; add a separate Chinese line below it
				queueInsert(node, value);
				break;
			case OFF:
			default:
				recolour(node, value); // not tracked: blue is a fine chat colour in English mode too
				break;
		}
	}

	/** INSERT mode: remember this player-chat line so {@link #tick()} can add a Chinese line for it. */
	private void queueInsert(MessageNode node, String value)
	{
		if (inserts.containsKey(node) || inserts.size() >= PENDING_MAX)
		{
			return;
		}
		String name = node.getName();
		inserts.put(node, new Insert(name == null ? "" : name, Tags.stripCol(value),
				System.currentTimeMillis() + PENDING_TIMEOUT_MS));
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
		Translator.Rendered r = translator.renderChat(in.english, BLUE_RGB, chatMaxChars(), glyph.uiSize(), true, false);
		if (r == null)
		{
			return false; // not translated yet
		}
		inserts.remove(node);
		String prefix = "OSRS_CN: " + (in.name.isEmpty() ? "" : in.name + ": ");
		client.addChatMessage(ChatMessageType.CONSOLE, "", prefix + r.text, null);
		return true;
	}

	private void handleGame(MessageNode node)
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
		translateNode(node, value, Tags.firstColor(value, GAME_DEFAULT_RGB), true);
	}

	private void translateNode(MessageNode node, String english, int color, boolean persist)
	{
		track(node, english, color, persist);
		Translator.Rendered r = translator.renderChat(english, color, chatMaxChars(), glyph.uiSize(), true, persist);
		if (r != null && write(node, r.text))
		{
			client.refreshChat();
		}
		addPending(node, english, color, persist);
	}

	/** Retry/re-assert chat translations; call each tick. */
	public void tick()
	{
		long now = System.currentTimeMillis();
		if (!inserts.isEmpty())
		{
			for (Iterator<Map.Entry<MessageNode, Insert>> it = inserts.entrySet().iterator(); it.hasNext(); )
			{
				Map.Entry<MessageNode, Insert> e = it.next();
				if (now > e.getValue().deadline)
				{
					it.remove();
				}
				else
				{
					tryInsert(e.getKey()); // removes itself from the map on success
				}
			}
		}
		if (pending.isEmpty())
		{
			return;
		}
		boolean changed = false;
		for (Iterator<Map.Entry<MessageNode, Pending>> it = pending.entrySet().iterator(); it.hasNext(); )
		{
			Map.Entry<MessageNode, Pending> e = it.next();
			Pending p = e.getValue();
			if (now > p.deadline)
			{
				it.remove();
				continue;
			}
			Translator.Rendered r = translator.renderChat(p.english, p.color, p.maxChars, glyph.uiSize(), true, p.persist);
			if (r != null && write(e.getKey(), r.text))
			{
				changed = true;
			}
		}
		if (changed)
		{
			client.refreshChat();
		}
	}

	/** Put the original English back on every translated chat line (switch to English). */
	public void goEnglish()
	{
		pending.clear();
		inserts.clear(); // stop adding new Chinese lines; already-inserted lines stay in chat history
		boolean changed = false;
		for (Map.Entry<MessageNode, Tracked> e : tracked.entrySet())
		{
			Tracked t = e.getValue();
			e.getKey().setValue(t.origValue);
			e.getKey().setRuneLiteFormatMessage(t.origRlfm);
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
		long deadline = System.currentTimeMillis() + PENDING_TIMEOUT_MS;
		for (Map.Entry<MessageNode, Tracked> e : tracked.entrySet())
		{
			Tracked t = e.getValue();
			pending.put(e.getKey(), new Pending(t.english, t.color, chatMaxChars(), deadline, t.persist));
		}
		tick();
	}

	/** Forget everything without reverting (plugin shutdown). */
	public void clear()
	{
		pending.clear();
		inserts.clear();
		tracked.clear();
	}

	private void track(MessageNode node, String english, int color, boolean persist)
	{
		if (!tracked.containsKey(node))
		{
			tracked.put(node, new Tracked(node.getValue(), node.getRuneLiteFormatMessage(), english, color, persist));
		}
	}

	/** Write our text to the node's value and (if it has one) its rlfm; returns true if it changed. */
	private boolean write(MessageNode node, String text)
	{
		if (text.equals(displayed(node)))
		{
			return false;
		}
		node.setValue(text);
		if (node.getRuneLiteFormatMessage() != null)
		{
			node.setRuneLiteFormatMessage(text);
		}
		return true;
	}

	private void recolour(MessageNode node, String value)
	{
		String wrapped = "<col=" + BLUE_HEX + ">" + Tags.stripCol(value) + "</col>";
		if (wrapped.equals(node.getValue()))
		{
			return;
		}
		node.setValue(wrapped);
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
		if (pending.containsKey(node) || pending.size() >= PENDING_MAX)
		{
			return;
		}
		pending.put(node, new Pending(english, color, chatMaxChars(), System.currentTimeMillis() + PENDING_TIMEOUT_MS, persist));
	}

	private int chatMaxChars()
	{
		return Math.max(8, glyph.wrapChars(CHAT_WIDTH_PX - TIMESTAMP_PAD_PX, glyph.uiSize()));
	}

}
