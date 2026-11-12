# docs/ft —— 模型微调资料索引

> 2026-11-12。训练环境在 `D:\应用源文件\AI微调环境`，不在仓库里。

| 文件 / 目录 | 作用 |
|---|---|
| `questions-bigdata.json` | **105 道**题库（现有 25 + 新增 28 + 扩展 52），含 `keyPoints`，可跨课题复用 |
| `system-prompt.txt` | 线上 `chatController.EXAMINER_SYSTEM_RULES` 的逐字副本，训练/评测共用 |
| `raw/` | 每条 DeepSeek 原始生成结果（断点续跑用，**已 gitignore**） |
| `dataset/` | `train.jsonl` 468 条 / `val.jsonl` 50 条 / `rejected.jsonl` / `stats.json`（**已 gitignore**） |

## 相关脚本（`scripts/ft/`）

| 脚本 | 作用 | 典型命令 |
|---|---|---|
| `gen-dataset.ps1` | 调 DeepSeek 生成五形态样本（断点续跑） | `powershell -File scripts\ft\gen-dataset.ps1 -Limit 5` |
| `train_qlora.py` | QLoRA 训练（unsloth） | `python scripts\ft\train_qlora.py --smoke` |
| `export_gguf.py` | 合并/导出 GGUF（6GB 显存需 `--cpu`，但会撞页面文件上限） | 见 `D:\应用源文件\AI微调环境\README.md` 的 LoRA-GGUF 方案 |
| `eval_ollama.py` | 对任意 Ollama 模型跑格式/档位/分数区间评测 | `python scripts\ft\eval_ollama.py --model qwen2.5:3b-defense --n 20` |

## 结论入口

- **唯一入口**：`微调手册-2026-11-12.md` —— 问题证据、数据规格、训练参数、评测对比、部署形态、运行与反复调试、备选方案、产物路径、风险清单（已合并原方案/开工前/成果三份文档）
