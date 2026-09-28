# 拍照翻译架构

## 模块

| 模块 | 职责 | 主要文件 |
| --- | --- | --- |
| `:core` | 文字体系、语言、模板、坐标、服务接口、无损分批 | `PhotoTranslation.kt`、`PhotoServices.kt`、`TranslationBatches.kt`、`PhotoGeometry.kt` |
| `:data` | ML Kit OCR、DeepSeek HTTP/JSON、DataStore、Keystore | `MlKitPhotoRecognizer.kt`、`DeepSeekTranslator.kt`、`DeepSeekProtocol.kt`、`PhotoPreferences.kt`、`SecureApiKeyStore.kt` |
| `:app` | 相机、状态编排、Compose 设置、覆盖绘制 | `CameraPreview.kt`、`CameraViewModel.kt`、`PhotoTranslationApp.kt`、`PhotoOverlayView.kt` |

Hilt 在 `PhotoModule` 绑定服务。ViewModel 依赖 core 接口，测试使用可控制的假实现；HTTP 协议通过 MockWebServer 验证。

## 状态与数据流

1. CameraX 获取独立 Bitmap，并按拍摄方向旋转，再关闭 ImageProxy。预览结束时解绑相机、关闭补光。
2. ViewModel 固定当前照片、文字体系和翻译选项。只调用所选体系对应的本地识别器，保留文字块 ID、原文和像素坐标。
3. DeepSeek 请求只包含文字、临时分片 ID 和翻译要求。每批最多 6000 字符/40 个分片，超长单块无损拆分；全部分片成功才发布该原始块。
4. 校验响应完成原因、JSON、ID 集合和非空译文。分批失败保留完整成功块，手动重试未完成块。
5. 覆盖层在照片像素坐标中用 StaticLayout 测量字号、行宽和换行并缓存，照片和文字使用同一个缩放及留白变换；取消图片文字的 12sp 屏幕下限，空间不足时缩放完整布局，不做省略或截断，缩放手势不触发重新排版。采样周边底色先遮盖全部原文，再绘制译文；点按和无障碍节点保留全文。

取消操作撤销协程和 HTTP 连接，操作代号阻止迟到结果更新界面。更改目标语言或模板只使结果过期，点击后复用 OCR；更改体系后重新识别。

## 存储

- DataStore：`photo_translation.preferences_pb`，存语言、文字体系、模板及选中 ID，允许备份。
- Key：Android Keystore AES/GCM 加密，密文在 `noBackupFilesDir/deepseek-key.enc`，不进入界面状态、日志、备份。
- 照片和翻译：仅 ViewModel 内存，进设置或配置重建可保留，结束会话或进程退出后消失。
- 旧版 Room 会话库、语音及本地翻译服务已经退出业务链路；升级不读取旧数据，也不主动删除用户已有文件。

需求与参数见 [AGENTS.md](../AGENTS.md) 和[实现说明](planning/CAMERA_TRANSLATION_V1.md)。实测范围见[验收记录](testing/ACCEPTANCE.md)。
