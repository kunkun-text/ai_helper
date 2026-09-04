# AI 答辩辅助系统 — 改动记录

> 基于 git commit `508d803`（母版），截至 2026-07-31

---

## 改动概述

| 类别 | 文件数 | 变更量 |
|------|--------|--------|
| 修改已有文件 | 7 个 | +651 / -661 行 |
| 新增文件 | 11 个 | 全新 |
| 总变更 | 18 个 | — |

---

## 一、修改的已有文件（7 个）

### 1. `application.yml` — Ollama 模型 & 超时配置

| 配置项 | 原值 | 新值 | 原因 |
|--------|------|------|------|
| `model` | `qwen3:8b` | `qwen3:4b-16k` | RTX 3050 只有 4GB 显存，8B 模型跑在 CPU 上 |
| `read-timeout` | 无 | `180s` | 防止频繁超时断连 |
| `connect-timeout` | 无 | `30s` | 新增 |
| `async.request-timeout` | 无 | `180000` | 异步请求兼容 |

### 2. `ChatConfiguration.java` — ChatClient 构建简化

- `@Bean chatClient(ChatModel)` — 移除 `TextTools` 参数和 `.defaultTools(textTools)`（避免每次推理评估工具调用浪费 token）
- 后又在稳定版中恢复 `defaultTools`

### 3. `chatController.java` — 核心对话逻辑（最大改动）

**Prompt 策略改造：**
- `buildQuestionModePrompt()` 重写：首轮发全部题目摘要（答案截断 ≤50 字），后续轮只发当前题
- 系统提示词改为极简管道格式：`总分/50|表达|逻辑|专业|应变|创新|优点|建议|下一题`
- 去掉寒暄、去除冗余格式说明

**上下文管理：**
- 新增 `MAX_HISTORY_MESSAGES = 12`（保留最近 6 轮 × 2 条）
- `sendMessageWithMemory()` 裁剪逻辑：超限丢弃最早的消息
- `trimChatMemory()` 同步裁剪 Redis 中的 ChatMemory
- 新增 `/api/chat/clear` 接口 + 前端进场自动清理旧会话

**模型调用：**
- 从 `Flux<String>` 流式 `.stream()` → `String` 阻塞 `.call()`
- 添加 `.options(OpenAiChatOptions.builder().maxTokens(60).model("qwen3:4b-16k").build())` 强制限制输出
- 后稳定版中移除 `maxTokens`（改为提示词约束），保留阻塞式调用

**Post-processing：**
- `doOnComplete` 回调 → 同步执行（配合阻塞调用）
- 5 维评分（表达/逻辑/专业/应变/创新）从 AI 回复中一次扫描提取
- 新增 `@Value("${spring.ai.openai.chat.options.model:unknown}")` 日志打印实际模型名

**新增接口：**
- `POST /api/chat/clear` — 清理 Redis 会话记忆
- `POST /api/final-evaluate` — 从 DB 聚合各轮分数，生成总体评价

**辅助方法：**
- `toFlux(String)` — 将 String 包装为 Flux（保持旧接口兼容）
- `truncateSummary(text, 50)` — 答案截断为 50 字
- `countAssistantMessages()` — 统计历史 AI 消息数（判断当前轮次）

### 4. `defense.js` — 前端答辩页

- 超时：60s → 180s
- 新增等待计时器：AI 思考气泡下方显示 `⏳ 已等待 X 秒`
- 新增重试机制：`lastError` + `showRetry` 状态 + `retryLastMessage()` 方法
- 新增 `parsePipeFormat()`：解析 AI 返回的管道格式 `36/50|8|7|8|6|7|概念清晰|多举例|下一题`
- `onLoad` 中自动调 `/api/chat/clear` 清 Redis 旧会话
- `sendMessage()` 增加 `isAiThinking` 防重复发送 + 60s 安全超时
- 按钮禁用条件：`!inputText.trim()` → `!inputText`（兼容微信工具表达式求值）

### 5. `defense.wxml` — 答辩页模板

- AI 思考气泡内新增 `<text class="wait-timer">⏳ 已等待 {{waitSeconds}} 秒</text>`
- 输入栏上方新增重试栏：`<view class="retry-bar" wx:if="{{showRetry}}">`

### 6. `defense.wxss` — 答辩页样式

- 新增 `.wait-timer` 样式（灰色小字）
- 新增 `.retry-bar` / `.retry-btn` 样式（红色错误信息 + 橙色重试按钮）

### 7. `project.config.json` / `project.private.config.json` — 小程序配置

- 调整为当前开发环境设定

---

## 二、新增文件（11 个）

### 五维评分持久化（5 个）

| 文件 | 作用 |
|------|------|
| `pojo/entity/DefenseScoreRecord.java` | 评分实体（defenseId, questionId, roundNum, 5 维分数, comment, createdAt） |
| `mapper/DefenseScoreRecordMapper.java` | MyBatis Mapper 接口（insert + 聚合查询） |
| `resources/Mapper/DefenseScoreRecordMapper.xml` | SQL（插入单条 + AVG 聚合查询） |
| `Service/ScorePersistenceService.java` | 接口（saveRoundScoreAsync + parseScoresFromResponse + buildFinalEvaluatePrompt） |
| `Service/Impl/ScorePersistenceServiceImpl.java` | 实现（异步写入 + 管道格式解析 + 兜底默认值） |

### 基础设施（3 个）

| 文件 | 作用 |
|------|------|
| `Config/GlobalExceptionHandler.java` | 全局异常处理（静默吞掉 `ClientAbortException`） |
| `diagnose.ps1` | 一键诊断脚本（Ollama 状态 / 显存占用 / 推理速度） |
| `docs/ddl_defense_score_record.sql` | `defense_score_record` 表 DDL |

### 小程序根配置（2 个）

| 文件 | 作用 |
|------|------|
| `project.config.json` | 小程序项目配置 |
| `project.private.config.json` | 小程序私有配置 |

---

## 三、Git 提交历史（最近 5 次，母版截止点）

```
508d803 完善答辩         ← 母版基准
5fd892c 完善答辩
0d632c6 完善ai答辩
e4d6d2a 修复大模型会话记忆不保存问题
dacffd4 接入千问模型实现ai会话，使用redis存储会话记忆
```

---

## 四、未提交变更清单

```
modified:   application.yml
modified:   ChatConfiguration.java
modified:   chatController.java
modified:   defense.js
modified:   defense.wxml
modified:   defense.wxss
modified:   project.config.json
modified:   project.private.config.json

new:        diagnose.ps1
new:        docs/ddl_defense_score_record.sql
new:        GlobalExceptionHandler.java
new:        ScorePersistenceServiceImpl.java
new:        ScorePersistenceService.java
new:        DefenseScoreRecordMapper.java
new:        DefenseScoreRecord.java
new:        DefenseScoreRecordMapper.xml
```

---

## 五、运行环境要求

| 组件 | 版本/配置 |
|------|----------|
| Ollama 模型 | `qwen3:4b-16k`（需手动 `ollama pull`，约 2.5GB） |
| Ollama 环境变量 | `OLLAMA_NUM_GPU=999`，`OLLAMA_KEEP_ALIVE=-1` |
| MySQL 新增表 | 执行 `docs/ddl_defense_score_record.sql` |
| Redis | 无需改动 |
| 小程序 | 微信开发者工具 → 清除缓存 → 重新编译 |
