# Android CI

[工作流](../../.github/workflows/android.yml) 在 main/codex 分支推送和指向 main 的 PR 上运行：安装 JDK 21、SDK 36 和 Build Tools 35.0.0；执行三个模块的单元测试、app lint，构建 Debug、未签名 Release 和设备测试 APK。报告及 APK 保存 14 天。

不在 CI 中提供真实 DeepSeek Key，也不将合成样张的单元测试等同于真机验收。设备及真实 API 验证使用[验收方法](../testing/ACCEPTANCE.md)。
