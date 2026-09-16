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

---

# 2026-09-14 改动（10 轮答辩改造 + 补题库 + 内存崩溃修复）

> 改动日期：2026.9.14，涉及 chatController.java + MySQL 题库数据
> **⚠️ 交接状态：所有代码改动已写入但【尚未编译、尚未提交、尚未实测 10 轮流程】。** 见文末「待办」。

## 目标
把答辩从旧的「预设题 + 2 次追问」改为 **5 道预设题 + 5 次 AI 追问 = 共 10 轮**，配合题库补题达到每个课题 ≥5 道题，实现 5+5=10 轮才允许总结出总分。

## 一、代码改动（全部在 chatController.java）

### 1. 追问额度 2 → 5
```java
- private static final int EXTRA_QUESTION_LIMIT = 2;
+ private static final int EXTRA_QUESTION_LIMIT = 5;   // 5 预设题 + 5 追问 = 10 轮
```
全局引用均读该常量（`remainingExtra`/`quotaRemains`/`countExtraQuestions`），无硬编码 2 冲突。`resetAiFollowUps` 已移除，未加回。

### 2. 进度标签追加到用户消息末尾
`sendMessageWithMemory` 末尾拼：
```
[进度: 第X题/共Y题, 已追问Z/5次]
```
`Y = existingQuestionCount + EXTRA_QUESTION_LIMIT`，让模型无需回看历史也知道进度。`MAX_HISTORY_MESSAGES` 保持 12（不调大，避免撑爆 16k 上下文）。

### 3. 系统提示词加固（buildQuestionModePrompt）
- 明确「本场约 10 轮：5 预设 + 至多 5 追问，严禁提前输出总结」
- 追问阶段也统一用 **`下一题:`** 标签（不用"追问:"，因前端 `defense.js parseSegmentFormat` 只认 下一题/下一问，不认"追问:"）——这是为了前后端标签一致，避免前端解析不到题
- 保留「学生说不知道 → 本题 0 分但继续下一题/追问」规则

### 4. 收尾硬校验 + 轮次硬上限兜底（防"停不下来"）
> **这是本轮内存崩溃的根因修复**，见下文「三、内存崩溃」。

## 二、数据库改动（2026-09-14 已执行）

| 步骤 | SQL | 说明 |
|------|-----|------|
| 备份 | `CREATE TABLE defense_topics_bak_20260914 AS ...`、`defense_questions_bak_20260914 AS ...` | 改前备份（9 行原始题） |
| 改名 | `UPDATE defense_topics SET topic_name=...` WHERE topic_id IN (13,14,20,21) | 13/14/20/21 改名为"大数据技术原理"系列 |
| 清垃圾题 | `DELETE FROM defense_questions WHERE topic_id IN (13,14,20,21) AND question_type='teacher'` | 清掉"你好/今天星期几/诗句"等测试题 |
| 补题 | 13/14/20/21 各插 5 道大数据技术原理题；27 补到 5 道、28 补到 5 道 | 见下 |
| 验证 | 见下方「当前题库」 | 每课题 =5 |

**当前题库分布（question_type='teacher'）：**

| topic_id | topic_name | 题数 |
|---|---|---|
| 13 | 大数据技术原理（Hadoop与存储） | 5 |
| 14 | 大数据技术原理（Spark与计算） | 5 |
| 20 | 大数据技术原理（数据仓库与NoSQL） | 5 |
| 21 | 大数据技术原理（数据采集与ETL） | 5 |
| 27 | 基于 Spark 的电商用户行为大数据分析平台 | 5 |
| 28 | 基于Hadoop的城市空气质量数据分析与可视化 | 5 |

**新增备份表**：`defense_topics_bak_20260914`、`defense_questions_bak_20260914`。
**注意**：`defense_records_bak_20260910`（2026-09-10 遗留，172 行）为**历史淘汰记录备份，保留勿动**。

`defense_topics.question_count` 字段为**废弃字段**（代码不读取，前端仅本地自增计数），未修改，保持默认 3，不影响出题。

## 三、内存崩溃根因与修复（重要）

### 现象
连续多次答辩后后端崩溃，`hs_err_pid10860.log`：`malloc failed to allocate 1.5MB ... Chunk::new`，JVM 堆实际只用了 53MB，但进程被系统杀。第二次答辩「答了十几次停不下来」。

