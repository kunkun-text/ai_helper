# CLAUDE.md — AI-helper 项目说明

> 本文件供 Claude Code 每次会话自动阅读。项目所有者：学生开发者，初次上手 Claude Code（此前用 Trae），**请用中文交流**。

---

## 一、项目是什么

**AI-helper（AI 答辩辅助系统）**：微信小程序 + Spring Boot 后端。
学生选择答辩课题后，与 AI（本地大模型）进行多轮模拟答辩对话；AI 按五个维度
（表达 / 逻辑 / 专业 / 应变 / 创新）逐轮评分并落库，最后生成总评；教师端管理答辩课题、查看答辩记录。

```
小程序答辩页 → POST /api/chat → chatController → Spring AI ChatClient → 本地 Ollama
                                          ↓
                       Redis 存会话记忆（最多 12 条消息，超出裁剪最早的）
                                          ↓
             每轮从回复中解析管道格式评分 → ScorePersistenceService 异步写入 MySQL
```

## 二、技术栈

| 层 | 技术 |
|---|---|
| 后端 | Spring Boot 3.5.11 + Java 17 + Maven（入口类 `AiHelperApplication`） |
| AI | Spring AI 1.1.2（OpenAI 兼容模式）→ 本地 Ollama `http://localhost:11434`，模型 **qwen2.5:3b-16k** |
| 数据 | MySQL（库名 `ai_helper`）+ MyBatis + PageHelper；Redis（存 AI 会话记忆） |
| 安全 | Spring Security + JWT（`AuthInterceptor` 做 token 校验，区分 student / teacher 角色） |
| 文件 | 阿里云 OSS（答辩报告、视频上传，视频支持分片：init/upload/complete/abort） |
| 前端 | 微信小程序，位于 `src/main/resources/static/Ai/`（app.js + pages/） |

## 三、目录速查

```
src/main/java/com/ai_helper/ai_helper/
  Controller/login/      注册、登录、忘记/重置密码
  Controller/student/    答辩记录、报告上传、视频上传
  Controller/teacher/    课题管理（addDefense/editDefense）、记录查询
  Controller/chatController.java   ★ AI 对话核心（/api/chat 等）
  Config/                Security / Redis / OSS / MyBatis / ChatConfiguration
  Service/ + Impl/       业务层（ScorePersistenceService 为评分落库）
  mapper/ + pojo/        MyBatis 接口与实体
src/main/resources/
  Mapper/*.xml           SQL（注意：chatController.xml 是 DefenseStudentQuestionsMapper 的，
                          chatController_1775616568352.xml 是 DefenseAnswersMapper 的——文件名有误导性，都不是备份）
  application.yml        ★ 本地配置，已被 .gitignore 排除，绝不能提交
  static/Ai/             微信小程序前端（答辩页 pages/defense/ 三件套）
docs/ddl_defense_score_record.sql   评分表 DDL（需手动在 MySQL 执行）
CHANGES.md               最近一轮改动的详细记录
diagnose.ps1             Ollama 状态/显存/推理速度诊断脚本
AI-helper/（根目录同名子文件夹）  只有一个游离的 student.js，是残留文件，勿引用
```

## 四、本地运行环境

| 组件 | 要求 |
|---|---|
| Ollama | 需先 `ollama pull qwen2.5:3b-16k`；本机显卡 RTX 3050 只有 4G 显存，**不要换更大的模型**（之前用 8b 直接把显存打爆跑去 CPU，还产生过 JVM 崩溃日志 hs_err_pid*.log） |
| MySQL | root / 123456 @127.0.0.1:3306/ai_helper，执行过 `docs/ddl_defense_score_record.sql` |
| Redis | 127.0.0.1:6379，密码 123456 |
| 启动后端 | 项目根目录 `mvn spring-boot:run`，端口 8080 |
| 小程序 | 微信开发者工具打开 `src/main/resources/static/Ai/` 目录 |

## 五、AI 对话协议（改 chatController 前必读）

- AI 回复采用**管道格式**：`总分/50|表达|逻辑|专业|应变|创新|优点|建议|下一题`
  例：`36/50|8|7|8|6|7|概念清晰|多举例子|下一题`
