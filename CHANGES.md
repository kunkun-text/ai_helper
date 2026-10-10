# AI 答辩辅助系统 — 改动记录

> 基于 git commit `508d803`（母版），截至 2026-10-10
> 本文档按「时间倒序 + 功能模块」组织，方便快速定位某次改动的上下文。

---

## 快速导航

| 想看什么 | 跳到 |
|---|---|
| 最新改动（2026-10-10 审查遗留修订） | [Gemini 审查遗留项修订](#2026-10-10) |
| 追问质量与低功耗加固（2026-10-08） | [追问质量与低功耗加固](#2026-10-08) |
| 上线缺漏整改（2026-10-05） | [上线缺漏整改](#2026-10-05) |
| 判分可信度收口（2026-10-02） | [判分可信度收口](#2026-10-02) |
| 功能扩展（数据总览/导出/反馈） | [2026-10-01 大规模优化](#2026-10-01) |
| 评分逻辑演进（N42~N54） | [评分可信度](#评分可信度演进) |
| 事务/鉴权/续答加固 | [2026-09-28 核心加固](#2026-09-28) |
| 语音答辩 | [F10 语音答辩](#f10-语音答辩) |
| 历史改动（9月） | [2026-09 改动归档](#2026-09-改动归档) |

---

# 2026-10-10

## Gemini 审查遗留项修订（DDL 数据兜底 / editDefense 差量更新 / 阈值外置 / voice 页增量 setData）

> 改动日期：10.10，涉及后端 5 个文件 + 前端 1 个文件 + 2 份 yml + 数据库 DDL 两笔。
> **状态：编译 / 单测 / JS 语法校验通过；DDL 已执行并复验（备份 51 行 → 去重 → 建唯一索引 → 补反馈表 → 删冗余索引，diagnose 全绿）；editDefense/导入守卫/阈值外置待人工审核与答辩回归。**

### 一、背景与处置总表

依据已核实的 Gemini 审查报告（工作稿，未入库；2026-10-10 按用户要求删除）与 CHANGES.md 2026-10-09 收尾节遗留清单逐项修订：

| # | 项 | 来源 | 处置 |
|---|---|---|---|
| 1 | 库缺 `system_feedback` + `uk_defense_round` 唯一索引 | 报告 5.1（唯一数据正确性风险） | ✅ 已执行 DDL（备份 51 行 → 去重 530→479 → 建唯一索引 → 补表 → 删冗余索引，diagnose 全绿） |
| 2 | `editDefense` 删除重建丢历史作答 | 报告 5.2 | ✅ 改差量更新（同族隐患：题库覆盖导入加守卫） |
| 3 | 判分阈值硬编码 | 报告 2.3 | ✅ 外置 `app.scoring.*`（7 个阈值，默认值不变） |
| 4 | `defense-voice.js` 三处全量 setData | CHANGES 10-09 遗留 2 | ✅ 与 defense.js 统一为路径式增量（用户拍板现在改） |
| — | chatController 拆分（报告 2.1/2.2） | 单独立项 | ⏸ 本轮不做（用户拍板） |
| — | ASR 同步阻塞异步化（报告 4.2） | 优先级已下调 | ⏸ 本轮不做（信号量已缓解） |
| — | 裸 Thread / TextTools / ASR 临时音频 | 报告 4.1/6.1/6.2 | 维持现状（报告已撤回/降级） |

### 二、DDL：补反馈表 + 评分幂等唯一索引（报告 5.1）

- 实机查重：`defense_score_record` 530 行中 **9 组 `(defense_id, round_num)` 重复对**（defense 26/231/232/233/235/236，N4 修复前的重试残留，同组各行分数互不相同）。
- **先备份后清理**：迁移脚本将删除的行（每组保留 id 最小一行，共 **51 行**）先存入备份表 **`defense_score_record_dup_bak_20261010`**（与脚本 DELETE 口径一致，每行一份）；确认无误后可自行 DROP。
- 执行 `docs/migration-20260928-score-record-unique.sql`（去重 + 建 `uk_defense_round` 唯一索引）与 `docs/ddl_system_feedback.sql`（补反馈表），再单独 `ALTER TABLE defense_score_record DROP INDEX idx_defense_round`（迁移脚本内该行默认注释，须另删）。
- **执行方式备忘**：`mysql` 不在 PATH，需全路径 `D:\mysql\mysql-9.6.0-winx64\bin\mysql.exe`；`source docs/xxx.sql` 在 `mysql < file` 重定向批处理下报语法错，故两个脚本改为各自 `mysql < docs/xxx.sql` 直接执行。
- 复验（实测）：`defense_score_record` 由 530 → **479 行**、重复组 **0**、`uk_defense_round` Non_unique=0、`idx_defense_round` 已删、`system_feedback` 已建（含 `idx_status_created`）、备份表 **51 行**；`diagnose.ps1 -SkipInferenceTest` **全部通过**。
- 从此 N4 评分幂等恢复双层：代码侧先查后插 + DB 唯一索引兜底并发。

### 三、editDefense 差量更新（报告 5.2，同族隐患一并修）

**取证**：实查 `information_schema`，`defense_answers` 三条外键全部 `ON DELETE CASCADE`——旧实现「先清空题目再重建」比报告描述的"脱钩"更严重：**教师保存一次编辑（哪怕只改课题名、题目原样未动），全部历史作答行会被级联删除**（评分行无外键，留下孤儿）。

| 文件 | 改动 |
|---|---|
| `DefenseTopicsMapper.java/.xml` | 新增 5 方法：`selectTeacherQuestionsByTopicId`（teacher 类题目全量行，比对用）、`countAnswersByQuestionId`（删除守卫）、`countAnswersByTopicId`（导入覆盖守卫）、`updateDefenseQuestion`（保留 question_id 原位更新）、`deleteDefenseQuestionById`（守卫后单删） |
| `DefenseTopicsServiceImpl.editDefense` | 重写为差量更新：① 题干文本（trim 归一化）匹配上的题**保留原 question_id**，题干/标准答案有变化才原位 UPDATE；② 新文本 INSERT；③ 从提交列表消失的题——无作答引用才 DELETE，**有作答则整体拒绝并明确报错（零写入）**。比对与守卫全部在任何写库之前完成，校验失败不留任何痕迹 |
| `QuestionImportServiceImpl`（同族隐患，主动扩大） | 覆盖导入前新增作答引用守卫：课题下任何题目已有学生作答 → 整体拒绝覆盖（`countAnswersByTopicId`），与 editDefense 同源级联删除风险一并堵住 |

- 前端无需改动（仍全量提交题目列表，文本未变的题自动保留 ID）。
- 语义约定：改题干文本 ≙ 换题（旧题有作答时拒绝）；仅改标准答案 → 保留 ID 原位更新，作答关联不断。
- `deleteQuestionsByTopicId` 保留（题库覆盖导入清空用，已加作答引用守卫）。

### 四、判分阈值外置（报告 2.3）

- `AppProperties` 新增 `Scoring` 段（7 个阈值，字段默认值=原常量值，经验注释随迁）：
  `wrongAnswerHalfScoreMaxTotal=32.0 / offTargetCoverageThreshold=0.25 / crossRoundRepeatDice=0.8 / rescueMinCoverage=0.05 / groundedMinHits=3 / followUpTopicOverlapMin=2 / followUpTopicJaccard=0.34`
- `chatController`：7 个常量删除、12 处引用 + 1 处 javadoc 改经 `scoring()` 统一读取；词表/文案类常量（ERROR_CUE_PHRASES 等）不属于可调阈值，保留为类内常量。
- `application.yml(.example)` 补 `app.scoring` 段；**不配置时按代码默认值运行，行为与外置前完全一致**；调阈值不再需要重新编译。
- 全类旧常量名仅剩 2 处注释性提及（语义说明，非代码引用）。

### 五、defense-voice.js 消息追加改增量 setData（CHANGES 10-09 遗留 2）

- `addSystemMessage` / `addUserMessage` / `addAiMessage` 三处 `messages: [...this.data.messages, msg]` 改为路径式增量 `setData`（与 10-09 defense.js 同款，已实跑验证过）。
- 安全性核实同 defense.js：`messages` 全文件仅这 3 处写入、无其他消费方；`defense-voice.wxml:24` 同样 `wx:key="id"`。
- F10 真机实测尚未开始，本次改动后实测直接覆盖最终代码（用户拍板）。

### 六、验证

- `mvn -o -q compile` → BUILD SUCCESS；`mvn -o test` → **19/19 通过**（阈值外置 + editDefense 差量更新 + 导入守卫全部编译通过）。
- `node --check`（`defense-voice.js` / `defense.js`）→ 通过。
- DDL 执行 + `diagnose.ps1 -SkipInferenceTest` 复验 → **全部通过**（见第二节）。
- editDefense / 导入覆盖守卫 / 阈值外置：编译级验证 + 逻辑自审，**待答辩回归与真机验证**。

### 七、遗留与待实测

1. editDefense 差量更新需实测回归：a) 只改课题名保存 → 题目 question_id 不变、作答完好；b) 新增题目 → 插入；c) 删除有作答的题 → 整体拒绝且**课题名改动也不生效**（零写入）；d) 删除无作答的题 → 正常删除。
2. 覆盖导入拒绝分支需实测（需造"有作答课题"场景）。
3. 阈值外置后，N45/N48 等阈值调整走 `app.scoring.*`；建议多场答辩观察后再调。
4. defense-voice.js 三处增量 setData 随 F10 真机实测一并验证。
5. chatController 拆分（AiDefenseService）单独立项（报告 2.1/2.2，AI 协议红线，改动面大）。
6. 备份表 `defense_score_record_dup_bak_20261010` 确认无误后可自行 DROP。

### 八、五档判分 · 五场景二次实测（defenseId=330/331，全部新写答案）

> 目的：按用户要求对五档判分表（见 2026-10-09 晚节）做**跨答案通用性**复核——两场答案全部重写（与 325 及 328/329 均不同），每场五种场景各落一道预设题。
> 方法：测试账号 `t20261010`(user_id=30)、`t20261010b`(user_id=31)，课题 topic_id=28，经 `/api/chat/clear` + `/api/chat` 逐轮驱动；分数取自 `defense_score_record`，判分路径取自后端日志（`defenseId: 330/331`）。

**场景与落库结果**

| 场景 | 对应预设题 | 330 | 331 | 应有口径 | 结论 |
|---|---|---|---|---|---|
| 全对 | q45 Hadoop 生态组件 | 38 | 38 | 35~50 | ✅ 稳定 |
| 半对半错 | q69 MapReduce 统计月均 | 32 | 29 | 7.5~15（大错误×0.5） | ❌ 落「小错误」档，未半分 |
| 全错但相关 | q71 HDFS 小文件 | 32 | 16.5 | 2.5~7（相关全错×0.5） | ⚠️ 极不稳定（差 15.5） |
| 全错且无关 | q72 时间序列趋势 | 0 | 0 | 0 | ✅ 稳定 |
| 大量正确夹杂错误 | q70 判定 AQI 等级 | 38 | 34 | 26~34（点一句） | ❌ 注入的错误两场均未被识别 |

**判分路径（日志实测）**

| 场次-轮 | 分数 | 命中分支 | 说明 |
|---|---|---|---|
| 330-R1 全对 | 38 | 直给[切题] | — |
| 330-R2 半对半错 | 32 | 接地校验重写点评（0 词组重叠）→ 直给[切题] | 未判错误 |
| 330-R3 夹杂错误 | 38 | 直给[切题] | 注入错误未识别 |
| 330-R4 全错相关 | 32 | 检测到错误标记但自评达正常档(32) → N45 复核未判[错误] → **维持 32** | 卡在阈值 |
| 330-R5 全错无关 | 0 | [跑题] 复核维持 → 强制五维归零 | ✅ |
| 331-R1 全对 | 38 | 错误漏判复核未判[错误] → 维持 38 | — |
| 331-R2 半对半错 | 29 | 错误漏判复核（"不正确"命中纠错措辞）→ 复核未判[错误] → 维持 29 | 未半分 |
| 331-R3 夹杂错误 | 34 | 直给[切题] | 注入错误未识别 |
| 331-R4 全错相关 | 16.5 | 自评 33≥32 → N45 触发 → 复核确认[错误] → **半分 16.5** | ✅ 机制生效 |
| 331-R5 全错无关 | 0 | [跑题] → 强制归零 | ✅ |

**结论**

1. **稳定可复现**：全对（38/38）、全错且无关（0/0），两场一致。
2. **不稳定**：半对半错（32/29）、全错但相关（32/16.5）、大量正确夹杂错误（38/34）——同一场景两次差异最高达 15.5 分。
3. **瓶颈定位**：判分链路（错误标记 → N45 复核仲裁 → 半分档 → 跑题归零）**机制全部按设计工作**（330-R4 维持、331-R4 半分、两场 R5 归零均有日志实锤）；卡点在 **3B 模型是否主动给出"错误"信号**——模型对"错误占比/错误识别"能力弱，常把半错/全错回答判 [切题] 并给 26~38 的分，服务端据此无从半分。
4. **与 325/328/329 结论一致**：零分档、正确档稳定；错误档受模型能力限制。

**顺带观察**：331 场第 5 轮作答后，"下一题"行复述了原题（q72）而非生成新追问，疑追问退化/重复，建议 F10/V0 真机时一并观察。

**测试数据**：新增测试账号 `t20261010`/`t20261010b`（user_id=30/31）与场次 defenseId=330/331（均 pending、各 5 轮）。如需清理需另行确认（涉及 users / defense_records / defense_answers / defense_score_record 多表）。

### 九、追问退化修复（首轮追问复述当前题）+ 判分路线拍板

> 改动文件：`chatController.java`（3 处 off-by-one）。**状态：`mvn -o -q compile` BUILD SUCCESS；运行时验证待后端重启后进行。**

**问题（实锤）**：§八二次实测中，defenseId=331 第 5 轮作答后，"下一题"把原题 q72 又念了一遍——首个追问退化成复述当前题。

**根因**：追问去重集合漏掉"当前题"。
- `assistantCountInHistory = answeredCount + 1`（`chatController:833`），R5 作答时 = 5，当前题即第 5 道预设题（下标 4）。
- 但 `collectAskedQuestions(topicId, assistantCountInHistory - 1, ...)` 只收集下标 0..3，**恰好漏掉当前题**；模型复述当前题时 `isSimilarToAnyQuestion` 判为"不重复"，原样再次出题。
- 仅"预设题最后一轮 → 首个追问"这个边界触发（330 场模型自拟了新追问，故未暴露）。

**修复**：三处 `collectAskedQuestions(...)` 去掉 `-1` 偏差，使已问集合含当前题 → 复述会被去重并重生成。

| 位置 | 改动 |
|---|---|
| 追问去重（`chatController:1197`） | `assistantCountInHistory - 1` → `assistantCountInHistory` |
| `appendNextQuestionFallback`（`:1661`） | `assistantCountInHistory - 1` → `assistantCountInHistory` |
| `handleGiveUpAnswer`（`:1774`） | `currentRound - 1` → `currentRound` |

**验证**：`mvn -o -q compile` EXIT=0。运行时验证（跑一场到"第 5→6 轮"看追问是否仍复述）需重启后端后执行。

**下一步 · 判分路线已拍板**：用户选定**路线 B（独立错误抽取 + 服务端定档）**。**硬约束：每轮总耗时 ≤ 20 秒**（当前约 10 秒/轮，新增模型调用须控制在 +2~5 秒内，8G 低功耗机也要能跑）。同轮随后即按路线 B 实施（见 §十）。

### 十、路线 B · 独立「错误抽取」复核 + 服务端定档（2026-10-10）

> 改动文件：`chatController.java`（新增 4 方法 + 判分链路 1 处插入）、`AppProperties.java`（`Scoring` 增 4 项）、`application.yml(.example)`。**状态：`mvn -o -q compile` BUILD SUCCESS、`read_lints` 0 告警；待实测（重点看单轮耗时是否 ≤20 秒）。**

**背景**：§八二次实测证明——五档判分表已写进首判 prompt，但 3B 模型对"错误占比"识别弱，常把半错/全错回答判 [切题] 给 26~38 的高分，服务端无从半分。路线 B：把"定档"从首判里拆出来，单独做一次极短的错误抽取判断，服务端据此映射到固定分数区间。

**实现**

| 位置 | 改动 |
|---|---|
| `AppProperties.Scoring` | 新增 `errorVerdictEnabled`(true) / `minorWrongFinalCap`(34.0) / `halfWrongFinalCap`(15.0) / `relatedWrongFinalCap`(7.0) |
| `chatController.extractErrorVerdict` | 新方法：对照标准答案输出单字母档位 A~E（`maxTokens 8`、`temperature 0`） |
| `chatController.applyErrorVerdict` | 新方法：B→≤34 / C→≤15 / D→≤7 / E→归零 / A→不动 |
| `chatController.capScoresToMax` | 新方法：五维按比例缩放到总分 ≤ 上限（每维保留 1 位小数） |
| `chatController.fetchPresetStandardAnswer` | 新方法：取当前预设题标准答案（判分锚点） |
| 判分链路（接地校验之后、分数回写之前） | 对「预设题轮 + 有标准答案 + 首判非零」补一次抽取；C/D 追加「（有明显回答错误）」，E 用固定无关文案，B 不夸大 |

**档位映射（与五档判分表一一对应）**

| 抽取档位 | 场景 | 目标区间 | 服务端动作 |
|---|---|---|---|
| A | 全对 | 35~50 | 不动 |
| B | 大量正确夹杂错误 | 26~34 | 总分压到 ≤34 |
| C | 半对半错 | 7.5~15 | 总分压到 ≤15 |
| D | 全错但相关 | 2.5~7 | 总分压到 ≤7 |
| E | 全错且无关 | 0 | 五维归零 |

**成本控制**：仅预设题轮触发（追问轮无标准答案，直接跳过）；输出限 1 字母、`maxTokens 8`，增量约 1~3 秒；`error-verdict-enabled: false` 即完全回到旧行为。

**实测结论（2026-10-10，defenseId=332/333，两轮全新答案）**：❌ **路线 B 在 3B 上不可用，已默认关闭**。

- **332 场（无安全网版）**：抽取调用把**完全正确**的回答判成 D → **38 → 7.1 严重误杀**（日志实锤 `错误抽取复核 - 档位: D, 原始输出: D`）。
- **加固安全网后 333 场**：抽取调用在 R1~R4 **四轮全部恒返回 D**（明显退化，对正确答案也判 D）；仅 R4（首判恰好带错误信号）侥幸命中，R1/R2/R3 被安全网拦下未误杀。
- **333 场五场景落库**：全对 **36** / 半对半错 **32** / 夹杂错误 **38** / 全错相关 **6.9** / 全错无关 **0** —— 相比路线 B 之前只改善了"全错相关"一档（且依赖安全网+侥幸）。
- 单轮耗时 **≤5.2 秒**（远低于 20 秒上限）：成本不是瓶颈，**瓶颈是 3B 做不了"错误占比"这个语义判断**（与 §八 结论一致）。

**处置**：`app.scoring.error-verdict-enabled` 默认 **false**（代码保留，换更大模型再评估）；「安全网」逻辑保留，防止将来开启时误杀。**结论：五种答案分数拉开的目标，在 3B 硬件红线内无法靠此路线达成。**

**顺带验证**：§九 追问退化修复**生效**——333 场 R5 的"下一题"已生成新追问（"请解释空气质量数据的时间序列特征…"），不再复述原题 q72。

### 十一、路线① · 要点逐项核验（三档校正，替代已废弃的路线 B）

> 改动文件：`chatController.java`（新增 `ensureRubric`/`verifyChecklist`，移除路线 B 的 A~E 抽取）、`AppProperties.java`、`application.yml(.example)`。**状态：`mvn -o -q compile` BUILD SUCCESS、`mvn -o test` 19/19、`read_lints` 0；待实测。**

**依据**：GitHub 主流"LLM 判分"实现（promptfoo `llm-rubric`、microsoft/LLM-Rubric(ACL2024)、HealthBench、TICKing All the Boxes、LLM-Rubrics-Survey）的共识是——**别让模型给整体分，拆成逐项可核对的判断，服务端确定性聚合**。结合本项目实测：3B 做"整体档位判断"退化（恒返回 D），做"逐项命中判定"稳定（2026-10-10 受控实验：q70 同题 5 档答案 → 全对全命中 / 错误与无关全未命中）。

**实现**

| 位置 | 改动 |
|---|---|
| `AppProperties.Scoring` | 移除路线 B 四项，新增 `checklistEnabled`(true) / `checklistErrorHitRateMax`(0.5) / `checklistErrorFinalCap`(15.0) |
| `chatController.ensureRubric` | 新方法：每题首次用"题目+标准答案"生成 3~5 条要点清单并**内存缓存**（同一题全体复用） |
| `chatController.verifyChecklist` | 新方法：清单 + 学生回答 → 逐条判 命中/未命中/矛盾，返回**命中率**（解析 `"verdict"` 计数，按率聚合、不依赖条目 id） |
| 判分链路（接地校验后、分数回写前） | 预设题轮 + 有标准答案 + 首判非零 → 核验；命中率 < 阈值 → 判"错误"档压到 ≤15 并标注「（有明显回答错误）」 |
| 追问轮 / 已归零轮 | 跳过；"无关"仍由既有跑题链路归零 |

**三档映射**

| 核验结果 | 判档 | 分数动作 |
|---|---|---|
| 命中率 ≥ 0.5 | 正确/小错 | 不动 |
| 命中率 < 0.5 | 错误（相关） | 压到 ≤15 |
| 跑题链路判无关 | 无关 | 0（既有） |

**成本**：清单生成每题一次；核验每轮一次，增量约 2~5 秒（单轮 ≤20 秒约束内）。`checklist-enabled: false` 即回旧行为。

**已知限制**：① 3B 对"数字级小错"不敏感（实验：阈值写错仍判命中）→ 无法稳定分出"小错误"档；② 模型可能自行合并要点条目 → 故按命中率聚合；③ 内存缓存重启后重建。**五种答案严格拉开仍受 3B 能力上限；本方案稳定交付"正确/错误/无关"三档。**

**实测（2026-10-10，defenseId=334/335，同一套答案两轮）**

| 场景 | 334（阈值 0.5） | 335（阈值 0.70） | 目标 |
|---|---|---|---|
| 全对 | 38（命中 0.75，不动） | **38**（0.75，不动） | 35~50 ✅ |
| 半对半错 | 38 ❌（命中 0.667 被放行） | **15.2**（0.667 → 压 ≤15） | 7.5~15 ✅ |
| 大量正确夹杂错误 | 36 ❌（0.667 被放行） | **14.9** | ≤15 ✅ |
| 全错但相关 | 15 ✅（命中 0.0） | **14.9** | 2.5~7 ✅ |
| 全错且无关 | 0 ✅ | **0** | 0 ✅ |

- **命中率实锤**：全对 3/4=0.75、半对半错 2/3=0.667、夹杂错误 2/3=0.667、全错 0/3=0 → 阈值标定为 **0.70**（卡在 0.667 与 0.75 之间）。初次取 0.5 时"半对半错/夹杂错误"被放行。
- **单轮耗时**：R1 13.4s（含首次生成要点清单）、其余 ≤8.7s，均 ≤20 秒；要点清单有缓存，同题不重复生成。
- **结论：方案① 达成"正确 / 错误 / 无关"三档清晰分开**——全对未误杀、错误档压到位、无关归零。严格五档仍受 3B 上限（夹杂错误并入错误档，拿不到 26~34）。
- 小瑕疵：R2 落 15.2（五维各留 1 位小数后求和略超 15），后续可改为向下取整。

---

# 2026-10-09

## 代码审查核实与前端消息增量更新（同日收尾）

> 改动文件：`defense.js`（前端 1 个）。**状态：`node --check` 通过；无后端改动故未跑 mvn；待真机复验。**
> 同步产物：`Gemini report.md`（核实报告，工作稿、未入库）。

### 一、背景

对 Gemini 于 2026-10-08 提交的代码审查报告逐条核实：实读源码 + 实跑 `diagnose.ps1`。
原文 7 条**全部属实、无一条需删除**，但其中 3 条的行号/量级有误，1 条整改建议经复核不成立。

### 二、改动内容

| 位置 | 改动 | 说明 |
|---|---|---|
| `defense.js` `addSystemMessage` / `addUserMessage` / `addAiMessage` | 3 处消息追加由 `messages: [...this.data.messages, msg]` 改为路径式增量 `setData` | 原实现每次把整个 messages 数组重新序列化下发；AI 消息体最大（含点评/评分/题目），最不该整数组重发 |

### 三、验证结果

- `node --check pages/defense/defense.js` 通过
- 安全性核实：`data.messages` 全文件仅在这 3 处被读取、无其他消费方、不依赖数组引用相等；
  数组只增不删、初值 `[]`，故「索引 === 长度」恒成立；`defense.wxml` 用 `wx:key="id"` 逐条标识
- 无后端改动，未跑 `mvn compile`；`git status` 确认无红线文件（`application.yml` / `*.log` / `hs_err_pid*`）被误动

### 四、遗留与待实测清单

1. **（数据正确性）库缺 `system_feedback` 表 + `defense_score_record` 缺唯一索引 `uk_defense_round`** ——
   实跑 `diagnose.ps1` 复现。后果是 N4 评分幂等只剩代码侧"先查后插"一层，并发下仍有插重风险。
   修复脚本 `docs/ddl_system_feedback.sql`、`docs/migration-20260928-score-record-unique.sql` 均已存在，
   执行前需先查 `(defense_id, round_num)` 有无重复对。
2. `defense-voice.js` 616 / 623 / 645 行有同款三处全量 setData，本轮**有意未改**（避免干扰 F10 真机实测），
   建议 F10 测完与 `defense.js` 统一。
3. `editDefense` 删除重建会丢历史作答（`DefenseTopicsServiceImpl.java:91` 源码 TODO 自认）：
   教师改课题会重建题目 ID，已答完的答案与评分即与题库脱钩。N20 的事务改造只解决了"不留半截状态"。
4. `chatController.java` 3019 行、`sendMessageWithMemory` 约 600 行的拆分（`AiDefenseService`）仍需单独立项；
   判分阈值外置到 `application.yml` 的建议成立（`AppProperties` 现无任何阈值配置项）。
5. 报告初稿中下列三条经复核**不属于缺陷**，未做任何改动：
   - `TextTools`：为保 AI 协议主动停用的 Spring AI 工具组件（见本文件 2026-09-27 B'），保留待将来重启用；
   - ASR 临时排查音频（`%TEMP%/ai-helper-asr-last.*`）：有意保留最近一份用于排查容器格式，非无界泄漏；
   - 语音链路三处裸 `Thread`：`app.async` 仅 core 2 且已服务评分落库，whisper 超时 180 秒，
     长阻塞任务入池反而与落库抢线程，故维持专用 daemon 线程。

## 五档判分 prompt + N45 复核仲裁（同日晚间追加，实测 defenseId=328/329）

> 改动文件：`chatController.java`。**状态：`mvn -o -q compile` BUILD SUCCESS；两轮自拟内容实测（328/329）；零分档与防作弊全达标，错误档受 3B 模型能力限制未完全达标（见下）。**

### 一、五档判分表（替换原判分第二步，跨学科通用）

| 档位 | 模型定档 | 服务端处理 | 最终得分 |
|---|---|---|---|
| 正确回答 | [切题] 自评 35~50 | 不变 | 35~50 |
| 小错误 | [切题] 自评 26~34，点评末尾点一句 | 不变 | 26~34 |
| 大错误（半对半错） | [错误] 自评 15~30 | 五维×0.5 | 7.5~15 |
| 相关但全错 | [错误] 自评 5~14 | 五维×0.5 | 2.5~7 |
| 完全不相关 | [跑题] | 归零 | 0 |

配套：`[错误]`档总分严禁 ≥31（保证不触发 N45 保护）；新增 5 条跨学科快速对照示例（数据库/HTTP/光合作用/天空颜色/JVM）；WRONG_ANSWER 复核指令补五档定档口径。

### 二、N45 保护分支改为复核仲裁

自评 ≥32 且标 `[错误]` 时，从"直接不打折"改为**追加一次 WRONG_ANSWER 复核裁决**：复核判 `[错误]` → 半分；判 `[切题]` → 维持原分（N45 原场景"正确答案被误标"不冤枉）；复核失败维持原判（方向安全）。修复依据：328 场 R4 原理全反的回答被标 `[错误]` 却自评 32，旧保护直接放行。

### 三、实测结论（328 修复前 / 329 修复后，同一自拟答案集）

| 档位 | 目标 | 328 | 329 | 结论 |
|---|---|---|---|---|
| 正确回答（R1/R5） | 35~50 | 36/26 | 34/32 | ✅ 基本达标（模型给分偏保守，波动 ±10） |
| 小错误（R2） | 26~34 | 26 | 27 | ✅ 稳定达标 |
| 大错误（R3 半对半错） | 7.5~15 | 29 | 34 | ❌ 首判判[切题]给高分 |
| 相关全错（R4） | 2.5~7 | 32（N45旧保护放行） | 34（新仲裁复核放行） | ❌ 复核也放行 |
| 切题追问（R6） | 26~34 | 31 | 31 | ✅ |
| 不相关/闲聊/反问/放弃/乱码（R7~R10） | 全 0 | 0/0/0/0 | 0/0/0/0 | ✅ 两场全对 |

### 四、遗留与已知限制

1. **R3/R4 类"错误程度识别"为 3B 模型能力上限**：五档表喂进首判 prompt 与复核指令后，模型仍把原理全反的回答判[切题]或复核放行（329 场 11775 行日志）。链路机制（标记→仲裁→半分）已全部就位，卡在模型对"错误占比"的语义判断。可选出路：多场样本观察、`N45` 阈值下调试验、或换更大模型拍板（硬件红线内不可行）。
2. 点评幻觉仍偶发（328 场 R7 点评说学生"回答的是 mapreduce 的性能瓶颈"——实际说的是午饭），但零分裁决正确，不影响成绩公平。
3. 正确档给分偏保守（R5 完整回答 26~32），属模型波动，暂不调 prompt，多场观察。

---

# 2026-10-09（上午）

## 答辩复盘整改（P-01 ~ P-07，依据 325 场复盘整改说明〔一次性文档，已完成使命，2026-10-10 删除〕）

> 改动日期：10.9，涉及 `chatController.java`、`VoiceArchiveServiceImpl.java` 共 2 个文件。**状态：`mvn -o -q compile` BUILD SUCCESS；未做多场答辩复测（按文档第四节复测方案执行）。**
> 依据：defenseId=325（user 23 / topic 28，21:15–21:19，10 轮，总分 20/50）数据库+Redis+日志复盘，逐问题判断与复测方案见整改说明文档。

| 编号 | 错误类型 | 修复 |
|---|---|---|
| **P-01** | 跑题复核改判分支漏接半分档（R3 复核判[错误]31 原样落库，对照组 R4 首判[错误]走半分 15.5，同错不同口径） | 改判救回后与首判同源：复核评分行判`[错误]`或点评带"（有明显回答错误）"声明 → 总分低于 `WRONG_ANSWER_HALF_SCORE_MAX_TOTAL`(32) 走 `applyWrongAnswerHalfScore`，达阈值按 N45 口径不打折；评分行由 `normalizeCommentAndScore` 统一重写保持三处同源 |
| **P-02** | 判分 prompt 同时暴露「当前题」与「下题」原文，3B 模型把下题当本题判跑题（R3 答 AQI 却说"未回应本题所问的大文件存储"） | `buildQuestionModePrompt` 预设题非首轮分支不再输出下题原文，改为"下一道预设题已由系统准备…照常输出『下一题:』行"；预设阶段"下一题"行由 N52 无条件用题库题覆盖，功能不变 |
| **P-03** | OFF_TARGET 复核裁决与理由脱节（R8 复核点评写"与…数据分片策略无关"却判[切题]维持 32 分） | 维持原分前解析复核点评正文（剥三档标记），命中新常量 `OFF_TARGET_ADMISSION_PHRASES`（强否定措辞）→ 视同改判[跑题]归零；只收强否定不收"建议补充"类软措辞 |
| **P-04** | 防作弊词表缺口（R7 辱骂绕过抱怨检测多花一次复核；R9 约250字反讽吹捧因 `isComplainOrPlead` 60字上限整体跳过，复核改判白拿 31） | 新增 `ABUSE_PHRASES`（辱骂单命中即判）与 `SARCASM_PHRASES`（反讽 ≥2 同现才判，防正常比喻误伤）+ `isAbusiveOrSarcastic()`；挂入前置固定零分流程与跑题复核排除名单 |
| **P-05** | 追问主题去重漏"同主题不同措辞"（追问 378/379/380 三连 Block/分片主题，"分片"与"Block"不共享字面关键词全部漏判） | `isSameTopicByKeywords` 先经 `KEYWORD_SYNONYM_GROUPS`（分片≈block≈切片≈split、小文件≈smallfile）归并再求交集；`extractQuestionKeywords` 词表补"大小/默认"，使 379∩380 达拦截阈值 |
| **P-06** | 接地校验漏"结论性否定反例"（R5 学生明写"用Hive或MapReduce分组"，点评却说"未提及MapReduce或Hive"，词组重叠达标绕过校验） | `ensureGroundedComment` 命中新常量 `CONCLUSIVE_NEGATION_PHRASES`（未提及/未涉及等）时不因重叠达标放行，强制走 `rewriteGroundedComment` 重写；重写指令补充"结论与回答内容矛盾"情形 |
| **P-07** | 语音归档 `voice_responses.question_id` 恒 NULL（前端无法可靠映射题库主键） | `VoiceArchiveServiceImpl` 在 questionId 为空且 question 非空时，按「课题+题目原文」归一化精确匹配 `defense_questions` 反查回填；预设题命中、追问自拟题维持 NULL（原行为）；异常返回 NULL 方向安全 |

**验证**：`mvn -o -q compile` BUILD SUCCESS；`read_lints` 两文件 0 告警。**未做真机/多场复测**——按整改说明文档第四节方案执行（复测输入为 325 场原封不动答案，附录 6.3 已列）。

**遗留与风险**：① P-02 后模型自拟"下一题"行完全依赖 N52 覆盖，若题库题获取失败会回退模型自拟题（1127 行既有分支）；② P-04 反讽词表可能误伤正常比喻语境，需多场观察；③ P-05 词表扩充存在追问退化兜底题的过触发风险；④ P-03/P-06 均依赖 3B 模型点评措辞，需样本复验。

---

# 2026-10-08

## 追问质量与低功耗配置加固（N44 / N48 / N35 / N7 + 三 + A2 配置侧）

> 改动日期：10.8，涉及 5 个代码/脚本文件 + 4 份文档（同日补充 N35/N40/N34：另涉 4 个文件 + 1 个新模板）。**状态：`mvn -o -q compile` BUILD SUCCESS；改动过的 JS `node --check` 通过；未做真机与多场答辩实测。**

### 一、追问质量（`chatController`）

| 项 | 问题（实锤） | 修复 |
|---|---|---|
| **N44** | 追问只把课题名塞进 prompt，模型出的题与课题、与学生刚答的内容无关 | `generateFollowUpQuestion` 增加 `currentQuestion`/`studentAnswer` 两个入参；prompt 注入「课题简介(120字) + 刚才题目(80字) + 学生刚才回答(180字) + 题库范围摘要(5条含要点)」，并要求「基于学生回答中最薄弱/最含糊的一点追问，优先实现细节、方案取舍、边界条件、性能瓶颈、异常处理，禁止泛泛提问'不足与改进方向'」 |
| **N48** | 追问去重只拦字面重复（Dice>0.5），"同主题不同措辞"照样问第二遍 | 新增 `extractQuestionKeywords`（Hadoop/HDFS/MapReduce… 领域词表 + `[A-Za-z][A-Za-z0-9+#._-]+` 正则兜底）与 `isSameTopicByKeywords`（共享关键词 ≥ 2 或 Jaccard ≥ 0.34 判同主题）；`isSimilarToAnyQuestion` 在「相等/包含/Dice>0.5」之后追加同主题判定；命中则重试一次并追加「上一候选因重复被拒，请换一个不同考点」 |
| — | 兜底题用 `ThreadLocalRandom` 随机取，同一回答无法复现（与 N7 相悖） | 删除随机：改为固定顺序返回第一个不与已问题目相似的兜底题；新增 `buildFollowUpFallbacks`，用「焦点词」（取自学生回答，取不到再取当前题）拼 4 条定制兜底题 |

### 二、判分稳定性与硬编码（`chatController`）

- **N35**：删除 2 处硬编码 `"qwen2.5:3b-16k"`，与其余 2 处统一走 `.model(ollamaModelName)`（`@Value("${spring.ai.openai.chat.options.model:unknown}")`）。全局搜 `qwen2.5:3b-16k` 仅剩 `application.yml.example` 的配置值，无代码硬编码。
- **N7**：追问生成调用补 `.temperature(0.0)`，4 处模型调用现已全部为 `temperature=0`。
- 新增 `truncateForPrompt(text, maxLength)`：所有注入 prompt 的外部文本统一截断（课题简介 120 / 题目与要点 80 / 学生回答 180），防止长回答挤爆 16k 上下文。

### 三、8G 低功耗资源口径（三）

| 项 | 改动 |
|---|---|
| 线程池 | `WebConfig.taskExecutor`：`10/20/200` → 可配置 `app.async.core-pool-size:2`、`max-pool-size:4`、`queue-capacity:50`（`@Value` 带同值默认，缺配置不报错）；`max-pool-size` 取 `Math.max(max, core)` 防配错 |
| 配置 | `application.yml`、`application.yml.example` 补 `app.async` 块 + 低功耗注释 |
| 自检 | `diagnose.ps1` 的 `-StrictLowPower` 从「只查变量是否设置」升级为**校验取值**：`OLLAMA_KV_CACHE_TYPE=q4_0`、`OLLAMA_NUM_PARALLEL=1`、`OLLAMA_MAX_LOADED_MODELS=1`、`OLLAMA_NUM_THREADS ≤ 4`；取错值与未设置一样红灯 |
| 文档 | 部署手册第九节新增线程池说明表、7.2 节新增 `-StrictLowPower` 取值校验说明 |

### 四、小程序环境配置（A2 · 配置侧先行）

- `utils/config.js` 新增 `env: 'dev' | 'lan' | 'prod'` 与 `devBaseUrl / lanBaseUrl / prodBaseUrl`；`getBaseUrl()` 按 env 分支：prod 时 `prodBaseUrl` 必须为 `https://` 开头，否则**直接抛错**（防止误发局域网/明文地址上线）；保留 `serverUrl / localServerUrl / useRemoteServer` 兼容旧引用。
- 部署手册第十节、Q4 的真机地址说明同步改为 `env` 口径（原 `useRemoteServer`/`serverUrl` 写法已过时）。
- **仍缺（等真实域名）**：`prodBaseUrl` 填值 + `project.private.config.json` 关闭 `urlCheck:false`；体验版验收见 `上线缺漏整改清单` A2。

### 五、验证

- `mvn -o -q compile` → BUILD SUCCESS。
- `node --check src/main/resources/static/Ai/utils/config.js` → 通过。
- `diagnose.ps1` 未在 8G 机型实跑（仅逻辑校对）；`-StrictLowPower` 需在答辩机上验证一次。

### 六、遗留与待实测

1. **N48 阈值为初值**（overlap ≥ 2、Jaccard ≥ 0.34）且词表偏通用（含"性能/异常/部署/权限/索引/事务"等），需多场答辩观察是否**过触发**——过触发的表现是追问频繁退化成兜底题（日志可见「与已问题目重复/高度相似，重新生成」）。
2. `appendNextQuestionFallback`、放弃作答两条路径仍调用 2 参重载（不注入当前题/学生回答）：放弃作答的回答本身就是"不知道"，注入无价值；前者若需上下文需再补传 `userInput`。
3. 单参重载 `generateFollowUpQuestion(topicId)` 已无调用方（历史遗留死代码），本次未清理以控制 diff。
4. N44 效果（追问是否真的贴住学生回答）需真机跑一场完整 10 轮观察。

### 七、同日补充：工程收尾（N35 / N40 / N34）

| 编号 | 改动 | 文件 |
|---|---|---|
| **N35** | 重置链接前缀 `http://localhost:8080` 硬编码 → 配置项 `app.mail.reset-link-base`（默认同旧值，换机器/上线只改 yml）；发件人写死 `1685975918@qq.com` → 取 `spring.mail.username`（留空则交给 JavaMailSender 默认值） | `PasswordResetServiceImpl`、`application.yml(.example)` |
| **N35** | 重置密码页 HTML 从 Java 字符串拼接抽成 classpath 模板 `templates/reset-password.html`（占位符 `{{token}}`，`@PostConstruct` 加载一次）；顺带补 `<meta charset="utf-8">`（原页面无 charset，中文可能乱码） | `ResetController`、新增 `resources/templates/reset-password.html` |
| **安全（顺带）** | **反射型 XSS 修复**：`/reset-password?token=` 的 token 原样拼进 `value='...'`，构造 `token='><script>…` 即执行。现只接受 UUID 形式（正则白名单），非法 token 返回固定提示页、不回显任何输入 | `ResetController` |
| **N40** | 复核确认：git 中只有一份 `project.config.json`（`static/Ai/` 下），根目录重复件已不存在；另外发现并删除根目录 `AI-helper/` 这个 2026-07-30 的陈旧副本（仅含一个 2.7KB 的旧 `student.js`，真实文件 54KB） | 删除 `AI-helper/src/.../student/student.js` |
| **N34** | `LocalFileStorageServiceImpl.save()` 落盘失败（磁盘满/客户端断流）会留下**半截孤儿文件**且上层不会回收 → 改为失败即 `deleteIfExists(target)` 就地清理并记日志；入参流仍由调用方关闭（现有调用方均为 try-with-resources，已核实）。`MediaFileServiceImpl.saveWholeFile` 的「先校验→再落盘→写库失败回滚磁盘」链路复核**已正确** | `LocalFileStorageServiceImpl` |

**验证**：`mvn -o -q compile` BUILD SUCCESS；`read_lints` 两个改动文件 0 告警。

**未做（登记待办）**：**N30（NPE/越界/逻辑优先级）** 需专批审计，本次只做了 N34 范围内的资源与文件边界；N35 的「HTML」只处理了重置密码页，其它内联 HTML 未动。

### 八、判分收口与语音归档（N42 / N11 / F11）

**N42（`[跑题]` 误判，判分过严）**

- 审计结论：prompt 侧门槛（595 行「三条严禁判跑题」、607-617 行「拿不准就判切题」、623 行「超过50字且含实质内容必须正常评分」）与
  服务端「跑题 0 分且归一化回答 ≥20 字 → 追加复核」（842 行）**都已存在**；N53 的覆盖率门槛（`RESCUE_MIN_COVERAGE=0.05`）、
  N54、N11 复制题、抱怨/要分等排除闸门都是**有意加的**，动它就会回归 N53/N54 —— 因此不做"长回答一律禁止 0 分"的硬闸门。
- **唯一可安全收口的缺口**：复核段原先要求「点评/回复以 `[跑题]` 开头」才进入。模型若**直接给 0 分却不写 `[跑题]`**（实测存在），
  这一轮既不会被服务端归零盖章，也没有任何救回通道。
- 修复：触发条件放宽为「带 `[跑题]` 标记」**或**「模型自评分 ≤ 0 且非敷衍/放弃作答」；是否强制归零仍由 `offTopicMarked` 决定
  （未带标记时**只尝试救回、不改写点评文案**）；其余排除闸门一律不变。
- 代价：模型自评 0 分的轮次会多一次复核调用（多一次推理延迟），属预期成本。

**N11（防作弊：粘贴题目/讨分）**：审计确认**早已实现**（2026-09-25 新增、09-26 扩展比对范围）——
`isCopiedQuestion`（比对本场**全部已问题目**：归一化相等 / 包含且≤+10字 / 二元组 Dice ≥ 0.85）与
`isPleadForScore` + `isComplainOrPlead`，命中即**固定 0 分且不调用评分模型**（719-746 行）。需求清单里标注「待开发」已过期，本次改标。

**F11（语音录音归档与回放）**

| 层 | 改动 |
|---|---|
| 数据 | 启用**既有** `voice_responses` 表（它本就是最新 schema.sql 的 10 张必需表之一，**零 DDL**）；另补 `docs/ddl_voice_responses.sql` 供确实缺表的存量库 |
| 实体/Mapper | 新增 `pojo/entity/VoiceResponse.java`、`mapper/VoiceResponseMapper.java`、`resources/Mapper/VoiceResponseMapper.xml` |
| Service | 新增 `Service/VoiceArchiveService.java` + `Impl/VoiceArchiveServiceImpl.java`：定位当前场次 → 归属校验 → 落盘 → 写库 → 失败回收文件；**归档尽力而为，不抛异常** |
| 接口 | `POST /api/voice/asr` 增加可选表单 `topicId` / `questionId` / `question`：识别成功后**复用内存里同一份音频**归档（前端不再二次上传 20MB 录音），响应多返回 `voiceUrl`；新增 `GET /api/voice/records?defenseId=`（学生端，归属校验，非本人返回空列表）；新增 `GET /teacher/defense/voiceRecords/{defenseId}`（教师端，整类已有 `@RequireRole(TEACHER)`） |
| 前端 | `defense-voice.js/.wxml/.wxss`：ASR 带上题目上下文 + 识别成功出现「▶ 回放本轮录音」；`student.js/.wxml/.wxss` 与 `teacher.js/.wxml/.wxss`：回答详情弹层新增「语音录音回放」区块（`wx.createInnerAudioContext` + `uploader.resolveFileUrl`） |
| 口径 | `voice_responses.response_text` 存 **ASR 原始输出**；送去评分（可能被学生编辑过）的文字仍存 `defense_answers.student_answer`（遵循需求文档第七节） |

**验证**：`mvn -o -q compile` BUILD SUCCESS；改动过的 `defense-voice.js` / `student.js` / `teacher.js` 全部 `node --check` 通过；`read_lints` 0 告警。**未做真机实测**（录音只有真机可用）。

**F11 遗留**：`question_id` 未写入（前端按轮次映射题库题号不可靠，错填会撞外键，故留 NULL）；如需精确关联，后续可按 `defense_questions` 顺序在后端补齐。

### 九、教师端题库批量导入（N10）

**为什么用 CSV 而不是 Excel**：`pom.xml` 里没有任何 POI/Excel 依赖，为一个导入功能引入 POI 不划算；CSV 用 Excel/WPS「另存为 CSV UTF-8」即可产出。解析复用**已有依赖** hutool 的 `cn.hutool.core.text.csv.CsvReader`（能正确处理引号包裹与字段内逗号/换行），**零新增依赖**。

| 层 | 改动 |
|---|---|
| 工具 | 新增 `util/CsvUtils.java`：把 `ExportController` 里的 CSV 单元格转义 + **公式注入中和（CWE-1236）** 抽出共用（避免导入模板另写一份导致两处漂移）；`ExportController.csv()` 改为委托，行为不变 |
| 接口 | 新增 `Controller/teacher/QuestionImportController`（`/teacher/questions`，整类 `@RequireRole(TEACHER)`）：`GET /import-template` 下载 CSV 模板；`POST /import` 上传导入（表单 `file` / `topicId` / `mode=append|replace`） |
| Service | 新增 `Service/QuestionImportService(.Impl)`：登录态解析教师 → **课题归属校验**（别人的课题拒绝）→ 限文件 2MB / 限 500 条 → 逐行校验 → 覆盖模式先清空原题目 → 逐条入库（`@Transactional`，写库阶段本身不会留半截） |
| VO | 新增 `pojo/vo/QuestionImportResultVo`：导入数 / 跳过数 / 是否覆盖 / 跳过原因（最多回传 20 条） |
| 前端 | `teacher.js/.wxml/.wxss`：课题详情弹层新增「题库批量导入」区（追加/覆盖切换 + 覆盖二次确认、下载模板、从聊天选 CSV 导入、导入结果弹窗列出跳过原因、导入后自动刷新题目列表） |

**CSV 约定**：第一列＝题目（必填）、第二列＝标准答案（可留空）；`#` 开头为注释行（模板里的说明即用此形式）；首行若是「题目,标准答案」表头自动忽略；同一课题内题目重复自动跳过并记原因。

**验证**：`mvn -o -q compile` BUILD SUCCESS；`teacher.js` `node --check` 通过。**未做真机实测**（`wx.chooseMessageFile` 需真机/工具实测）。

### 十、教师端按课题导出与统计（N9）

| 层 | 改动 |
|---|---|
| Mapper | `StatsMapper` 新增 `selectTopicStats`（题目数 / 答辩场次 / 已完成 / 平均分，**含 0 场课题**；题目数用子查询避免 JOIN 放大行数）、`selectRecordsByTopic`（某课题的答辩记录，列与教师端列表同口径） |
| 接口 | 新增 `GET /teacher/stats/topics`（按课题统计）；`GET /teacher/export/records.csv` 支持可选 `topicId`（只导该课题）；新增 `GET /teacher/export/topic-stats.csv`（课题统计导出） |
| 修复（顺带） | `DefenseRecordsMapper.getDefenseRecords` 未 `SELECT dr.status`，导致**成绩导出的「状态」列恒为空**（`statusText(null)`）→ 补上该列（教师端列表状态也一并恢复正常） |
| 前端 | `teacher.js/.wxml`：导出逻辑抽成通用 `_downloadCsv()`；新增「导出课题统计」入口；课题详情弹层新增「导出本课题成绩 CSV」；`stats` 页新增「按课题统计」卡片（题目数/场次/已完成/均分） |

**验证**：`mvn -o -q compile` BUILD SUCCESS；`teacher.js` / `stats.js` `node --check` 通过。**未做真机实测**。

### 十一、答辩记录详情增强（N8）

| 层 | 改动 |
|---|---|
| 后端 | `QuestionDetailVo` 增 `standardAnswer`；`getDefenseQuestionsAnswers` 的 SQL 补 `dq.standard_answer`，并显式 `ORDER BY da.answer_id ASC`（原实现无排序，逐轮对照顺序依赖存储引擎返回顺序） |
| 前端 | 新增 `utils/radar.js`：五维雷达图用**原生 canvas 2d 手绘**（零图表库依赖），含 `averageDims()`（逐轮五维平均）与 `drawRadar()`（网格/轴线/数据多边形/顶点/维度名+分值，含 dpr 高清适配）；学生端与教师端共用 |
| 前端 | 学生端、教师端「回答详情」弹层新增：五维雷达图（逐轮平均）+ 雷达下方文字汇总 + 每条回答下的「标准答案」对照区块（追问无标准答案时不显示）。教师端此前没有逐轮五维数据，本次一并接上 `/teacher/defense/scoreDetail/{defenseId}` |

**验证**：`mvn -o -q compile` BUILD SUCCESS；`radar.js` / `student.js` / `teacher.js` `node --check` 通过。**未真机实测**（canvas 渲染需真机/工具看图）。

### 十二、核心纯函数回归测试（N14）

新增 3 个纯单元测试类（**不启动 Spring 容器、不连库**），共 **18 条用例，全部通过**（`mvn -o test -Dtest=...`：Tests run 18, Failures 0, Errors 0）：

| 测试类 | 覆盖 | 守住的回归点 |
|---|---|---|
| `util/CsvUtilsTest` | CSV 转义 | **公式注入中和**（= + - @ Tab CR 前置单引号）、逗号/引号/换行包裹、null 与数值 |
| `util/AiTextUtilsTest` | AI 协议文本解析 | 三段式「下一题:」、无问号追问、多标签取最后一个、旧格式 `【问题】`、问号兜底、null 安全、`cleanQuestionText` 拒绝纯点评 |
| `Service/Impl/ScorePersistenceServiceImplTest` | 评分解析 | **总分必须等于五维之和**（N6，无视模型自报 48）、单维越界夹到 0~10、旧格式五维解析、空输入不抛异常 |

**说明**：`src/test` 里原有的 `AiHelperApplicationTests`（`@SpringBootTest contextLoads`）需要 MySQL/Redis 才能过，本次未改动；上面 18 条用 `-Dtest=` 精确执行，不需要基础设施。

### 十三、答辩场次审计日志（N15）

| 层 | 改动 |
|---|---|
| 数据 | 新增表 `defense_audit_log`（defense_id / user_id / topic_id / event / round_num / detail / created_at）。**故意不建外键、不级联删除** —— 审计要在答辩记录被删后仍可追溯。同步：`docs/ddl_defense_audit_log.sql`（存量库）、`docs/schema.sql`（新库直接含，共 **11 张表**） |
| 后端 | 新增 `DefenseAuditLog` 实体、`DefenseAuditLogMapper(.xml)`、`AuditLogService(.Impl)`。写入**尽力而为且不参与答辩事务**（审计"已发生的就该留下"，不能因主流程回滚而消失） |
| 埋点 | `DefenseRecordsServiceImpl`：新建答辩 → `START`（detail 记「清理空壳 N 条」）；用户确认续答 → `RESUME`（记已答轮次）；`finishDefenseRecord` 落库成功 → `FINISH`（记总轮次与总分，user/topic 由新增的 `selectAuditContext` 反查） |
| 接口 | `GET /teacher/defense/auditLog/{defenseId}`（教师端查轨迹，整类已有 `@RequireRole(TEACHER)`） |
| 自检/文档 | `diagnose.ps1` 必需表清单补 `defense_audit_log`（10→11）并加补表提示；CLAUDE / AGENTS / 部署手册「10 张表」→「11 张表」 |

**验证**：`mvn -o -q compile` BUILD SUCCESS。

### 十四、N30 / N33 审计结论（无代码改动）

- **N33（视频处理状态存进程内 Map）**：**已实现**。`VideoProcessingServiceImpl` 已改走 Redis（`upload:video:processing:{id}` + 24h TTL），`getProcessingStatus` 未命中返回 `not_found`；`processingId` 由 `complete` 阶段生成并回传前端（类注释即记录了这次改造）。需求清单标注过期，本次改标。
- **N30（NPE/越界/逻辑优先级）**：抽查了全部历史高风险点——`qr.getData().get(0)`（有 `!isEmpty()` 守卫）、`rounds.get(0)`（有 `!isEmpty()` 守卫）、`UploadUtils.extensionOf`（`dot <= 0 || dot >= len-1` 守卫）、`toRelativePath`（先判 `startsWith(prefix + "/")`）、`LoginTokenValue.decode`（`idx < 0` 分流）、`ScorePersistenceServiceImpl` 评语截断（先判 length）、`DefenseController.resolveCurrentTeacherId`（try/catch）——**均已修复**，本次无新增改动。107 个 Java 文件未做逐行全量审计，如需要可另开专批。

---

# 2026-10-05

## 上线缺漏整改（P0/P1 全批次，A2 除外）

> 依据 `上线缺漏整改清单-2026-10-05.md` 执行；**A2（小程序生产域名）不做**，需真实 HTTPS 合法域名后单独拍板。
> **状态：`mvn -o -q compile` BUILD SUCCESS；全部改过的 JS `node --check` 通过；需在真机/多场答辩中复验。**

### 一、P0 阻断项

| 项 | 问题 | 修复 |
|---|---|---|
| **A1** | `/api/chat*`、`/api/final-evaluate`、`/api/voice/**` 未鉴权，可伪造他人 userId 答辩 | `WebConfig.PROTECTED_PATHS` 补入四类路径；`chatController` 的 `chat/chatPost/finalEvaluate/clearChatMemory/resumeInfo` 增加 `HttpServletRequest`，身份一律取 `AuthInterceptor.currentUserNumber`，body 里的 userId 非空且不一致直接拒绝；`VoiceController` 更新鉴权说明并只服务登录用户 |
| **A3** | 教师新增/编辑课题写死 `teacherId: 20` | `teacher.js` 删除该字段；`DefenseController` 用登录态 userNumber → `users.user_id` 覆写 DTO；编辑前查 `defense_topics.teacher_id` 做归属校验（他人的课题返回"无权编辑"）；新增 `DefenseTopicsMapper.selectTeacherIdByTopicId` |

### 二、P1 数据一致性与 DDL

| 项 | 修复 |
|---|---|
| **B1（N55）** | 追问阶段三表同事务：`saveExtraQuestionPhase`（追问题登记 + 答案行）与评分行合并进 `saveScoreThenAnswer`；放弃作答轮次同样改为"评分+答案"同事务（原 `saveRoundScoreAsync` + 独立答案写入取消）；`ScorePersistenceServiceImpl` 新增 `insertScoreRecordOrThrow`——事务内评分插入失败**抛异常触发回滚**，不再吞异常返回 false |
| **B2（N56）** | `EditUserInfoMapper.xml` 改动态 `<set>` + `<if>`；Service 空白字段归一、`没有可更新内容`、长度/格式校验、学号/邮箱唯一冲突预检（新增两条 count 查询）；只更新非空字段 |
| **B3** | `EditDefenseDto` 日期解析失败返回 null → `addDefense/editDefense` 直接拒绝"格式必须为 yyyy-MM-dd"；不再 `String.valueOf(null)` 写 `"null"` |
| **B4** | `new BigDecimal(double)` 全部改 `BigDecimal.valueOf`（chatController 2 处、DefenseRecordsServiceImpl 2 处）；聚合总分维持 SQL 层 `ROUND(...,1)` 统一口径 |
| **B5** | `schema.sql`：`defense_score_record` 普通索引改 `UNIQUE KEY uk_defense_round`（与新库部署同步）；补入完整 `system_feedback` 建表（含 `idx_status_created`）→ 新库导入即 10 张表 |

### 三、P1 小程序稳定性

- **C1**：新增 `utils/auth.js`（AUTH_KEYS/saveLogin/getToken/getUserInfo/updateUserInfo/clearLogin/handleAuthExpired）；login 页唯一写入口；学生/教师退出统一 `clearLogin()`（含清理历史遗留 `user`/`role`）；学生页资料保存修复"读 `user` 键写 `userInfo`"的 key 不一致。
- **C2**：新增 `utils/request.js`（requestWithAuth/uploadWithAuth/downloadWithAuth）；defense、defense-voice、feedback、feedback-admin、stats、student、teacher 全面补 401/403 统一处理（清 storage + 提示 + reLaunch 登录页），`handleAuthExpired` 并发去重。
- **C3**：忘记密码按钮补 `sendResetEmail` 别名（WXML 绑定与 JS 方法对齐），加 `sending` 防重与 loading 三态闭环。
- **C4**：登录/注册/忘记密码/教师课题保存/教师资料保存/学生报告上传全部加状态位 + 按钮 disabled；loading 一律在 complete 复位。
- **C5**：删除打印密码、token、完整登录响应、完整用户信息与全量业务响应的 console；错误日志只留必要行。
- **C6**：`uploader.js` 跟踪在途 `RequestTask/UploadTask`，新增 `cancelUpload()`（先 abort 本地任务再通知后端 abort，旧任务回调因 `session.cancelled` 不再覆盖状态）；`student.js` 视频处理轮询改为 `this._processingTimer` 并在 `onUnload/onHide` 清理；取消后不再弹"上传失败"。

### 四、P1 语音答辩（F10）

- **D1**：`VoiceController.asr` 先 `file.getSize()` 限 20MB 再读内存；新增 `Semaphore(1)` 并发控制（3 秒排队超时提示"稍后重试"）；`WhisperAsrServiceImpl` 保留二次大小校验。
- **D2**：录音按钮 disabled 增加 `isRecording || isRecognizing || !asrReady`；`onVoiceTouchStart` 同守卫；ASR `UploadTask` 可 abort；`_unloaded` + `_safeSetData` 保证卸载后不再 setData；ASR/TTS/chat/status 全部处理 401。
- **D3**：新增 `utils/url.js#resolveUrl`（绝对地址原样、相对路径拼 base），TTS 播放不再 `getBaseUrl() + url` 硬拼。

### 五、P1 部署与自检

- **E1**：部署手册、项目情况说明、AGENTS、CLAUDE 全部 9 张表 → 10 张表；反馈模块描述改为"内容/联系方式，pending → resolved"；修正 `defense_questions.question/standard_answer`、`defense_answers.student_answer/feedback`、`defense_score_record.id/expression_score/...` 字段名。
- **E2**：`diagnose.ps1` 必需表加 `system_feedback`（10 张）；新增 `uk_defense_round` 唯一性（Non_unique=0）与 `idx_status_created` 检查；修复提示区分空库/补反馈表/补唯一索引；新增 `-StrictLowPower`（低功耗环境变量缺失判失败）。
- **E3**：统一自检命令为 `powershell -ExecutionPolicy Bypass -File .\diagnose.ps1`（脚本在根目录），AGENTS/CLAUDE 结构树同步。
- **E4**：`application.yml.example` MySQL 端口改 3307、补 `app.voice.prompt` 注释；删除 `AppProperties.Auth.excludePaths`（WebConfig 从未读取该配置）。

### 六、P2 工程收尾

- **F1**：`AiProtocolConstants` 补 `SUMMARY_TAG_FULL`；chatController 点评/下一题/总结的 `contains/indexOf + 魔法数字` 全部改常量 + `tag.length()`；前端新增 `utils/protocol.js` 并在两答辩页使用。
- **F2**：删除 `RegisterServiceImpl.printStackTrace` 与异常 message 透传；`PasswordResetServiceImpl`、`WhisperAsrServiceImpl` 不再回传 SMTP/IO 细节；`GlobalExceptionHandler` 补 `NumberFormatException`、`MethodArgumentTypeMismatchException`。
- **F3**：`/student/getDefenseTopic` 支持 `pageNum/pageSize/keyword`，Mapper 增 `LIMIT` 与 count，学生页按第一页 50 条加载。
- **F4**：`teacher.wxss` 去掉 4 处 fixed（page 100vh flex + header/content/tabbar 文档流），tabbar 增加 `env(safe-area-inset-bottom)`；学生 tabbar 同步补安全区。
- **F5**：删除未使用且可加载任意 URL 的 `pages/webview/*` 并在 `app.json` 移除。
- **F6**：`app.json` 删除 `scope.userLocation`、`scope.writePhotosAlbum`；`project.config.json` 删除位置权限与 `requiredBackgroundModes:["audio"]`；学生页权限判断同步只认 camera。
- **F7**：报告上传表单带 `originalFileName/fileSize/kind`；后端生成 `答辩报告_学号_原名_短随机.ext` 安全存储名（去路径字符、长度截断），docx/xlsx/pptx/pdf 增加魔数文件头校验。

### 七、语音组件开箱即用（启动自举，2026-10-05 增补）

> 目标：新机器 clone 后只需开 Redis + Ollama + 启动后端，语音答辩即可用，**无需手动下载**。
> **状态：`mvn -o -q compile` BUILD SUCCESS；`node --check defense-voice.js` 通过；下载源已探测可达，未做完整 550MB 实测。**

- **背景**：引擎（约 15MB）/ 模型（约 487MB）/ ffmpeg（约 40MB）合计约 550MB，超过 Git 仓库单文件上限（GitHub 100MB），不可能随代码提交，故改为「启动后自动补齐」。
- **新增 `Service/Impl/VoiceComponentInstaller`**：缺失时自动下载，流式写盘（不进内存）、多镜像（hf-mirror 优先 / huggingface 兜底）、GitHub API 动态定位引擎包（固定 v1.9.2 兜底）、解压带 Zip Slip 防护、进度单调递增；ffmpeg 优先复用本机已有，找不到再下载。
- **新增 `constant/VoiceConstants`**：引擎/模型/ffmpeg 文件名常量，「查找」与「下载」共用，避免"下载完却找不到"。
- **`WhisperAsrServiceImpl`**：`@PostConstruct` 只做检测；缺失且 `app.voice.auto-install=true` 时改由 `ApplicationReadyEvent` 起 daemon 线程下载（**不阻塞启动**），完成后 `refresh()` 自动就绪、无需重启；`unavailableReason()` 在下载中返回带进度的文案。
- **`AsrService`** 增 `installing()/installProgress()` 默认方法；`GET /api/voice/status` 增 `asrInstalling/asrProgress`。
- **前端 `defense-voice.js`**：下载期间显示「语音组件首次下载中 x%」并每 15s 回查，完成后自动放行；`onUnload/onHide` 清理轮询定时器。
- **配置**：`AppProperties.Voice` 增 `auto-install / install-dir / model / proxy`；`application.yml.example` 同步注释（默认 `auto-install: true`、`model: small`）。
- **文档**：部署手册新增「十·一 语音答辩组件（默认自动安装）」+ 检查清单第 11 项。

**验证**：编译通过；JS 语法通过；三个下载源 HEAD 探测均 200（hf-mirror 模型 / GitHub 引擎 / gyan.dev ffmpeg）；已实拉 `whisper-blas-bin-x64.zip`（21MB）确认包内为 `Release/whisper-cli.exe` + `whisper.dll` + `ggml-*.dll`，与解压后的定位逻辑吻合。**未做完整 550MB 端到端实测**——本机 `F:\whisper` 已存在，`hasEngine/hasModel` 命中会跳过下载；需在无 whisper 的干净机器（或临时指向空 `install-dir`）上首启验证。

---

# 2026-10-02

## 判分可信度收口（N46/N47/N49/N51/N53/N54 + N55 事务修复）

> 涉及 `Controller/chatController.java`、`Service/ScorePersistenceService(.java/Impl)`、
> `pages/student/student.js`、`teacher.wxss`/`student.wxss`。
> **状态：`mvn -o -q compile` BUILD SUCCESS；N55 / N47 已实测通过（defenseId=320）；
> N46/N51/N53/N54 已实现，依赖模型行为，待多场答辩累积样本复验。**

### 一、判分家族

| 编号 | 问题（历史证据） | 修复 |
|---|---|---|
| **N46+N49** | 幻觉点评 + 模板化（298/299两场"概念阐述准确…"刷屏、299第4轮评的是第5轮主题） | **服务端接地校验**：点评与本轮答案的二元组交集 < `GROUNDED_MIN_HITS`(3) → 判不接地，追加一次「带本题+回答原文」的重写调用（`rewriteGroundedComment`，maxTokens 120）；重写结果仍不接地或失败则维持原点评 |
| **N47** | 跨轮重复作答照给34分（297第5轮重发第2轮答案） | 早判定区新增 `isRepeatOfPriorRoundAnswer`：归一化后与**更早轮次**答案 Dice ≥ `CROSS_ROUND_REPEAT_DICE`(0.8) → 走固定零分流程、不调模型 |
| **N51** | 答非所问复核被 `recheckDone` 短路（298第2轮问MapReduce答NameNode漏网拿38分） | else分支复核重排：**硬信号（覆盖率 < 0.25）优先**跑 OFF_TARGET 复核；复核判切题时仍允许继续走错误漏判复核，`recheckDone` 仅在硬信号复核命中归零后置位 |
| **N53** | 明显跑题的长回答被复核误救（300第6/8轮"今天天气不错…做菜"救成28/14分） | 跑题救回复核前加门槛 `isClearlyOffTarget`：回答与当前题覆盖率 < `RESCUE_MIN_COVERAGE`(0.05) → 不救回维持0分 |
| **N54** | 明显错误/正确答案被误判（基线第2轮正确做法被复读成"纠正"砍到12分） | `runRecheck`/`buildRecheckInstruction` 签名扩展：三类复核指令统一注入「当前题 + 学生本轮回答**原文**」；错误复核指令补硬约束「学生回答原文本身正确时，严禁把学生原话复述成纠正」 |
| N50回归 | — | 本轮3轮点评均挂「（有明显回答错误）」后缀，错误漏判复核零误触发（N50修复持续生效） |

### 二、N55 事务修复

- `ScorePersistenceService` 新增 `saveScoreThenAnswer(record, AnswerWriter)`：`@Transactional` 内先写评分行（幂等 doSave）再回调答案写入，答案异常原样上抛触发**整体回滚**——两行要么都落、要么都不落。
- 落库链路重构：预设路径评分行+答案行同事务（原N21「末轮同步/其余异步」分流取消——insert毫秒级，统一同步）；追问阶段改为**先答案后评分**（写库失败不再出现半截数据）。
- 放弃作答路径（`saveGiveUpScoreRecord`）本轮未动，同源风险留观。

### 三、前端/样式

- **N25**：删除 `student.js` 第一个被同名方法覆盖的 `logout()` 死代码。
- **新-1**：`teacher.wxss` 14组 + `student.wxss` 2组重复选择器合并；合并后复扫0重复。

### 四、实测记录（defenseId=320，curl全自动）

| 用例 | 结果 |
|---|---|
| N55重演（CHECK约束拒答案行） | 评分行=0 / 答案行=0（旧行为评分行会落1行）；约束已清理 |
| N47第2轮原样重发第1轮答案 | 112ms秒回（未调模型）、0分固定文案、轮次照常推进 |
| N45顺带验证 | 模型自评33 ≥ 32 → 保护生效不打折 |
| N50顺带验证 | 后缀零误触发 |

### 五、遗留

- N46/N51/N53/N54均含模型行为依赖，本轮复验只覆盖了"不误触发"方向；改判/重写方向需多场答辩观察日志。
- N45阈值仍为32（待样本）；N46与N54的边界：接地校验只管"有没有引用本轮内容"，不管"引用得对不对"。

---

# 2026-10-01

## 审计修复（对抗性代码审查 P1/P2 落地）

> 对同日「大规模优化」交付做全栈对抗性审查后修复。**后端 `mvn -o -q compile` BUILD SUCCESS**，
> 改动的4个页面JS `node --check` 全部通过。审查结论：无P0，修复5项P1 + 6项P2。

### P1修复（5项）

| # | 问题 | 修复 |
|---|---|---|
| P1-1 | **CSV公式注入**：`ExportController.csv()` 只转义逗号/引号/换行，`=`开头的单元格在Excel中会被当公式执行 | `csv()` 对危险前缀统一前置单引号中和；行分隔符固定为 `\r\n`；`Content-Disposition` 补ASCII回退 |
| P1-2 | **B3导出功能无前端入口**：后端接口写好了，小程序里没有任何按钮触发 | teacher页新增「导出答辩成绩」入口 + `exportRecords()`：`wx.downloadFile` 带token，非200拒绝交付 |
| P1-3 | **`scoreDetailLines`串台**：学生详情弹层的逐轮五维评分只在成功时setData，换记录/加载失败/关闭时不清空 | `openAnswerDetail` 打开即置空、scoreDetail失败/空数据显式置空、`closeAnswers` 同步清空 |
| P1-4 | **`app.wxss`全局样式污染**：新增的`page`规则带background/color/font-size，作用于全部旧页面 | 保留CSS变量定义，**剥离4条视觉覆写**；旧页面回到原白底 |
| P1-5 | **`feedback.js loadList()`静默失败**：无fail回调、非1码无提示 → 401/断网时页面显示"暂无反馈记录" | 补else（显示后端msg）+ fail（网络提示）+ token缺失提示；删掉`onLoad`里的重复首载 |

### P2修复（6项）

| # | 问题 | 修复 |
|---|---|---|
| 1 | `chatController`主流程开场白仍硬编码 | 首轮出题改用 `AiProtocolConstants`，拼法与 `restoreChatMemory` 严格一致 |
| 2 | feedback-admin `loadMore`连点重复请求、`submitReply`双击重复回复 | 加 `loadingMore` 在途标记 + `_replying` 防双击标记 |
| 3 | defense进度条 `questionCount>totalRounds` 时 `width>100%` 溢出 | `.progress-strip` 补 `overflow: hidden` |
| 4 | `ddl_system_feedback.sql` 无 `(status, created_at)` 组合索引 | 补 `idx_status_created`（已建表的库需手动ALTER） |
| 5 | 非法JSON请求体落到兜底Exception，提示"服务器处理失败" | `GlobalExceptionHandler` 补 `HttpMessageNotReadableException` → code 400 |
| 6 | `/teacher/feedback/pending-count` 前端零调用、反馈无限频、my列表无分页 | **未改**，登记待拍板 |

### 实测待验证

1. 学生token打教师接口 → 403；无token → 401。
2. 学生A查学生B的详情 → 「无权查看」。
3. 教师页「导出答辩成绩」：下载成功；构造姓名 `=1+1` → 导出单元格应为 `'=1+1`。
4. 详情弹层：打开10轮记录 → 关闭 → 打开空壳记录 → 逐轮区块应消失。
5. 视觉回归：login/student/teacher/defense/defense-voice/register/forgetPassword 逐页对比。
6. 反馈页断网/token过期时应有toast而非"暂无反馈"。
7. 新开一场答辩：首轮开场白、题目解析、进度条与改动前一致。

---

## 大规模优化（工程规范 + 功能扩展 + UI）

> 背景：参考开源项目 `373675032/smart-medicine` 的工程规范。
> **核实结论：该项目并未微调大模型**，而是通过 `dashscope-sdk-java` 调用通义千问云API。
> 因此本次「借鉴」落在**工程规范与少量通用功能**上。

### 一、工程规范加固

| # | 改动 | 文件 |
|---|---|---|
| A1 | 新增AI协议常量类，收敛协议标签与三档标记 | `constant/AiProtocolConstants.java`（新增） |
| A1 | 改用常量，删除本地重复定义 | `util/AiTextUtils.java` |
| A1 | 解析「点评/下一题/评分」行改用常量 | `Service/Impl/ScorePersistenceServiceImpl.java` |
| A1 | 续答回灌拼接标签改用常量 | `Controller/chatController.java` |
| A2 | 新增统一响应码常量（SUCCESS=1/FAIL=0不变，新增4xx/5xx语义码） | `result/ResultCode.java`（新增） |
| A2 | 新增 `Result.error(int code, String msg)` 重载 | `result/Result.java` |
| A3 | 新增参数校验依赖 | `pom.xml` |
| A3 | 新增 `MethodArgumentNotValidException` / `ConstraintViolationException` 全局处理 | `Config/GlobalExceptionHandler.java` |
| — | **修复预存在语法错误**：`AiHelperApplication.java:11` 的 `}.` → `}` | `AiHelperApplication.java` |

### 二、功能扩展

| # | 功能 | 说明 |
|---|---|---|
| B2 | **教师端数据总览** | `GET /teacher/stats/overview`（总场次/已完成/进行中/课题数/学生数/平均分 + 课题分布 + 近14天趋势） |
| B3 | **答辩成绩导出** | `GET /teacher/export/records.csv`，UTF-8 BOM，零新依赖，上限10000行 |
| B4 | **系统反馈模块** | 建表 `docs/ddl_system_feedback.sql`（**需手动执行**）；学生接口 `/student/feedback/submit|my`、教师接口 `/teacher/feedback/list|pending-count|reply` |
| B1 | **答辩记录详情增强** | 新增逐轮五维明细：`GET /student/scoreDetail/{id}`（带归属校验）、`GET /teacher/defense/scoreDetail/{id}` |

### 三、UI/交互

| # | 改动 | 文件 |
|---|---|---|
| C1 | 全局设计系统：CSS变量 + `g-card/g-btn/g-tag/g-stats/g-bar/g-empty` 通用类 | `app.wxss` |
| C2 | 补齐登录页缺失样式 | `pages/login/login.wxss` |
| C3 | 答辩页顶部新增可视化进度条 | `pages/defense/defense.wxml`、`defense.wxss` |
| B4前端 | 新增「意见反馈」页（提交+我的反馈） | `pages/feedback/*`（新增） |
| B4前端 | 新增「反馈管理」页（教师端，筛选/分页/回复） | `pages/feedback-admin/*`（新增） |
| B2前端 | 新增「数据总览」页（概览卡+课题分布+趋势条） | `pages/stats/*`（新增） |

### 四、部署/验证清单

1. **执行建表脚本**：`mysql -uroot -p ai_helper < docs/ddl_system_feedback.sql`
2. 后端编译：`mvn compile` ✅ **已通过**（JDK 17）
3. 需实测：登录/注册空学号密码 → 提示是否友好；教师端「数据总览」数字是否正确；「导出成绩」能否下载且Excel不乱码；反馈提交→教师回复→学生看到回复；**答辩主流程回归**（跑一场完整10轮）。

### 五、未做/遗留

- **N55（评分行与答案行不同事务）**：本次未动，仍为遗留问题。
- **N56（`/editUserInfo`全量UPDATE）**：本次未动。
- 导出为CSV（无新依赖）；若需小程序端 `wx.openDocument` 直接打开，后续可改导出xlsx（需引入POI）。

---

# 评分可信度演进

> 2026-09-25 ~ 2026-09-28 期间，围绕3B模型判分不稳定问题的一系列修复。
> 按时间顺序排列，方便理解演进逻辑。

## 2026-09-25：N42 跑题误判修复 + N11 防作弊

**触发**：defenseId=288 实测第5轮——实质性切题回答被判[跑题]0分。

| 层 | 改动 |
|---|---|
| prompt | 判分第一步明确"只有整段回答完全未回应本题才判跑题"；三条严禁判跑题：Markdown排版/夹带口头语但主体回应/含相关具体概念步骤 |
| prompt | 特别注意段加【长度门槛】：超50字且含实质内容的，夹带口头语也必须按内容正常评分 |
| 服务端 | [跑题]0分且归一化后回答≥20字 → 追加纠正指令重评一次（`rescoreSuspectedMisjudge`） |
| 词表 | `isJunkAnswer` 归一化补"么"（"你是对的么"→"你是对的"命中） |
| N11 | 新增 `isCopiedQuestion()` / `isPleadForScore()`：复制题目/讨分 → 固定零分，不调用评分模型 |

## 2026-09-26：防作弊扩展 + 列表页伪造分数清理 + 明显答错半分档

**触发**：defenseId=292 第4轮——学生把HDFS小文件机制说反，模型却判[切题]给35分。

| 改动 | 说明 |
|---|---|
| 三档标记 | `[切题]/[错误]/[跑题]` 三选一（原二选一） |
| 半分档 | 命中 `[错误]` → 五维分各×0.5、总分重算为五维和 |
| 评分口径统一 | 新增 `buildScoreLine()` 与 `normalizeCommentAndScore()`，在落库与写Redis之前重写回复，使**前端气泡分 == defense_answers.score == defense_score_record五维和 == Redis记忆** 四处同源 |
| 防作弊扩展 | `isCopiedQuestion()` 由「仅当前题」改为遍历本场全部已问题目 |
| 伪造分数清理 | 教师端/学生端列表去掉 `item.score × 0.9 / × 1.1` 现算，只显示库中真实分 |

## 2026-09-27：错误漏判复核 + 答非所问复核 + 工具调用泄漏修复

**触发**：defenseId=295 复盘——10轮里4轮点评自曝错误却给高分。

| 编号 | 改动 |
|---|---|
| B' | `ChatConfiguration.chatClient` 去掉 `.defaultTools(textTools)`：模型把工具调用意图输出成自然语言，整轮没有点评/评分行 |
| A2 | 原"影子诊断"升级为**错误漏判复核**：点评命中 `ERROR_CUE_PHRASES`（35词）→ 追加正确性复核 → 复核判[错误]才按半分档计 |
| C | 新增**答非所问复核**：`questionCoverage(当前题, 回答) < 0.25` 且回答≥20字 → 追加"是否回应本题"复核 |
| 复用 | 抽出 `applyWrongAnswerHalfScore()` 与 `applyZeroScore()`，跑题分支与复核分支共用同一套计分逻辑 |

## 2026-09-27 补充：296场实测反馈与提示词收敛

| 调整 | 原因 |
|---|---|
| 判分第五步加**反滥用硬约束** | 296场10轮里4轮首判[跑题]，其中3轮是误判——模型学会了"指控学生答的是另一道题"并滥用 |
| `PLEAD_FOR_SCORE_PHRASES` 补11个变体 | "给我评判一个高分"因中间多了"评判一个"未命中 |
| `handled` → `recheckDone` | 296场第5轮两个复核串联各跑一次，白白多一次模型调用 |

## 2026-09-27 补充二：297场复盘问题登记（N45~N49）

| 编号 | 问题 | 297场实测证据 | 已定方案 |
|---|---|---|---|
| N45 | `[错误]`标记与模型自评分矛盾 → 正确答案被砍半 | 第3轮AQI正确版，模型自给36分却标[错误] → 砍成18 | 一致性保护：模型原分≥30则保护不打折 |
| N46 | 幻觉点评（点评与本轮答案无关） | 第3轮点评"把大文件与小文件的读写机制说反了"——那是第4轮主题 | prompt加硬约束：点评必须引用学生本轮回答里的1~2个具体词 |
| N47 | 跨轮重复作答未检测 | 第6轮把第5轮答案原样重发，照给34分 | Dice≥0.8 → 固定零分 |
| N48 | 追问去重漏"同主题不同措辞" | 追问2与追问5同一主题 | 主题词重叠 |
| N49 | 点评模板化、评分区分度低 | 三场30轮点评高频以"回答正确且逻辑清晰"开头 | prompt禁用套话 + 服务端对连续两轮相同点评触发重写 |

## 2026-09-27 实测三：298场（N45/N46首测复盘）

> **结论：本场不能作为N45/N46的验收依据**——输入数据本身无效（测试文案混进答案）。
> N45保护分支触发0次，N46未生效，另暴露N50/N51。

**新登记**：
- **N50**：`[切题]`点评挂"（有明显回答错误）"→ 每轮误触发错误漏判复核。修复：`containsErrorCue` 先剥 `WRONG_ANSWER_NOTE` 后缀。
- **N51**：答非所问复核被 `recheckDone` 短路 → 明显答非所问漏网。方案：优先跑答非所问复核（硬信号更可靠）。

## 2026-09-27 实测四：299场（N45首次生效 + 发现N52题号错位）

> **本场数据干净**，可作验收依据。

| 结论 | 说明 |
|---|---|
| N45 ✅ | 第4轮模型自评35分却标[错误] → 保护不打折，落库35（无保护则17.5） |
| N50 ✅ | 后缀零误触发 |
| N46 ❌ | 第2/3/5/6轮点评几乎一字不差，未引用学生答案具体词 |
| **N52（高危）** | 预设题阶段模型自拟题目 → 落库题号与学生所见不符。298/299连续两场 |

## 2026-09-28：N52修复（预设题阶段强制使用题库题目）

**方案A'**：预设阶段无条件用题库题覆盖模型自拟的题（`rewriteNextQuestionInResponse` 决定前端展示）。
- 一处到位，三处下标假设同时恢复成立，不需要DDL。
- 仅预设阶段生效；追问阶段模型自拟是设计意图，不受影响。

## 2026-09-28 实测五：300场（N52修复生效 + 暴露N53/N54）

| 结论 | 说明 |
|---|---|
| N52 ✅ | 拦截2次自拟题，落库题号与答案首次对上 |
| N45 ⚠️ | 第4轮故意说错，模型标[错误]却给32分 → N45保护不打折（应半分档16）。阈值32卡边界，待调 |
| **N53** | 明显跑题的长回答被复核误救（第6/8轮"今天天气不错…做菜"救成28/14分） |
| **N54** | 明显错误的长回答拿高分，错误漏判复核未纠正（第7轮"完全不能提升效率"给35分） |

---

# 2026-09-28

## 核心加固（越权修复 + 中途退出续答 + 事务/幂等加固 + 自检脚本扩展）

> 来源：项目审计输出的P0/P1清单。
> **状态：后端 `mvn -o compile` BUILD SUCCESS；`defense.js` node语法校验通过；`diagnose.ps1` 实机跑通。**
> **本次未触碰评分prompt与判分/复核逻辑**（N45/N46/N50~N54区域保持原样）。

### P0-1 越权修复（N19）

| 改动 | 文件 | 说明 |
|---|---|---|
| token携带角色 | 新增 `util/LoginTokenValue.java`、`pojo/enums/UserRole.java` | Redis值格式 `userNumber\|role`；**兼容旧token**：解析不出角色时为null → 只要求登录的接口照常放行 |
| 声明式角色校验 | 新增 `interceptor/RequireRole.java`，改 `AuthInterceptor` | 注解可标在Controller类或方法上；不写死路径判断 |
| 保护范围 | `Config/WebConfig.java` | `PROTECTED_PATHS` 补 `/teacher/**`、`/editUserInfo` |
| 教师接口 | `teacher/DefenseController`、`teacher/DefenseRecordsController` | 整类 `@RequireRole(UserRole.TEACHER)` |
| 只能改自己 | `login/editUserInfoController` + Service + Impl | 身份取自登录态；Service校验「请求体id == 登录用户user_id」 |

### P0-2 中途退出后重进 → 续答（N1）

| 改动 | 文件 | 说明 |
|---|---|---|
| 开始or续答 | `DefenseRecordsServiceImpl#startOrResumeDefenseRecord` + 新增 `DefenseResumeVo` | 存在「已作答的pending记录」→ 不删不建，返回已答轮数/当前题/历史轮次；只有一题未答的空壳才清理并新建 |
| 历史组装 | 同文件 `fillResumeDetail` | 以 `defense_score_record` 为轮次骨架，答案按question_id匹配 |
| 会话记忆回灌 | `chatController#restoreChatMemory` | 按正常轮次格式回灌；最后一条的『下一题:』即当前题 |
| 前端展示 | `pages/defense/defense.js` | `onLoad` 按 `resumed` 分流：续答时提示「已为你继续（已答N题）」并直接展示当前题 |
| 只读探测 | 新增 `POST /api/chat/resume-info` | 只查不写，返回 `resumable/answeredCount/roundNum/totalRounds/currentQuestion` |
| 前端确认 | `defense.js` 进场流程 | `resume-info` → 可续答则 `wx.showModal`（「继续作答」/「重新开始」，**默认取消=重新开始**） |

### P1-3 事务/异步落库/会话锁（N20/N21/N22）

| 编号 | 改动 |
|---|---|
| N20 | `DefenseRecordsServiceImpl` 的 `saveAiQuestion`/`savePresetQuestionAnswer`/`startOrResumeDefenseRecord`/`finishDefenseRecord` 加 `@Transactional(rollbackFor = Exception.class)`；catch由「log后return」改为「记录上下文+抛BusinessException」 |
| N21 | `ScorePersistenceService` 新增同步方法 `saveRoundScore`；`saveRoundScoreAsync` 返回 `CompletableFuture<Boolean>`；**最后一轮改同步落库**——修掉「收尾聚合总分时末轮分数还没写库」的漏算窗口 |
| N22 | `ChatConfiguration.RedisChatMemory` 去掉「无锁强制写」分支，改为「等待50ms × 最多5次重试」，仍失败则放弃本次写入 |

### P1-4 幂等/放弃作答落库/总分口径（N4/N5/N6）

| 编号 | 改动 |
|---|---|
| N4 | 代码侧：`DefenseScoreRecordMapper#countByDefenseIdAndRound` + `doSave` 落库前判重；DB侧：新增 `docs/migration-20260928-score-record-unique.sql` |
| N5 | `saveGiveUpFollowUpAnswer` 增加兜底：历史被裁剪时改用「最新登记的追问题」定位sqId |
| N6 | **核实为已修复**（2026-09-26的 `normalizeCommentAndScore` + `buildScoreLine` 已把评分行重写为五维之和） |

### P1-6 答辩前自检（N2）

`diagnose.ps1` 扩展为**答辩前7项自检**：
1. 可用物理内存
2. MySQL连通 + 必需9表齐全 + 评分表可读
3. Redis连通（带密码PING）
4. Ollama服务 + 模型已pull + 是否已加载进显存
5. GPU + 后端端口
6. Ollama环境变量
7. 推理速度抽测

**不写死**：全部走 `param()` 可覆盖；默认值**自动读本机 `application.yml`**。

---

## 续答回归修复

**触发**：用户实测——重新编译后第一次进答辩页直接就是追问的问题。

**根因**：续答判定过宽——既没有「是不是刚刚那一次」（无时间窗口），也没有「用户是否真想继续」（无确认），更没排除「已答满轮次却没收尾」的脏记录。

**修复**：
- `findResumableDefenseId`：三条**同时**满足才算"可续答"——① pending记录；② `0 < 已答轮数 < 总轮次`；③ 最后一次作答在**30分钟**内
- `startOrResumeDefenseRecord(..., boolean resume)`：**只有 `resume=true` 才可能续答**；默认一律「清理空壳+新建」
- `AppProperties` 新增 `defense.resumeWindowMinutes`（代码默认30）

**实测结果（defenseId=301）**：
- 续答与「重新开始」均通过，分数三方一致
- 题号严格q45→q69→q70→q71→q72（N52持续生效）
- `round_num` 1~8连续无跳号无重复（N4）

---

# 2026-09-29

## P0/P1待测项清完（N19/N21/N22通过，N20部分达成）

> 本轮**未改任何业务代码**，只做验证。

### N19 教师端越权修复 —— 通过

| # | 步骤 | 结果 |
|---|---|---|
| 1 | 学生登录 | 返回 `token` + `role: student` |
| 2 | 学生token → `GET /teacher/defense/records` | **403** `当前账号无权访问该功能` |
| 3 | 学生token → `POST /teacher/addDefense` | **403**，未写入任何课题 |
| 4 | 无token → `GET /teacher/getAllDefense` | **401** `未登录或token缺失` |
| 5 | 教师token → `GET /teacher/defense/records` | **200**，返回63条记录 |
| 6 | 学生改自己资料 | `保存成功`，库中字段已更新 |
| 7 | 学生改他人资料 | `只能修改自己的资料`，库中目标行未被改动 |

### N21 完整10轮 + 末轮同步落库 —— 通过（defenseId=304）

- `defense_score_record` **10行**、`round_num` 1~10连续无重复；`defense_answers` **10行**
- 总分口径一致：各轮五维和 = 306 → 306/10 = **30.60** = `defense_records.score`
- **末轮同步落库实锤**：roundNum 1~9写库线程是 `mvc-task-*`（异步），**roundNum 10是 `http-nio-8080-exec-2`（同步）**
- Redis记忆中的原始评分行与库中逐轮五维分**逐条一致**

### N20 写流程事务 —— 部分达成，派生N55

| 待测项预期 | 实测 |
|---|---|
| 答案行不落库 | `defense_answers` **0行**（事务回滚生效） |
| **评分行必须一起回滚** | ❌ `defense_score_record` **落了1行**（round 1，五维全0） |
| 前端报错/不再静默 | ❌ HTTP **200** + 正常三段式回复，前端完全无感 |

**根因**：`chatController.java:872-880` 先 `saveRoundScoreAsync`（`@Async`独立线程+独立事务，先提交）再 `savePresetQuestionAnswer`（另一事务），两者不在同一事务边界。

→ 已登记为 **N55**，未修，待用户拍板方案。

---

# F10 语音答辩

## 方案演进

| 阶段 | 方案 | 状态 | 原因 |
|---|---|---|---|
| V1 | 微信同声传译插件（ASR+TTS） | **已废弃** | 个人主体小程序无法添加该插件 |
| V2 | 后端本地实现（Windows SAPI TTS + whisper.cpp ASR + ffmpeg转码） | **已开发，待真机实测** | 不依赖插件资质、不联网 |

## V2 实现

| 能力 | 实现 |
|---|---|
| AI提问朗读（TTS） | Windows自带SAPI离线合成（`SapiTtsServiceImpl`），结果缓存在存储目录 `tts/` |
| 学生语音作答（ASR） | 小程序原生录音（`wx.getRecorderManager`）→ 上传 → 后端whisper.cpp识别 |
| 音频转码 | ffmpeg（必需） |

## 依赖目录

| 位置 | 内容 |
|---|---|
| `F:\whisper\whisper-cli.exe` | 识别引擎（whisper.cpp BLAS版，release b5130） |
| `F:\whisper\models\ggml-small.bin` | 中文模型（465MB） |
| `F:\whisper\ffmpeg.exe` | 音频转码 |
| `scripts/install-whisper.ps1` | 一键安装：下载引擎/模型 + 自动复用本机已有ffmpeg |

## 实测结论

- TTS播报：题目自动朗读、可重播，正常
- ASR全链路：模拟器录音 → ffmpeg转16kHz wav → whisper识别 → 文字回填 → 发送评分 → 正常落库
- 识别耗时：约5~10秒/条（CPU，small模型）
- 识别准确率：加提示词后 `Matplotlib`/`pandas` 能纠正，但 `avg(pm25)`、`汇制浙线读` 仍错

## 未完成

- [ ] `mvn compile` 尚未验证（新增 `opencc4j` 依赖）
- [ ] 重启后端，实测提示词+繁简转换效果
- [ ] 真机实测（录音ASR仅真机可用）
- [ ] Git提交与推送

---

# 2026-09 改动归档

> 9月早期的改动，已稳定运行，归档备查。

## 2026-09-17

### 改动（一）：弹层遮挡修复

**教师端**：`teacher.wxss` 删除第二套重复的 `.modal-*` 定义（约60行），每个类名只保留一处；卡片改 `width:100% + max-width:670rpx + box-sizing`，结构上不可能横向溢出。

**学生端**：`student.wxml` 把 `</scroll-view>` 收口移到两个弹层之前，使4个弹层统归 `.page` 直属子节点（`transform` 会使内部 `position:fixed` 退化）。

### 改动（二）：忘记密码修复 + 服务器地址统一 + 小程序残留清理

- **N23**：`pages/forgetPassword/forgetPassword.js` `email: email` → `email: this.data.email`（原写法抛ReferenceError，请求根本不执行）
- **N29**：全项目统一 `config.getBaseUrl()`，修正7处裸用 `config.serverUrl`；`serverUrl` 填入真实WLAN IP
- **N39**：删除 `pages/logs/`、`pages/index/`、`pages/reset/`、`utils/util.js` 等零引用残留

### 改动（三）：上传功能改造（本地存储 + 真分片 + 越权修复）

**决策**：存储改用本地磁盘（不走阿里云）；视频做真分片；上传相关越权一起修。

**后端**：
- 新增存储抽象：`FileStorageService` + `LocalFileStorageServiceImpl`（`F:\ai wordplace`，三级降级自动选盘）
- 真分片：`init/part/complete/abort`，按真实字节区间 `FileChannel.write(buf, offset)` 写入
- 附件统一读写：`MediaFileService`（校验→落盘→写库→清理旧文件）
- 越权：`WebConfig` 注册 `AuthInterceptor`（此前从未注册，全站实际无须登录）；保护 `/api/video/**`、`/api/report/**`、`/student/**`

**前端**：
- 新增 `utils/uploader.js` 通用上传模块（真分片+自动重试+降级兜底）
- `student.js` 瘦身（删除约300行重复上传实现）
- 新增「附件所属题目」选择器、视频预览/删除、报告查看下载/删除
- 附件上传提示改造：废弃常驻绿字，改 `.top-banner`（成功绿/失败红，5秒自动消失）

### 补充改动（同日稍后）

- **上传规则代码级默认值**：`AppProperties.FileRule` 新增 `videoDefaults()` / `reportDefaults()`，`application.yml` 从"必需"变成"可选覆盖"
- **存储目录自动选择**：`LocalFileStorageServiceImpl.resolveRoot()` 三级降级（显式配置 → 自动挑盘 → 用户主目录）
- **`application.yml.example` 配置模板**：新增 `src/main/resources/application.yml.example`（会被git跟踪），敏感值改为占位符

## 2026-09-16

### 前端体验优化

- **微信式输入框**：多行 `<textarea>`，`auto-height`（min 176rpx/3行，max 288rpx/6行），发送只走按钮
- **登录键盘流转**：账号Enter → 跳密码框；密码Enter → 登录
- **渲染错乱根治**：`defense`/`student` 两页统一改为"固定100vh视口 + flex三段式"（`.page` height:100vh+overflow:hidden，header/输入栏/tab-bar回归文档流）

## 2026-09-15

### 权威轮次计数修复 + 防死循环兜底重连

**9-14实测暴露的问题**：
1. `trimChatMemory` 把Redis会话记忆物理截断到12条 → `assistantCountInHistory` 永远≤6 → 收尾条件整体不可能成立
2. 轮次号落库错乱：235场 roundNum序列 `1,2,1,4,5,6,6,6,6,6,6`

**修复**：
- 轮次改按 `defense_score_record` 落库行数+1（天然免疫记忆裁剪）
- 第10次作答即收尾：模型输出"总结:"用总结，没有则剥残留"下一题:"并补占位总结强制收尾
- `extraAskedCount` 纠正为"本轮之前已完成的追问次数"
- 放弃短语补漏："不太清楚/太不清楚/不太懂/不太会"

### 敷衍/无实质作答稳定判0分

- 新增 `JUNK_ANSWER_PHRASES` + `isJunkAnswer()`：纯标点、1~2位纯数字、与短语表精确相等（26条）→ 固定零分流程，不调用评分模型
- 精确匹配避免子串误伤（"一般用快排"不受影响）
- 同日二次加强：语气词字符集规则（嗯哦啊呃额唔哎嘿诶唉噢喔哈呀哇）、剥离开头缓和语（感觉/我觉得/我认为）、词表补附和/声称类
- 同日三轮加强：复读判定（同一字符重复≥2次或双字单元重复≥4字）

### 题目序号与顶部进度 + 开场白修复 + 答辩体验四项优化

- **题目序号**：1~5 → "第N题"，6~10 → "追问N-5"，总结轮不编号
- **开场白bug**：原纯文本不含格式标记 → 前端解析不出题目。改三段式：`点评:你好...\n下一题:<题目>`
- **四项优化**：敷衍判定补网（"我是250"）、追问题去重、题目文本清洗（收敛连续问号）、总结带总评

## 2026-09-14

### 10轮答辩改造 + 补题库 + 内存崩溃修复

**目标**：「预设题 + 2次追问」→ **5道预设题 + 5次AI追问 = 共10轮**

- `EXTRA_QUESTION_LIMIT` 2 → 5
- 进度标签追加到用户消息末尾：`[进度: 第X题/共Y题, 已追问Z/5次]`
- 系统提示词加固：明确「本场约10轮：5预设+至多5追问，严禁提前输出总结」
- **内存崩溃根因修复**：`finishDefenseAggregation` 硬依赖模型输出"总结:" → 小模型常输出"下一题:" → 不收尾 → 无限循环直到内存枯竭
  - 兜底路径：`assistantCount` 到11仍未输出总结 → 强制收尾 + 补"总结:"信号

## 2026-09-10

### 答辩Bug修复 + 每次答辩独立记录

**Bug 1**：学生回答"不知道/不会"类短语时，AI直接结束答辩给分 → 改为固定模板零分流程，继续下一题

**Bug 2**：答辩结束后小程序前端看不到答辩记录 → 多原因修复（重复空壳记录、列表无排序、总分从不落库、页面不刷新）

**记录模型重构**：
- `getOrCreateDefenseRecord` 改为「取该学生该课题**进行中（pending）**的最新一条，没有才新建」
- 进入答辩页的 `/api/chat/clear` 清理上次遗留的空壳记录（一题未答）+ 创建本次答辩的独立记录
- 答辩结束：聚合各轮五维平均分 + 总结写回 `defense_records.score/feedback`，状态置 `completed`

---

# 附录

## Git提交历史（最近5次，母版截止点）

```
508d803 完善答辩         ← 母版基准
5fd892c 完善答辩
0d632c6 完善ai答辩
e4d6d2a 修复大模型会话记忆不保存问题
dacffd4 接入千问模型实现ai会话，使用redis存储会话记忆
```

## 运行环境要求

| 组件 | 版本/配置 |
|------|----------|
| Ollama模型 | `qwen2.5:3b-16k` |
| Ollama环境变量 | `OLLAMA_NUM_PARALLEL=1`，`OLLAMA_MAX_LOADED_MODELS=1`，`OLLAMA_KV_CACHE_TYPE=q4_0` |
| MySQL新增表 | 执行 `docs/ddl_defense_score_record.sql`、`docs/ddl_system_feedback.sql` |
| Redis | 无需改动 |
| 小程序 | 微信开发者工具 → 清除缓存 → 重新编译 |

## 测试残留数据（未自动清理）

| 残留 | 说明 |
|---|---|
| `users` id=24/25 | 一次性测试账号（99900000001/99900000002，密码test123456） |
| `defense_records` **303** | N20的失败现场（评分行1行、答案行0行），故意保留作证据 |
| `defense_records` **304** | N21的完整10轮场次（可作回归样例） |
| Redis `chat:memory:n22_conc_test` | N22并发测试会话 |
| `docs/migration-20260928-score-record-unique.sql` | 仍未执行（代码侧幂等已生效，DB唯一索引是并发兜底） |
