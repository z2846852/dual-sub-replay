# 个人 Fork 的构建与更新

本文件仅描述 `z2846852/dual-sub-replay` 的**当前**流程。旧的上游 PR/预览/自动保留版本工作流属于历史记录，已从生效的 GitHub Actions 中删除；`tools/automatic_release.py` 等历史工具不参与个人版发布。

## 唯一日常分支和正式发布

1. 直接提交到 `main`；默认没有功能分支、PR、审查等待或合并审批。
2. [.github/workflows/personal-fast-apk.yml](../.github/workflows/personal-fast-apk.yml) 在每次 `main` push 时自动触发，也可以手动运行。
3. 此工作流构建 `com.z2846852.dualsubreplay` 的固定签名 Release APK，使用 `3.0.<Actions run number>` 版本规则；会核对 APK 包名、版本、签名。
4. 以 `v3.0.<Actions run number>` 创建 GitHub Release，包含 `DualSub-Replay.apk`、SHA256、`update-info.json` 和匹配的直达差分补丁。应用内检查更新使用该仓库最新 Release。
5. 构建失败不等于发布成功。以 [Actions](https://github.com/z2846852/dual-sub-replay/actions/workflows/personal-fast-apk.yml) 的实际最终结论及 [Release](https://github.com/z2846852/dual-sub-replay/releases/latest) 的资源为准。

## 可选验证

[.github/workflows/android.yml](../.github/workflows/android.yml) **仅手动触发**，检查单元测试、Android Lint 和 Debug APK；选择 emulator 时额外运行模拟器测试。不含 PR 门禁、不做代码格式检查，不会发布其他版本，也不影响快速构建流程。

本地可按需运行 `bash ./gradlew testDebugUnitTest lintDebug assembleDebug`。真机操作、语音输入、字幕和安装升级仍必须用实际设备验证；CI 不能代替。

## 数据与安全

保留应用数据迁移、私有 GitHub PAT 的 Keystore 保护和有效 APK 校验。公开的固定 APK 签名材料并不能证明发布者身份，避免安装不受信任来源的同签名安装包。原作者 MIT 许可证及第三方声明继续保留。
