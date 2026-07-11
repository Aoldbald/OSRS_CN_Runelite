package com.osrscn.ui;

import com.osrscn.OsrscnConfig;
import com.osrscn.translate.AiTranslator;
import com.osrscn.translate.TranslationStore;
import com.osrscn.translate.Translator;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.File;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ScrollPaneConstants;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.RuneLite;
import net.runelite.client.ui.components.materialtabs.MaterialTab;
import net.runelite.client.ui.components.materialtabs.MaterialTabGroup;
import net.runelite.client.util.LinkBrowser;

/**
 * OSRSCN side panel, organised as browser-style tabs to save space: 对话 (dialogue history),
 * 检索 (name lookup), 翻译 (manual AI translate), and 调试 (AI monitor, only when debugMonitor is on).
 * The selected tab and the dialogue toggles persist across sessions via {@link ConfigManager}.
 */
@Singleton
public class OsrscnPanel extends PluginPanel
{
	private static final Color ACCENT = ColorScheme.BRAND_ORANGE;
	private static final String QQ_GROUP = "978108806";
	private static final String SURVEY_URL = "https://docs.qq.com/form/page/DWW5BcmpRZmRORFho";
	private static final int SEARCH_LIMIT = 40;
	private static final long TRANSLATE_TIMEOUT_MS = 15_000;
	private static final String K_TAB = "panelTab";
	private static final String K_SHOW_ZH = "panelShowZh";
	private static final String K_SHOW_EN = "panelShowEn";
	private static final String K_DIALOGUE_OPEN = "panelDialogueOpen";
	private static final String K_SEARCH_OPEN = "panelSearchOpen";
	private static final String K_TRANSLATE_OPEN = "panelTranslateOpen";
	private static final String K_DEBUG_OPEN = "panelDebugOpen";

	private final AiTranslator ai;
	private final OsrscnConfig config;
	private final ConfigManager configManager;
	private final DialogueHistory history;
	private final TranslationStore store;
	private final Translator translator;

	private final JPanel display = new JPanel(new BorderLayout());
	private final JPanel tabBar = new JPanel(new BorderLayout());
	private final MaterialTabGroup tabGroup = new MaterialTabGroup(display);
	private final java.util.List<MaterialTab> tabList = new java.util.ArrayList<>();
	private MaterialTab debugTab;
	private int currentTab;

	// 对话
	private final JTextArea dialogueArea = new JTextArea();
	private final JButton zhBtn = new JButton("中文");
	private final JButton enBtn = new JButton("英文");
	private boolean showZh;
	private boolean showEn;

	// 检索
	private final JTextField searchField = new JTextField();
	private final JTextArea searchResults = new JTextArea();
	private final Timer searchDebounce;

	// 翻译
	private final JTextArea translateInput = new JTextArea();
	private final JTextArea translateOutput = new JTextArea();
	private final JLabel connLabel = new JLabel(" ");
	private final Timer translatePoll;
	private String translatePending;
	private long translateDeadline;

	// 调试
	private boolean lastDebug;
	private final JLabel cacheLabel = statLabel();
	private final JLabel inflightLabel = statLabel();
	private final JLabel sessionLabel = statLabel();
	private final JTextArea recentArea = new JTextArea();

	private final Timer refreshTimer;
	private int lastVersion = -1;
	private boolean lastShowZh;
	private boolean lastShowEn;

	@Inject
	OsrscnPanel(AiTranslator ai, OsrscnConfig config, ConfigManager configManager,
			DialogueHistory history, TranslationStore store, Translator translator)
	{
		this.ai = ai;
		this.config = config;
		this.configManager = configManager;
		this.history = history;
		this.store = store;
		this.translator = translator;
		this.showZh = getBool(K_SHOW_ZH, true);
		this.showEn = getBool(K_SHOW_EN, false);

		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setLayout(new BorderLayout(0, 6));

		display.setBackground(ColorScheme.DARK_GRAY_COLOR);
		tabBar.setBackground(ColorScheme.DARK_GRAY_COLOR);
		tabBar.add(tabGroup, BorderLayout.WEST);
		addTab("对话", buildDialogue());
		addTab("检索", buildSearch());
		addTab("翻译", buildTranslate());
		debugTab = addTab("调试", buildDebugTab());
		debugTab.setVisible(false);

		JPanel north = new JPanel();
		north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
		north.setBackground(ColorScheme.DARK_GRAY_COLOR);
		north.add(buildContactBar());
		north.add(tabBar);
		add(north, BorderLayout.NORTH);
		add(display, BorderLayout.CENTER);

		searchDebounce = new Timer(180, e -> runSearch());
		searchDebounce.setRepeats(false);
		translatePoll = new Timer(300, e -> pollTranslate());
		refreshTimer = new Timer(1000, e -> refresh());
	}