### 根因（两层）
1. **业务层**：`finishDefenseAggregation` 结束条件**硬依赖模型输出"总结:"**。小模型 qwen2.5:3b-16k 在第 10 轮常输出"下一题:"而非"总结:" → `hasSummary=false` → 不收尾 → 「下一题:」一路滚下去 → 前端继续让学生答 → 后端再调 Ollama → 无限循环直到内存枯竭。（首次正常是模型恰好吐了"总结:"，第二次没吐就卡死）
2. **资源层**：机器物理内存仅 7G，Ollama 模型占 2.9GB 且部分在 CPU/内存跑，叠加 IDEA/工具后系统内存耗尽，JVM 连编译器线程要的几 MB native 内存都申请不到。

### 修复（chatController.java 收尾逻辑）
```java
// 计数口径（已验证）：assistantCountInHistory = 用户已答次数 + 1（首轮出题 greeting 也 add 了 1 条 AssistantMessage）
// 完整 5+5=10 轮 → 用户答 10 次 → assistantCount 最终 = 11
boolean presetDone  = assistantCountInHistory >= existingQuestionCount;
boolean followUpDone = presetDone && (assistantCountInHistory - 1) >= existingQuestionCount + EXTRA_QUESTION_LIMIT;
boolean hardCapReached = assistantCountInHistory >= existingQuestionCount + EXTRA_QUESTION_LIMIT + 1; // = 11
if ((presetDone && followUpDone && hasSummary) || hardCapReached) {   // ① 正常 ② 兜底
    if (!hasSummary) aiResponse += "\n总结:本轮答辩已进行X轮，已自动结束。";  // 补占位总结让前端收到信号
    finishDefenseAggregation(defenseId, ...);
} else if (!presetDone || !followUpDone) {
    // 未满则剥离提前总结 + 补"下一题:"兜底，防前端"有分无题"
}
```
- **① 正常路径**：模型输出"总结:" → 与旧逻辑完全一致。
- **② 兜底路径**：assistantCount 到 11（第 10 次回答）仍未输出总结 → 强制收尾 + 补"总结:"信号，**杜绝死循环**。

### extraAskedCount 虚高（已绕过）
`countExtraQuestions` 从 DB 统计 `question_type='ai'` 条数，但**追问入库有两个点**（`saveExtraQuestionPhase` 登记当前题 + `submitIfNewQuestion` 登记新下一题），一轮登记 2 条不同题 → DB ai 数 ≈ 实际追问 ×2（实测 5 追问 → DB 10 条）。为避免 `followUpDone` 提前 true 导致轮次错乱，**收尾判断已改用权威回合数**（assistantCountInHistory）而非 DB 计数。

## 四、待办（交接给 Claude 时先做这些）

1. **编译验证**（改动后还没 `mvn compile`，因改动时机器内存耗尽连 Maven 都起不来）：
   ```
   mvn -o compile
   ```
2. **重启后端 + 跑一场 10 轮答辩实测**，重点验证：
   - 第 1~5 题（预设题）→ 6~10 题（AI 追问），第 10 轮才出总结及总分
   - 逆反向测试：全程答"不知道" → 0 分但继续，满 10 轮才停
   - 看后端日志 `硬校验拦截提前总结` / `硬上限兜底` 是否触发、有无死循环
3. **资源优化（可选，建议）**：
   - 后端 JVM 加 `-Xmx512m -XX:MaxMetaspaceSize=256m`（当前无 -Xmx 配置，JVM 按物理内存预留大堆，加剧 native 内存压力）
   - Ollama 环境变量 `OLLAMA_NUM_PARALLEL=1`、`OLLAMA_MAX_LOADED_MODELS=1`
   - 若答辩不需长上下文，可考虑小 num_ctx 的模型（需确认，非必修）
4. **清理**：若确认备份表无用可删 `defense_topics_bak_20260914`、`defense_questions_bak_20260914`；`defense_records_bak_20260910` 保留勿删。

## 五、未提交 / 未验证状态（交接清单）
- **代码**：`chatController.java` 已改，**未提交**、**未编译确认**。
- **数据库**：题库补题已执行到位，MySQL 正常。
- **Git**：本轮所有改动**尚未提交**，做好准备后再 `git add + git commit`（信息用中文一行标题，参考历史风格）。

---

