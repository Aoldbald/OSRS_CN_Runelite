package com.osrscn.hooks;

import com.osrscn.glyph.GlyphService;
import com.osrscn.text.Tags;
import com.osrscn.translate.Translator;
import com.osrscn.ui.DialogueHistory;
import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;

/**
 * Translates the NPC/player dialogue boxes and option list each tick.
 *
 * <p>No need to remember the English source: when a line is set we record what we wrote, so the
 * next tick we skip it; when the dialogue advances the game overwrites the widget with new English
 * and we translate again. AI misses simply stay English and retry on a later tick.
 */
@Slf4j
@Singleton
public class DialogueHandler
{
	// horizontal padding (px) kept clear of the widget edge so wrapped lines never overflow
	private static final int WRAP_PAD = 8;

	// Single text widgets (component id -> last text we wrote).
	// ChatRight.NAME is the local player's own name - never translate it.
	private static final int[] TEXT_WIDGETS = {
			InterfaceID.ChatLeft.TEXT,      // NPC line
			InterfaceID.ChatLeft.NAME,      // NPC name
			InterfaceID.ChatLeft.CONTINUE,  // "Click here to continue"
			InterfaceID.ChatRight.TEXT,     // player line
			InterfaceID.ChatRight.CONTINUE,
	};

	private static final String NPC_TEXT_KEY = Integer.toString(InterfaceID.ChatLeft.TEXT);
	private static final String PLAYER_TEXT_KEY = Integer.toString(InterfaceID.ChatRight.TEXT);

	@Inject
	private Client client;
	@Inject
	private Translator translator;
	@Inject
	private GlyphService glyph;
	@Inject
	private DialogueHistory history;

	private final Map<String, String> lastSet = new HashMap<>();
	private final Map<String, String> original = new HashMap<>(); // our translation -> original text, for restore
	private final Map<String, Integer> lastColor = new HashMap<>(); // colour we baked the glyphs with
	private String lastNpcName = ""; // remembered across ticks: the options screen has no name widget

	public void translateDialogues()
	{
		for (int id : TEXT_WIDGETS)
		{
			translateWidget(client.getWidget(id), Integer.toString(id));
		}
		translateOptions();
	}

	private void translateOptions()
	{
		Widget options = client.getWidget(InterfaceID.Chatmenu.OPTIONS);
		if (options == null)
		{
			return;
		}
		Widget[] children = options.getDynamicChildren();
		if (children == null)
		{
			return;
		}
		for (int i = 0; i < children.length; i++)
		{
			translateWidget(children[i], "opt" + i);
		}
	}

	/** Put the original English back on any dialogue widget we translated (instant EN switch). */
	public void restore()
	{
		for (int id : TEXT_WIDGETS)
		{
			restoreWidget(client.getWidget(id), Integer.toString(id));
		}
		Widget options = client.getWidget(InterfaceID.Chatmenu.OPTIONS);
		if (options != null && options.getDynamicChildren() != null)
		{
			Widget[] children = options.getDynamicChildren();
			for (int i = 0; i < children.length; i++)
			{
				restoreWidget(children[i], "opt" + i);
			}
		}
		lastSet.clear();
		original.clear();
		lastColor.clear();
	}

	private void restoreWidget(Widget w, String key)
	{
		if (w == null)
		{
			return;
		}
		String orig = original.get(key);
		// only restore if the widget still holds the exact translation we wrote
		if (orig != null && lastSet.containsKey(key) && lastSet.get(key).equals(w.getText()))
		{
			w.setText(orig);
		}
	}