	private MaterialTab addTab(String name, JComponent content)
	{
		final int index = tabList.size();
		MaterialTab tab = new MaterialTab(name, tabGroup, content);
		tab.setFont(uiFont(Font.BOLD, 14));
		tab.setOnSelectEvent(() ->
		{
			currentTab = index;
			setInt(K_TAB, index);
			return true;
		});
		tabGroup.addTab(tab);
		tabList.add(tab);
		return tab;
	}

	// Always-visible contact strip pinned above the tabs so feedback channels are easy to find.
	private JComponent buildContactBar()
	{
		JPanel bar = new JPanel(new BorderLayout(0, 4));
		bar.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		bar.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

		JLabel qq = new JLabel("QQ群: " + QQ_GROUP);
		qq.setForeground(ACCENT);
		qq.setFont(uiFont(Font.BOLD, 14));
		bar.add(qq, BorderLayout.NORTH);

		JPanel btns = new JPanel(new GridLayout(1, 3, 4, 0));
		btns.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		JButton copy = new JButton("群号");
		copy.setToolTipText("复制 QQ 群号");
		styleButton(copy);
		copy.addActionListener(e ->
				Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(QQ_GROUP), null));
		JButton survey = new JButton("问卷");
		survey.setToolTipText("打开反馈问卷");
		styleButton(survey);
		survey.addActionListener(e -> LinkBrowser.browse(SURVEY_URL));
		JButton missingDir = new JButton("缺词");
		missingDir.setToolTipText("打开缺词文件夹（missing.tsv 所在位置）");
		styleButton(missingDir);
		missingDir.addActionListener(e -> openMissingDir());
		btns.add(copy);
		btns.add(survey);
		btns.add(missingDir);
		bar.add(btns, BorderLayout.CENTER);
		return bar;
	}

	/** Open the folder holding the collected {@code missing.tsv}; fall back to copying the path. */
	private void openMissingDir()
	{
		File dir = new File(RuneLite.RUNELITE_DIR, "osrscn");
		//noinspection ResultOfMethodCallIgnored
		dir.mkdirs();
		try
		{
			Desktop.getDesktop().open(dir);
		}
		catch (Exception ex)
		{
			Toolkit.getDefaultToolkit().getSystemClipboard()
					.setContents(new StringSelection(dir.getPath()), null);
		}
	}

	// ---- 对话 history ----

	private JComponent buildDialogue()
	{
		JPanel panel = new JPanel(new BorderLayout(0, 4));
		panel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		dialogueArea.setEditable(false);
		dialogueArea.setLineWrap(true);
		dialogueArea.setWrapStyleWord(true);
		dialogueArea.setFont(uiFont(Font.BOLD, 14));
		panel.add(scroll(dialogueArea, 420), BorderLayout.CENTER);

		JPanel controls = new JPanel(new BorderLayout());
		controls.setBackground(ColorScheme.DARK_GRAY_COLOR);
		JPanel toggles = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
		toggles.setBackground(ColorScheme.DARK_GRAY_COLOR);
		styleButton(zhBtn);
		styleButton(enBtn);
		zhBtn.addActionListener(e -> toggleZh());
		enBtn.addActionListener(e -> toggleEn());
		applyToggleStyles();
		toggles.add(zhBtn);
		toggles.add(enBtn);
		JButton clear = new JButton("清空");
		styleButton(clear);
		clear.addActionListener(e ->
		{
			history.clear();
			refresh();
		});
		controls.add(toggles, BorderLayout.WEST);
		controls.add(clear, BorderLayout.EAST);
		panel.add(controls, BorderLayout.SOUTH);
		return collapsible("对话历史", K_DIALOGUE_OPEN, panel);
	}

	private void toggleZh()
	{
		showZh = !showZh;
		setBool(K_SHOW_ZH, showZh);
		applyToggleStyles();
		refresh();
	}

	private void toggleEn()
	{
		showEn = !showEn;
		setBool(K_SHOW_EN, showEn);
		applyToggleStyles();
		refresh();
	}

	private void applyToggleStyles()
	{
		zhBtn.setForeground(showZh ? ACCENT : Color.GRAY);
		enBtn.setForeground(showEn ? ACCENT : Color.GRAY);
	}

	private void rebuildDialogue()
	{
		StringBuilder sb = new StringBuilder();
		for (DialogueHistory.Entry e : history.snapshot())
		{
			if (!showZh && !showEn)
			{
				continue;
			}
			String header = speakerHeader(e);
			if (!header.isEmpty())
			{
				sb.append('【').append(header).append("】\n");
			}
			boolean zhFellBack = false;
			if (showZh)
			{
				sb.append(e.zh != null ? e.zh : e.en).append('\n');
				zhFellBack = e.zh == null;
			}
			if (showEn && !zhFellBack)
			{
				sb.append(e.en).append('\n');
			}
		}
		dialogueArea.setText(sb.toString());
		dialogueArea.setCaretPosition(dialogueArea.getDocument().getLength());
	}

	/** Speaker name to show, matching the active language toggles ("中文 / English" when both differ). */
	private String speakerHeader(DialogueHistory.Entry e)
	{
		String zh = e.speakerZh == null ? "" : e.speakerZh;
		String en = e.speakerEn == null ? "" : e.speakerEn;
		boolean wantZh = showZh && !zh.isEmpty();
		boolean wantEn = showEn && !en.isEmpty();
		if (wantZh && wantEn && !zh.equals(en))
		{
			return zh + " / " + en;
		}
		if (wantZh)
		{
			return zh;
		}
		if (wantEn)
		{
			return en;
		}
		return zh.isEmpty() ? en : zh; // fall back so the speaker still shows if a name side is missing
	}

	// ---- 检索 name lookup ----

	private JComponent buildSearch()
	{
		JPanel panel = new JPanel(new BorderLayout(0, 4));
		panel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		searchField.setFont(uiFont(Font.BOLD, 14));
		searchField.getDocument().addDocumentListener(new DocumentListener()
		{
			public void insertUpdate(DocumentEvent e)
			{
				searchDebounce.restart();
			}

			public void removeUpdate(DocumentEvent e)
			{
				searchDebounce.restart();
			}

			public void changedUpdate(DocumentEvent e)
			{
				searchDebounce.restart();
			}
		});
		JPanel top = new JPanel(new BorderLayout());
		top.setBackground(ColorScheme.DARK_GRAY_COLOR);
		JLabel hint = new JLabel("输入中文或英文名");
		hint.setForeground(Color.GRAY);
		hint.setFont(uiFont(Font.BOLD, 13));
		hint.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
		top.add(hint, BorderLayout.NORTH);
		top.add(searchField, BorderLayout.CENTER);
		panel.add(top, BorderLayout.NORTH);

		searchResults.setEditable(false);
		searchResults.setLineWrap(true);
		searchResults.setWrapStyleWord(true);
		searchResults.setFont(uiFont(Font.BOLD, 14));
		panel.add(scroll(searchResults, 400), BorderLayout.CENTER);
		return collapsible("名称检索", K_SEARCH_OPEN, panel);
	}

	private void runSearch()
	{
		String q = searchField.getText();
		if (q == null || q.trim().isEmpty())
		{
			searchResults.setText("");
			return;
		}
		StringBuilder sb = new StringBuilder();
		java.util.List<TranslationStore.Match> matches = store.searchNames(q, SEARCH_LIMIT);
		if (matches.isEmpty())
		{
			sb.append("无匹配");
		}
		for (TranslationStore.Match m : matches)
		{
			sb.append(m.zh).append('\n').append(m.en).append("\n\n");
		}
		searchResults.setText(sb.toString());
		searchResults.setCaretPosition(0);
	}

	// ---- 翻译 manual AI translate ----

	private JComponent buildTranslate()
	{
		JPanel panel = new JPanel(new BorderLayout(0, 4));
		panel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		translateInput.setLineWrap(true);
		translateInput.setWrapStyleWord(true);
		translateInput.setFont(uiFont(Font.BOLD, 14));
		JScrollPane inScroll = scroll(translateInput, 120);
		translateInput.setEditable(true);

		JLabel hint = new JLabel("英文 → 中文");
		hint.setForeground(Color.GRAY);
		hint.setFont(uiFont(Font.BOLD, 13));
		hint.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));

		JButton go = new JButton("翻译成中文");
		styleButton(go);
		go.setForeground(ACCENT);
		go.addActionListener(e -> startTranslate());

		translateOutput.setEditable(false);
		translateOutput.setLineWrap(true);
		translateOutput.setWrapStyleWord(true);
		translateOutput.setFont(uiFont(Font.BOLD, 14));

		JPanel north = new JPanel(new BorderLayout(0, 4));
		north.setBackground(ColorScheme.DARK_GRAY_COLOR);
		north.add(hint, BorderLayout.NORTH);
		north.add(inScroll, BorderLayout.CENTER);
		north.add(go, BorderLayout.SOUTH);
		panel.add(north, BorderLayout.NORTH);
		panel.add(scroll(translateOutput, 240), BorderLayout.CENTER);

		JButton test = new JButton("测试 AI 连接");
		styleButton(test);
		test.addActionListener(e -> runConnectionTest());
		connLabel.setForeground(Color.GRAY);
		connLabel.setFont(uiFont(Font.BOLD, 12));
		JPanel south = new JPanel(new BorderLayout(0, 2));
		south.setBackground(ColorScheme.DARK_GRAY_COLOR);
		south.add(test, BorderLayout.NORTH);
		south.add(connLabel, BorderLayout.SOUTH);
		panel.add(south, BorderLayout.SOUTH);
		return collapsible("手动翻译", K_TRANSLATE_OPEN, panel);
	}

	private void runConnectionTest()
	{
		connLabel.setForeground(Color.GRAY);
		connLabel.setText("测试中…");
		ai.testConnection((ok, msg) -> javax.swing.SwingUtilities.invokeLater(() ->
		{
			connLabel.setForeground(ok ? new Color(0x4c, 0xaf, 0x50) : new Color(0xff, 0x53, 0x50));
			connLabel.setText(msg);
		}));
	}

	private void startTranslate()
	{
		String text = translateInput.getText();
		if (text == null || text.trim().isEmpty())
		{
			return;
		}
		translatePending = text.trim();
		translateDeadline = System.currentTimeMillis() + TRANSLATE_TIMEOUT_MS;
		translateOutput.setText("翻译中…");
		pollTranslate();
		translatePoll.start();
	}

	private void pollTranslate()
	{
		if (translatePending == null)
		{
			translatePoll.stop();
			return;
		}
		String zh = translator.plainNoPlayer(translatePending);
		if (zh != null)
		{
			translateOutput.setText(zh);
			translatePending = null;
			translatePoll.stop();
		}
		else if (System.currentTimeMillis() > translateDeadline)
		{
			translateOutput.setText("（翻译超时，确认 Ollama 和 AI 翻译已开启）");
			translatePending = null;
			translatePoll.stop();
		}
	}

	// ---- 调试 AI monitor ----

	private JComponent buildDebug()
	{
		JPanel panel = new JPanel(new BorderLayout(0, 6));
		panel.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel grid = new JPanel(new GridLayout(0, 1, 0, 4));
		grid.setBackground(ColorScheme.DARK_GRAY_COLOR);
		grid.add(cacheLabel);
		grid.add(inflightLabel);
		grid.add(sessionLabel);
		panel.add(grid, BorderLayout.NORTH);

		recentArea.setEditable(false);
		recentArea.setLineWrap(true);
		recentArea.setWrapStyleWord(true);
		recentArea.setFont(uiFont(Font.BOLD, 13));
		panel.add(scroll(recentArea, 150), BorderLayout.CENTER);
		return panel;
	}

	private JComponent buildDebugTab()
	{
		return collapsible("AI 翻译状态", K_DEBUG_OPEN, buildDebug());
	}

	// ---- lifecycle / refresh ----

	public void start()
	{
		int saved = getInt(K_TAB, 0);
		if (saved < 0 || saved >= tabList.size() || (saved == tabList.indexOf(debugTab) && !config.debugMonitor()))
		{
			saved = 0;
		}
		syncDebugTab();
		tabGroup.select(tabList.get(saved));
		refresh();
		refreshTimer.start();
	}

	public void stop()
	{
		refreshTimer.stop();
		translatePoll.stop();  // a manual-translate poll in flight would otherwise keep ticking ~15s
		searchDebounce.stop();
	}

	/** Re-read config-driven visibility (called when settings change). */
	public void onConfigChanged()
	{
		refresh();
	}

	private void refresh()
	{
		syncDebugTab();

		int v = history.version();
		if (v != lastVersion || showZh != lastShowZh || showEn != lastShowEn)
		{
			lastVersion = v;
			lastShowZh = showZh;
			lastShowEn = showEn;
			rebuildDialogue();
		}

		if (config.debugMonitor())
		{
			cacheLabel.setText(stat("已缓存", ai.cacheSize() + " 条"));
			inflightLabel.setText(stat("翻译中", String.valueOf(ai.inFlightCount())));
			sessionLabel.setText(stat("本次会话", ai.sessionCount() + " 条"));
			StringBuilder sb = new StringBuilder();
			for (String line : ai.recent())
			{
				sb.append(line).append('\n');
			}
			String text = sb.toString();
			if (!text.equals(recentArea.getText()))
			{
				recentArea.setText(text);
				recentArea.setCaretPosition(0);
			}
		}
	}

	/** Show/hide the debug strip to match the config flag. */
	private void syncDebugTab()
	{
		boolean want = config.debugMonitor();
		if (want != lastDebug)
		{
			lastDebug = want;
			debugTab.setVisible(want);
			if (!want && currentTab == tabList.indexOf(debugTab))
			{
				tabGroup.select(tabList.get(0));
			}
			revalidate();
			repaint();
		}
	}

	// ---- helpers ----

	private JComponent collapsible(String title, String key, JComponent body)
	{
		JPanel outer = new JPanel(new BorderLayout(0, 4));
		outer.setBackground(ColorScheme.DARK_GRAY_COLOR);
		boolean open = getBool(key, true);
		JButton header = new JButton();
		styleButton(header);
		header.setHorizontalAlignment(JButton.LEFT);
		header.setForeground(ACCENT);
		header.setText((open ? "▼ " : "▶ ") + title);
		body.setVisible(open);
		header.addActionListener(e ->
		{
			boolean next = !body.isVisible();
			body.setVisible(next);
			header.setText((next ? "▼ " : "▶ ") + title);
			setBool(key, next);
			outer.revalidate();
			outer.repaint();
		});
		outer.add(header, BorderLayout.NORTH);
		outer.add(body, BorderLayout.CENTER);
		return outer;
	}

	private JScrollPane scroll(JComponent view, int height)
	{
		view.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		view.setForeground(Color.LIGHT_GRAY);
		if (view instanceof JTextArea)
		{
			((JTextArea) view).setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
		}
		JScrollPane sp = new JScrollPane(view,
				ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
				ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		sp.setBorder(BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR));
		sp.setPreferredSize(new Dimension(0, height));
		return sp;
	}

	private static void styleButton(JButton b)
	{
		b.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		b.setForeground(Color.LIGHT_GRAY);
		b.setFont(uiFont(Font.BOLD, 14));
		b.setFocusPainted(false);
		b.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
	}

	private static JLabel statLabel()
	{
		JLabel l = new JLabel();
		l.setForeground(Color.LIGHT_GRAY);
		l.setFont(uiFont(Font.BOLD, 13));
		return l;
	}

	private boolean getBool(String key, boolean def)
	{
		String v = configManager.getConfiguration(OsrscnConfig.GROUP, key);
		return v == null ? def : Boolean.parseBoolean(v);
	}

	private void setBool(String key, boolean val)
	{
		configManager.setConfiguration(OsrscnConfig.GROUP, key, val);
	}

	private int getInt(String key, int def)
	{
		String v = configManager.getConfiguration(OsrscnConfig.GROUP, key);
		try
		{
			return v == null ? def : Integer.parseInt(v);
		}
		catch (NumberFormatException e)
		{
			return def;
		}
	}

	private void setInt(String key, int val)
	{
		configManager.setConfiguration(OsrscnConfig.GROUP, key, val);
	}

	private static String stat(String label, String value)
	{
		return "<html><span style='font-family:Microsoft YaHei;font-weight:bold;font-size:13px;'>"
				+ label + "：<font color='#ff981f'>" + value + "</font></span></html>";
	}

	/** A clear CJK UI font for the panel (Microsoft YaHei), independent of the in-game glyph font. */
	private static Font uiFont(int style, int size)
	{
		Font f = new Font("Microsoft YaHei", style, size);
		return f.canDisplay('中') ? f : new Font(Font.SANS_SERIF, style, size);
	}
}
