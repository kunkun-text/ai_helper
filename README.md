# AI-helper 答辩辅助系统

微信小程序 + Spring Boot 的 AI 模拟答辩系统：学生与本地大模型进行 10 轮答辩（5 道预设题 + 至多 5 次追问），模型按五维（表达 / 逻辑 / 专业 / 应变 / 创新）逐轮点评打分，教师端可查看记录、复审与导出。

## 技术栈

| 层 | 选型 |
|---|---|
| 后端 | Java 17 · Spring Boot 3.5.11 · MyBatis · MySQL 8 · Redis |
| 前端 | 微信小程序（原生） |
| AI | Ollama 本地模型 `qwen2.5:3b-defense-v2`（QLoRA 微调版）；云端 GLM / DeepSeek 作为评分复核与报告评析的候选 |
| 语音 | whisper.cpp（ASR）· SAPI（TTS） |

## 目录结构

```
ai/
├── pom.xml                     # Maven 构建
├── README.md                   # 本文件（项目入口）
├── AGENTS.md                   # AI 协作入口：定位、开工提醒、状态速查
├── CLAUDE.md                   # 协作契约：技术栈、红线、编码规范、必读文档
├── CHANGES.md                  # 改动日志（按时间倒序，只搜不读）
├── src/
│   ├── main/java/              # 后端源码（Config / Controller / Service / mapper / util …）
│   ├── main/resources/         # 配置、Mapper XML、static/Ai（微信小程序）
│   └── test/java/              # 单测
├── docs/                       # 全部文档与数据库脚本
│   ├── README.md               # 文档地图（先看这里）
│   ├── planning/               # 需求清单、新阶段路线图
│   ├── ft/                     # 模型微调（手册 / 题库 / 评测 / 图表）
│   ├── review/                 # 全项目审查（清单 + 施工指令）
│   ├── history/                # 历史清单归档
│   ├── schema.sql              # 12 张表结构
│   ├── ddl_*.sql               # 增量建表脚本
│   └── migration-*.sql         # 数据迁移脚本
├── deploy/
│   └── ollama/                 # Ollama Modelfile（qwen2.5:3b-16k 等）
├── scripts/                    # 运维与开发脚本
│   ├── diagnose.ps1            # 答辩前自检（表/索引/环境/推理抽测）
│   ├── install-whisper.ps1     # whisper.cpp 一键安装
│   └── ft/                     # 微调：生成数据 / 训练 / 导出 / 评测 / 绘图
├── logs/                       # 运行日志（gitignore）
└── target/                     # 构建产物（gitignore）
```

## 快速开始

```bat
:: 1) 编译（必须 clean，增量编译会假绿）
D:\maven\apache-maven-3.8.1\bin\mvn.cmd -o clean compile

:: 2) 跑测试（基线 117/117）
D:\maven\apache-maven-3.8.1\bin\mvn.cmd -o clean test

:: 3) 答辩前自检
powershell -ExecutionPolicy Bypass -File .\scripts\diagnose.ps1

:: 4) 启动 Ollama（模型在 F:\ollama\models）
F:\ollama\Ollama\start_ollama.bat
```

小程序：用微信开发者工具导入 `src/main/resources/static/Ai`。

## 文档入口

| 想知道什么 | 看哪里 |
|---|---|
| 文档太多，先读哪个 | [`docs/README.md`](./docs/README.md) |
| 写代码前确认规矩 | [`CLAUDE.md`](./CLAUDE.md) |
| 当前需求 / 阶段进度 | [`docs/planning/`](./docs/planning) |
| 某天改了什么 | [`CHANGES.md`](./CHANGES.md) |
| 改模型 / 调评分格式 | [`docs/ft/微调手册-2026-11-12.md`](./docs/ft/微调手册-2026-11-12.md) |
| 历史审查结论 | [`docs/review/审查-2026-11-05.md`](./docs/review/审查-2026-11-05.md) |
| 部署到新机器 | [`docs/从零部署手册-8G低功耗标准.md`](./docs/从零部署手册-8G低功耗标准.md) |

## 约定

- `application.yml` 含密钥，**不入库**；模板见 `application.yml.example`。
- 协议标签统一走 `AiProtocolConstants`，不要硬编码。
- 提交前必须 `mvn -o clean test` 通过，且不提交 `target/`、`logs/`、模型产物。
