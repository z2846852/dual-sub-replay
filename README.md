# 双语字幕与离线英语听力（DualSub Replay 中文自用版）

[![Android](https://img.shields.io/badge/Android-8.0%2B-3ddc84?logo=android&logoColor=white)](https://developer.android.com/)
[![个人版构建](https://github.com/z2846852/dual-sub-replay/actions/workflows/personal-fast-apk.yml/badge.svg)](https://github.com/z2846852/dual-sub-replay/actions/workflows/personal-fast-apk.yml)
[![开源许可证](https://img.shields.io/badge/源码-MIT-blue)](LICENSE)
[![原始项目](https://img.shields.io/badge/基于-DualSub%20Replay-6c757d)](https://github.com/hoangkien1703/dual-sub-replay)
[![原项目贡献者：9 人](https://img.shields.io/badge/all_contributors-9-orange.svg)](CONTRIBUTORS.md)

> **说明：** 这是个人学习用途的中文 Fork，基于 [hoangkien1703/dual-sub-replay](https://github.com/hoangkien1703/dual-sub-replay) 二次开发。原作者为 Hoang Trung Kien。本仓库并非原作者发布的正式版本；原有的 YouTube 播放与字幕功能继续保留。新增功能在真机和 GitHub Actions 编译通过前，均应视为**测试阶段**。

**本项目的目标：随时听英语，遇到听不懂的词就收藏，让后续听力材料逐渐针对自己的薄弱点。** 平时手机离线使用，GitHub 用于保存对话包和学习事件，ChatGPT 则负责在用户明确要求时分析学习记录、生成新的专项训练内容。

<p align="center">
  <img src="docs/images/dualsub-replay-demo.gif" alt="原始项目的 YouTube 双语字幕、点击单词与跟读功能演示" width="380">
</p>


## 项目总览与跨窗口接手（优先阅读）

> **这是本个人 Fork 的项目摘要和开发决策入口。** 换 ChatGPT 窗口、Codex 会话或其他代码 Agent 时，先读本 README，再核对当前 `main` 的实际源码、Actions 和 Release。README 记录已确定的默认行为、技术边界及文档索引；**不能把旧规格、原作者习惯或聊天里的猜测当成现状**。凡影响功能、默认设置、工程流程、同步协议或升级方式的改动，都应同步更新这里的摘要，并在必要时附详细文档链接。

### 已确定的个人开发规则

| 事项 | 当前约定 |
| --- | --- |
| 唯一日常开发分支 | **只用 `main`**：直接修改、提交、触发 Actions。除非仓库所有者明确要求，否则不要创建功能分支或 PR |
| 代码 Agent | 不受上游 `AGENTS.md`、PR 审查和规格模板约束；仓库已删除这些强制规则。用户的**当前明确需求**优先于旧技术方案 |
| 完成标准 | **代码提交 ≠ 编译成功 ≠ APK 发布 ≠ 真机通过**；每项状态分别依据 GitHub 记录确认，不能只触发构建就宣称已交付 |
| 格式与测试 | 不强制代码风格、缩写和格式门禁；合理保留有价值的编译、功能、回归与安全检查。需要时手动运行验证，不阻断快速个人出包 |
| 功能取舍 | 不擅自隐藏、删减上游功能，也不将“不常用”理解成“允许废弃”；收到明确要求后，**优先直接调整真实默认行为和交互**，无需仅做隐藏 |
| 发布入口 | `main` push → [Personal Fast APK](.github/workflows/personal-fast-apk.yml) → 签名/版本核对 → [本 Fork Release](https://github.com/z2846852/dual-sub-replay/releases/latest) → 应用内检查更新 |
| 文档习惯 | 本 README 做**总结点**；详细算法、架构与历史记录留在各自文档，必要时链接；新窗口优先读这里而不是猜以前的对话 |

### 2026-11-24 工程清理记录

- 已从 `main` 删除上游 `AGENTS.md`、`CONTRIBUTING.md`、`CODE_OF_CONDUCT.md`、`ROADMAP.md`、PR/Issue 模板、Dependabot 自动 PR 配置和 `.editorconfig` 等个人使用不需要的协作/排版规则。
- 已删除上游 PR 预览、PR 风险标记、旧合并发布和网站发布 Actions；**不是停止构建**，而是去掉重复或不适合本 Fork 的流程。日常只由 `personal-fast-apk.yml` 发布 `v3.0.<Actions run number>` 个人 APK。
- 已去除 `quality.gradle.kts`、原有 Detekt/格式基线与格式 ratchet 脚本，不让上游代码风格检查阻断个人开发。Gradle 和 Kotlin 的**实际编译错误**仍须修复。
- 原 `android.yml` 改为**仅手动触发**：单元测试、Android Lint、Debug 构建，可选模拟器测试；不自动发第二套 APK，不要求 PR 合并。
- 已同步更新 [当前构建/发布说明](docs/releasing.md)、[项目目标](docs/project/mission.md) 和 [历史规格说明](docs/specs/README.md)。历史 `docs/specs/` 可供参考，**不构成强制流程**。
- **未删除应用功能**，未改变用户数据迁移、包名与签名验证、GitHub PAT 的设备端保护；[MIT 许可证](LICENSE)、[原作者/贡献者署名](CONTRIBUTORS.md) 和 [第三方许可](THIRD_PARTY_NOTICES.md)继续保留。
- 此次工程清理提交：[d68502f](https://github.com/z2846852/dual-sub-replay/commit/d68502ffff243f6a03a0ec6f71664926758d645d)。后续改动以 `main` 当前源码为准。

### 最近确定的产品行为（以当前源码为准）

| 模块 | 默认与主要交互 |
| --- | --- |
| 离线听力发音 | **优先 Google 自然语音 MP3 与本地缓存**；缓存后可离线播放。Google 不可用时提示，用户可以**主动选择本机 TTS**，不应静默换发音 |
| 逐句复述 | 用系统输入法文字或语音转写复述；允许长按选词替换、右侧 × 清空；**完全匹配即自动判绿**，无需点核对；未匹配不会自动计错 |
| 错误提示 | 主动核对首次错误只提示未通过；第二次错误后可选择「仅标红」，答案始终需要主动揭晓；保留错句重练与逐句进度 |
| 容错 | 常见英语缩写和完整形式等价（如 `there's = there is`、`I'm = I am`），但不能忽略 `a/the` 或时态等真正差异；**文字核对不等于口音/音素评分** |
| 全对自动下一句 | 小勾选项，**默认关闭**，可由用户开启并记住选择；通过后短暂展示绿色状态再进入下一句，不跨过最后一句或循环跳转 |
| 故事列表 | 紧随练习卡片；**最近使用优先**，可切换按难度升降和标题排序；手动同步 GitHub 和独立听辨复习放在下方 |
| 重点错误收藏 | 复述核对后点「☆ 收藏有价值的错误」，主动选择**听辨、漏词、介词/搭配、语法、输入法误识别或其他**，可写备注；与自动核对事件分离 |
| 同步前审核 | 点击「审核并同步 GitHub」先显示所有待上传记录、类型、句号、原始输入和结果；× **仅排除上传**、本机历史继续保留，可恢复后再同步。确认只上传已审核 ID |
| 每日自动同步 | 自动获取故事和备份词汇，但**不上传未经人工审核的逐句记录**；复述事件和主动收藏都必须经审核按钮确认后才上传 |
| App 更新 | 一键检查→按条件下载差分或完整 APK→校验→交给系统安装；显示已下载量、总大小和实时速度。系统要求的安装授权不能省略 |

主要实现位置：[逐句界面](app/src/main/java/com/kienhoang/dualsubreplay/listening/ListeningRecallCard.kt) · [学习主页面](app/src/main/java/com/kienhoang/dualsubreplay/listening/ListeningScreen.kt) · [文字核对](app/src/main/java/com/kienhoang/dualsubreplay/listening/ListeningRecallCheck.kt) · [学习数据](app/src/main/java/com/kienhoang/dualsubreplay/listening/ListeningStore.kt) · [升级逻辑](app/src/main/java/com/kienhoang/dualsubreplay/update/AppUpdater.kt)。

**验证边界：**上述内容是当前 `main` 中有实现的行为，不保证所有输入法、网络和手机机型已完成真机测试；当前最新可安装版本及构建状态请以 [Releases](https://github.com/z2846852/dual-sub-replay/releases/latest) 和 [Actions](https://github.com/z2846852/dual-sub-replay/actions/workflows/personal-fast-apk.yml) 为准，避免把 README 的描述当作现场构建结果。

## 功能概览

| 功能模块 | 主要用途 | 当前状态 |
| --- | --- | --- |
| YouTube 双语字幕 | 原文与译文同屏显示、字幕逐句重播 | 继承自原始项目 |
| 单词查询与学习卡 | 查词、发音、词性和语法、保存原句 | 继承自原始项目 |
| 生词复习、学习进度 | 保存单词、查看观看时长、复习记录 | 继承自原始项目 |
| 离线英语听力 | 对话包连续播放、Google 自然语音优先与 MP3 缓存，本机 TTS 可选 | main 已实现，真机表现依环境 |
| 逐句复述与单词收藏 | 逐句复述、自动核对、错句练习、点击原词收藏 | main 已实现，待按机型验证 |
| 私人 GitHub 数据同步 | 本地加密 PAT、学习事件持久化、按规则同步及手动重试 | main 已实现，需按权限验证 |
| 听辨复习与遗忘曲线 | 四档自评、FSRS-6 风格调度、未稳固词提醒 | main 已实现，数值需持续验证 |
| AI 专项练习 | ChatGPT 根据已同步的学习事件生成对话包 | 目前由用户在 ChatGPT 中触发；尚未自动调度 |

**注意：** 默认的 Google 自然语音首次下载需要网络，已缓存 MP3 可以离线播放；明确选择本机 TTS 时需设备具备对应的英语语音引擎或语音包。**GitHub 同步不等于自动调用 ChatGPT**，也不会自动产生新的英语文章。

## 下载安装

### 第一次安装（正式自用版）

1. 打开 [本 Fork 最新 Release](https://github.com/z2846852/dual-sub-replay/releases/latest)。
2. 本 Fork 的 [Personal Fast APK](https://github.com/z2846852/dual-sub-replay/actions/workflows/personal-fast-apk.yml) 在 `main` 提交时构建、签名并发布个人安装包；这是**快速构建，不运行完整测试**。它独立于完整 CI，因此 Release 存在不代表所有测试通过。
3. 下载并安装 `DualSub-Replay.apk`，系统可能要求一次「允许安装未知应用」。
4. 今后进入 App 的「检查更新」，即可从**本 Fork** 下载、校验并安装新版。适用时优先使用小差分包，否则完整下载 APK。

按需验证：[Android verification（手动运行）](https://github.com/z2846852/dual-sub-replay/actions/workflows/android.yml)，用于单元测试、Android Lint、Debug 编译，并可选模拟器测试。不强制执行代码格式检查，也不阻断个人版快速出包。快速 APK 成功不等于已通过真机测试。

**当前状态由实时 Actions / Releases 为准。** 源码已提交不等于生成了 APK，更不等于真机测试通过。正式个人版的包名为 `com.z2846852.dualsubreplay`，可与原作者的独立安装，不能直接覆盖其安装或自动迁移应用数据。

### 设备要求

- Android 8.0 或更高版本（继承原项目的最低支持要求）。
- 使用 YouTube 观看在线视频时需要网络。
- 离线英语听力需要先获取对话包。Google 自然语音需联网生成或预缓存；本机 TTS 则需要安装可用的英语语音引擎。
- 访问个人私有 `english` 仓库时需要单独配置该仓库的 GitHub 访问凭据。

## 入口归属：本 Fork 与原项目

当前安装包主要入口应当指向本 Fork，不能默默跳转到原作者：

| App 入口 | 目标与说明 |
| --- | --- |
| 侧边栏「我的 Fork 仓库」 | [z2846852/dual-sub-replay](https://github.com/z2846852/dual-sub-replay) |
| 「检查更新」「新功能」 | [本 Fork Releases](https://github.com/z2846852/dual-sub-replay/releases)，不会从上游安装 APK |
| 「报告问题」→「提交到我的 Fork」 | [本 Fork Issues](https://github.com/z2846852/dual-sub-replay/issues) |
| 「邮件联系原作者」、AI 回答举报邮件 | **原作者项目邮箱** `dualsubreplay.help@gmail.com`，不属于本 Fork。发送前会在 App 内提示 |
| 「原作者仓库（跳转外部项目）」 | [原作者 hoangkien1703/dual-sub-replay](https://github.com/hoangkien1703/dual-sub-replay)，单独保留以致谢和遵守署名 |
| Google Play 版本中的商店链接 | 原作者版本的 Google Play 页面；本 Fork 自用版从 GitHub Releases 安装，App 内会明确说明 |

原作者署名、MIT 许可证和第三方开源声明不会被更改为本 Fork 的名义。

## 模式一：YouTube 双语字幕（原功能保留）

本 Fork **没有删除或替换**原有的 YouTube 网页播放器。原项目的主要功能包括：

- 在应用内搜索和播放 YouTube 视频，也可以通过分享链接打开视频。
- 显示原文字幕和翻译字幕；支持第三行字幕（需在设置中开启）。
- 点击字幕句子跳回对应视频位置，反复听某一句。
- 点击字幕中的单词或词组查看释义、语法和发音。
- 将单词及原句保存到生词簿，查看并复习已有学习卡。
- 支持英语、日语、西班牙语、中文等语言的部分词性与语法辅助信息。
- 提供翻译、字幕外观调整、学习进度、备份和导出等功能。
- 可选择使用在线 Google 翻译，部分语言也可通过已下载的 ML Kit 语言模型离线翻译。
- 可选 AI 助手（需要用户自行配置相应服务及 API Key）。
- 提供 Android TV 的实验性界面，实际兼容性视设备而定。

这些原有功能来自上游项目。YouTube 页面、字幕接口及第三方翻译服务可能随外部平台变化而受影响。

## 模式二：离线英语听力（新增）

新增入口为应用导航菜单中的 **「离线英语听力」**。

**当前使用流程（以实际版本功能为准）：**

1. 首次打开「离线英语听力」，配置一次 GitHub fine-grained PAT。
2. 点击同步，获取私人 `english` 仓库中的对话包。
3. 选择话题，例如曼谷出行、编程排错、游戏聊天等。
4. 选择「连续播放」或「逐句复述」；默认优先使用 Google 自然发音及 MP3 缓存。必要时可主动改用本机 TTS。
5. 平时隐藏英文原文，只需要听；听不清时再显示文字。
6. 点击英文单词收藏到本机生词簿；保存单词、原句和来源 ID。
7. 在逐句复述中用键盘或系统语音输入答案：完全匹配自动变绿（允许常见缩写），失败需主动点核对；也可进入独立听辨复习，选择四档自评。
8. 认为错误值得长期关注时主动「☆ 收藏有价值的错误」，选择类型并补充备注；普通核对事件仍自动保存在手机。
9. 点击「审核并同步 GitHub」，先查看待上传条数和每条记录，按 × 排除笔误（支持恢复），最后确认上传。每日首次自动同步只获取内容、备份词汇，不抢先上传待审复述记录。

本地未同步数据先写入持久化存储，GitHub 上传失败时保留待上传事件。**目前没有宣称已在任何指定手机上完成真机验证。**

### 首批示例内容

对话包保存在独立的私人仓库：

- `content/dialogues/bangkok-street-001.json`：曼谷街头问路与咖啡店。
- `content/dialogues/programming-tea-001.json`：程序员讨论排错。
- `content/dialogues/gaming-story-001.json`：朋友讨论游戏直播。
- `content/drills/listening-confusions-001.json`：`driver / Java`、`wallet` 和 `bus / boss` 听辨。

清单文件：`z2846852/english/content/manifest.json`（**私人仓库，需具有访问权限**）。

### GitHub 同步规则

- PAT 只在设备本地加密保存，绝不直接写入仓库源码或 APK。
- 使用 Android Keystore 和 AES-GCM；硬编码固定加密密钥不是可接受的做法。
- 首次连接时联网验证 PAT 和目标仓库权限。
- 之后以北京时间为准，**每天第一次启动 App 且已经配置 PAT 时自动尝试同步一次内容和词汇记忆**，但不会自动上传未审阅的复述事件。
- 其他打开、点击单词、听音、复习等操作不主动访问 GitHub。
- 手动入口改为「审核并同步 GitHub」：点击后先看待传事件表、数量、类别与具体输入；可 × 排除误判/笔误，并在同一窗口恢复已排除的记录。仅确认时固定的 ID 集合允许上传。
- **自动保存 ≠ 自动上传**：普通学习轨迹先存入本机 outbox；人工确认上传时写入私有 `english/data/events/<device-id>/<YYYY-MM>.jsonl`。主动收藏重点错误是另一条独立的数据流，审核后写入 `english/data/insights/<device-id>/<YYYY-MM>.jsonl`；无需额外 GitHub 仓库或 PAT。
- 生词事件使用 UUID 标识，通过读取、合并和 GitHub 文件 SHA 检查防止覆盖与重复。
- 删除 PAT 不会清除本机的对话、词汇和学习记录。

**私人仓库 PAT 的最小授权建议：** Resource owner 选择 `z2846852`，Repository access 选择 **Only select repositories → english**，Contents 选择 **Read and write**。不要把 Token 发给他人，也不要写进 README、Issues、日志或截图。

### 听辨复习与记忆提示

第一版实现 FSRS-6 思路的四档复习调度，区别对待「认识词义」和「能否听出发音」。目标保留率先设为 90%。

未稳固单词超过约 100 个时，应用内可提示优先复习；不妨碍你继续听英语或收藏新词。没有看原文、独立听辨后作出的自评，才有资格参与复习调度。**仅仅反复播放一段录音，不意味着单词已掌握。**

目前采用的是项目内的调度实现，仍需与成熟 FSRS 实现及真实设备测试对照；因此不要把第一版调度结果当作精确的记忆能力测量。

## 中文界面

本 Fork 在**首次安装或首次运行且未明确选择过应用语言**时，优先将界面设为**简体中文**。已设置的其他语言偏好不会被强制覆盖。

需要切换时，可在导航菜单底部点击「应用语言」，也可以选择「设备语言」。原有的英语、越南语、西班牙语、葡萄牙语、日语、韩语、印尼语等界面资源仍然保留。

**界面中文化不等于把学习材料翻译成中文**：英文对话、英语单词和音标应保持原文，方便听辨练习。YouTube 网站页面的语言由 YouTube 自身设置和网页环境决定，不受本 Fork 的本地资源翻译完全控制。

## 常见问题

**问：离线英语听力必须使用付费 TTS 或 AI 接口吗？**

答：不需要付费 TTS 或 AI Key。默认使用 Google 自然语音网页发音和本机 MP3 缓存；首次缓存需要联网，非官方接口可能不可用，届时会明确提示。也可以主动使用 Android 系统 TTS，取决于设备是否有可用英语语音包。

**问：关闭网络后能继续听吗？**

答：已经下载的对话包及**已经缓存**的语音 MP3 可离线使用；若选择本机 TTS，还需安装相应离线英语语音。没有缓存的 Google 自然发音不能凭空离线生成。锁屏/后台播放仍需真机验证。

**问：为什么收藏了单词，GitHub 上没有马上出现？**

答：所有学习记录先保存在手机。现在**逐句核对日志与主动重点错误不会在每日自动同步时上传**；必须点击「审核并同步 GitHub」，筛掉不值得保存的事件，才会上传你认可的 ID。每日首次自动联网仍可下载内容、备份词汇记忆。

**问：当天打开 App 会自动触发 ChatGPT 生成新文章吗？**

答：不会。目前自动同步的是 GitHub 文件，不是 ChatGPT 会话；新文章仍由用户在 ChatGPT 中明确发起，或以后单独搭建自动生成任务。

**问：怎么确认数据是否同步成功？**

答：在「离线英语听力」页面查看待上传数，点「审核并同步 GitHub」查看待传表；私有 `english/data/events/` 是自动练习原始事件，`english/data/insights/` 是人工挑选的重点错误。已上传到 GitHub 的历史记录不会因为新版本预览中点 × 而被追溯删除。

**问：为什么测试 APK 可能无法覆盖安装原版？**

答：包名和签名不一致时，Android 不能直接原位升级。先确认安装包类型、签名及数据备份情况，再决定是否卸载旧版本。

## 个人自用版签名声明

此 Fork 按照 My Work 的简化方式，**在公开仓库保存独立的固定 APK 签名材料及其口令**。它与 My Work 使用的密钥完全不同。好处是无需手动配置 GitHub Secrets、自动构建和更新不需要 ChatGPT 逐次干预；代价是私钥**不再是秘密**，他人理论上也可生成同签名 APK。因此该签名**不能用作可信软件分发或抵抗恶意替换的安全边界**。只有自己从此 Fork 的 GitHub Releases 下载并安装，仍应核对来源；若以后正式公开分发，应迁移到真正保密的签名并处理旧版迁移。

这套签名是 APK 更新证书，不是 GitHub PAT。**私有 `english` 仓库的 PAT 始终只能保存在手机 Keystore，绝不写进公开仓库。**

## 隐私与安全

- 原项目在普通模式下无需注册应用账号，没有广告或常规广告分析 SDK。
- YouTube 模式会访问 YouTube；选择在线翻译或 AI 服务时，会向对应服务发送完成该功能所需的数据。
- 离线英语学习模式在内容已经缓存时，不需要网络语音生成。
- GitHub PAT 存放在 Android Keystore 保护的本机数据中；凭据失效时应重新授权。
- 私人 `english` 仓库也不适合保存原始私密录音、明文密钥或无关隐私信息。
- GitHub 的提交历史会保留已提交的学习事件；敏感数据一旦进入历史，普通删除不足以彻底擦除。
- 本 Fork 未经过独立安全审计，不应将“代码开源”等同于“已经完成安全验证”。

上游原有隐私说明参见 [PRIVACY.md](PRIVACY.md)；第三方依赖和数据集授权参见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## 开发与构建

**个人仓库的唯一日常开发分支是 `main`。** 修改直接提交到 `main`，由 [Personal Fast APK](https://github.com/z2846852/dual-sub-replay/actions/workflows/personal-fast-apk.yml) 自动构建、校验签名、生成差分补丁并发布正式个人版。无需功能分支、PR、预览包、格式门禁、发布标签或额外审批。仅当仓库所有者明确要求时，才使用其他分支或 PR。开发者可按需手动运行 Android 测试。已废弃上游自动发布脚本的 PR 合并规则，不再作为本 Fork 的开发要求。

保留必要的事实性约束：不可泄露 PAT/密钥或覆盖用户数据；更新 APK 必须通过包名、版本、签名和完整性校验；保留 MIT 及第三方许可声明。这些不是代码格式要求。

项目主要使用 **Kotlin、Jetpack Compose、Android SDK 和 Gradle**。编译环境以仓库中实际的 Gradle 配置和 GitHub Actions 工作流为准。

本地常见命令：

```bash
bash ./gradlew testDebugUnitTest lintDebug assembleDebug
```

Windows 环境可将 `./gradlew` 替换为 `gradlew.bat`。未经编译验证的代码改动应优先通过 Actions 检查，而不是直接发布正式 APK。

主要代码位置：

| 路径 | 作用 |
| --- | --- |
| `app/src/main/java/com/kienhoang/dualsubreplay/ui/` | 原有应用导航、字幕交互、页面和界面语言 |
| `app/src/main/java/com/kienhoang/dualsubreplay/listening/ListeningScreen.kt` | 新增离线听力页面 |
| `app/src/main/java/com/kienhoang/dualsubreplay/listening/ListeningStore.kt` | 对话缓存、本地生词和事件记录 |
| `app/src/main/java/com/kienhoang/dualsubreplay/listening/ListeningGitHub.kt` | PAT 加密、每日首次同步、手动同步 |
| `app/src/main/java/com/kienhoang/dualsubreplay/listening/ListeningSpeechService.kt` | 本地英语 TTS 后台朗读 |
| `app/src/main/java/com/kienhoang/dualsubreplay/listening/Fsrs6.kt` | FSRS-6 风格的记忆状态与复习计算 |
| `.github/workflows/android.yml` | 可手动触发的单元测试、Lint 与可选模拟器检查 |

详细文档索引：[真实发布流程](docs/releasing.md) · [个人版项目目标](docs/project/mission.md) · [技术结构参考](docs/project/tech-stack.md) · [历史规格记录](docs/specs/README.md) · [安全说明](SECURITY.md)。旧上游文档仅用于查询历史，不能覆盖本 README 中已确认的个人开发约定。

## 已知限制与待办

1. 新修改仍需根据实际构建和真机结果验证，不能把源码提交等同于 APK 已安装且工作正常。
2. GitHub Actions 必须在 Fork 中实际启用后才会运行；单纯合并代码不能证明 APK 可用。
3. 自动生成新对话和深度专项例句尚未接入 App 内在线 AI；目前通过私人仓库预先分发。
4. 自建 FSRS 调度实现仍需对照成熟实现验证数值与边界条件。
5. 外部 YouTube 页面、网络字幕、第三方登录和云端翻译可能受外部服务变化影响。
6. 本 Fork 的部分底层技术说明和第三方许可原文保留英文，以免误改法律含义。

## 原作者、许可与致谢

- **原始项目：** [DualSub Replay](https://github.com/hoangkien1703/dual-sub-replay)，作者 Hoang Trung Kien。
- **原始项目许可证：** [MIT License](LICENSE)，必须保留原著作权和许可声明。
- **第三方组件和素材：** [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。部分语料或数据集适用不同许可证，应分别遵守。
- **当前中文 Fork：** [z2846852/dual-sub-replay](https://github.com/z2846852/dual-sub-replay)。
- **原项目贡献者：** [CONTRIBUTORS.md](CONTRIBUTORS.md)。

原项目的名称、图标及商标授权不等同于代码 MIT 许可。公开分发重新包装的 Fork 时应采用自己的名称、图标和 Android 包名，并注明基于原项目开发；本中文说明仅描述个人使用的 Fork，不宣称受到原作者认可或支持。
