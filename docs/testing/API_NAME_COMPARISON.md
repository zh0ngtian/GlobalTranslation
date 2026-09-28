# DeepSeek 经名输出对照实验

日期：2026-09-29。35 次真实 API 请求，19 组条件；未启用搜索工具，未发送图片。

## 结论

- 使用用户提供的整份法文，当前 `deepseek-flash`、关闭思考、App 内置提示词＋“使用佛教美术术语”：3/3 输出《方广大庄严经》，单次约 3.0–3.3 秒。
- 同一份输入上，简化纯文本提示词、通行汉译规则及 Pro 关闭思考也都 3/3 命中；不需要在提示词中直接给出答案。
- 去掉领域要求或只发送正文第一段：各 0/3 命中目标经名，返回外文、音译或意译名称。
- Pro 思考模式部分选择《普曜经》。这是 Lalitavistara 的另一汉译本名，不能直接判为识别错误；本表统计是否输出指定的《方广大庄严经》。
- 30 次返回完整有效译文，5 次达到输出上限而截断；完整结果均未出现原文没有的《佛所行赞》。

## 方法和边界

- 法文逐字采用用户粘贴文本，保留 OCR 错字；全文含 27 行。每行一块时正文 ID 为 b3p0；单段和单块条件另列。
- 基线 system 提示词直接从生产 `DeepSeekProtocol.kt` 的 photo-translation-v3 提取，目标 Simplified Chinese (zh-Hans)。领域要求原句为“使用佛教美术术语”。
- 基线输出 JSON、max_tokens=8192、关闭思考时 temperature=0.1。思考组显式指定 low/high/max，省去不生效的 temperature。
- 每种条件先筛查一次，再对 8 种关键条件增加两次独立采样。补测两种输出格式条件，以及两种 max_tokens=32768 条件。
- 所有请求均不含目标经名或《佛所行赞》，评估标签只在本地检查。计为命中须响应完整、JSON ID 集合正确（结构化组）、目标名称出现且无《佛所行赞》。
- 耗时为此电脑直连 API 的单次请求墙钟时间，包含网络和模型生成。最多三个并发请求/进程；不是手机端端到端耗时。
- 只验证这一份输入和经名，3 次重复不能证明普遍稳定，也不代表整段译文完全忠实。提高预算的补测仍是独立采样，不能将输出差异全部归因于预算。
- 实验没有修改 App 的生产模型、内置提示词、文字识别或 UI。

## 全部条件

| 条件 | 输出上限 | 样本数 | 目标经名命中 | 完整结果采用《普曜经》 | 截断 | 耗时中位数 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Flash，关闭思考，App 提示词＋全文分块＋领域要求 | 8,192 | 3 | 3/3 | 0 | 0 | 3.04 s |
| Flash，low，App 提示词＋全文分块＋领域要求 | 8,192 | 1 | 0/1 | 0 | 1 | 35.30 s |
| Flash，high，App 提示词＋全文分块＋领域要求 | 8,192 | 1 | 0/1 | 0 | 1 | 36.01 s |
| Flash，max，App 提示词＋全文分块＋领域要求 | 8,192 | 1 | 0/1 | 0 | 1 | 36.31 s |
| Pro，关闭思考，App 提示词＋全文分块＋领域要求 | 8,192 | 3 | 3/3 | 0 | 0 | 5.26 s |
| Pro，low，App 提示词＋全文分块＋领域要求 | 8,192 | 3 | 1/3 | 2 | 0 | 22.06 s |
| Pro，high，App 提示词＋全文分块＋领域要求 | 8,192 | 3 | 0/3 | 2 | 1 | 38.84 s |
| Pro，max，App 提示词＋全文分块＋领域要求 | 8,192 | 1 | 0/1 | 0 | 1 | 54.55 s |
| Flash，关闭思考，去掉领域要求 | 8,192 | 3 | 0/3 | 0 | 0 | 2.96 s |
| Flash，关闭思考，简化中文指令＋纯文本全文 | 8,192 | 3 | 3/3 | 0 | 0 | 2.77 s |
| Flash，关闭思考，简化纯文本，temperature 默认值 | 8,192 | 1 | 1/1 | 0 | 0 | 2.86 s |
| Flash，关闭思考，追加通行汉译名称规则 | 8,192 | 3 | 3/3 | 0 | 0 | 3.05 s |
| Flash，关闭思考，追加明确 OCR 错字修正规则 | 8,192 | 1 | 1/1 | 0 | 0 | 3.20 s |
| Flash，关闭思考，仅发送第一段正文 | 8,192 | 3 | 0/3 | 0 | 0 | 1.40 s |
| Flash，关闭思考，全文放入一个 JSON 文字块 | 8,192 | 1 | 1/1 | 0 | 0 | 2.57 s |
| Flash，low，保留 JSON 指令但取消 response_format | 8,192 | 1 | 0/1 | 0 | 0 | 22.72 s |
| Flash，low，简化中文指令＋纯文本全文 | 8,192 | 1 | 1/1 | 0 | 0 | 4.76 s |
| Flash，low，App 提示词＋全文分块＋领域要求 | 32,768 | 1 | 0/1 | 0 | 0 | 33.31 s |
| Pro，max，App 提示词＋全文分块＋领域要求 | 32,768 | 1 | 0/1 | 1 | 0 | 41.03 s |

## 复现

脚本：[test-translation-variants.py](../../scripts/test-translation-variants.py)。Key 通过隐藏输入读取，不写入 argv、源码或结果。脚本不自动重试，遇到鉴权、余额或限流错误时取消尚未开始的调用。

将私有原文存入被 Git 忽略的 build 目录，以下命令运行关键条件，每组 3 次：

```bash
python3 scripts/test-translation-variants.py \
  app/build/reports/api-name-matrix/source.txt \
  app/build/reports/api-name-matrix/new-run \
  --focus-line 4 --expected 方广大庄严经 --forbidden 佛所行赞 \
  --cases flash_off,flash_base_only,flash_plain,flash_term_rule,flash_paragraph_only,pro_off,pro_low,pro_high \
  --repeats 3
```

脚本的 passed 指“命中本次指定名称”，不是所有合法经名的判定。原始输入、请求、完整译文及 token 用量只保留在本地忽略目录；不保存模型思考正文。公开记录只包含配置和汇总。

## 参数及经名依据

- [DeepSeek Chat Completions 参数](https://api-docs.deepseek.com/api/create-chat-completion/)：model、thinking、reasoning_effort 和 JSON 模式。
- [DeepSeek 思考模式](https://api-docs.deepseek.com/guides/thinking_mode/)：low/high/max，temperature 在思考模式不生效。
- [台大佛学资料库文献](https://dlbs.liberal.ntu.edu.tw/FULLTEXT/JR-BM054/bm102930.htm)：Lalitavistara 的汉译包括《普曜经》和《方广大庄严经》。