# 2026-09-15 改动（权威轮次计数修复 + 防死循环兜底重连）

> 改动日期：2026.9.15 深夜，涉及 chatController.java + DefenseScoreRecordMapper（接口/XML）+ DefenseRecordsMapper.xml + .gitignore
> 本节是对上文 9-14「待办」的闭环：**编译已通过、实测未复现 bug、已提交。**

## 一、9-14 实测暴露的问题（根因）

9-14 晚重新编译后完整跑了两场答辩（defenseId 235 / 236，user 12408060102、topic 28），复盘日志与 Redis 发现：

1. **防死循环硬上限兜底实际永不触发（严重回归）**：`trimChatMemory` 会把 Redis 会话记忆**物理截断到 12 条**，而收尾判断用的 `assistantCountInHistory = countAssistantMessages(history)` 是从这份被裁剪的记忆里数 assistant 消息 → **永远 ≤ 6**。于是 `followUpDone`（要 count-1 ≥ 10）、`hardCapReached`（要 count ≥ 11）永远为 false，收尾条件整体不可能成立。当晚两场能正常结束，纯粹因为最后一答是"我不会"走了放弃作答收尾——若学生全程认真作答，后端永远不会收尾，9-14 要修的死循环/内存崩溃场景原样存在。
2. **轮次号落库错乱**：235 场 roundNum 序列 `1,2,1,4,5,6,6,6,6,6,6`（第 7 轮起封顶在 6，另有 1 条疑似前端重试产生的重复行）；236 场落了 14 行（超过 10 轮设计上限）。收尾日志「轮次数： 11」其实是落库行数（`records.size()`），不是回合数——此前 9-14 的"计数口径已验证"结论被这个日志误导。
3. **连带问题**：`questionId` 按 count-1 映射 → 后半场评分行挂错题；追问阶段放弃作答时报"未从历史中解析到当前追问题，跳过回答落库"；`extraAskedCount` 按 DB 追问题条数统计、因两个入库点各登记一次而虚高 ≈2 倍（9-10 已发现，本轮一并修掉）。

## 二、修复内容

| 修复 | 位置 | 说明 |
|---|---|---|
| 权威轮次计数 | chatController.java（chat / sendMessageWithMemory / handleGiveUpAnswer / 收尾块）+ `DefenseScoreRecordMapper` 新增 `countByDefenseId` | 轮次改按 `defense_score_record` 落库行数 +1：每次作答（含放弃 0 分）恰落一行，天然免疫记忆裁剪。收尾改为 `lastRound = 轮次 ≥ 预设数 + 5`，**第 10 次作答即收尾**：模型输出"总结:"用总结，没有则剥残留"下一题:"（新增 `stripNextQuestionFromText`）并补占位总结强制收尾。`extraAskedCount` 纠正为"本轮之前已完成的追问次数" |
| 放弃短语漏判 | GIVE_UP_PHRASES | 补"不太清楚/太不清楚/不太懂/不太会"（contains 要求连续子串，"不太清楚"原本漏判，实测同一回答三次得分 25/0/20 口径不一） |
| 开局必抛 MyBatis 异常 | DefenseRecordsMapper.xml `createDefenseRecord` | 多 @Param 方法带 `useGeneratedKeys` 无法回填生成键，每次 `/api/chat/clear` 必抛 ExecutorException（靠重查兜底未断功能但刷错误日志）；两处调用方均不用生成键，已移除该属性 |
| 日志防泄漏 | .gitignore | 补 `logs/`（原只挡 `*.log`，`ai-helper.log.*.gz` 会漏进提交） |
| 进度标签 / 总结剥离阈值 | sendMessageWithMemory 进度行、stripPrematureSummary | 与权威口径对齐；第 10 轮模型的"总结:"不再被误剥（旧阈值导致最后一轮只能走占位总结） |

## 三、验证结果（2026-09-15）

- `mvn -o compile` BUILD SUCCESS（66 个源文件；编译用 `D:\maven\apache-maven-3.8.1\bin\mvn.cmd`，PATH 无 mvn）
- 用户实测一场完整答辩：未复现 bug，轮次正常、第 10 轮收尾、无死循环

## 四、遗留（非阻塞，暂不处理）

