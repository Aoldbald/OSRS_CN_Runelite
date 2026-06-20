package com.osrscn;

import com.google.inject.Provides;
import com.osrscn.glyph.GlyphService;
import com.osrscn.hooks.ChatHandler;
import com.osrscn.hooks.DialogueHandler;
import com.osrscn.hooks.InterfaceTranslator;
import com.osrscn.hooks.MenuTranslator;
import com.osrscn.hooks.OverheadHandler;
import com.osrscn.translate.AiTranslator;
import com.osrscn.translate.TranslationStore;
import com.osrscn.translate.Translator;
import com.osrscn.ui.HoverTooltipOverlay;
import com.osrscn.ui.LoginBanner;
import com.osrscn.ui.ToggleOverlay;
import com.osrscn.ui.OsrscnPanel;
import java.awt.image.BufferedImage;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.events.BeforeRender;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.input.KeyManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.HotkeyListener;

@Slf4j
@PluginDescriptor(
		name = "OSRSCN",
		description = "Lightweight Simplified Chinese translation for OSRS (lookup + local AI translation for missing text)."
)
public class OsrscnPlugin extends Plugin
{
	@Inject
	private Client client;
	@Inject
	private ClientThread clientThread;
	@Inject
	private KeyManager keyManager;
	@Inject
	private OsrscnConfig config;
	@Inject
	private ConfigManager configManager;
	@Inject
	private ToggleService toggle;
	@Inject
	private TranslationStore store;
	@Inject
	private Translator translator;
	@Inject
	private AiTranslator aiTranslator;
	@Inject
	private DialogueHandler dialogueHandler;
	@Inject
	private MenuTranslator menuTranslator;
	@Inject
	private InterfaceTranslator interfaceTranslator;
	@Inject
	private OverheadHandler overheadHandler;
	@Inject
	private ChatHandler chatHandler;
	@Inject
	private GlyphService glyph;
	@Inject
	private OverlayManager overlayManager;
	@Inject
	private HoverTooltipOverlay hoverTooltipOverlay;
	@Inject
	private ToggleOverlay toggleOverlay;
	@Inject
	private LoginBanner loginBanner;
	@Inject
	private ClientToolbar clientToolbar;
	@Inject
	private OsrscnPanel panel;

	// Glyph pre-warm pace: characters registered per client tick. Small enough that the one-off warm
	// (~thousands of chars) stays well under a frame; the lazy path still covers anything not yet warmed.
	private static final int PREWARM_CODEPOINTS_PER_TICK = 8;

	private boolean announced;
	private NavigationButton navButton;

	private final HotkeyListener toggleHotkey = new HotkeyListener(() -> config.toggleHotkey())
	{
		@Override
		public void hotkeyPressed()
		{
			boolean zh = toggle.toggle();
			clientThread.invoke(() ->
			{
				if (zh)
				{
					// switching back to Chinese: re-translate chat history; dialogue/interface
					// re-translate themselves on the next tick scan.
					chatHandler.goChinese();
				}
				else
				{
					// switching to English: actively put the original text back so it's instant
					dialogueHandler.restore();
					interfaceTranslator.restore();
					overheadHandler.clear();
					chatHandler.goEnglish();
				}
			});
			toggleOverlay.flash(zh ? "中文" : "English");
		}
	};

	private final HotkeyListener clearCacheHotkey = new HotkeyListener(() -> config.clearCacheHotkey())
	{
		@Override
		public void hotkeyPressed()
		{
			translator.clearAiCache();
			toggleOverlay.flash("已清缓存");
		}
	};

