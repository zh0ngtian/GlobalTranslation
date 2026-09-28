# GlobalTranslation · 拍照翻译

Android 拍照翻译 App：**CameraX 拍照 → ML Kit 在本机识字 → DeepSeek 翻译文字 → 在原图位置覆盖译文**。照片不上传给翻译服务。

## 使用

1. 打开 App，授予相机权限，在设置中填写自己的 DeepSeek API Key。
2. 手动选择原文文字体系：拉丁字母、中文、日文、韩文或天城文。英语、法语、意大利语使用拉丁字母。
3. 选择目标语言，以及一套自定义翻译要求模板；也可使用“仅基础翻译”。目标语言始终以界面选择为准。
4. 拍照后自动识别与翻译。译文按原文位置覆盖，字号自适应并随照片一起放大；完整绘制全部译文，不用省略号或圆点替代。可放大、点按查看或复制全文；侧转照片可先向左旋转，再重新识别。
5. 改目标语言或模板后点击“重新翻译”，复用 OCR；改文字体系后点击“重新识别并翻译”。

API Key 通过 Android Keystore 加密，排除备份。保存模板和语言偏好；照片及译文只保留在当前会话。首版面向清晰印刷体，无语音对话、手动输入翻译、离线翻译模型管理或历史记录。

## 构建与测试

需要 JDK 21、Android SDK Platform 36、Build Tools 35.0.0、Platform Tools。Java/Kotlin 产物目标为 17，最低 Android 10（API 29）。

在本地 `local.properties` 配置 `sdk.dir`，或设置 `ANDROID_HOME`；将 `JAVA_HOME` 指向 JDK 21。不要提交本机路径或 API Key。

```bash
./gradlew :core:test :data:testDebugUnitTest :app:testDebugUnitTest :app:lintDebug
./gradlew :app:assembleDebug :app:assembleRelease
./gradlew :app:connectedDebugAndroidTest
```

Debug APK：`app/build/outputs/apk/debug/app-debug.apk`。Release APK 默认未签名，上架签名由发布者另行配置。

设备测试需要已解锁、保持亮屏的真机或模拟器；包含五种文字体系、四种语言的 12 组 OCR 样张、排版、加密存储及 Compose 交互。真实 DeepSeek 测试默认跳过，私密注入方式见[测试记录](docs/testing/ACCEPTANCE.md)。

## 开发入口

- [AGENTS.md](AGENTS.md)：已确认需求和项目约束。
- [实现说明](docs/planning/CAMERA_TRANSLATION_V1.md)：状态、协议、布局及失败处理。
- [架构](docs/architecture.md)：`:core`、`:data`、`:app` 分工。
- [验收结果与复现方法](docs/testing/ACCEPTANCE.md)。

当前 fork：[zh0ngtian/GlobalTranslation](https://github.com/zh0ngtian/GlobalTranslation)。基于 [patlar104/GlobalTranslation](https://github.com/patlar104/GlobalTranslation) 二次开发；保留原有许可证。
