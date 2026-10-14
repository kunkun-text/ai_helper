# AI-helper 项目协作约定（CLAUDE.md）

> 本文件是 AI 与开发者之间的协作契约。任何改动前，先读这里。

---

## 项目速览

| 项目 | AI-helper（AI 答辩辅助系统） |
|---|---|
| 技术栈 | Spring Boot 3.5.11 + Java 17 + Spring AI 1.1.2 + MyBatis + Redis + MySQL |
| AI 模型 | 本地 Ollama `qwen2.5:3b-16k`（`http://localhost:11434`） |
| 前端 | 微信小程序原生（`src/main/resources/static/Ai/`） |
| 数据库 | 11 张表（见 `docs/schema.sql`，含 `system_feedback` 与 `defense_audit_log`） |
| 运行环境 | 荣耀猎人 V700 / i5-10300H / 16GB / RTX 2060 6GB / Win10 22H2 |

---

## 当前进度（2026-10-14，代码基线 commit `2bc6c62` / 2026-10-10）

### 已完成 ✅

| 模块 | 状态 | 关键交付 |
|---|---|---|
| 核心答辩流程 | 稳定 | 10轮答辩（5预设+5追问）、中途退出续答、评分落库 |
| 评分可信度 | ✅ 链路就位（三档已实测） | N45~N54 已落地；10-10 路线①要点逐项核验实测通过（正确/错误/无关三档分开）；**严格五档受 3B 上限**，路线 B 已实测不可用并默认关闭 |
| 追问质量 | 已实现待实测 | N44/N48：追问注入「课题简介+刚才题目+学生回答+题库要点」，关键词/Jaccard 同主题去重，兜底题去随机改按焦点词定制 |
| 教师端功能 | 已上线 | 数据总览、成绩导出CSV、反馈管理 |
| 系统反馈模块 | 已上线 | 学生提交/教师回复（需执行 `docs/ddl_system_feedback.sql`） |
| 语音答辩 | 已开发待实测 | F10 V2（SAPI TTS + whisper.cpp ASR）；组件缺失时**启动后自动下载**（约550MB，开箱即用），待真机验证 |
| 语音录音归档回放 | 已开发待实测 | F11：`voice_responses` 启用；ASR 顺手归档原录音（不再二次上传）；答辩页/学生详情/教师详情三处回放入口 |
| 鉴权/越权 | 已加固 | N19 角色校验、只能改自己资料 |
| 事务/幂等 | ✅ 已闭环 | N4幂等、N21末轮同步；N55 已修复（评分行/答案行/追问题同事务，任一失败整体回滚） |
| 答辩链路鉴权 | ✅ 已加固 | A1：/api/chat、/api/final-evaluate、/api/voice/** 纳入登录保护，身份取登录态 |
| 低功耗加固 | ✅ 已落地 | 异步线程池改 `app.async`（2/4/50）；`diagnose.ps1 -StrictLowPower` 校验低功耗变量**取值** |
| 小程序环境 | ✅ 配置侧就绪 | A2：`config.js` 三环境 dev/lan/prod，prod 强制 HTTPS（等真实域名填 `prodBaseUrl`） |
| 详情页增强 | 已开发待实测 | N8：原生 canvas 五维雷达图（逐轮平均）+ 标准答案对照；学生/教师两端 |
| 题库批量导入 | 已开发待实测 | N10：CSV 模板下载 + 追加/覆盖导入 + 逐行校验与跳过原因 |
| 教师端统计导出 | 已开发待实测 | N9：`/teacher/stats/topics` + 按课题导出 + 课题统计 CSV |
| 场次审计日志 | 已开发待实测 | N15：新表 `defense_audit_log`（**11 张表**）+ START/RESUME/FINISH 埋点 |
| 纯函数回归测试 | ✅ 18/18 通过 | N14：`CsvUtilsTest` / `AiTextUtilsTest` / `ScorePersistenceServiceImplTest` |
| 判分复盘整改 | ✅ 已实测 | P-01~P-09（2026-10-09，defenseId=325 复盘）：五档判分表、N45 复核仲裁、防作弊词表（辱骂/反讽前置零分）、追问主题同义去重、接地校验反例、语音归档回填 question_id；零分档两场 8/8 全对。2026-10-10 五场景二次实测（defenseId=330/331，全新答案）：全对(38/38)、全错无关(0/0)稳定；半对半错、全错相关、夹杂错误仍受 3B 模型"错误识别"能力限制（同场景两场差异达 15.5 分），链路机制本身按设计工作 |
| Gemini 审查修订 | ✅ 编译/单测/语法过；DDL 已执行复验 | 2026-10-10 四项：① `editDefense` 改差量更新（实证 `defense_answers` 三外键全 CASCADE，旧「清空重建」会级联删历史作答）；② 题库覆盖导入加作答引用守卫；③ 7 个判分阈值外置 `app.scoring.*`（默认值不变，行为一致）；④ defense-voice.js 三处改路径式增量 setData。DDL 收尾：评分表去重 530→479、`uk_defense_round` 唯一索引就位、补 `system_feedback`、删冗余 `idx_defense_round`（diagnose 全绿）。chatController 拆分 / ASR 异步化单独立项（用户拍板本轮不做） |
| 判分档位校正（路线① · 要点逐项核验） | ✅ 已实测 | 2026-10-10 晚：路线 B（独立错误抽取 A~E）实测 3B 退化恒返回 D（332 场把全对判 D → 38→7.1 误杀），已默认关闭 `app.scoring.error-verdict-enabled: false`（代码保留待换更大模型）；改落地路线①——每题生成 3~5 条要点清单（内存缓存）+ 逐条命中判定 + 服务端按命中率聚合，阈值 `checklist-error-hit-rate-max=0.70`。defenseId=334/335 实测 **38 / 15.2 / 14.9 / 14.9 / 0**，正确/错误/无关三档清晰分开，单轮 ≤13.4s；严格五档仍受 3B 上限 |
| 工程收口批次1（N15/N35/N58/N59） | ✅ 编译/lint 通过 | 2026-10-14：教师端场次审计日志入口；内联 HTML 全部外置为 `templates/*.html`；3 个 Mapper XML 重命名为对应接口名；删除重复的 `POST /api/reset-password` 与 `PasswordResetDto` |
| 语音体验批次2（N12） | ✅ 编译/lint 通过 | 2026-10-14：录音上限后端下发 + 上限提示；上滑取消（真正丢弃）；识别失败一键重试 + 清空重录；发送按钮录音/识别中禁用；学生/教师端回放补齐音频实例回收 |

### 待开发 🔧

| 优先级 | 需求 | 说明 |
|---|---|---|
| **P0** | A2 小程序生产域名 | 配置侧已完成；**只差真实已备案 HTTPS 域名**（填 `prodBaseUrl`、切 `env:'prod'`、关 `urlCheck:false`） |
| **P0** | F11 语音归档回放 | 🔧 2026-10-08 已开发（归档+三处回放入口），待真机实测 |
| **P0** | V0 前端真机验证 | 9-16/9-17 起的所有改动仅开发者工具验证 |
| **P0** | N42 [跑题] 误判 | 2026-10-08 已收口唯一缺口（0分不带标记轮次纳入复核），待多场样本复验 |
| **P2** | N7 评分稳定性 | temperature=0 已全覆盖；判分规则已表化并落地「路线①要点核验」（正确/错误/无关三档达成）；遗留"数字级小错"3B 无法稳定识别 |
| **P3** | N16 Git 协作 | 需你决定（fork 到自账号或加协作者） |
| **P3** | N57 前端请求封装未接入 | `utils/request.js`（C2 交付物：401/403 统一处理 + 上传/下载封装）零引用，页面仍直接 `wx.request`；或删或迁移，**暂挂账待拍板** |

### 待你拍板 🎯

1. **判分路线①阈值是否继续调？** 现为 `checklist-error-hit-rate-max=0.70`（卡在实测 0.667 与 0.75 之间）。继续调只影响「半对半错 / 夹杂错误」是否并入错误档，五档拉不开仍是 3B 上限——建议先跑多场真机样本再定
2. **「严格五档」是否接受在 3B 红线内不达成？** 路线 B（独立错误抽取）已实测不可用并默认关闭；若要五档清晰，唯一路径是换更大模型（触及硬件红线），需你拍板
3. **F10 何时真机实测？** 无需插件（已改后端 TTS/ASR）；whisper.cpp 引擎/模型/ffmpeg 会**启动后自动下载**，装好即可真机验证
4. **A2 生产域名何时切换？** 配置侧已就绪，只差已备案 HTTPS 域名（填 `prodBaseUrl`、`env` 切 `prod`、关 `urlCheck:false`）

> **已拍板归档**：N42 收口（10-08）、N43 命名方案1（10-05）、N55 一次性合并（10-05）、资源加固三（10-08）、判分路线选型（10-10 选定路线①要点逐项核验）。

---

## 项目结构

```
ai/                                    # 项目根
├── src/main/java/com/ai_helper/ai_helper/
│   ├── AiHelperApplication.java       # 启动类
│   ├── Config/
│   │   ├── WebConfig.java             # 拦截器注册 + 静态资源映射
│   │   ├── GlobalExceptionHandler.java # 全局异常统一处理（新增）
│   │   └── ChatConfiguration.java     # Spring AI ChatClient + Redis记忆
│   ├── Controller/
│   │   ├── chatController.java        # AI 对话主流程（核心）
│   │   ├── loginController.java       # 登录/注册/改密/改资料
│   │   ├── student/                   # 学生端接口
│   │   └── teacher/                   # 教师端接口
│   ├── Service/ & Service/Impl/       # 业务层
│   ├── Mapper/                        # MyBatis Mapper
│   ├── pojo/                          # 实体/VO/枚举
│   ├── util/                          # 工具类（TextTools / AiTextUtils / CsvUtils / UploadUtils / LoginTokenUtils）
│   ├── constant/                      # 常量（AiProtocolConstants / VoiceConstants 等）
│   ├── exception/                      # 业务异常（BusinessException）
│   ├── result/                        # 统一响应（Result / ResultCode / PageResult）
│   └── interceptor/                   # 鉴权拦截器（AuthInterceptor / RequireRole）
├── src/main/resources/
│   ├── application.yml                # 主配置（gitignore，不提交）
│   ├── application.yml.example        # 配置模板（git跟踪）
│   ├── mapper/*.xml                   # MyBatis XML
│   └── static/Ai/                     # 微信小程序前端代码
│       ├── app.js / app.json / app.wxss
│       ├── pages/
│       │   ├── login/                 # 登录
│       │   ├── register/              # 注册
│       │   ├── forgetPassword/        # 忘记密码
│       │   ├── student/               # 学生主页
│       │   ├── defense/               # 文字答辩
│       │   ├── defense-voice/         # 语音答辩（新增）
│       │   ├── teacher/               # 教师主页
│       │   ├── feedback/              # 学生反馈（新增）
│       │   ├── feedback-admin/        # 教师反馈管理（新增）
│       │   └── stats/                 # 数据总览（新增）
│       └── utils/                     # 前端工具（uploader.js 等）
├── docs/
│   ├── schema.sql                     # 数据库表结构（11张表，含 system_feedback + defense_audit_log + uk_defense_round）
│   ├── ddl_defense_score_record.sql   # 评分表建表
│   ├── ddl_system_feedback.sql        # 反馈表建表（需手动执行）
│   ├── migration-*.sql                # 数据迁移脚本
│   ├── 从零部署手册-8G低功耗标准.md     # 部署文档
│   └── 答辩系统-项目情况说明.md         # 业务说明
├── diagnose.ps1                       # 答辩前自检脚本（11张表/索引/环境/推理抽测，根目录）
├── scripts/
│   └── install-whisper.ps1            # whisper.cpp 一键安装
├── CHANGES.md                         # 改动记录（重构版）
├── 需求清单-2026-09-15.md              # 需求清单（重构版）
├── AGENTS.md                          # AI协作入口
├── Modelfile-qwen25                   # Ollama模型定义
└── pom.xml                            # Maven依赖
```

---

## 操作红线（违反即回滚）

1. **不提交** `application.yml` / `*.log` / `hs_err_pid*` / `~$*.xlsx`
2. **不强推远程**，**不改写 Git 历史**
3. **硬件红线**：只用 `qwen2.5:3b-16k`，不换更大模型
4. **AI协议标签**：统一用 `AiProtocolConstants` 常量，不硬编码

---

## 编码规范

- 中文注释，代码与注释保持同步
- 新增依赖先确认必要性，优先用现有技术栈
- 前端改动后 `node --check` 验证 JS 语法
- 后端改动后 `mvn -o -q compile` 验证编译
- 数据库改动提供 `docs/migration-*.sql`

---

## 必读文档优先级

| 优先级 | 文档 | 什么时候读 |
|---|---|---|
| 1 | `docs/从零部署手册-8G低功耗标准.md` | 换新机器/从零部署/环境报错/推理变慢 |
| 2 | `CHANGES.md` | 想了解某次改动的上下文 |
| 3 | `需求清单-2026-09-15.md` | 开工前确认当前需求状态 |
| 4 | `docs/schema.sql` | 改数据库结构前 |
| 5 | `docs/答辩系统-项目情况说明.md` | 理解业务流程与AI输出格式 |

---

> 本文件由原版 `CLAUDE.md` 更新而来，截至 2026-10-14（代码基线 commit `2bc6c62` / 2026-10-10）。
