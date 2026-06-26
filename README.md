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
- Lightweight: the JAR ships no translation tables or logs — translation data is
  downloaded separately on first run (see below). Chinese glyphs are rendered with a
  bundled open-source font (Source Han Sans SC, SIL OFL), so they display out of the box.

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

## Safety, privacy & compliance (FAQ)

**Does this modify the game client or any game files?**
No. OSRSCN is a RuneLite plugin. It does not patch, replace, or modify any game
file, font file, or client binary, and it does not read or write the official
client's memory. It only displays Chinese text using RuneLite's public
`ChatIconManager` API and the game's own `<img=N>` inline markup. There is no
file to "fail a hash check" — nothing on disk is changed.

**Will it get my account banned?**
OSRSCN is display-only: it does not automate anything, does not send input or
clicks, and gives no gameplay advantage — it just shows the English text you
already see, in Chinese. Its risk profile is the same as any other plugin on the
RuneLite Plugin Hub. That said, be honest with yourself: RuneLite is a
third-party client, and Jagex's stated position on third-party clients is that
they are permitted but used **at your own risk**. We make no guarantee on
Jagex's behalf, and we don't promise "you will never be banned" — anyone who
promises that is lying.

**Is this just "translation", not a real localization? Do I have to buy an API?**
No purchase or API key is required. The core translation is a free, offline
lookup against bundled-on-first-run TSV tables — no network LLM, no cost. AI
translation is an **optional** fallback for text not yet in the tables, and it
defaults to a **local** Ollama instance on your own machine (free, offline).
Text is only ever sent to a cloud provider if you deliberately switch the AI
option to a cloud LLM yourself.

**What data leaves my computer?**
On first run, the translation tables are downloaded once. With AI translation
**off** (the default), nothing else is sent anywhere. With AI **on**, only the
in-game English text missing from the tables is sent to the LLM server you
configured (local Ollama by default). Player names are never sent.

## Contact & feedback

- **QQ group:** 978108806
- **Feedback form:** https://docs.qq.com/form/page/DWW5BcmpRZmRORFho

Both are also reachable from inside the plugin: the side panel has a contact
bar at the top (with an "open feedback form" button), and the QQ group is shown
in the plugin's config under "联系作者 / 反馈".

## Credits & license

BSD 2-Clause. See [LICENSE](LICENSE).

- **Translation data** — the Simplified Chinese transcript and translation work by
  [lck3141592654](https://github.com/lck3141592654).
- **Font** — Chinese glyphs are rendered with [Source Han Sans SC](https://github.com/adobe-fonts/source-han-sans)
  (© 2014-2025 Adobe, Reserved Font Name 'Source'; Source is a trademark of Adobe),
  licensed under the SIL Open Font License 1.1. See [OFL-1.1.txt](OFL-1.1.txt) and [NOTICE](NOTICE).
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
- 轻量：JAR 不打包译文表或日志，译文数据首次运行时单独下载（见下）。中文字图用内置
  开源字体（Source Han Sans SC 思源黑体，SIL OFL）渲染，开箱即用。

## 译文数据

译文表不打包进插件。首次运行时插件会从公开数据源下载到 `~/.runelite/osrscn/zh/`
并在本地缓存。

如果默认数据源对你太慢，可以在配置里把 **翻译数据地址** 改成镜像地址。

## AI 翻译（第三方服务器提示）

AI 翻译**默认关闭**。开启后，本地查表缺失的游戏英文文本会发送到你配置的 LLM 服务器
（默认是本地 Ollama，地址 `http://localhost:11434`）。不开启、不配置服务器就不会发送
任何数据。玩家名永不发送。

## 安全、隐私与合规（FAQ）

**它会修改游戏客户端或游戏文件吗？**
不会。OSRSCN 是一个 RuneLite 插件，**不修改、不替换、不打补丁**到任何游戏文件、字体
文件或客户端程序，也**不读写官方客户端内存**。它只是用 RuneLite 公开的
`ChatIconManager` 接口和游戏自带的 `<img=N>` 内联标记来显示中文。磁盘上没有任何文件被
改动，所谓"破坏文件哈希、触发校验"无从谈起。

**会不会导致封号？**
OSRSCN 是纯显示插件：**不自动化任何操作、不模拟点击、不发送输入、不提供任何游戏优势**，
只是把你本来就看得到的英文显示成中文。它的封号风险，和 RuneLite Plugin Hub 上任何其它
插件**完全同级**。但我们也实话实说：RuneLite 是第三方客户端，Jagex 对第三方客户端的官方
态度是"允许、但**风险自担**"。我们不代表 Jagex 做任何保证，也**不会承诺"绝不封号"——
任何拍胸脯保证绝不封号的人都是在骗你**。

**这只是"翻译"不算"汉化"？是不是还得自费买 API？**
不需要任何付费或 API key。核心翻译是**免费、离线**的本地查表（TSV 译文表，首次运行下载
后本地缓存），不联网、不调用任何云端大模型、零成本。AI 翻译只是**可选的兜底**，用来翻
译文表里还没有的零碎文本，而且**默认走你本机的本地 Ollama（免费、离线）**。只有你自己
主动把 AI 选项切到云端大模型，文本才会发给该服务商。

**到底有什么数据离开我的电脑？**
首次运行会下载一次译文表。AI 翻译**关闭**时（默认），不再向任何地方发送数据。AI **开启**
时，只有查表缺失的游戏英文文本会发给你配置的 LLM 服务器（默认本地 Ollama）。玩家名永不发送。

## 联系与反馈

- **QQ 群：** 978108806
- **反馈问卷：** https://docs.qq.com/form/page/DWW5BcmpRZmRORFho

插件内也能直接找到：侧边栏顶部有联系条（带"打开反馈问卷"按钮），插件设置里的
"联系作者 / 反馈"分组也会显示 QQ 群号。

## 致谢与许可

BSD 2-Clause，见 [LICENSE](LICENSE)。

- **翻译数据** —— 简体中文词条/译文整理来自 [lck3141592654](https://github.com/lck3141592654)。
- **字体** —— 中文字图使用 [Source Han Sans SC（思源黑体）](https://github.com/adobe-fonts/source-han-sans)
  （© 2014-2025 Adobe，保留字体名 'Source'；'Source' 是 Adobe 商标）渲染，
  采用 SIL Open Font License 1.1。详见 [OFL-1.1.txt](OFL-1.1.txt) 与 [NOTICE](NOTICE)。
- **RuneLingual** —— 游戏内中文显示方案（把 CJK 字符渲染成内联 chat-icon 图片）灵感来自
  [RuneLingual](https://github.com/YS-jack/RuneLingual-Plugin)（BSD 2-Clause）。OSRSCN 是独立
  净室实现，没有复制 RuneLingual 代码。颜色/数字占位符格式的归属见 [NOTICE](NOTICE)。
