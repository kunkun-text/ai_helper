# 文档地图（2026-11-12 整理）

> **按需读，不要全读。** 先看这张表定位，再打开对应文件。

## 一分钟版

| 你想干什么 | 读这个 |
|---|---|
| 刚接手项目 / 开新会话 | 根目录 `AGENTS.md`（入口 + 开工提醒） |
| 写代码前确认规矩 | 根目录 `CLAUDE.md`（技术栈、红线、当前进度、必读顺序） |
| 查某个需求做没做 | `docs/planning/需求清单-2026-09-15.md` |
| 查某天改了什么 | 根目录 `CHANGES.md`（按时间倒序，只搜不读） |
| 查新阶段（OPT/VID/DOC/BASE）进度 | `docs/planning/新阶段路线图-2026-10-15.md` |
| 改模型 / 调评分格式 | `docs/ft/微调手册-2026-11-12.md` |
| 看历史审查结论与修法 | `docs/review/审查-2026-11-05.md` |
| 部署 / 换机器 | `docs/从零部署手册-8G低功耗标准.md` |
| 理解业务流程与 AI 输出格式 | `docs/答辩系统-项目情况说明.md` |
| 表结构 | `docs/schema.sql` |
| 历史清单（已归档） | `docs/history/` |

## 目录结构

```
ai/
├── README.md                     # 项目入口（技术栈、目录、快速开始）
├── AGENTS.md                     # AI 入口索引：定位、开工提醒、状态速查
├── CLAUDE.md                     # 协作契约：技术栈、红线、编码规范、必读文档
├── CHANGES.md                    # 改动日志（时间倒序，只搜不读）
├── pom.xml                       # Maven 构建
│
├── src/                          # 代码（Maven 标准布局）
│   ├── main/java/                # 后端源码
│   ├── main/resources/           # 配置 + Mapper XML + static/Ai（小程序）
│   └── test/java/                # 单测
│
├── docs/                         # 全部文档与 SQL
│   ├── README.md                 # 本文件（文档地图）
│   ├── planning/                 # 需求清单、新阶段路线图
│   ├── ft/                       # 模型微调
│   │   ├── 微调手册-2026-11-12.md # ★ 唯一入口（数据/训练/评测/运行调试/风险）
│   │   ├── README.md             # 资料索引
│   │   ├── questions-bigdata.json# 105 道题库
│   │   ├── system-prompt.txt     # 线上 system prompt 逐字副本
│   │   ├── eval/                 # metrics.json + figures/（两张图）
│   │   └── raw/ dataset/         # 生成过程与数据集（gitignore）
│   ├── review/审查-2026-11-05.md  # 全项目审查：37 项清单 + 可派工施工指令
│   ├── history/                  # 历史清单：10.28优化、上线缺漏整改清单
│   ├── 答辩系统-项目情况说明.md    # 业务流程 + 五维口径 + 协议标签
│   ├── 从零部署手册-8G低功耗标准.md# 环境搭建与部署
│   ├── schema.sql                # 12 张表结构
│   └── ddl_*.sql / migration-*.sql
│
├── deploy/
│   └── ollama/Modelfile-qwen25   # Ollama 模型定义
│
├── scripts/
│   ├── diagnose.ps1              # 答辩前自检（表/索引/环境/推理）
│   ├── install-whisper.ps1       # whisper.cpp 一键安装
│   └── ft/                       # 生成数据/训练/导出/评测/绘图
│
├── logs/                         # 运行日志（gitignore）
└── target/                       # 构建产物（gitignore）
```

## 维护约定

1. **不再新增平级文档**：新内容优先并入上表已有文件（微调→`docs/ft/微调手册`；审查→`docs/review/审查-YYYY-MM-DD.md`）。
2. `CHANGES.md` 只增不改，按日期分段；单次改动控制在 1 节内。
3. 任何文档移动/合并后，同步更新 `AGENTS.md` 的快速导航与 `CLAUDE.md` 的必读表。
4. 历史文档保持原样（不改写历史），统一放 `docs/history/`。
