# AI-helper 项目协作约定（CLAUDE.md）

> 本文件是 AI 与开发者之间的协作契约。任何改动前，先读这里。

---

## 项目速览

| 项目 | AI-helper（AI 答辩辅助系统） |
|---|---|
| 技术栈 | Spring Boot 3.5.11 + Java 17 + Spring AI 1.1.2 + MyBatis + Redis + MySQL |
| AI 模型 | 本地 Ollama `qwen2.5:3b-16k`（`http://localhost:11434`） |
| 前端 | 微信小程序原生（`src/main/resources/static/Ai/`） |
| 数据库 | 10 张表（见 `docs/schema.sql`，含 `system_feedback`） |
| 运行环境 | 荣耀猎人 V700 / i5-10300H / 16GB / RTX 2060 6GB / Win10 22H2 |

---

## 当前进度（2026-10-05）

### 已完成 ✅

| 模块 | 状态 | 关键交付 |
|---|---|---|
| 核心答辩流程 | 稳定 | 10轮答辩（5预设+5追问）、中途退出续答、评分落库 |
| 评分可信度 | 已实现待复验 | N45~N54 八项修复已落地，需多场答辩累积样本验证 |
| 教师端功能 | 已上线 | 数据总览、成绩导出CSV、反馈管理 |
| 系统反馈模块 | 已上线 | 学生提交/教师回复（需执行 `docs/ddl_system_feedback.sql`） |
| 语音答辩 | 已开发待实测 | F10 V2（SAPI TTS + whisper.cpp ASR）；组件缺失时**启动后自动下载**（约550MB，开箱即用），待真机验证 |
| 鉴权/越权 | 已加固 | N19 角色校验、只能改自己资料 |
| 事务/幂等 | ✅ 已闭环 | N4幂等、N21末轮同步；N55 已修复（评分行/答案行/追问题同事务，任一失败整体回滚） |
| 答辩链路鉴权 | ✅ 已加固 | A1：/api/chat、/api/final-evaluate、/api/voice/** 纳入登录保护，身份取登录态 |

### 待开发 🔧

| 优先级 | 需求 | 说明 |
|---|---|---|
| **P0** | A2 小程序生产域名 | 需真实 HTTPS 合法域名后切换 `env:'prod'`（改动已列清单，等域名） |
| **P0** | F11 语音归档回放 | F10跑通后开工 |
| **P0** | N3 教师页fixed改造 | 4处fixed未改 |
| **P0** | V0 前端真机验证 | 9-16/9-17改动仅开发者工具验证 |
| **P1** | N43 上传命名规范化 | 需拍板命名方案 |
| **P1** | N7 评分稳定性 | temperature=0，判分规则表化 |
| **P2** | N8/N9 详情页+导出 | 五维雷达图、标准答案对照 |
| **P2** | N10 题库批量导入 | Excel/CSV模板 |
| **P3** | N30~N38 工程收尾 | NPE/精度/日志/测试 |
| **P3** | N56 `/editUserInfo` 动态SQL | 全量UPDATE改`<set>` |

### 待你拍板 🎯

1. **N42怎么改？** 建议 A + C（改prompt加门槛 + 长度闸门）
2. **N43命名方案？** 推荐方案1：`答辩报告_学号_原名_短随机.ext`
3. **N55是否一次性合并改动？**（省多轮重启实测）
4. **资源加固（三）是否仍挂账？** 建议至少做JVM `-Xmx512m`
5. **F10何时真机实测？** 无需插件（已改后端 TTS/ASR）；whisper.cpp 引擎/模型/ffmpeg 会**启动后自动下载**，装好即可真机验证

---

## 项目结构

```
ai/                                    # 项目根
├── src/main/java/com/aihelper/
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
│   ├── util/                          # 工具类
│   ├── constant/                      # 常量（AiProtocolConstants / VoiceConstants 等）
│   ├── result/                        # 统一响应（Result/ResultCode）
│   └── interceptor/                   # 鉴权拦截器
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
│   ├── schema.sql                     # 数据库表结构（10张表，含 system_feedback + uk_defense_round）
│   ├── ddl_defense_score_record.sql   # 评分表建表
│   ├── ddl_system_feedback.sql        # 反馈表建表（需手动执行）
│   ├── migration-*.sql                # 数据迁移脚本
│   ├── 从零部署手册-8G低功耗标准.md     # 部署文档
│   └── 答辩系统-项目情况说明.md         # 业务说明
├── diagnose.ps1                       # 答辩前自检脚本（10张表/索引/环境/推理抽测，根目录）
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

> 历史版本：本文件由原版 `CLAUDE.md` 更新而来，截至 2026-10-04。
