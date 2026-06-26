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

	@ConfigSection(name = "翻译", description = "", position = 2)
	String translate = "translate";

	@ConfigSection(name = "外观", description = "", position = 3)
	String display = "display";

	@ConfigSection(
			name = "AI 翻译",
			description = "开启后，本地查表缺失的游戏英文文本会发送到你设置的翻译服务器（本地 Ollama 或在线 API）翻译。玩家名永不发送。默认用本地 Ollama。",
			position = 4)
	String ai = "ai";

	@ConfigSection(name = "调试", description = "", position = 5, closedByDefault = true)
	String debug = "debug";

	// ===== 联系作者 / 反馈 =====

	@ConfigItem(
			keyName = "qqGroup",
			name = "QQ 群",
			description = "汉化交流 / 问题反馈 QQ 群号。反馈问卷：https://docs.qq.com/form/page/DWW5BcmpRZmRORFho（侧边栏顶部有一键打开按钮）",
			section = feedback,
			position = 0
	)
	default String qqGroup()
	{
		return "978108806";
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

	// ===== 翻译 =====

	@ConfigItem(
			keyName = "translateMenus",
			name = "菜单",
			description = "翻译右键菜单和左上角悬停的动作文字",
			section = translate,
			position = 0
	)
	default boolean translateMenus()
	{
		return true;
	}

	@ConfigItem(
			keyName = "translateGameMessages",
			name = "游戏消息",
			description = "翻译聊天框里的游戏消息（物品/NPC 查看、升级、系统提示等）。不影响玩家发言。",
			section = translate,
			position = 1
	)
	default boolean translateGameMessages()
	{
		return true;
	}

	@ConfigItem(
			keyName = "playerChatMode",
			name = "玩家发言",
			description = "其他玩家公共聊天的处理方式。玩家名字任何情况都不翻译。"
					+ "「不翻译」只显示蓝色英文；「翻译」直接把英文行替换成中文；"
					+ "「翻译并另起一行」保留英文行，下面另插一条中文翻译（如 OSRS_CN: 玩家名: 你好）。"
					+ "玩家黑话翻译质量不稳，且会占用 AI 翻译队列。",
			section = translate,
			position = 2
	)
	default PlayerChatMode playerChatMode()
	{
		return PlayerChatMode.OFF;
	}

	// ===== 外观 =====

	@ConfigItem(
			keyName = "mainFontSize",
			name = "对话字号",
			description = "对话文字的中文字号。只影响对话；界面、菜单、悬停提示用固定字号以避免重叠超框。",
			section = display,
			position = 0
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
			description = "中文字图用的字体文件路径（.ttf/.otf）。留空则用插件内置开源字体（思源黑体 Source Han Sans）。改后即时生效。",
			section = display,
			position = 1
	)
	default String fontPath()
	{
		return "";
	}

	// ===== AI 翻译 =====

	@ConfigItem(
			keyName = "useLocalAi",
			name = "启用 AI 翻译",
			description = "查表没有时，用 AI 翻译缺失文本（后端见下方「AI 后端」）。"
					+ "开启后会把缺失的游戏英文文本发送到你配置的翻译服务器。",
			section = ai,
			position = 0
	)
	default boolean useLocalAi()
	{
		return false;
	}

	@ConfigItem(
			keyName = "aiBackend",
			name = "AI 后端",
			description = "AI 翻译用哪个后端。「本地 Ollama」免费私密但要 GPU；"
					+ "「在线 API」用 DeepSeek/OpenAI 等云端服务，按量付费，会把游戏文本发到该服务商。",
			section = ai,
			position = 1
	)
	default AiBackend aiBackend()
	{
		return AiBackend.OLLAMA;
	}

	@ConfigItem(
			keyName = "aiFillInterface",
			name = "界面缺词 AI 翻译",
			description = "界面里查表没有的文字也用 AI 翻译（新版技能指南会跳过，建议用旧版指南）",
			section = ai,
			position = 2
	)
	default boolean aiFillInterface()
	{
		return true;
	}

	@ConfigItem(
			keyName = "reconstructJournals",
			name = "成就/任务日志整句翻译(实验)",
			description = "把成就日志/任务日志里被折行拆碎的任务拼回整句再翻译。实验功能，"
					+ "需配合「界面缺词 AI 翻译」效果最好；不满意可关闭。",
			section = ai,
			position = 3
	)
	default boolean reconstructJournals()
	{
		return false;
	}

	@ConfigItem(
			keyName = "aiConcurrency",
			name = "翻译并发数",
			description = "同时进行的 AI 翻译请求数。越大填充越快，但更吃 GPU、可能掉帧（Ollama 需开并行）",
			section = ai,
			position = 4
	)
	@Range(min = 1, max = 6)
	default int aiConcurrency()
	{
		return 1;
	}

	@ConfigItem(
			keyName = "aiPaceMs",
			name = "翻译间隔(ms)",
			description = "每次 AI 翻译之间的最小间隔。越小填充越快、越吃 GPU；越大越省 FPS",
			section = ai,
			position = 5
	)
	@Range(min = 0, max = 1000)
	@Units("ms")
	default int aiPaceMs()
	{
		return 120;
	}

	@ConfigItem(
			keyName = "ollamaUrl",
			name = "Ollama 地址",
			description = "本地 Ollama 服务地址（后端选「本地 Ollama」时使用）",
			section = ai,
			position = 6
	)
	default String ollamaUrl()
	{
		return "http://localhost:11434";
	}

	@ConfigItem(
			keyName = "ollamaModel",
			name = "Ollama 模型",
			description = "用于翻译的模型名（后端选「本地 Ollama」时使用）",
			section = ai,
			position = 7
	)
	default String ollamaModel()
	{
		return "qwen2.5";
	}

	@ConfigItem(
			keyName = "aiProvider",
			name = "API 服务商",
			description = "后端选「在线 API」时使用。选一个常用服务商会自动填好下面的「API 地址」"
					+ "（之后仍可手改）。选「自定义」则自己填地址。",
			section = ai,
			position = 8
	)
	default AiProvider aiProvider()
	{
		return AiProvider.DEEPSEEK;
	}

	@ConfigItem(
			keyName = "apiUrl",
			name = "API 地址",
			description = "OpenAI 兼容接口的基础地址，如 https://api.deepseek.com。"
					+ "插件会自动补 /chat/completions。一般选好「API 服务商」即可自动填。",
			section = ai,
			position = 9
	)
	default String apiUrl()
	{
		return "https://api.deepseek.com";
	}

	@ConfigItem(
			keyName = "apiKey",
			name = "API 密钥",
			description = "服务商给的 API Key（后端选「在线 API」时使用）。保存在本地 RuneLite 配置里，不要分享给别人。",
			section = ai,
			position = 10,
			secret = true
	)
	default String apiKey()
	{
		return "";
	}

	@ConfigItem(
			keyName = "apiModel",
			name = "API 模型",
			description = "模型名（后端选「在线 API」时使用），如 deepseek-chat、gpt-4o-mini、Qwen/Qwen2.5-7B-Instruct 等。",
			section = ai,
			position = 11
	)
	default String apiModel()
	{
		return "deepseek-chat";
	}

	@ConfigItem(
			keyName = "testConnection",
			name = "测试连接（点一下）",
			description = "勾一下立即测试当前「AI 后端」是否连通（在线 API 还会验密钥），不消耗 token。"
					+ "勾后会自动弹回，结果用弹窗提示。",
			section = ai,
			position = 12
	)
	default boolean testConnection()
	{
		return false;
	}

	// ===== 调试 =====

	@ConfigItem(
			keyName = "debugMonitor",
			name = "AI 翻译状态",
			description = "侧边栏多出一个「调试」tab，显示 AI 翻译进度（已缓存/翻译中/最近翻译）。"
					+ "只是查看，不影响性能，随便开。",
			section = debug,
			position = 0
	)
	default boolean debugMonitor()
	{
		return false;
	}

}
