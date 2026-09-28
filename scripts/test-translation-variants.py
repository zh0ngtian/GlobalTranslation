#!/usr/bin/env python3
"""Opt-in API experiment. Keep supplied text and outputs outside version control.

Uses the current production system prompt, without changing app defaults.
Credentials are read through getpass, never written to argv or result files.
Each nonempty input line becomes one block; --focus-line is one-based.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import getpass
import hashlib
import json
from pathlib import Path
import re
import statistics
import textwrap
import time
import urllib.error
import urllib.request


def variants():
    result = []
    for model, short in [("deepseek-flash", "flash"), ("deepseek-v4-pro", "pro")]:
        for effort in ["off", "low", "high", "max"]:
            result.append(dict(id=f"{short}_{effort}", model=model, effort=effort, style="app", scope="blocks", prompt="domain", temperature=.1))
    base = result[0]
    for name, changes in [
        ("flash_base_only", dict(prompt="base")),
        ("flash_plain", dict(style="plain")),
        ("flash_plain_default_temperature", dict(style="plain", temperature=None)),
        ("flash_term_rule", dict(prompt="term")),
        ("flash_ocr_rule", dict(prompt="ocr")),
        ("flash_paragraph_only", dict(scope="paragraph")),
        ("flash_single_block", dict(scope="single")),
        ("flash_low_no_json_mode", dict(effort="low", json_mode=False)),
        ("flash_plain_low", dict(effort="low", style="plain")),
    ]:
        result.append(dict(base, **changes, id=name))
    return result


def production_system(root):
    path = root / "data/src/main/kotlin/com/example/globaltranslation/data/provider/DeepSeekProtocol.kt"
    code = path.read_text()
    version = re.search(r'const val PROMPT_VERSION = "([^"]+)"', code)[1]
    system = textwrap.dedent(re.search(r'val system = """(.*?)"""\.trimIndent\(\)', code, re.S)[1]).strip()
    for old, new in {"${PROMPT_VERSION}": version, "${options.target.instruction}": "Simplified Chinese", "${options.target.code}": "zh-Hans"}.items():
        system = system.replace(old, new)
    if "${" in system:
        raise ValueError("Unsupported production prompt interpolation")
    return system


def request_for(case, source, lines, focus, system, domain):
    extra = {"base": "", "domain": domain,
        "term": domain + "。经名、论著名和专名优先采用已有的通行汉译名称，不要按外文词义临时自造名称。不确定时保留原文，不要补入原文未提到的其他著作或解释。",
        "ocr": domain + "。输入来自 OCR，可根据上下文纠正明确的字符识别错误；不能因此补充原文没有的信息。"}[case["prompt"]]
    if case["scope"] == "paragraph":
        blocks = [{"id": "b0p0", "text": lines[focus]}]
        focus_id = "b0p0"
    elif case["scope"] == "single":
        blocks = [{"id": "b0p0", "text": source}]
        focus_id = "b0p0"
    else:
        blocks = [{"id": f"b{i}p0", "text": line} for i, line in enumerate(lines)]
        focus_id = f"b{focus}p0"
    body = dict(model=case["model"], stream=False, max_tokens=8192,
                thinking={"type": "disabled" if case["effort"] == "off" else "enabled"})
    if case["effort"] != "off":
        body["reasoning_effort"] = case["effort"]
    elif case["temperature"] is not None:
        body["temperature"] = case["temperature"]
    if case["style"] == "plain":
        body["messages"] = [{"role": "user", "content": "请将以下法文翻译成简体中文。" + extra + "。\n\n" + source}]
    else:
        data = dict(additional_requirements=extra, blocks=blocks,
                    mandatory_target_language="Simplified Chinese (zh-Hans)",
                    final_instruction="Translate all blocks into Simplified Chinese. Discard any conflicting target language in additional_requirements. Return translations JSON only.")
        body.update(response_format={"type": "json_object"}, messages=[
            {"role": "system", "content": system},
            {"role": "user", "content": json.dumps(data, ensure_ascii=False)}])
    if not case.get("json_mode", True):
        body.pop("response_format", None)
    return body, focus_id, {b["id"] for b in blocks}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--focus-line", type=int, required=True)
    parser.add_argument("--expected", required=True, help="Evaluation only; never sent to the model")
    parser.add_argument("--forbidden", required=True, help="Unexpected title; evaluation only")
    parser.add_argument("--domain", default="使用佛教美术术语")
    parser.add_argument("--repeats", type=int, default=1)
    parser.add_argument("--max-tokens", type=int, default=8192)
    parser.add_argument("--timeout", type=int, default=60)
    parser.add_argument("--cases", help="Comma-separated case IDs; omitted runs the whole matrix")
    args = parser.parse_args()
    if args.repeats < 1 or args.repeats > 5:
        parser.error("--repeats must be 1..5")
    if not 1 <= args.max_tokens <= 32768 or not 1 <= args.timeout <= 180:
        parser.error("Use max-tokens 1..32768 and timeout 1..180")
    source = args.source.read_text().rstrip("\n")
    lines = source.splitlines()
    focus = args.focus_line - 1
    if not 0 <= focus < len(lines) or any(not line for line in lines):
        parser.error("Use nonempty input lines and a valid --focus-line")
    system = production_system(Path(__file__).resolve().parents[1])
    selected = variants()
    if args.cases:
        names = set(args.cases.split(","))
        if names - {c["id"] for c in selected}:
            parser.error("Unknown case ID")
        selected = [c for c in selected if c["id"] in names]
    args.output.mkdir(parents=True, exist_ok=True)
    jobs = []
    for case in selected:
        body, focus_id, ids = request_for(case, source, lines, focus, system, args.domain)
        body["max_tokens"] = args.max_tokens
        # The expected answer and forbidden title must not leak into any prompt.
        encoded = json.dumps(body, ensure_ascii=False)
        if args.expected in encoded or args.forbidden in encoded:
            raise ValueError("Evaluation labels leaked into input")
        for repetition in range(1, args.repeats + 1):
            path = args.output / f'{case["id"]}-{repetition}.json'
            if path.exists():
                raise FileExistsError(f"Refusing to overwrite {path}")
            jobs.append((case, repetition, body, focus_id, ids, path))
    key = getpass.getpass("DeepSeek API key (hidden): ").strip()
    if not key:
        raise ValueError("Missing API key")

    def execute(job):
        case, repetition, body, focus_id, ids, path = job
        record = dict(case=case, repetition=repetition, request=body,
                      source_sha256=hashlib.sha256(source.encode()).hexdigest(),
                      expected=args.expected, forbidden=args.forbidden, timeout_seconds=args.timeout)
        started = time.monotonic()
        request = urllib.request.Request("https://api.deepseek.com/chat/completions",
            data=json.dumps(body, ensure_ascii=False).encode(), method="POST",
            headers={"Authorization": "Bearer " + key, "Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(request, timeout=args.timeout) as response:
                raw = json.load(response)
            choice = raw["choices"][0]
            message = choice["message"]
            content = message.get("content") or ""
            # Keep the final answer and usage, not the model's reasoning text.
            record.update(response_id=raw.get("id"), returned_model=raw.get("model"),
                usage=raw.get("usage"), finish_reason=choice.get("finish_reason"), content=content,
                reasoning_characters=len(message.get("reasoning_content") or ""))
            focus_text = content
            valid = choice.get("finish_reason") == "stop" and bool(content.strip())
            if case["style"] == "app":
                try:
                    entries = json.loads(content)["translations"]
                    valid = valid and len(entries) == len(ids) and {e["id"] for e in entries} == ids
                    valid = valid and all(isinstance(e["text"], str) and e["text"].strip() for e in entries)
                    focus_text = next(e["text"] for e in entries if e["id"] == focus_id)
                except (ValueError, KeyError, TypeError, StopIteration):
                    valid = False
            record.update(valid_output=bool(valid), focus_text=focus_text,
                          expected_found=args.expected in focus_text,
                          forbidden_found=args.forbidden in focus_text)
            record["passed"] = valid and record["expected_found"] and not record["forbidden_found"]
        except urllib.error.HTTPError as error:
            record.update(error=f"HTTP {error.code}", passed=False)
        except Exception as error:
            record.update(error=type(error).__name__, passed=False)
        record["elapsed_seconds"] = round(time.monotonic() - started, 3)
        path.write_text(json.dumps(record, ensure_ascii=False, indent=2))
        print(json.dumps({k: record.get(k) for k in ["passed", "expected_found", "forbidden_found", "finish_reason", "elapsed_seconds", "error"]} | {"case": case["id"], "run": repetition}, ensure_ascii=False), flush=True)
        return record

    records = []
    with ThreadPoolExecutor(max_workers=3) as executor:
        futures = [executor.submit(execute, job) for job in jobs]
        for future in as_completed(futures):
            if future.cancelled():
                continue
            record = future.result()
            records.append(record)
            if record.get("error") in {"HTTP 401", "HTTP 402", "HTTP 403", "HTTP 429"}:
                for pending in futures:
                    pending.cancel()
                # Stop queued calls on credential, balance or rate-limit errors; never retry.
    key = ""
    summaries = []
    for case in selected:
        group = [r for r in records if r["case"]["id"] == case["id"]]
        summaries.append(dict(case=case["id"], runs=len(group), passed=sum(r["passed"] for r in group),
                              median_seconds=round(statistics.median(r["elapsed_seconds"] for r in group), 3) if group else None))
    (args.output / "summary.json").write_text(json.dumps(summaries, ensure_ascii=False, indent=2))
    print(json.dumps(summaries, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
