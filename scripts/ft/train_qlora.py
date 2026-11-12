# -*- coding: utf-8 -*-
"""
AI-helper 微调训练脚本（QLoRA · 2026-11-12）

用法（在 F:\\ai-ft-env 环境里跑）：
    F:\\ai-ft-env\\Scripts\\python.exe scripts\\ft\\train_qlora.py --smoke     # 只跑 10 步，验证显存与链路
    F:\\ai-ft-env\\Scripts\\python.exe scripts\\ft\\train_qlora.py             # 正式训练（2 epoch）

产物：
    F:\\ft-out\\adapter\\             LoRA 适配器 + tokenizer
    F:\\ft-out\\train.log            训练日志

设计约束（RTX 2060 6GB / Turing）：
    - 只能 QLoRA 4bit；fp16（2060 无 bf16）
    - 序列 1536，batch 1 + 梯度累积 8
    - 关掉 flex attention（当前 triton 版本不匹配，会退化成 eager 且更吃显存）
"""

import argparse
import json
import os
import sys
from pathlib import Path

# ---- 环境变量必须在 import transformers 之前设置 ----
# 训练环境统一放在 D 盘（2026-11-12 迁移），可用 FT_HOME 覆盖
FT_HOME = Path(os.environ.get("FT_HOME", r"D:\应用源文件\AI微调环境"))
os.environ.setdefault("HF_HOME", str(FT_HOME / "hf-cache"))
os.environ.setdefault("HF_ENDPOINT", "https://hf-mirror.com")   # 国内镜像
os.environ.setdefault("TOKENIZERS_PARALLELISM", "false")
os.environ.setdefault("UNSLOTH_ENABLE_LOGGING", "0")

ROOT = Path(__file__).resolve().parents[2]
DATA_DIR = ROOT / "docs" / "ft" / "dataset"
OUT_DIR = FT_HOME / "ft-out"
ADAPTER_DIR = OUT_DIR / "adapter"


def parse_args():
    p = argparse.ArgumentParser()
    p.add_argument("--model", default="Qwen/Qwen2.5-3B-Instruct")
    p.add_argument("--max-seq-length", type=int, default=1536)
    p.add_argument("--epochs", type=float, default=2.0)
    p.add_argument("--lr", type=float, default=2e-4)
    p.add_argument("--lora-r", type=int, default=16)
    p.add_argument("--accum", type=int, default=8)
    p.add_argument("--smoke", action="store_true", help="只跑 10 步，验证链路")
    p.add_argument("--max-steps", type=int, default=-1)
    return p.parse_args()


def load_jsonl(path):
    rows = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                rows.append(json.loads(line))
    return rows


def main():
    args = parse_args()
    OUT_DIR.mkdir(parents=True, exist_ok=True)

    train_rows = load_jsonl(DATA_DIR / "train.jsonl")
    val_rows = load_jsonl(DATA_DIR / "val.jsonl")
    print(f"[data] train={len(train_rows)} val={len(val_rows)}")
    if not train_rows:
        sys.exit("训练集为空，先跑 scripts/ft/gen-dataset.ps1")

    import torch
    from datasets import Dataset
    from unsloth import FastLanguageModel, is_bfloat16_supported
    from trl import SFTTrainer, SFTConfig

    # 关掉 flex attention：Triton 3.9 与 torch 2.5.1 不匹配，开了反而更吃显存
    try:
        from unsloth import unsloth_train  # noqa: F401
    except Exception:
        pass
    try:
        import unsloth_zoo
        os.environ["UNSLOTH_USE_FLEX_ATTENTION"] = "0"
    except Exception:
        pass

    print(f"[gpu] {torch.cuda.get_device_name(0)} | "
          f"{torch.cuda.get_device_properties(0).total_memory / 1024**3:.1f} GB")

    model, tokenizer = FastLanguageModel.from_pretrained(
        model_name=args.model,
        max_seq_length=args.max_seq_length,
        dtype=torch.float16,          # 2060 不支持 bf16
        load_in_4bit=True,            # QLoRA
        attn_implementation="sdpa",   # 避开 flex attention
    )

    model = FastLanguageModel.get_peft_model(
        model,
        r=args.lora_r,
        target_modules=["q_proj", "k_proj", "v_proj", "o_proj",
                        "gate_proj", "up_proj", "down_proj"],
        lora_alpha=args.lora_r,
        lora_dropout=0.05,
        bias="none",
        use_gradient_checkpointing="unsloth",
        random_state=3407,
        use_rslora=False,
        loftq_config=None,
    )

    def to_text(row):
        # 与线上一致的 ChatML 模板；只训练 assistant 段（train_on_responses_only 可选）
        return {"text": tokenizer.apply_chat_template(
            row["messages"], tokenize=False, add_generation_prompt=False)}

    ds_train = Dataset.from_list([to_text(r) for r in train_rows])
    ds_val = Dataset.from_list([to_text(r) for r in val_rows]) if val_rows else None

    smoke = args.smoke
    cfg = SFTConfig(
        output_dir=str(OUT_DIR / "checkpoints"),
        per_device_train_batch_size=1,
        gradient_accumulation_steps=args.accum,
        warmup_steps=10,
        num_train_epochs=args.epochs,
        max_steps=(10 if smoke else args.max_steps),
        learning_rate=args.lr,
        fp16=not is_bfloat16_supported(),
        bf16=is_bfloat16_supported(),
        logging_steps=5,
        save_steps=100,
        save_total_limit=2,
        optim="paged_adamw_8bit",
        weight_decay=0.01,
        lr_scheduler_type="cosine",
        seed=3407,
        dataset_text_field="text",
        max_seq_length=args.max_seq_length,
        packing=False,
        report_to="none",
        dataloader_num_workers=0,
    )

    trainer = SFTTrainer(
        model=model,
        tokenizer=tokenizer,
        train_dataset=ds_train,
        eval_dataset=ds_val,
        args=cfg,
    )

    print("[train] start")
    trainer.train()

    ADAPTER_DIR.mkdir(parents=True, exist_ok=True)
    model.save_pretrained(str(ADAPTER_DIR))
    tokenizer.save_pretrained(str(ADAPTER_DIR))
    print(f"[done] adapter saved -> {ADAPTER_DIR}")
    print(f"[mem] peak = {torch.cuda.max_memory_reserved() / 1024**3:.2f} GB")


if __name__ == "__main__":
    main()