- ⚠️ **2026-09-14 起本轮答辩为「5 预设题 + 5 追问 = 10 轮」**，追问阶段模型也统一输出 **`下一题:`**（**不要用"追问:"**，前端 `defense.js parseSegmentFormat` 只匹配 下一题/下一问，不认"追问:"，用错会导致前端解析不到题、变"有分无题"）。
- 结束条件已加**轮次硬上限兜底**：轮次按 `defense_score_record` 落库行数 +1 权威计数（2026-09-15 修复；旧实现按 Redis 历史消息数计数，被 `trimChatMemory` 截断封顶在 6，兜底永不触发），第 `existingQuestionCount + EXTRA_QUESTION_LIMIT`（=10）次作答即收尾——模型输出"总结:"用总结，没输出则剥残留"下一题:"并补占位总结强制收尾，防止无限"下一题"死循环（这是内存崩溃根因，已修）。
- 前端 `defense.js` 用 `parsePipeFormat()` 解析；`ScorePersistenceServiceImpl.parseScoresFromResponse()` 负责提取五维分
- 会话记忆在 Redis，键按用户/答辩区分；`MAX_HISTORY_MESSAGES = 12`，超限裁剪最早消息
- 进场时前端会调 `POST /api/chat/clear` 清空旧会话；答辩结束调 `POST /api/final-evaluate` 聚合总分
- 对模型用阻塞式 `.call()`（不是流式 `.stream()`），前端超时 180s，有重试栏

## 六、主要接口

| 接口 | 说明 |
|---|---|
| `GET/POST /api/chat` | AI 对话（核心），`/api/chat/clear` 清记忆，`/api/final-evaluate` 总评 |
| `POST /register/student`、`/register/teacher`、`/login/student`、`/login/teacher` | 注册登录 |
| `POST /api/forgot-password`、`/api/auth/reset-password` | 邮件验证码找回密码 |
| `GET /student/DefenseRecords` 等 | 学生查答辩记录 |
| `POST /teacher/addDefense`、`/teacher/defense/records` | 教师管理课题/查记录 |
| `POST /api/report/upload`、`GET /api/report/url` | 报告上传（OSS） |
| `POST /api/video/init|upload|complete|abort`、`GET /api/video/processing-status` | 视频分片上传+处理 |

## 七、操作约定（Claude 必守）

1. **中文交流**；代码注释跟随现有风格（中文为主）。
2. **绝不提交**：`application.yml`（含本地密码和 QQ 邮箱，已在 .gitignore）、任何 `*.log` / `hs_err_pid*` 崩溃日志、根目录重复的 `project.config.json`。
3. **Git**：远程是 `github.com/kunkun-text/ai_helper`（别人的仓库！本机凭据账号是 chew303-cmd，尚未被加为协作者，推送会 403）。提交信息用中文一行标题 + 可选正文，风格参考历史（如「完善答辩」）。
4. 改 `chatController.java` / `defense.js` 这类大文件时先读再改，改动要克制，遵循现有代码风格。
5. 后端报错时先看是否 Ollama 未启动 / 显存不足（用 `diagnose.ps1` 诊断），再查代码。
6. 开发协作流程（提问、review、开发标准）见 `.claude/skills/ai-defense-dev/SKILL.md`

## 八、当前进行中的事（2026-09-15 更新）

**9-14 十轮答辩改造 + 9-15 轮次计数修复均已完成：编译通过、实测未复现 bug、已提交。**

- 9-15 核心修复：轮次/收尾判断改按 `defense_score_record` 落库行数 +1 权威计数（旧实现按 Redis 历史消息数计数，被 `trimChatMemory` 物理截断封顶在 6，导致轮次号落库卡死、防死循环兜底永不触发——9-14 晚两场实测实锤，根因与修复详见 `CHANGES.md`「2026-09-15 改动」）。
- 实测遗留的小问题（非阻塞）见 `CHANGES.md` 9-15 节「遗留」：前端重试可产生重复评分行、追问阶段放弃作答的答案行可能跳过落库、模型偶发"总分≠五维之和"（落库/展示已按五维和纠正）。
- 待办：JVM 加 `-Xmx512m`、Ollama `OLLAMA_NUM_PARALLEL=1` 并发限制仍未做，机器仅 7G 内存，建议尽快。
- 编译注意：PATH 无 mvn，用 `& "D:\maven\apache-maven-3.8.1\bin\mvn.cmd" -o compile`（JDK 17，JAVA_HOME 已配好）。

**历史背景（备忘）**：远程 `github.com/kunkun-text/ai_helper` 是别人的仓库，**不要改写历史或强推**；`application.yml`、`*.log`、`hs_err_pid*` 绝不提交。运行环境要求见上文第四节（Ollama 模型 `qwen2.5:3b-16k`，RTX 3050 4G 显存勿换大模型）。