- 前端重试可产生同轮重复评分行（235 场出现过 1 条），会使权威计数 +1、提前一轮收尾，罕见
- 追问阶段放弃作答时，答案行可能因历史被裁剪而跳过落库（评分行仍在），日志有 WARN
- 模型偶发"总分 ≠ 五维之和"：落库与展示均按五维之和强制纠正，但 AI 气泡原文数字与页面可能对不上（提示词层面，未改）
- 资源优化未做：JVM `-Xmx512m`、Ollama `OLLAMA_NUM_PARALLEL=1`，机器仅 7G 内存，建议尽快

# 2026-09-15 改动（敷衍/无实质作答稳定判 0 分）

> 改动日期：2026.9.15，仅涉及 chatController.java（5 处小改动）。**编译已通过，待实测与人工审核。**

## 一、问题

用户实测（defenseId 238，topic 28）发现：第 1 题回答"开始"竟得 34 分；第 4 题回答"感觉差不多"得 0 分（模型判对）。两处都走了评分模型自由裁量——"无实质内容给 0 分"的提示词规则（9-10 已写入）3B 模型遵循不稳定，"开始/1/一般"这类敷衍输入得分随机。

## 二、修复内容（双保险）

| 层 | 位置 | 说明 |
|---|---|---|
| 代码兜底 | chatController.java：新增 `JUNK_ANSWER_PHRASES` + `isJunkAnswer()`，在放弃作答分支接线 `isGiveUpAnswer(userInput) || isJunkAnswer(userInput)` | 敷衍输入归一化（去空白/标点/符号、转小写）后判定：纯标点（"？？"）、1~2 位纯数字（"1"）、与短语表精确相等（"开始/一般/差不多/还行/嗯/ok"等 26 条）→ 复用 `handleGiveUpAnswer` 固定零分流程，**不调用评分模型**，轮次照常推进/收尾。精确匹配避免子串误伤（"一般用快排"不受影响）；3 位以上纯数字（如端口号"3306"）可能是有效简答，仍交模型判断 |
| 点评区分 | `handleGiveUpAnswer` 增加 `zeroComment` 参数（原硬编码文案上移到调用点） | 放弃作答点评仍为"学生表示不知道该题…"；敷衍作答为"学生未给出实质回答，本题计0分，建议结合问题认真作答。"，落库与前端展示更符合实际 |
| 提示词加强 | 评分提示词"特别注意"条 | 补充：无实质内容（"开始""一般""差不多""1"等敷衍输入、纯数字、纯标点、与问题完全无关的跑题内容）→ 五维全 0，兜住代码判不了的长篇跑题回答 |

## 三、验证结果

- `mvn -o compile` BUILD SUCCESS（离线编译，`D:\maven\apache-maven-3.8.1\bin\mvn.cmd`）
- 待实测：敷衍输入（"开始"/"1"/"一般"）应 0 分且答辩正常进下一题；正常短回答（如"栈"、"一般用快排"、端口号"3306"）不应被误判 0 分

## 四、遗留（非阻塞）

- 长篇答非所问的跑题回答仍依赖模型判断（提示词已加强，代码无法穷举语义）；若实测仍漏判，再考虑代码侧语义相似度方案

## 五、同日初测反馈与二次加强（敷衍判定补网）

15:26 重启后用户以敷衍回答实测一场（defenseId 239，15:27-15:28）：代码新路径生效（"1"→0 分新点评文案、"我不会这道题"→放弃流程 0 分），但 6 类变体漏网落入模型自由裁量，其中 4 类被误给 20-30 分，且"额"得到"思路清晰"式模板点评。

| 漏网输入 | 实测结果 | 漏网原因 | 二次修复 |
|---|---|---|---|
| 额 | 点评"思路清晰…" | 不在词表 | 新增语气词字符集规则：仅由 嗯哦啊呃额唔哎嘿诶唉噢喔哈呀哇 组成即判敷衍 |
| 一般吧 / 还好吧 | 模型自由判 | 精确匹配差一个语气词 | 归一化剥离结尾语气词（吧/呢/啊/呀…）后再匹配，词表补"还好" |
| 感觉不太行 | 20/50 | 开头缓和语"感觉" | 剥离开头缓和语（感觉/我觉得/我认为）后再匹配，词表补"不太行/不行" |
| 你是对的 | 24/50 | 附和类非回答未收录 | 词表补附和/声称类：你是对的/对的/是的/没错/我会这道题/这题我会/我会/我知道/简单/没问题/忘了 等 |
| 我会这道题 | 30/50 | 只声称会、未实际回答 | 同上（声称类短语精确匹配，"我会这道题：答案是XX"不受影响） |

