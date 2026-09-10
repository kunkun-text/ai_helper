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

---

# 2026-09-10 改动（答辩 Bug 修复 + 每次答辩独立记录）

> 改动日期：2026.9.10，涉及 9 个代码/前端文件 + 本文档

## 改动概述

修复两个答辩流程 Bug，并将答辩记录模型重构为「一次答辩 = 一条独立记录」：

1. **Bug 1**：学生回答"不知道/不会"类短语时，AI 直接结束答辩给分 → 改为固定模板零分流程，继续下一题
2. **Bug 2**：答辩结束后小程序前端看不到答辩记录 → 多原因修复（重复空壳记录、列表无排序、总分从不落库、页面不刷新）
3. **记录模型**：所有答辩挤在同一条历史记录里 → 每次答辩独立生成一条记录，时间显示到分钟

## 一、Bug 1：放弃作答固定零分流程（chatController.java）

- 新增放弃作答判定：回答 ≤15 字且含"不知道/不会/不清楚/不了解/不懂/没学过/没复习/没准备/没印象/跳过"任一短语即命中
- 命中后**不调用评分模型**（零延迟），走固定模板：`点评(0分说明) / 评分:0/50|0|0|0|0|0 / 下一题或总结`
- 预设题阶段说"不会" → 本题五维 0 分落库 → 从题库取下一题继续答辩
- 最后一道预设题说"不会" → 0 分后进入 AI 追问阶段（模型围绕课题生成追问，失败有兜底问题库）
- 追问阶段说"不会" → 0 分继续追问；额度（2 次）用完后说"不会" → 0 分 + 固定总结正常收尾
- 提示词同步加固：禁止模型因"不知道"输出『总结』提前结束答辩
- 修复追问计数泄漏：预设题阶段的"下一题"不再误登记为 AI 追问（此前导致额度计数虚高、防提前总结机制失效）
- 轮次统计口径统一：与 chat() 一致，基于未裁剪历史统计，避免裁剪后轮次错乱

## 二、Bug 2 + 答辩记录模型重构

### 后端
- `getOrCreateDefenseRecord` 语义改为「取该学生该课题**进行中（pending）**的最新一条，没有才新建」
- 进入答辩页的 `/api/chat/clear` 现在会：清理上次遗留的空壳记录（一题未答）+ 创建本次答辩的独立记录（答辩时间=当下，精确到秒）
- 移除 `resetAiFollowUps`：追问额度按答辩记录隔离统计，新记录天然从零开始，无需再删除历史追问数据
- 答辩结束收尾：总结生成时聚合各轮五维平均分（0-50 制）+ 总结写回 `defense_records.score/feedback`，状态置 `completed`
- 学生端记录列表：按 `defense_id` 倒序（最新在前）+ 过滤一题未答的空壳记录 + 返回 `status` 字段

### 前端（小程序）
- 答辩记录页 `onShow` 自动刷新（答辩结束返回立即可见新记录，无需重启小程序）
- 记录时间显示到分钟（如 `2026-09-10 14:30`，兼容 ISO 与空格两种时间格式）
- 未答完就退出的记录显示"未完成"标注，正常结束的显示总分

## 三、涉及文件（9 个）

| 文件 | 改动 |
|------|------|
| `Controller/chatController.java` | 放弃作答固定流程及全套辅助方法、提示词加固、追问计数修复、答辩收尾聚合、clear 端点开启新记录 |
| `Service/DefenseRecordsService.java` | 新增 `finishDefenseRecord`、`startNewDefenseRecord`，移除 `resetAiFollowUps` |
| `Service/Impl/DefenseRecordsServiceImpl.java` | 实现上述接口；`getOrCreateDefenseRecord` 改为 pending 最新语义 |
| `mapper/DefenseRecordsMapper.java` | 新增 `updateFinalResult`、`deleteEmptyShellRecords` |
| `resources/Mapper/DefenseRecordsMapper.xml` | 同上两条 SQL；`getDefenseIdByUserAndTopic` 仅取 pending 最新；学生列表加排序/过滤/status |
| `pojo/vo/DefenseRecordsVo.java` | 新增 `status` 字段 |
| `pom.xml` | 移除重复声明的 `spring-boot-starter-web` 依赖 |
| `static/Ai/pages/student/student.js` | onShow 刷新记录、时间格式化到分钟、status 处理 |
| `static/Ai/pages/student/student.wxml` | 记录卡片"未完成"状态标注（首页与记录页两处） |

## 四、数据库变更（已于 2026-09-10 手动执行）

```sql
-- 1. 清理历史重复空壳记录（defense_records 无唯一约束时 ON DUPLICATE KEY 失效，积累 172 条；
--    全部子表数据挂在 defense_id=26 上，已备份至 defense_records_bak_20260910）
DELETE FROM defense_records WHERE user_id=23 AND topic_id=28 AND defense_id > 26;

-- 2. 老记录补聚合总分（按 defense_score_record 五维平均）并完结
UPDATE defense_records SET score=23.3, status='completed' WHERE defense_id=26;

-- 3. (user_id, topic_id) 保持普通索引——新模型允许多次答辩多条记录，不能加唯一索引
ALTER TABLE defense_records DROP INDEX uk_user_topic, ADD INDEX idx_user_topic (user_id, topic_id);
```

> 注意：曾尝试加唯一索引 `uk_user_topic` 修复重复问题，后因记录模型改为"每次答辩一条"而撤销，勿再添加。

## 五、验证要点

- 重启后端 + 微信开发者工具重新编译小程序
- 答辩中说"不知道" → 该题 0 分并继续下一题；全程说"不知道"也能正常走完并出总分
- 每次答辩在记录列表中生成独立一条（时间到分钟），点开只显示本次问答内容
- 中途退出的记录显示"未完成"，一题未答的不显示
- 运行日志落盘于 `logs/ai-helper.log`（`application.yml` 本地配置，该文件不入库）