	private void translateWidget(Widget w, String key)
	{
		if (w == null || w.isHidden())
		{
			return;
		}
		String text = w.getText();
		if (text == null || text.isEmpty())
		{
			return;
		}
		if (text.equals(lastSet.get(key)))
		{
			// our translation is still in place. Char-images bake their colour at render time, so a
			// native recolour (an option highlights under the mouse) does not change them - re-render
			// when the widget's colour no longer matches what we baked, so hover highlight follows.
			Integer baked = lastColor.get(key);
			if (baked == null || baked == w.getTextColor())
			{
				return;
			}
			String orig = original.get(key);
			if (orig != null)
			{
				String zh = translator.plain(Tags.stripTags(orig));
				if (zh != null)
				{
					applyGlyphs(w, key, zh, null); // recolour only: keep original + history intact
				}
			}
			return;
		}
		String english = Tags.stripTags(text);
		if (english.isEmpty())
		{
			return;
		}
		// translate once and reuse for both the history panel and the widget render. Pass NPC + speaker so
		// a collected miss (when enabled) records the same columns as the transcript tables. The options
		// screen has no name widget, so remember the conversation's NPC across ticks and reuse it there.
		String npc = npcSpeakerEn();
		if (!npc.isEmpty())
		{
			lastNpcName = npc;
		}
		else
		{
			npc = lastNpcName;
		}
		String speaker = key.equals(NPC_TEXT_KEY) ? npc : "Player";
		String zh = translator.plainCollect(english, "dialogue", npc, speaker);
		// record new NPC/player dialogue lines into the history panel (this runs only when the line
		// actually changed, so it captures each line once as the dialogue advances)
		captureHistory(key, english, zh);
		if (zh == null)
		{
			return; // not translated yet; a later tick retries
		}
		applyGlyphs(w, key, zh, text);
	}

	/**
	 * Render {@code zh} to char-images in the widget's current colour. When {@code origText} is
	 * non-null it is recorded as the English to restore; pass null for a colour-only re-render that
	 * must not disturb the stored original or the history capture.
	 */
	private void applyGlyphs(Widget w, String key, String zh, String origText)
	{
		// wrap long lines: a glyph string has no spaces for the client to break on, and a single
		// line wider than the widget renders as nothing, so wrap conservatively by measured width.
		int size = glyph.dialogueSize();
		int maxChars = glyph.wrapChars(w.getWidth() - WRAP_PAD, size);
		int color = w.getTextColor();
		String translated = glyph.toImgTags(zh, color, maxChars, size);
		if (translated == null)
		{
			return;
		}
		if (origText != null)
		{
			original.put(key, origText); // remember the English we are replacing, for instant restore
		}
		w.setText(translated);
		// native line height is sized for the small bitmap font; wrapped char-image lines
		// overflow and render blank unless we raise it to fit the taller glyphs.
		if (translated.contains("<br>"))
		{
			w.setLineHeight(glyph.glyphHeight(size));
		}
		lastSet.put(key, translated);
		lastColor.put(key, color);
	}

	private void captureHistory(String key, String english, String zh)
	{
		if (key.equals(NPC_TEXT_KEY))
		{
			String en = npcSpeakerEn();
			String zhName = npcSpeakerZh(en);
			history.add(zhName, en, zh, english);
		}
		else if (key.equals(PLAYER_TEXT_KEY))
		{
			String player = playerSpeaker();
			history.add(player, player, zh, english); // player name is never translated
		}
	}

	/** Current NPC speaker name in English (empty if no name widget). */
	private String npcSpeakerEn()
	{
		Widget name = client.getWidget(InterfaceID.ChatLeft.NAME);
		if (name == null)
		{
			return "";
		}
		String key = Integer.toString(InterfaceID.ChatLeft.NAME);
		// the name widget may already hold our char-images; use the stored English in that case
		return (name.getText() != null && name.getText().contains("<img="))
				? Tags.stripTags(original.getOrDefault(key, ""))
				: Tags.stripTags(name.getText() == null ? "" : name.getText());
	}

	/** NPC speaker name in Chinese, or the English name if it has no translation. */
	private String npcSpeakerZh(String en)
	{
		if (en.isEmpty())
		{
			return "";
		}
		String zh = translator.plain(en);
		return zh != null ? zh : en;
	}

	private String playerSpeaker()
	{
		return client.getLocalPlayer() != null && client.getLocalPlayer().getName() != null
				? client.getLocalPlayer().getName() : "玩家";
	}

	/** Forget what we translated so everything re-translates (e.g. after a font-size change). */
	public void reset()
	{
		lastSet.clear();
		original.clear();
		lastColor.clear();
		lastNpcName = "";
	}
}