	@Provides
	OsrscnConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(OsrscnConfig.class);
	}

	@Override
	protected void startUp()
	{
		store.setBaseUrl(config.dataBaseUrl());
		store.loadAsync();
		glyph.reloadFont();
		keyManager.registerKeyListener(toggleHotkey);
		keyManager.registerKeyListener(clearCacheHotkey);
		overlayManager.add(hoverTooltipOverlay);
		overlayManager.add(toggleOverlay);
		loginBanner.apply();
		addPanel();
		log.info("OSRSCN started");
	}

	@Override
	protected void shutDown()
	{
		keyManager.unregisterKeyListener(toggleHotkey);
		keyManager.unregisterKeyListener(clearCacheHotkey);
		overlayManager.remove(hoverTooltipOverlay);
		overlayManager.remove(toggleOverlay);
		loginBanner.remove();
		if (navButton != null)
		{
			panel.stop();
			clientToolbar.removeNavigation(navButton);
			navButton = null;
		}
		log.info("OSRSCN stopped");
	}

	@Subscribe
	public void onClientTick(ClientTick t)
	{
		if (!announced && store.isLoaded())
		{
			announced = true;
			chat("就绪 " + store.size() + " 条");
			// Pre-warm every translated character's glyph so its first appearance never flashes English
			// while the sprite uploads (the dominant remaining flash cause). Drained a few per tick below.
			glyph.beginPrewarm(store.chineseCodepoints());
		}
		if (glyph.prewarmPending())
		{
			glyph.prewarmStep(PREWARM_CODEPOINTS_PER_TICK);
		}
		if (toggle.isChineseEnabled())
		{
			dialogueHandler.translateDialogues();
			overheadHandler.tick();
			chatHandler.tick();
			// Scan every client tick so newly opened interfaces spend less time flashing English.
			// If this ever costs too much, switch to dirty/adaptive scanning here.
			interfaceTranslator.translateOpen();
			// Re-translate the open right-click menu so options whose glyphs were not yet uploaded on the
			// first right-click (new characters) pick up their now-ready glyphs without a second click.
			if (config.translateMenus())
			{
				menuTranslator.retranslateOpenMenu();
			}
		}
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		// Translate the moment an interface is built - before its first frame is drawn - so it does not
		// flash English for a tick until the per-tick scan catches it. The onClientTick scan stays as the
		// backstop for interfaces whose text is populated later by client scripts. The scan self-scopes
		// and skips already-translated widgets, so the extra calls on open are cheap.
		if (toggle.isChineseEnabled())
		{
			interfaceTranslator.translateOpen();
		}
	}

	@Subscribe
	public void onScriptPostFired(ScriptPostFired event)
	{
		// Some panels write their English text from a client script when opened / switched to / updated.
		// Translate the affected surface the instant that script finishes (before the frame draws) so it
		// does not flash English until the next per-tick scan. Which scripts map to which surface/targets
		// lives in SurfaceRegistry; this is just the dispatch. One-shot per fire, so hover hit-test cells
		// are untouched (a per-frame redraw of the account summary previously broke its mouseover).
		// NOTE: combat achievements (CaTasks) is intentionally not handled here - it resists draw-time
		// widget rewrites, so it is handled via overlay rendering instead.
		if (!toggle.isChineseEnabled())
		{
			return;
		}
		interfaceTranslator.translateForScript(event.getScriptId());
	}

	@Subscribe
	public void onBeforeRender(BeforeRender event)
	{
		// spell tooltip and other per-frame-rewritten widgets must be re-translated after their
		// client scripts run each frame, or our text is overwritten before it is drawn.
		if (toggle.isChineseEnabled())
		{
			interfaceTranslator.translateRedraw();
		}
	}

	@Subscribe
	public void onMenuOpened(MenuOpened event)
	{
		if (toggle.isChineseEnabled() && config.translateMenus())
		{
			menuTranslator.handleMenuOpened(event);
		}
	}

	// run before other plugins so they see the original English option/target (e.g. the core Examine
	// plugin needs "Examine" to show vendor/GE prices). The game executes by opcode, so this is invisible.
	@Subscribe(priority = 1)
	public void onMenuOptionClicked(net.runelite.api.events.MenuOptionClicked event)
	{
		if (toggle.isChineseEnabled() && config.translateMenus())
		{
			menuTranslator.restoreForClick(event.getMenuEntry());
		}
	}

	@Subscribe
	public void onChatMessage(net.runelite.api.events.ChatMessage event)
	{
		if (toggle.isChineseEnabled())
		{
			chatHandler.handle(event);
		}
	}

	@Subscribe
	public void onOverheadTextChanged(net.runelite.api.events.OverheadTextChanged event)
	{
		if (toggle.isChineseEnabled())
		{
			overheadHandler.handle(event);
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!OsrscnConfig.GROUP.equals(event.getGroup()))
		{
			return;
		}
		String key = event.getKey();
		if (key != null && key.startsWith("panel"))
		{
			return; // panel UI state (collapse / show-English): not a translation setting
		}
		panel.onConfigChanged(); // show/hide the debug section
		// Picking an API provider preset auto-fills the API address (still user-editable afterwards).
		if ("aiProvider".equals(key) && config.aiProvider() != AiProvider.CUSTOM)
		{
			configManager.setConfiguration(OsrscnConfig.GROUP, "apiUrl", config.aiProvider().getUrl());
		}
		// "测试连接" is a checkbox acting as a button: tick -> reset to off, run the no-token test, pop the result.
		if ("testConnection".equals(key) && config.testConnection())
		{
			configManager.setConfiguration(OsrscnConfig.GROUP, "testConnection", false);
			aiTranslator.testConnection((ok, msg) -> javax.swing.SwingUtilities.invokeLater(() ->
					javax.swing.JOptionPane.showMessageDialog(null, msg, "OSRSCN AI 连接测试",
							ok ? javax.swing.JOptionPane.INFORMATION_MESSAGE : javax.swing.JOptionPane.WARNING_MESSAGE)));
		}
		// Only re-render when a setting that changes glyph output changed. Other settings
		// (AI params, toggles, data URL) take effect on the next translation without dropping
		// the whole glyph cache and re-walking every interface.
		if ("fontPath".equals(key) || "mainFontSize".equals(key))
		{
			glyph.reloadFont();
			dialogueHandler.reset();
			interfaceTranslator.reset();
			// reloadFont cleared the glyph cache: re-warm so the new font/size doesn't flash on first use.
			if (store.isLoaded())
			{
				glyph.beginPrewarm(store.chineseCodepoints());
			}
		}
	}

	/** Side panel is always available; the debug section inside it is gated on the config flag. */
	private void addPanel()
	{
		navButton = NavigationButton.builder()
				.tooltip("OSRS 汉化")
				.icon(navIcon())
				.priority(7)
				.panel(panel)
				.build();
		clientToolbar.addNavigation(navButton);
		panel.start();
	}

	private BufferedImage navIcon()
	{
		BufferedImage img = new BufferedImage(24, 24, BufferedImage.TYPE_INT_ARGB);
		java.awt.Graphics2D g = img.createGraphics();
		g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
				java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setColor(java.awt.Color.WHITE);
		g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 20));
		java.awt.FontMetrics fm = g.getFontMetrics();
		String s = "中";
		g.drawString(s, (24 - fm.stringWidth(s)) / 2, (24 - fm.getHeight()) / 2 + fm.getAscent());
		g.dispose();
		return img;
	}

	private void chat(String zh)
	{
		// glyphs register a cycle late, so toImgTags is null on first use; retry until ready
		clientThread.invokeLater(() ->
		{
			String msg = glyph.toImgTags(zh, 0xffff00);
			if (msg == null)
			{
				return false; // not ready yet, retry next cycle
			}
			// CONSOLE (not GAMEMESSAGE): a local notice that other plugins' chat parsers ignore, so our
			// "ready" line can't trip e.g. the Loot Tracker trying to parse it as a drop message.
			client.addChatMessage(ChatMessageType.CONSOLE, "", "OSRSCN: " + msg, null);
			return true;
		});
	}
}