**提示词根因修复（点评规则）**：原规则"先一句话肯定优点"强制模型给所有回答找优点，是"额→思路清晰"的直接原因；改为"回答有实质内容才肯定优点，敷衍/未实际作答必须直接写明回答无实质内容，严禁虚构肯定"。零分反例清单同步补入"特别注意"条（额/嗯/一般吧/还好吧/666/你是对的/感觉不太行/我会这道题等）。

另：放弃/敷衍固定流程日志补充点评文案输出，便于后续审计区分两类判定。**二次修复已编译通过（15:4x），待重启实测。**

## 六、第三轮实测（15:44）与复读型补网

二次修复实测：整体质量提升（"你赢了""我草泥马"被模型诚实判 0 且点评不再虚构优点；"1""我不太会"走代码路径 0 分）。仍漏 2 例，均为复读/附和变体：**"对对对"→8/50、"我是对的"→28/50**（点评均为"答案正确但缺乏详细解释"模板话）。补网：
- 新增**复读判定**：归一化后同一字符重复 ≥2 次（对对对/嗯嗯/666/333）或双字单元重复 ≥4 字（对的对的/是的是的/没错没错）→ 敷衍
- 词表补：我是对的/你说的对/你说得对/有道理
- 提示词零分反例清单补"对对对""我是对的"
已编译通过，待重启实测。

# 2026-09-15 改动（题目序号与顶部进度 + 开场白修复 + 答辩体验四项优化）

> 改动日期：2026.9.15 下午，涉及 defense.js / defense.wxml / defense.wxss + chatController.java。**编译已通过，待重启实测与人工审核。**

## 一、题目序号与顶部进度（前端，用户需求）

学生视角不知道当前是第几题（AI 回复只有点评/评分/下一题）。经确认：**追问单独标注（第1~5题 / 追问1~5）+ 题目标签序号 + 顶部进度**。

| 文件 | 改动 |
|---|---|
| defense.js | data 新增 `presetRounds=5` / `totalRounds=10`；三处解析出题统一走新增 `markQuestionNumber()`（计数+1，1~5 → "第N题"，6~10 → "追问N-5"）；`addAiMessage` 消息对象带 questionLabel |
| defense.wxml | 题目区块标签由写死"下一问"改为 `{{item.questionLabel}}`；顶栏状态旁加"进度 X/10" |
| defense.wxss | 新增 `.header-progress` 样式 |

序号纯前端确定性计数（追问号 = 轮次 - 5），**不让模型自己数**；总结轮不编号。

## 二、开场白纯文本 bug 修复（后端 1 处 + 前端 1 处防御）

**bug 实锤（Redis 缓存 user_12408060102_topic_28）**：开场白是后端拼的纯文本"你好，我是本次答辩的AI考官。现在我们开始第1题：…"，不含任何格式标记 → ① 前端解析不出题目、整段进普通气泡（与其他题 UI 对不上）；② questionCount 不计数 → 第二轮回复被标"第1题"，与开场文本里的"第1题"撞号。

| 位置 | 修复 |
|---|---|
| chatController.java 首轮出题 | 开场白改三段式：`点评:你好，我是本次答辩的AI考官。请开始作答。\n下一题:<题目>`（"第1题"字样删除，序号交给前端标签，避免重复） |
| defense.js parseSegmentFormat | 无"评分:"行的回复不渲染五维评价（否则新开场白会出现"表达:0 逻辑:0…"全 0 区块） |

## 三、答辩体验四项优化（后端，缓存记录逐条实锤后用户选定）

