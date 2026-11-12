# -*- coding: utf-8 -*-
"""
把训练好的 LoRA 适配器导出为 GGUF 并注册到 Ollama（2026-11-12）

前置：F:\\ft-out\\adapter 已由 train_qlora.py 产出。

用法：
    F:\\ai-ft-env\\Scripts\\python.exe scripts\\ft\\export_gguf.py              # Q4_K_M（默认，约 2GB）
    F:\\ai-ft-env\\Scripts\\python.exe scripts\\ft\\export_gguf.py --q8        # Q8_0（更准，约 3.5GB）

产物：
    F:\\ft-out\\qwen2.5-3b-defense-Q4_K_M.gguf
    F:\\ft-out\\Modelfile-defense
    然后执行（脚本会打印命令，不自动跑）：
    ollama create qwen2.5:3b-defense -f F:\\ft-out\\Modelfile-defense
"""

import argparse
import os
from pathlib import Path

FT_HOME = Path(os.environ.get("FT_HOME", r"D:\应用源文件\AI微调环境"))
os.environ.setdefault("HF_HOME", str(FT_HOME / "hf-cache"))
os.environ.setdefault("HF_ENDPOINT", "https://hf-mirror.com")
os.environ.setdefault("TOKENIZERS_PARALLELISM", "false")

ROOT = Path(__file__).resolve().parents[2]
ADAPTER_DIR = FT_HOME / "ft-out" / "adapter"
OUT_DIR = FT_HOME / "ft-out"


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--q8", action="store_true", help="导出 Q8_0 而不是 Q4_K_M")
    p.add_argument("--base", default="Qwen/Qwen2.5-3B-Instruct")
    p.add_argument("--cpu", action="store_true",
                   help="强制在 CPU 上合并（6GB 显存放不下 16bit 3B 时必须加）")
    args = p.parse_args()

    if not ADAPTER_DIR.exists():
        raise SystemExit(f"找不到适配器目录：{ADAPTER_DIR}，先跑 train_qlora.py")

    quant = "q8_0" if args.q8 else "q4_k_m"
    tag = "Q8_0" if args.q8 else "Q4_K_M"

    import torch
    from unsloth import FastLanguageModel

    print(f"[load] base={args.base} adapter={ADAPTER_DIR} cpu={args.cpu}")
    kwargs = dict(
        model_name=str(ADAPTER_DIR),       # 直接加载 adapter 目录（含 base 引用）
        max_seq_length=2048,
        dtype=torch.float16,
        load_in_4bit=False,                # 导出要合并为 16bit
    )
    if args.cpu:
        # 6GB 显存装不下 16bit 3B（约 6.2GB），强制放 CPU 合并（需要 ~8GB 内存）
        kwargs["device_map"] = {"": "cpu"}
    model, tokenizer = FastLanguageModel.from_pretrained(**kwargs)

    out = OUT_DIR / f"qwen2.5-3b-defense-{tag}"
    print(f"[export] {quant} -> {out}.gguf")
    model.save_pretrained_gguf(str(out), tokenizer, quantization_method=quant)

    gguf = Path(str(out) + ".gguf")
    if not gguf.exists():
        # unsloth 有时会输出到目录内
        cand = list(OUT_DIR.glob(f"*{tag}*.gguf"))
        if cand:
            gguf = cand[0]
    print(f"[ok] {gguf}  ({gguf.stat().st_size/1024**3:.2f} GB)")

    modelfile = OUT_DIR / "Modelfile-defense"
    modelfile.write_text(
        f'FROM {gguf.as_posix()}\n'
        'PARAMETER num_ctx 16384\n'
        'PARAMETER temperature 0\n'
        'PARAMETER top_p 0.9\n'
        'PARAMETER repeat_penalty 1.05\n',
        encoding="utf-8",
    )
    print(f"[ok] {modelfile}")
    print("\n下一步手动执行：")
    print(f'  ollama create qwen2.5:3b-defense -f "{modelfile}"')
    print("  ollama run qwen2.5:3b-defense \"测试一句\"")


if __name__ == "__main__":
    main()
