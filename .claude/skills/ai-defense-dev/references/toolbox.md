# 工具箱 —— 本项目常用命令与模板

> Windows + **PowerShell 5.1** 环境；工作区根 `f:\杂七杂八\ai`（路径含中文，命令里一律带引号）。
> PowerShell 没有 `&&` / `||`（5.1 会直接解析报错）；串联用 `;`，条件执行用 `if ($?) { ... }`。不要拼 Linux 专有管道（`head`/`tail`/`grep`），用 `Select-Object -First/-Last`、`Grep` 工具。
> 判断成败要看 `$LASTEXITCODE` / `$?`，不能只看 stdout（编译成功时通常无输出）。

## 1. 后端编译（PATH 无 mvn，必须完整路径）

```powershell
# 工作目录已固定在项目根，无需 cd
& "D:\maven\apache-maven-3.8.1\bin\mvn.cmd" -o -q clean compile
if ($LASTEXITCODE -ne 0) { "COMPILE_FAILED" } else { "COMPILE_OK" }
```

- `-o` 离线（依赖已在本机仓库）；`-q` 静默，**以退出码 0 为准**（成功时 stdout 为空是正常的）。
- **必须带 `clean`**：2026-10-16 实测踩坑——`ExportController` 里 `Double` 传 `BigDecimal` 是编译错误，不带 `clean` 的增量编译却报 `EXIT 0`（IDE 预编译产物干扰 mtime），`clean compile` 才暴露。跑测试同理 `-o clean test`。
- 启动后端：`& "D:\maven\apache-maven-3.8.1\bin\mvn.cmd" spring-boot:run`（端口 8080）。
- 换机器：maven/JDK 路径不同（部署手册装的机器是 `D:\tools\maven\apache-maven-3.9.9\bin\mvn.cmd` + `D:\tools\jdk17`），先问用户或看 CLAUDE.md。
- 报"找不到符号 XXX"但文件明明存在 → 先删 `target` 全量重编（历史教训：内存不足时 mvn 被杀留下残缺 class）。

## 2. 前端 JS 语法校验

```powershell
Set-Location "f:\杂七杂八\ai\src\main\resources\static\Ai"
node --check pages/xxx/xxx.js
```

多个文件用 `;` 串联（PowerShell 5.1 不支持 `&&`），逐个检查 `$LASTEXITCODE`；也可用循环：

```powershell
$files = @("pages/teacher/teacher.js","pages/student/student.js","utils/gradeSheet.js")
foreach ($f in $files) { node --check $f; if ($LASTEXITCODE -ne 0) { "JS_FAIL: $f" } }
```

## 3. 运行状态自检（答辩前 / 排障第一步）

```powershell
powershell -ExecutionPolicy Bypass -File diagnose.ps1
```
覆盖：物理内存、MySQL 连通+**12 张必需表**齐全（含 `defense_rubric`）、Redis（带密码 PING）、Ollama 服务/模型/显存、GPU+端口、环境变量、推理速度。默认值自动读本机 `application.yml`。

## 4. 直查 MySQL（root/123456 @3306/ai_helper）

```bat
mysql -uroot -p123456 -e "SELECT defense_id, user_id, topic_id, status, score FROM ai_helper.defense_records ORDER BY defense_id DESC LIMIT 5;"
```
常用表：`users`（user_number 有唯一索引）、`defense_records`（status: pending/completed/graded）、`defense_score_record`（五维评分行，幂等唯一索引 `uk_defense_round`，存量库按 diagnose.ps1 提示补迁移脚本）、`defense_answers`、`defense_topics`、`defense_questions`、`system_feedback`、`voice_responses`、`defense_audit_log`。
完整 12 张表结构见 `docs/schema.sql`。

## 5. curl 接口测试套路（cmd 变量法）

```powershell
# 用 curl.exe（PowerShell 的 curl 是 Invoke-WebRequest 别名，参数不兼容）
$body = '{"userNumber":"学号","password":"密码"}'
$login = curl.exe -s -X POST http://localhost:8080/login/student -H "Content-Type: application/json" -d $body
$login
# 按实际返回结构取 token（先打印一次确认字段名）
$TOKEN = ($login | ConvertFrom-Json).data.token

# 受保护接口（-i 看状态码：401=未登录/旧token，403=角色不符）
curl.exe -i http://localhost:8080/student/DefenseRecords -H "Authorization: Bearer $TOKEN"

# 教师端越权测试（学生 token 应 403）
curl.exe -i http://localhost:8080/teacher/stats/overview -H "Authorization: Bearer $TOKEN"

# AI 对话链路（需 Ollama 在跑）：POST /api/chat/clear 建场 → 连续 POST /api/chat
```

> 含中文的 JSON 建议用 `[System.Text.Encoding]::UTF8.GetBytes($body)` 写入临时文件再 `-d "@file.json"`，避免控制台代码页导致乱码。

鉴权模型速记：token 值 = `userNumber|role`（Redis `login:token:*`）；`/student/**`、`/teacher/**`、`/api/video|report/**`、`/editUserInfo` 被拦截；角色由 `@RequireRole` 注解裁决。

## 6. CHANGES.md 条目格式模板

```markdown
# YYYY-MM-DD 改动（一句话主题）

> 改动日期：X.X.X，涉及 N 个文件。**状态：编译已通过 / 待实测 / 已实测**。

## 一、问题 / 需求背景（实锤证据：场次ID、日志行、截图描述）

## 二、改动内容（表：位置 | 改动 | 说明）

## 三、验证结果（编译/语法/实测各项）

## 四、遗留与待实测清单
```

同步义务：严重改动 → CHANGES.md 详细条目 + `CLAUDE.md`「当前进度」表状态行；新阶段任务 → `新阶段路线图-2026-10-15.md` 的「条目状态 + §2 总表 + §9 更新日志」三处；需求变更 → `需求清单-2026-09-15.md` 挂账编号（N/F 系列）。

## 7. Git 约定

- 提交信息：**中文一行标题**（让人一眼知道干了啥）+ `-m` 分条正文（功能/工程/修复分组）。
- push 前必查：`git status --porcelain` 无 `application.yml`、无 `*.log`、无 `hs_err_pid*`；文档（CHANGES.md / CLAUDE.md）与代码同步。
- 远程 `github.com/kunkun-text/ai_helper`（master）；禁止 force push / 改写历史。commit 与 push 都必须用户明确要求。