| 优化 | 缓存实锤 | 修复 |
|---|---|---|
| 敷衍判定补网 | "我是250"被判"回答正确"得 8/50（4\|4\|4\|4\|4） | JUNK_ANSWER_PHRASES 补"我是250/250/滚"（精确相等不误伤）；评分提示词补"报数玩笑（如'我是250'）、骂人或与课题无关的玩笑"→ 五维 0 分 |
| 追问题去重 | "实现数据的并行处理？"与"大规模空气质量数据的并行处理？？"几乎一样 | `generateFollowUpQuestion` 增加 excludeQuestions 重载：prompt 声明已问题目清单 + 生成结果相似最多重试一次 + 兜底题也避开相似项；主流程模型下一题与已问题目（预设已问部分 + 已登记追问）相似（归一化后相等/互含/字符二元组 Dice>0.5）→ 重新生成 |
| 题目文本清洗 | "？如何使用Hadoop框架进行数据清洗优化？？" | `cleanNextQuestion` 清洗：开头杂标点剥掉、结尾连续问号收敛为单个"？"（保留疑问句式）、结尾其他杂标点去掉；新增 `rewriteNextQuestionInResponse` 把规范后题目写回回复的"下一题:"行（前端展示/记忆/落库一致，避免"展示脏题、落库净题"两张皮） |
| 总结带总评 | 收尾只有"本轮答辩已进行10轮，已自动结束。"，无总分 | 新增 `buildFinalScoreText()`（口径与 finishDefenseAggregation 一致：各轮五维之和的平均，1 位小数，整数不带小数位）；三处收尾（占位总结/模型总结/放弃作答收尾）统一追加"总分X/50（表达A 逻辑B 专业C 应变D 创新E）。" |

## 四、性能保命待办（仅挂账，用户确认暂不做）

用户确认：当前 Claude Code 边编码边跑程序调模型稳定（历史崩溃源于 8b 模型实验，根目录 9 个 hs_err_pid*.log 为当时产物），暂不调整。具体做法记录备用：
1. JVM 内存上限：pom.xml 的 spring-boot-maven-plugin 加 `<jvmArguments>-Xmx512m</jvmArguments>`，或启动命令 `mvn spring-boot:run -Dspring-boot.run.jvmArguments=-Xmx512m`
2. Ollama 并发限制：管理员命令行 `setx OLLAMA_NUM_PARALLEL 1`，重启 Ollama 后生效

## 五、验证结果

- `mvn -o compile` BUILD SUCCESS（离线编译，`D:\maven\apache-maven-3.8.1\bin\mvn.cmd`）
- 待实测清单：
  1. 序号/进度：新开答辩第 1 题起序号与顶部进度正确，追问阶段显示"追问1~5"
  2. 开场白：绿色题目区块 + "第1题"标签，第二题显示"第2题"不撞号
  3. 敷衍：回答"我是250"应 0 分并照常进下一题
  4. 追问：追问阶段不再出现与已问题目高度相似的题
  5. 题目：显示的题目无"？…？？"脏字符
  6. 总结：收尾总结带"总分X/50（表达…）"

## 六、遗留（非阻塞）

- 总结总分依赖异步落库：极端情况下末轮评分行未写入时总分少算（与既有 finishDefenseAggregation 同口径，方向安全）
- 追问去重相似度阈值（Dice>0.5）为经验值；3B 模型对"不得相似"指令遵循有限，重试一次 + 兜底题保底
- 既有遗留（前端重试重复评分行、追问放弃答案行跳过落库、模型总分≠五维和）不变

---

# 2026-09-16 改动（前端体验优化：微信式输入框 + 渲染错乱根治 + 登录键盘流转）

> 改动日期：2026.9.16，涉及 defense.wxml/wxss/js、student.wxss、login.wxml/js。**已完整走通答辩流程验证（开发者工具环境），阶段成果，待真机复核。**

## 一、答辩输入框 → 微信式对话框（P0，定稿形态）

| 项 | 定稿 |
|---|---|
| 组件 | 多行 `<textarea>`，`auto-height`（min 176rpx/3 行起步，max 288rpx/6 行封顶，超出内部滚动） |
| 换行 | Enter（PC 物理键盘 / 真机软键盘"换行"键）；**发送只走右侧按钮**（微信手机版模式） |
| 字数 | `maxlength="2000"`（原需求 200，为支持复制粘贴题目/长回答放宽，用户确认） |
| 复制 | AI 消息题目标签行右侧"复制"按钮（`wx.setClipboardData`）；消息文本全部 `user-select`（真机长按可选） |
| 其他 | `cursor-spacing="20"`、`show-confirm-bar="{{false}}"`、固定行高 42rpx |

**交互演进记录**：曾短暂启用 `confirm-type="send"+bindconfirm`（Enter 发送），实测 PC 工具把 Enter/Shift+Enter 都转发为 confirm 且无法区分修饰键（小程序无键盘事件），换行能力丢失；界面 ↵ 换行按钮方案因 focus 回焦干扰打字被否。最终按用户决策：**视觉/打字体验优先，舍弃 Enter 发送**。

