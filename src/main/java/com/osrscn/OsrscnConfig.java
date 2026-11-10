package com.osrscn;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Keybind;
import net.runelite.client.config.Range;
import net.runelite.client.config.Units;

@ConfigGroup(OsrscnConfig.GROUP)
public interface OsrscnConfig extends Config
{
	String GROUP = "osrscn";

	@ConfigSection(name = "联系作者 / 反馈", description = "加 QQ 群或填写问卷，反馈问题与建议", position = 0)
	String feedback = "feedback";

	@ConfigSection(name = "通用", description = "", position = 1)
	String general = "general";

	@ConfigSection(
			name = "AI 翻译（共用）",
			description = "两种后端（本地 Ollama / 在线 API）共用的设置。开启后，"
					+ "<br>本地查表缺失的游戏英文文本会发送到你设置的翻译服务器翻译，玩家名永不发送。"
					+ "<br>默认用本地 Ollama；后端专属的地址与模型见下方两个分节。",
			position = 2)
	String aiCommon = "aiCommon";

	@ConfigSection(name = "AI 后端：本地 Ollama", description = "", position = 3, closedByDefault = true)
	String aiOllama = "aiOllama";

	@ConfigSection(name = "AI 后端：在线 API", description = "", position = 4, closedByDefault = true)
	String aiApi = "aiApi";

	@ConfigSection(name = "调试", description = "", position = 5, closedByDefault = true)
	String debug = "debug";

	// ===== 联系作者 / 反馈 =====

	@ConfigItem(
			keyName = "qqGroup",
			name = "QQ 群",
			description = "汉化交流 / 问题反馈 QQ 群号。反馈问卷："
					+ "<br>https://docs.qq.com/form/page/DWW5BcmpRZmRORFho（侧边栏顶部有一键打开按钮）",
			section = feedback,
			position = 0
	)
	default String qqGroup()
	{
		return OsrscnPlugin.QQ_GROUP;
	}

	@ConfigItem(
			keyName = "loginNotice",
			name = "登录提示",
			description = "每次登录时，在聊天框显示一行插件版本和发布渠道。本插件永久免费，"
					+ "<br>只在 RuneLite 插件库发布，这一行是为了让你认出别处收费转卖的假冒版本。"
					+ "<br>不想看可以关掉。",
			section = feedback,
			position = 3
	)
	default boolean loginNotice()
	{
		return true;
	}

	@ConfigItem(
			keyName = "collectMissing",
			name = "帮忙补全汉化",
			description = "想为汉化出份力就开它。游戏里查不到的文本会存到本地缺词文件（不含聊天；"
					+ "<br>单开此项不会上传），攒一阵点侧边栏「缺词」按钮即可一键提交。",
			section = feedback,
			position = 1
	)
	default boolean collectMissing()
	{
		return false;
	}

	@ConfigItem(
			keyName = "uploadMissing",
			name = "自动上传缺词",
			description = "定期把收集到、且通过本地检查的缺词自动发送给汉化组，免去手动提交。"
					+ "<br>每条包含游戏英文原文、文本类别和来源标记（如对话 NPC 名），"
					+ "<br>附匿名安装 ID（不含玩家聊天、不含账号信息）。开启时会弹窗确认，随时可关。",
			section = feedback,
			position = 2
	)
	default boolean uploadMissing()
	{
		return false;
	}

	// ===== 通用 =====

	@ConfigItem(
			keyName = "toggleHotkey",
			name = "中英切换",
			description = "在中文和英文之间切换",
			section = general,
			position = 0
	)
	default Keybind toggleHotkey()
	{
		return new Keybind(KeyEvent.VK_T, InputEvent.CTRL_DOWN_MASK);
	}

	@ConfigItem(
			keyName = "clearCacheHotkey",
			name = "清翻译缓存",
			description = "清掉 AI 翻译缓存，重新翻译",
			section = general,
			position = 1
	)
	default Keybind clearCacheHotkey()
	{
		return new Keybind(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK);
	}

	@ConfigItem(
			keyName = "dataBaseUrl",
			name = "翻译数据地址",
			description = "翻译表的下载地址。默认从公开数据仓库下载；国内可改成镜像地址。留空则只用本地缓存。",
			section = general,
			position = 2
	)
	default String dataBaseUrl()
	{
		return "https://raw.githubusercontent.com/Aoldbald/OSRS_CN_Data_Runelite/main/public/zh/";
	}

	// ===== 通用：翻译开关 =====

	@ConfigItem(
			keyName = "translateMenus",
			name = "菜单",
			description = "翻译右键菜单和左上角悬停的动作文字",
			section = general,
			position = 3
	)
	default boolean translateMenus()
	{
		return true;
	}

	@ConfigItem(
			keyName = "translateSearchResults",
			name = "搜索结果物品名",
			description = "大交易所和银行搜索时，把候选物品名显示成中文。只打一两个字母时候选很多，"
					+ "<br>一屏几十个名字要现画，配置较低的机器会掉帧，卡就关掉。关掉只影响候选列表，"
					+ "<br>搜索框的提示文字照常是中文。",
			section = general,
			position = 4
	)
	default boolean translateSearchResults()
	{
		return true;
	}

