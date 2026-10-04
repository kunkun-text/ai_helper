# AI-helper 项目 — AI 协作入口

> 本文件是 AI 进入项目的**快速索引页**。
> 详细约定见 `CLAUDE.md`，历史改动见 `CHANGES.md`，需求状态见 `需求清单-2026-09-15.md`。

---

## 一句话定位

**AI-helper**：微信小程序 + Spring Boot 后端，学生与本地 Ollama `qwen2.5:3b-16k` 进行 10 轮模拟答辩（5 预设题 + 5 追问），AI 按五维度评分落库。

---

## 快速导航

| 我想知道 | 看这里 |
|---|---|
| 项目结构、技术栈、操作红线 | [`CLAUDE.md`](./CLAUDE.md) |
| 当前需求状态（已完成/待开发/待拍板） | [`需求清单-2026-09-15.md`](./需求清单-2026-09-15.md) |
| 某次改动的详细上下文 | [`CHANGES.md`](./CHANGES.md) |
| 数据库表结构（10张表） | [`docs/schema.sql`](./docs/schema.sql) |
| 从零部署步骤 | [`docs/从零部署手册-8G低功耗标准.md`](./docs/从零部署手册-8G低功耗标准.md) |
| 答辩业务流程与AI输出格式 | [`docs/答辩系统-项目情况说明.md`](./docs/答辩系统-项目情况说明.md) |
| 写/改代码前的协作规范 | [`.claude/skills/ai-defense-dev/SKILL.md`](./.claude/skills/ai-defense-dev/SKILL.md) |

---

## 当前状态速查（2026-10-05）

| 维度 | 状态 |
|---|---|
| 核心答辩流程 | ✅ 稳定运行；答辩链路已鉴权（A1，伪造他人 userId 会被拒） |
| 评分可信度 | 🔧 已实现待复验（N45~N54） |
| 教师端功能 | ✅ 已上线（总览/导出/反馈）；课题归属取登录态（A3，不再写死 teacherId=20） |
| 语音答辩 | 🔧 F10已开发待真机实测；已加 ASR 限大小/并发控制与上传真取消 |
| 事务一致性 | ✅ N55 已闭环：评分行/答案行/追问题同事务，失败整体回滚（B1） |
| 数据库 | ✅ 10 张表（新库 schema.sql 直接含 system_feedback + uk_defense_round） |
| 前端真机验证 | ⚠️ V0待做 |

> 上述整改对应 `上线缺漏整改清单-2026-10-05.md`（A2 小程序生产域名除外，需真实 HTTPS 域名后才能落地）。

---

## 开始编码前

1. 读 `CLAUDE.md` 的「操作红线」和「当前进度」
2. 读 `需求清单-2026-09-15.md` 确认需求编号和状态
3. 读 `.claude/skills/ai-defense-dev/SKILL.md` 了解协作规范
4. 改完后更新 `CHANGES.md` 和 `需求清单`

---

## 常用命令

```bash
# 后端编译验证
mvn -o -q compile

# 前端 JS 语法验证（在项目根目录）
node --check src/main/resources/static/Ai/pages/xxx/xxx.js

# 答辩前自检（脚本在项目根目录，不在 scripts/）
powershell -ExecutionPolicy Bypass -File .\diagnose.ps1

# 8G 低功耗答辩机建议加 -StrictLowPower：低功耗环境变量缺失时判失败
powershell -ExecutionPolicy Bypass -File .\diagnose.ps1 -StrictLowPower

# 数据库建表（反馈模块）
mysql -uroot -p ai_helper < docs/ddl_system_feedback.sql
```

---

> 历史版本：本文件由原版 `AGENTS.md` 重构而来，截至 2026-10-04。
