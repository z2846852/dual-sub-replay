# 个人 Fork 安全说明

本仓库是 [z2846852/dual-sub-replay](https://github.com/z2846852/dual-sub-replay) 的自用 Android Fork，不是原作者的官方发布。存在安全问题时优先通过本仓库的 [Security 页面](https://github.com/z2846852/dual-sub-replay/security) 联系仓库所有者；请不要把 token、API key、私密字幕/录音或漏洞复现中的敏感数据放进公开 Issues。

## 安装与更新

- 只从 [本 Fork 的 Releases](https://github.com/z2846852/dual-sub-replay/releases/latest) 获取 APK。应用内升级必须保留版本、APK SHA256、包名及 Android 签名验证。
- 本个人版在公开仓库存放固定的 APK 签名材料，签名密钥**不是秘密**，不能作为防止恶意来源伪造 APK 的信任基础；仅凭“签名一致”不能证明来源安全。
- 私有 GitHub PAT、AI key 应保存在设备受保护存储里，不得提交到公开仓库或加入日志；撤销访问不应删除用户本地学习数据。
- 公开许可证与第三方资源的许可归属不受本个人工作流简化影响。

本文件不对外承诺固定响应时间或维护支持周期。个人版构建成功不等于经过独立安全审计。