	@ConfigItem(
			keyName = "translateGameMessages",
			name = "游戏消息",
			description = "翻译聊天框里的游戏消息（物品/NPC 查看、升级、系统提示等）。不影响玩家发言。",
			section = general,
			position = 5
	)
	default boolean translateGameMessages()
	{
		return true;
	}

	@ConfigItem(
			keyName = "playerChatMode",
			name = "公屏玩家发言",
			description = "其他玩家公共聊天的处理方式（含管理员和自动喊话）。玩家名字任何情况都不翻译。"
					+ "<br>「不翻译」按原频道颜色显示英文；「翻译」直接把英文行替换成中文；"
					+ "<br>「另起一行」保留英文行，下面另插一条中文翻译（如 OSRS_CN: 玩家名: 你好）。"
					+ "<br>玩家黑话翻译质量不稳，且会占用 AI 翻译队列。",
			section = general,
			position = 6
	)
	default PlayerChatMode playerChatMode()
	{
		return PlayerChatMode.OFF;
	}

	@ConfigItem(
			keyName = "groupChatMode",
			name = "频道玩家发言",
			description = "独立设置 Friends Chat、Clan、Guest、GIM 的玩家发言；不受「公屏玩家发言」影响。"
					+ "<br>默认不翻译；可直接翻译或保留英文另起一行。缺少译文时需开启「启用 AI 翻译」。"
					+ "<br>正文发送至所选 AI 后端：本机 Ollama 在本机处理，"
					+ "<br>在线 API 或远程地址会发送至远端。玩家名保持原样，不发送给 AI。"
					+ "<br>切换后仅翻译新来信。",
			section = general,
			position = 7
	)
	default PlayerChatMode groupChatMode()
	{
		return PlayerChatMode.OFF;
	}

	// ===== 通用：外观 =====

	@ConfigItem(
			keyName = "mainFontSize",
			name = "对话字号",
			description = "对话文字的中文字号。只影响对话；界面、菜单、悬停提示用固定字号以避免重叠超框。",
			section = general,
			position = 8
	)
	@Range(min = 8, max = 22)
	@Units("pt")
	default int mainFontSize()
	{
		return 14;
	}

	@ConfigItem(
			keyName = "fontPath",
			name = "自定义字体",
			description = "中文字图用的字体文件路径（.ttf/.otf）。留空则用插件内置开源字体（思源黑体"
					+ "<br>Source Han Sans）。改后即时生效。",
			section = general,
			position = 9
	)
	default String fontPath()
	{
		return "";
	}

	// ===== AI 翻译（共用） =====

	@ConfigItem(
			keyName = "useLocalAi",
			name = "启用 AI 翻译",
			description = "查表没有时，用 AI 翻译缺失文本（后端见下方「AI 后端」）。"
					+ "<br>开启后会把缺失的游戏英文文本发送到你配置的翻译服务器。",
			section = aiCommon,
			position = 0
	)
	default boolean useLocalAi()
	{
		return false;
	}

	@ConfigItem(
			keyName = "aiBackend",
			name = "AI 后端",
			description = "AI 翻译用哪个后端。「本地 Ollama」免费私密但要 GPU；「在线 API」"
					+ "<br>用 DeepSeek/OpenAI 等云端服务，按量付费，会把游戏文本发到该服务商。",
			section = aiCommon,
			position = 1
	)
	default AiBackend aiBackend()
	{
		return AiBackend.OLLAMA;
	}

	@ConfigItem(
			keyName = "aiFillInterface",
			name = "界面缺词 AI 翻译",
			description = "界面里查表没有的文字也用 AI 翻译（含新版技能指南正文的缺句）",
			section = aiCommon,
			position = 2
	)
	default boolean aiFillInterface()
	{
		return true;
	}

	@ConfigItem(
			keyName = "reconstructJournals",
			name = "成就/任务日志整句翻译",
			description = "把成就日志/任务日志里被折行拆碎的任务拼回整句再翻译，并保留完成/要求的颜色区分。"
					+ "<br>需配合「界面缺词 AI 翻译」效果最好；不满意可关闭。",
			section = aiCommon,
			position = 3
	)
	default boolean reconstructJournals()
	{
		return true;
	}

	@ConfigItem(
			keyName = "skillGuideOverlay",
			name = "新版技能指南翻译",
			description = "翻译新版技能指南（OSRS 设置 Enable new-style skill guide interface 的界面）："
					+ "<br>正文整段重排为中文，图标、链接、维基横幅保持可用。游戏更新若导致排版异常，"
					+ "<br>可临时关闭恢复英文。",
			section = aiCommon,
			position = 4
	)
	default boolean skillGuideOverlay()
	{
		return true;
	}

