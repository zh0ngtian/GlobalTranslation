# 拍照翻译验收

日期：2026-09-29。环境：macOS arm64、JDK 21、Gradle 8.13、SDK 36；真机 HONOR LGE-AN10，Android 12 / API 31。

## 初版基线验证

17 项单元测试、8 项真机测试全部通过；app lint 无错误，Debug 和未签名 Release 构建通过。最终内置 Prompt 为 `photo-translation-v3`。16 次中文翻译请求耗时为 733–1369 ms（不含 OCR），另有 1 次意大利语目标验证；这只是本次样张与网络条件下的结果。

## 范围

- 17 项 JVM 单元测试：core 4、data 7、app 6。覆盖无损分批、Unicode、完整块合并、坐标变换、JSON/ID 校验、鉴权/余额/限流/断连/超时、设置编解码、OCR 复用、体系切换、部分重试、取消和迟到结果。
- 8 项真机测试：CameraX 实际拍摄、设置返回和旋转；四语种 12 组 OCR；五种文字体系及空白图；字号适应/边缘/横竖比例/字体缩放；双指放大；Keystore/DataStore；真实 API；Compose 模板、语言、Key 和全文流程。
- 四语种真实 API：12 组基础翻译、4 组机械领域要求加冲突目标语言、1 次切换意大利语目标。使用 App 的 OCR 和 DeepSeek 实现，照片未上传。

设备测试的语言样张由 Android Canvas 和系统字体生成，分短文、长文、密集文字。真实相机硬件流程单独验证，**不等同于已经拍摄并核验四种语言的真实纸张**。

## OCR 与翻译证据

[OCR 指标](ocr-acceptance.txt)采用大小写归一化后仅保留字母和数字的字符编辑距离：11 组为 0，日语密集组约 0.51%。该指标不统计标点和空格，不能用它声称逐字完全正确。

[实际 OCR 与 DeepSeek 译文](real-api-acceptance.txt)保留各组输入、输出及请求耗时。单位中点 `N·m` 存在被 OCR 识别为 `Nm`、`N-m`、`N.m` 的情况，日语密集文本有一次 `bar。` 被识别为 `baro`。译文仍可能沿用识别误差；点按可核对原文和译文。

内置 Prompt 固定目标语言，并在结构化用户输入末尾重复目标约束；冲突模板中的日语要求在四语种验收中均未改变中文目标。JSON 校验只能验证结构、ID 和非空结果，语言质量仍需样张核对。

[排版边界截图](overlay-layout.png)展示极长译文的省略与图片边界。

[界面截图](translation-ui.png)来自 Compose 交互测试，使用合成图片和固定译文验证控件及布局；不作为真实模型译文的证据。设备输出见 [device-tests.txt](device-tests.txt)。

## 复现

```bash
./gradlew :core:test :data:testDebugUnitTest :app:testDebugUnitTest :app:lintDebug
./gradlew :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest
./gradlew :app:connectedDebugAndroidTest
```

手机需要解锁并亮屏。测试会请求相机权限，使用实际相机进行一次拍摄；不保存或导出该相机画面。无真实 Key 时，网络验收用例明确跳过。

真实 API 验收（会产生少量账户调用费用）：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
python3 scripts/test-real-api.py
```

脚本交互式隐藏输入 Key，经 stdin 送入 App 私有 `no_backup` 目录；Key 不出现在命令参数、仓库或报告中。测试读取后立即删除手机上的临时文件，脚本 finally 再次清理。测试只生成无敏感信息的样张结果：

```bash
adb exec-out run-as com.example.globaltranslation cat cache/real-api-acceptance.txt
```

## 限制与交付边界

- 未验证所有 Android 厂商/API 版本、手写、倾斜纸张、反光、弱光或所有领域术语。首版范围为清晰印刷体。
- 图片覆盖按用户参考图允许小于 12sp，保留原文位置并优先完整显示；整图状态的小字需要放大或点按详情阅读。详情和普通界面使用正常字号。
- Release 只验证构建和压缩，未配置正式签名或商店上架；真机运行的是 Debug APK。
- lint 保留依赖更新提示、静态绘制分配提示及兼容 API 弃用提示；不关闭错误检查、不使用 baseline 隐藏错误。
- 代码、运行、文档和规则在本仓库内核对；未改其他项目、Agent 全局规则或生成记忆。保留构建产物和测试证据供复核，不清理其他分支或工作树。

## 补充：真实密集说明牌（2026-09-29）

用户提供的 960×1280 法语实拍说明牌通过生产 `CameraViewModel`、ML Kit、DeepSeek 和 Compose 界面测试：识别 28 块，1–21 编号全部存在，OCR 475 ms；基础处理约 5.4 秒，佛教/艺术史模板重新翻译约 4.7 秒，后者复用 OCR。两次真实请求都返回完整 ID 集合，点按完整原文/译文可用。

**修正前的可读性问题**：默认视口 984×1250 下 28 块均触发省略或小块标记，大部分编号条目显示圆点，不能直接阅读整张译图。放大和拖动能查看局部，仍不能据此认定默认展示合格。浅色标题、细小题注和部分正文存在 OCR 错字；结构化响应成功不代表这些文字被正确翻译。

`ExternalPhotoTest` 是新增的可选照片回归入口，默认缺少私密注入文件时跳过。原图、具体 OCR 内容、译文和截图仅保留在本地忽略目录 `app/build/reports/external-photo/`，不随仓库发布。`replay=true` 使用前次真实译文，重新运行本地 OCR，验证覆盖完整性、位置、边界和界面手势，不调用 DeepSeek。结果写入 `layout-result.json`，保留原始请求记录。


## 参考效果调整与回归（2026-09-29）

用户提供参考效果后，覆盖文字改为在照片坐标中测量和缓存，取消原先 12sp 的屏幕下限；保留条目位置，捏合时文字和照片一起放大，不重新换行。遮盖底色采样周围照片颜色；先遮盖全部原文，再绘制译文，划分轻微重叠的 OCR 框，避免文字被相邻底色擦除。

使用同一实拍图重新运行本地 OCR，并回放此前两次真实 API 的完整译文。基础翻译和领域模板在默认 984×1250 覆盖视口中均为 **28/28 段完整显示，21/21 条编号完整显示，0 段省略**；实际 App 视口也检查完整显示，覆盖框无交叠，点按全文和缩放、拖动可用。原始 OCR 坐标和译文未改动，原有 OCR 误差仍存在。私有前后截图及本次数据保留在 `app/build/reports/external-photo/after/`，不提交照片或内容到公开仓库。

本次门禁：17 项 JVM 测试通过，app lint 无错误，Debug/Release 构建通过；固定设备回归 7 项通过，真实 API 与外部照片默认入口 2 项按条件跳过。随后单独注入原图及缓存译文，照片回放回归通过，并导出最终截图；合计验证 8 项不同设备用例。本次未新增 API 请求。
