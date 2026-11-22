# 双语字幕与离线英语听力（DualSub Replay 中文自用版）

[![Android](https://img.shields.io/badge/Android-8.0%2B-3ddc84?logo=android&logoColor=white)](https://developer.android.com/)
[![构建检查](https://github.com/z2846852/dual-sub-replay/actions/workflows/android.yml/badge.svg)](https://github.com/z2846852/dual-sub-replay/actions/workflows/android.yml)
[![开源许可证](https://img.shields.io/badge/源码-MIT-blue)](LICENSE)
[![原始项目](https://img.shields.io/badge/基于-DualSub%20Replay-6c757d)](https://github.com/hoangkien1703/dual-sub-replay)
[![原项目贡献者：9 人](https://img.shields.io/badge/all_contributors-9-orange.svg)](CONTRIBUTORS.md)

> **说明：** 这是个人学习用途的中文 Fork，基于 [hoangkien1703/dual-sub-replay](https://github.com/hoangkien1703/dual-sub-replay) 二次开发。原作者为 Hoang Trung Kien。本仓库并非原作者发布的正式版本；原有的 YouTube 播放与字幕功能继续保留。新增功能在真机和 GitHub Actions 编译通过前，均应视为**测试阶段**。

**本项目的目标：随时听英语，遇到听不懂的词就收藏，让后续听力材料逐渐针对自己的薄弱点。** 平时手机离线使用，GitHub 用于保存对话包和学习事件，ChatGPT 则负责在用户明确要求时分析学习记录、生成新的专项训练内容。

<p align="center">
  <img src="docs/images/dualsub-replay-demo.gif" alt="原始项目的 YouTube 双语字幕、点击单词与跟读功能演示" width="380">
</p>

## 功能概览

| 功能模块 | 主要用途 | 当前状态 |
| --- | --- | --- |
| YouTube 双语字幕 | 原文与译文同屏显示、字幕逐句重播 | 继承自原始项目 |
| 单词查询与学习卡 | 查词、发音、词性和语法、保存原句 | 继承自原始项目 |
| 生词复习、学习进度 | 保存单词、查看观看时长、复习记录 | 继承自原始项目 |
| 离线英语听力 | 拉取英文对话包，使用手机本地英语语音朗读 | 第一版，待验证 |
| 点击单词收藏 | 在英文原文中点词，保存词语及原句到本地 | 第一版，待验证 |
| 私人 GitHub 数据同步 | 首次输入 PAT、每天首次启动自动同步一次、手动重试 | 第一版，待验证 |
| 听辨复习与遗忘曲线 | 四档自评、FSRS-6 记忆调度、未稳固词提醒 | 第一版，待验证 |
| AI 专项练习 | ChatGPT 根据已同步的学习事件生成对话包 | 目前由用户在 ChatGPT 中触发；尚未自动调度 |

**注意：** 本地 TTS 不依赖在线语音合成 API，但需要设备上存在已安装、可离线使用的英语语音包。**GitHub 同步不等于自动调用 ChatGPT**，也不会自动产生新的英语文章。

## 下载安装

### 通过 GitHub Actions 构建测试版

1. 打开本仓库的 [构建页面](https://github.com/z2846852/dual-sub-replay/actions/workflows/android.yml)。
2. 如果 GitHub 提示工作流被禁用，先启用此 Fork 的 Actions。
3. 点击 **Run workflow**，选择 `main`，运行 Android 构建。
4. 等待构建完成并确认测试通过，在成功的构建记录中下载生成的测试 APK 工件。
5. 在 Android 手机上打开 APK 并按照系统提示安装。

**请勿把“已合并源码”理解成“已编译验证”。** 如果构建失败，请先检查失败日志，不要把未经验证的 APK 当作可用版本。测试版一般使用与正式版不同的包名或签名，可能与原版并存，数据也不会自动迁移。

### 设备要求

- Android 8.0 或更高版本（继承原项目的最低支持要求）。
- 使用 YouTube 观看在线视频时需要网络。
- 离线英语听力需要先下载对话包，并确保手机的英语 TTS 离线语音包已安装。
- 访问个人私有 `english` 仓库时需要单独配置该仓库的 GitHub 访问凭据。

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

**预期使用流程：**

1. 首次打开「离线英语听力」，配置一次 GitHub fine-grained PAT。
2. 点击同步，获取私人 `english` 仓库中的对话包。
3. 选择话题，例如曼谷出行、编程排错、游戏聊天等。
4. 点击连续播放，系统使用手机本地 TTS 逐句朗读。
5. 平时隐藏英文原文，只需要听；听不清时再显示文字。
6. 点击英文单词收藏到本机生词簿；保存单词、原句和来源 ID。
7. 在听辨复习中先听声音，再给出「没认出 / 很费力 / 认出 / 立即认出」自评。
8. 稍后手动同步，或等待次日第一次启动应用时同步到 GitHub。

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
- 之后以北京时间为准，**每天第一次启动 App 且已经配置 PAT 时自动尝试同步一次**。
- 其他打开、点击单词、听音、复习等操作不主动访问 GitHub。
- 保留「手动同步 GitHub」按钮，允许失败后重试。
- 先上传本地未同步的学习事件，再下载新的对话包。
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

答：不需要。Android 已有本地 TTS 接口，可以朗读给定英语文本。首次使用前可能需要通过手机系统设置下载离线英语语音包。如果设备缺少离线声音，应用应提示安装，而不是暗中切换云端语音服务。

**问：关闭网络后能继续听吗？**

答：在对话包已下载且离线语音包可用的前提下，设计目标是可以。后台锁屏播放也在第一版实现中，但仍需真机验证。

**问：为什么收藏了单词，GitHub 上没有马上出现？**

答：为了省电和降低同步冲突，收藏操作优先落地本机，只有明确点击同步按钮或者每天第一次启动时才尝试上传。

**问：当天打开 App 会自动触发 ChatGPT 生成新文章吗？**

答：不会。目前自动同步的是 GitHub 文件，不是 ChatGPT 会话；新文章仍由用户在 ChatGPT 中明确发起，或以后单独搭建自动生成任务。

**问：怎么确认数据是否同步成功？**

答：在「离线英语听力」页面查看同步状态和待上传记录数；必要时进入私人 `english` 仓库检查 `data/events/` 下的记录。不要把手机上的“已保存”误认为“已上传”。

**问：为什么测试 APK 可能无法覆盖安装原版？**

答：包名和签名不一致时，Android 不能直接原位升级。先确认安装包类型、签名及数据备份情况，再决定是否卸载旧版本。

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
| `.github/workflows/android.yml` | Android 自动检查与测试 APK 构建 |

更多原始项目说明保留在 `docs/` 下；其中部分原始技术文档仍为英文，不应误认为已经全部翻译。

## 已知限制与待办

1. 首次 Android 构建、Kotlin 测试、真机背景 TTS 与通知控制仍需完成验证。
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