	@ConfigItem(
			keyName = "aiConcurrency",
			name = "翻译并发数",
			description = "同时进行的 AI 翻译请求数。对本地 Ollama 和在线 API 都生效；上限 64；"
					+ "<br>实际吞吐仍受 AI 服务商限流与本机性能影响。越大填充越快，但更吃 GPU、"
					+ "<br>可能掉帧（Ollama 需开并行）",
			section = aiCommon,
			position = 5
	)
	@Range(min = 1, max = 64)
	default int aiConcurrency()
	{
		return 1;
	}

	@ConfigItem(
			keyName = "aiPaceMs",
			name = "翻译间隔(ms)",
			description = "每次 AI 翻译之间的最小间隔。越小填充越快、越吃 GPU；越大越省 FPS",
			section = aiCommon,
			position = 6
	)
	@Range(min = 0, max = 1000)
	@Units("ms")
	default int aiPaceMs()
	{
		return 120;
	}

	@ConfigItem(
			keyName = "testConnection",
			name = "测试连接（点一下）",
			description = "勾一下立即测试当前「AI 后端」是否连通（在线 API 还会验密钥），结果用弹窗提示。"
					+ "<br>先只查模型清单，不消耗 token；若供应商不提供 /models，"
					+ "<br>会补发一个最小请求验证（约 1 token；个别供应商不接受长度上限时会再试一次）。"
					+ "<br>测试后该项会自动重置为未勾选；RuneLite 设置面板不会即时刷新这个勾选框，"
					+ "<br>重新打开本页即可看到。侧边栏「翻译 → 测试 AI 连接」有同功能按钮，"
					+ "<br>结果直接显示在侧边栏。",
			section = aiCommon,
			position = 7
	)
	default boolean testConnection()
	{
		return false;
	}

	// ===== AI 后端：本地 Ollama =====

	@ConfigItem(
			keyName = "ollamaUrl",
			name = "Ollama 地址",
			description = "本地 Ollama 服务地址",
			section = aiOllama,
			position = 0
	)
	default String ollamaUrl()
	{
		return "http://localhost:11434";
	}

	@ConfigItem(
			keyName = "ollamaModel",
			name = "Ollama 模型",
			description = "用于翻译的模型名",
			section = aiOllama,
			position = 1
	)
	default String ollamaModel()
	{
		return "qwen2.5";
	}

	// ===== AI 后端：在线 API =====

	@ConfigItem(
			keyName = "aiProvider",
			name = "API 服务商",
			description = "选一个常用服务商会自动填好下面的「API 地址」（之后仍可手改）。选「自定义」则自己填地址。",
			section = aiApi,
			position = 0
	)
	default AiProvider aiProvider()
	{
		return AiProvider.DEEPSEEK;
	}

	@ConfigItem(
			keyName = "apiUrl",
			name = "API 地址",
			description = "OpenAI 兼容接口的基础地址，如 https://api.deepseek.com。"
					+ "<br>插件会自动补 /chat/completions。一般选好「API 服务商」即可自动填。",
			section = aiApi,
			position = 1
	)
	default String apiUrl()
	{
		return "https://api.deepseek.com";
	}

	@ConfigItem(
			keyName = "apiKey",
			name = "API 密钥",
			description = "服务商给的 API Key。保存在本地 RuneLite 配置里，不要分享给别人。",
			section = aiApi,
			position = 2,
			secret = true
	)
	default String apiKey()
	{
		return "";
	}

	@ConfigItem(
			keyName = "apiModel",
			name = "API 模型",
			description = "模型名，如 deepseek-chat、gpt-4o-mini、Qwen/Qwen2.5-7B-Instruct 等。",
			section = aiApi,
			position = 3
	)
	default String apiModel()
	{
		return "deepseek-chat";
	}

	@ConfigItem(
			keyName = "apiExtraHeaders",
			name = "附加请求头",
			description = "部分供应商要求每个请求带特定 header。例如 OpenCode Go 要求 x-opencode-session"
					+ "<br>（固定不变的 session id），并建议自定义 User-Agent。"
					+ "<br>session id 和 User-Agent 都可以随意填写，但只能用 ASCII、不要含分号。"
					+ "<br>例：x-opencode-session: OSRSCN; User-Agent: OSRSCN"
					+ "<br>留空＝不发送任何附加请求头（和以前完全一样）。"
					+ "<br>Host、Content-Type、Authorization 等保留头会被忽略。"
					+ "<br>此栏不遮挡，请勿粘贴 API 密钥。填完点击别处才会保存。",
			section = aiApi,
			position = 4
	)
	default String apiExtraHeaders()
	{
		return "";
	}

	// ===== 调试 =====

	@ConfigItem(
			keyName = "debugMonitor",
			name = "AI 翻译状态",
			description = "侧边栏多出一个「调试」tab，显示 AI 翻译进度（已缓存/翻译中/最近翻译），"
					+ "<br>同时往日志写走查耗时和聊天框控件明细。排查问题时才需要开。",
			section = debug,
			position = 0
	)
	default boolean debugMonitor()
	{
		return false;
	}


}
