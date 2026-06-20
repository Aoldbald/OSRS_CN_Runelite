# OSRSCN Chinese

Simplified Chinese translation for Old School RuneScape, running as a RuneLite plugin.

OSRS native UI cannot draw Chinese text directly. OSRSCN renders each Chinese
character into a small image, registers it with RuneLite's `ChatIconManager`, and
displays it inline via `<img=N>`, so translated text shows up in the original game UI.

## Features

- Simplified Chinese only, with one-key English/Chinese toggle.
- Local lookup first: most text is translated from local TSV tables — fast and stable.
- AI translation fills gaps: text missing from the tables can be sent to a local
  Ollama server. Works fine for the lookup part even without a local model.
- Translates dialogue, right-click menus, interfaces, chat, NPC overhead text, and examines.
- Player names are never translated.
- Lightweight: the JAR ships no translation tables, fonts, or logs — translation data
  is downloaded separately on first run (see below).

## Translation data

The translation tables are not bundled in the plugin. On first run the plugin
downloads them from a public data source into `~/.runelite/osrscn/zh/` and caches
them locally.

You can point the **translation data URL** config option at a mirror (for example a
faster regional mirror) if the default source is slow for you.

## AI translation (third-party server notice)

AI translation is **off by default**. When enabled, the in-game English text that is
missing from the local tables is sent to the LLM server you configure (by default a
local Ollama instance at `http://localhost:11434`). No data is sent anywhere unless
you turn this on and configure a server. Player names are never sent.

## Credits & license

BSD 2-Clause. See [LICENSE](LICENSE).

- **Translation data** — the Simplified Chinese transcript and translation work by
  [lck3141592654](https://github.com/lck3141592654).
- **RuneLingual** — the inline chat-icon approach for displaying CJK text is inspired by
  [RuneLingual](https://github.com/YS-jack/RuneLingual-Plugin) (BSD 2-Clause). OSRSCN is an
  independent clean-room implementation and does not copy RuneLingual code. See
  [NOTICE](NOTICE) for the colour/number placeholder format attribution.

---

# OSRSCN 简体中文

Old School RuneScape 的简体中文汉化 RuneLite 插件。

OSRS 原生界面无法直接显示中文。OSRSCN 把每个汉字渲染成小图，注册到 RuneLite 的
`ChatIconManager`，再用 `<img=N>` 内联显示，让译文出现在游戏原生界面里。

## 功能

- 只做简体中文，支持一键中英切换。
- 本地查表优先：大部分文本直接从本地 TSV 译文表命中，快且稳定。
- AI 翻译补缺：查表没有的文本可发给本地 Ollama 翻译；没有本地模型也能正常使用查表部分。
- 翻译对话、右键菜单、界面、聊天、NPC 头顶气泡、examine。
- 玩家名永不翻译。
- 轻量：JAR 不打包译文表、字体或日志，译文数据首次运行时单独下载（见下）。

## 译文数据

译文表不打包进插件。首次运行时插件会从公开数据源下载到 `~/.runelite/osrscn/zh/`
并在本地缓存。

如果默认数据源对你太慢，可以在配置里把 **翻译数据地址** 改成镜像地址。

## AI 翻译（第三方服务器提示）

AI 翻译**默认关闭**。开启后，本地查表缺失的游戏英文文本会发送到你配置的 LLM 服务器
（默认是本地 Ollama，地址 `http://localhost:11434`）。不开启、不配置服务器就不会发送
任何数据。玩家名永不发送。

## 致谢与许可

BSD 2-Clause，见 [LICENSE](LICENSE)。

- **翻译数据** —— 简体中文词条/译文整理来自 [lck3141592654](https://github.com/lck3141592654)。
- **RuneLingual** —— 游戏内中文显示方案（把 CJK 字符渲染成内联 chat-icon 图片）灵感来自
  [RuneLingual](https://github.com/YS-jack/RuneLingual-Plugin)（BSD 2-Clause）。OSRSCN 是独立
  净室实现，没有复制 RuneLingual 代码。颜色/数字占位符格式的归属见 [NOTICE](NOTICE)。