## 二、登录页键盘流转（P0，已实测）

| 文件 | 改动 |
|---|---|
| login.wxml | 账号框 `confirm-type="next"` + `bindconfirm="onAccountConfirm"`；密码框 `focus="{{pwdFocus}}"` + `confirm-type="send"` + `bindconfirm="handleLogin"` |
| login.js | 新增 `onAccountConfirm()` → 置 `pwdFocus:true` |

账号 Enter → 跳密码框；密码 Enter → 登录；失败报错机制不变。**用户已实测通过。**

## 三、渲染错乱 bug：根因链与修复全过程（已解决）

**现象演进**（同一根因链的三个表现）：
1. 答辩页输入框换行+输入 → 页面错乱重叠（student 首页底部导航叠入答辩页底部）
2. 修复过程中残影转移 → student 首页顶部标题"课程考核AI答辩辅助"叠入答辩页顶部
3. 粘贴长文（≥3 行内容）→ 发送按钮附近渲染异常，清空输入框即恢复；触发阈值随输入框可视行数增加而后移（3 行时 37 字触发，加余量后 50~70 字触发）

**根因（两个，叠加作用）**：
- **根因 A：fixed 悬浮层 + 原生组件的渲染合成缺陷**。残影宿主是各页面的 `position:fixed` 元素（student 页 header/tab-bar、defense 页 input-bar），页面切换/重绘时悬浮层内容残留、串位。最初"textarea 原生组件层级穿透"的假设不准确。
- **根因 B：textarea 内部滚动 + 相邻按钮的合成错乱**。内容超出可视行数触发内部滚动时，重绘与发送按钮区域冲突（异常位置在发送按钮、清空恢复、阈值随行数后移三条证据链锁定）。

**修复链**（v1→v6，含无效尝试）：
- v1 换回 input 验证 → 无效（input 单行构造不出触发条件，对照组不成立）
- v2 去掉 max-height → 无效（auto-height×max-height 冲突假设被否）
- v3 固定 3 行高去 auto-height → 缓解（内部滚动推迟到第 4 行），未根治
- v4 题目"复制"按钮 + 消息文本 user-select（PC 工具不支持鼠标选择文本，user-select 仅真机长按生效）
- v5 **文档流改造（根治根因 A）**：defense 与 student 两页统一改为"固定 100vh 视口 + flex 三段式"——`.page` height:100vh+overflow:hidden，header/输入栏/tab-bar 全部回归文档流，消息/内容区 `flex:1 + min-height:0`（min-height:0 缺失会导致 scroll-view 按内容撑开、把底栏顶出屏幕——修复过程中出现过，已加）。modal-mask 保留 fixed（纯 view 无原生组件，不受影响）
- v6 **auto-height 回归（根治根因 B）**：文档流下高度变化不再引发错乱，重新启用 auto-height 让 6 行内内容全展开、不触发内部滚动

**验证状态**：开发者工具全流程通过（短句/换行/粘贴/发送/清空/页面切换/答题 10 轮）；**真机尚未验证**。

## 四、遗留与待办

- **真机验证**：本版全部结论来自开发者工具；答辩前需真机走一场（尤其输入框、页面切换残影）
- 200 字以上回答（>6 行）仍会内部滚动，理论上仍可能触发根因 B,实测待观察；如复发可调大 max-height 或再议
- **teacher 页 4 处 fixed**（teacher.wxss:14/697/726/893）与 student/defense 同款隐患，建议同款文档流改造
- 模型行为：复制题目文本当回答，判定不一致（有的 0 分"无实质内容"，有的 27/50"思路清晰"）——答辩防作弊场景需留意
- 需求清单挂账不变：JVM/Ollama 资源加固、答辩记录详情页、语音输入
- 排查自动化备料：miniprogram-automator 已装（`C:\Users\Administrator\.trae-cn\builtin\work\mp-repro`），需在开发者工具 设置→安全设置 开启服务端口后可用

## 五、其他状态

- 后端正常：topic 28 第 10 轮兜底正常触发（总分 12.9/50）、Redis 记忆裁剪 14→12、锁正常、无异常报错
- 本节改动已提交 git
