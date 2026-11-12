# -*- coding: utf-8 -*-
"""
微调前后 A/B 验收（2026-11-12）

做法：取验证集里前 N 条（默认 20），只把 system + user 发给 Ollama，
      不给 assistant 标签，看模型自己能不能按三行协议输出、分数是否落在该形态的区间。

用法：
    F:\\ai-ft-env\\Scripts\\python.exe scripts\\ft\\eval_ollama.py --model qwen2.5:3b-16k --n 20
    F:\\ai-ft-env\\Scripts\\python.exe scripts\\ft\\eval_ollama.py --model qwen2.5:3b-defense --n 20
"""

import argparse
import json
import re
import sys
from pathlib import Path

import requests

# Windows 下 stdout 默认 GBK，打印模型原文里的生僻字/emoji 会抛 UnicodeEncodeError，
# 表现是"只打印了汇总就退出"，看不到失败样例。
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / "docs" / "ft" / "dataset" / "val.jsonl"

FORM_RANGE = {
    "fully_correct": (35, 50, "[切题]"),
    "half_right": (15, 30, "[错误]"),
    "all_wrong_relevant": (5, 14, "[错误]"),
    "irrelevant": (0, 0, "[跑题]"),
    "mostly_wrong_few_right": (5, 20, "[错误]"),
}

SCORE_RE = re.compile(r"^评分:\s*(\d+)/50\s*\|\s*(\d+)\s*\|\s*(\d+)\s*\|\s*(\d+)\s*\|\s*(\d+)\s*\|\s*(\d+)\s*$")
TAG_RE = re.compile(r"^\[(切题|错误|跑题)\]")


def parse_output(text):
    """返回 (结构合规, 算术一致, total, dims, tag, errors)

    结构合规 = 三行齐全 + 首行点评带标记 + 评分行可解析 + 第三行是一题/总结
    算术一致 = 五维之和 == 总分（后端会重算，所以这是"锦上添花"指标）
    """
    errs = []
    lines = [l.strip() for l in text.strip().splitlines() if l.strip()]
    if len(lines) < 3:
        return False, False, None, None, None, [f"行数不足({len(lines)})"]
    if not lines[0].startswith("点评:"):
        errs.append("第一行不是点评:")
    m = TAG_RE.match(lines[0][3:].strip())
    tag = m.group(1) if m else None
    if not tag:
        errs.append("点评缺少[切题]/[错误]/[跑题]")
    sm = SCORE_RE.match(lines[1])
    total = dims = None
    if not sm:
        errs.append("评分行格式不符")
    else:
        total = int(sm.group(1))
        dims = [int(sm.group(i)) for i in range(2, 7)]
    if not (lines[2].startswith("下一题:") or lines[2].startswith("总结:")):
        errs.append("第三行不是下一题:/总结:")
    struct_ok = len(errs) == 0
    sum_ok = bool(dims) and sum(dims) == total
    if dims and not sum_ok:
        errs.append(f"五维和({sum(dims)})!=总分({total})")
    return struct_ok, sum_ok, total, dims, tag, errs


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="qwen2.5:3b-defense")
    ap.add_argument("--n", type=int, default=20)
    ap.add_argument("--url", default="http://127.0.0.1:11434/api/chat")
    ap.add_argument("--verbose", action="store_true")
    args = ap.parse_args()

    rows = []
    with open(DATA, encoding="utf-8") as f:
        for line in f:
            if line.strip():
                rows.append(json.loads(line))
    rows = rows[: args.n]

    struct_cnt = 0
    sum_cnt = 0
    tag_ok = 0
    range_ok = 0
    failures = []

    for i, r in enumerate(rows, 1):
        form = r["meta"]["form"]
        msgs = r["messages"][:2]
        try:
            resp = requests.post(args.url, json={
                "model": args.model,
                "messages": msgs,
                "stream": False,
                "options": {"temperature": 0, "num_ctx": 16384, "top_p": 0.9},
            }, timeout=180)
            out = resp.json()["message"]["content"]
        except Exception as e:
            failures.append((i, form, f"请求失败: {e}", ""))
            continue

        struct_ok, sum_ok, total, dims, tag, errs = parse_output(out)
        lo, hi, want_tag = FORM_RANGE[form]
        if struct_ok:
            struct_cnt += 1
        if sum_ok:
            sum_cnt += 1
        if tag and f"[{tag}]" == want_tag:
            tag_ok += 1
        if total is not None and lo <= total <= hi:
            range_ok += 1
        if not struct_ok or (tag and f"[{tag}]" != want_tag):
            failures.append((i, form, "; ".join(errs) or "标签错", out[:160].replace("\n", " ⏎ ")))

    n = len(rows)
    print(f"model={args.model}  samples={n}")
    print(f"  结构合规率(三行+标签+可解析): {struct_cnt}/{n} = {struct_cnt/n*100:.1f}%")
    print(f"  五维和=总分           : {sum_cnt}/{n} = {sum_cnt/n*100:.1f}%")
    print(f"  档位标记命中          : {tag_ok}/{n} = {tag_ok/n*100:.1f}%")
    print(f"  分数区间命中          : {range_ok}/{n} = {range_ok/n*100:.1f}%")
    if failures and args.verbose:
        print("  ---- 失败样例 ----")
        for f in failures[:8]:
            print(f"  #{f[0]} {f[1]} | {f[2]} | {f[3]}")
    return 0 if struct_cnt == n else 1


if __name__ == "__main__":
    sys.exit(main())
