<#
  AI 答辩辅助系统 · 答辩前自检（preflight）
  ============================================================================
  用法（在项目根目录执行）：
      powershell -ExecutionPolicy Bypass -File .\diagnose.ps1

      换机器 / 换端口时用参数覆盖（默认值与本机 application.yml 一致，未写死）：
      powershell -ExecutionPolicy Bypass -File .\diagnose.ps1 -MysqlPort 3307 -RedisPassword "your-pass"
      powershell -ExecutionPolicy Bypass -File .\diagnose.ps1 -SkipInferenceTest

  检查项（逐条 ✅ / ❌ + 修复提示）：
      1. 可用物理内存
      2. MySQL 连通 + ai_helper 9 张表齐全
      3. Redis 连通（带密码）
      4. Ollama 服务 + 目标模型已 pull + 是否已加载进显存
      5. 后端端口占用
      6. 8G 低功耗标准的关键环境变量
      7. 推理速度抽测（默认执行，可用 -SkipInferenceTest 跳过）

  退出码：0 = 无失败项；1 = 存在 ❌（答辩前必须清零）
#>
param(
    [string]$MysqlHost       = "127.0.0.1",
    [int]   $MysqlPort       = 3306,
    [string]$MysqlUser       = "root",
    [string]$MysqlPassword   = "123456",
    [string]$MysqlDatabase   = "ai_helper",
    [string]$MysqlExe        = "",                 # 留空 = 自动查找

    [string]$RedisHost       = "127.0.0.1",
    [int]   $RedisPort       = 6379,
    [string]$RedisPassword   = "123456",
    [string]$RedisCli        = "",                 # 留空 = 自动查找

    [string]$OllamaUrl       = "http://127.0.0.1:11434",
    [string]$RequiredModel   = "qwen2.5:3b-16k",

    [int]   $BackendPort     = 8080,
    [double]$MinFreeMemoryGB = 1.0,                # 可用内存低于该值给警告

    [switch]$SkipInferenceTest
)

$ErrorActionPreference = "Continue"
$script:FailedCount = 0

# ---------------------------------------------------------------------------
# 从本机 application.yml（或 .example）读取实际配置作为默认值：
# 换机器 / 改端口后无需改脚本；用户显式传参时仍以参数为准。
# ---------------------------------------------------------------------------
function Read-YmlDefaults {
    param([string]$ProjectRoot)
    $result = @{}
    $candidates = @(
        (Join-Path $ProjectRoot "src\main\resources\application.yml"),
        (Join-Path $ProjectRoot "src\main\resources\application.yml.example")
    )
    foreach ($path in $candidates) {
        if (-not (Test-Path $path)) { continue }
        $text = Get-Content -Raw -Encoding UTF8 $path
        if ($text -match 'jdbc:mysql://([^:/]+):(\d+)/([^?\s]+)') {
            $result.MysqlHost = $Matches[1]
            $result.MysqlPort = [int]$Matches[2]
            $result.MysqlDatabase = $Matches[3]
        }
        # datasource 段内的 username / password（限定在该段内，避免误取 redis / mail 的密码）
        $ds = [regex]::Match($text, '(?s)datasource:\s*\n(.*?)(\n  \S|\z)')
        if ($ds.Success) {
            $block = $ds.Groups[1].Value
            if ($block -match 'username:\s*(\S+)') { $result.MysqlUser = $Matches[1] }
            if ($block -match 'password:\s*(\S+)') { $result.MysqlPassword = $Matches[1] }
        }
        # Ollama 模型名（chat.options.model）
        if ($text -match '(?m)^\s*model:\s*(\S+)') { $result.RequiredModel = $Matches[1] }
        $result.Source = $path
        break
    }
    return $result
}

$yml = Read-YmlDefaults -ProjectRoot $PSScriptRoot
if ($yml.Count -gt 0) {
    Write-Host "  配置来源: $($yml.Source)" -ForegroundColor DarkGray
    if ($yml.MysqlHost     -and -not $PSBoundParameters.ContainsKey('MysqlHost'))     { $MysqlHost = $yml.MysqlHost }
    if ($yml.MysqlPort     -and -not $PSBoundParameters.ContainsKey('MysqlPort'))     { $MysqlPort = $yml.MysqlPort }
    if ($yml.MysqlDatabase -and -not $PSBoundParameters.ContainsKey('MysqlDatabase')) { $MysqlDatabase = $yml.MysqlDatabase }
    if ($yml.MysqlUser     -and -not $PSBoundParameters.ContainsKey('MysqlUser'))     { $MysqlUser = $yml.MysqlUser }
    if ($yml.MysqlPassword -and -not $PSBoundParameters.ContainsKey('MysqlPassword')) { $MysqlPassword = $yml.MysqlPassword }
    if ($yml.RequiredModel -and -not $PSBoundParameters.ContainsKey('RequiredModel')) { $RequiredModel = $yml.RequiredModel }
} else {
    Write-Host "  配置来源: 脚本默认参数（未找到 application.yml，可用 -MysqlPort 等覆盖）" -ForegroundColor DarkGray
}

function Write-Check {
    param(
        [string]$Name,
        [bool]  $Ok,
        [string]$Detail = "",
        [string]$Fix = "",
        [switch]$WarnOnly          # 只提示不判失败（例如环境变量缺失但当前会话仍可用）
    )
    if ($Ok) {
        Write-Host ("  [OK] " + $Name) -ForegroundColor Green
    } else {
        if ($WarnOnly) {
            Write-Host ("  [!!] " + $Name) -ForegroundColor Yellow
        } else {
            Write-Host ("  [X ] " + $Name) -ForegroundColor Red
            $script:FailedCount++
        }
    }
    if ($Detail) { Write-Host ("       " + $Detail) -ForegroundColor Gray }
    if ($Fix -and -not $Ok) { Write-Host ("       修复: " + $Fix) -ForegroundColor Yellow }
}

function Test-PortOpen {
    param([string]$TargetHost, [int]$Port)
    # 判断本机是否有进程在监听该端口（本项目所有组件都部署在本机）
    return [bool](Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
}

function Find-Exe {
    param([string]$Explicit, [string]$ExeName, [string[]]$SearchRoots, [int]$ListeningPort = 0)
    if ($Explicit -and (Test-Path $Explicit)) { return $Explicit }
    $cmd = Get-Command $ExeName -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    # 优先从「正在监听该端口的进程」所在目录找同名工具 —— 换机器也不写死安装路径
    if ($ListeningPort -gt 0) {
        $conn = Get-NetTCPConnection -LocalPort $ListeningPort -State Listen -ErrorAction SilentlyContinue |
            Select-Object -First 1
        if ($conn) {
            $proc = Get-Process -Id $conn.OwningProcess -ErrorAction SilentlyContinue
            if ($proc -and $proc.Path) {
                $candidate = Join-Path (Split-Path $proc.Path -Parent) $ExeName
                if (Test-Path $candidate) { return $candidate }
            }
        }
    }
    foreach ($root in $SearchRoots) {
        if (Test-Path $root) {
            $hit = Get-ChildItem $root -Filter $ExeName -Recurse -ErrorAction SilentlyContinue |
                Select-Object -First 1
            if ($hit) { return $hit.FullName }
        }
    }
    return $null
}

# 读环境变量：User 级优先，其次 Machine（系统）级 —— 实测变量常被设在系统级
function Get-EnvValue {
    param([string]$Name)
    $v = [Environment]::GetEnvironmentVariable($Name, "User")
    if (-not $v) { $v = [Environment]::GetEnvironmentVariable($Name, "Machine") }
    return $v
}

function Test-Url {
    param([string]$Url, [int]$TimeoutSec = 5)
    try {
        Invoke-RestMethod -Uri $Url -TimeoutSec $TimeoutSec -ErrorAction Stop | Out-Null
        return $true
    } catch {
        return $false
    }
}

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  AI 答辩系统 · 答辩前自检" -ForegroundColor Cyan
Write-Host "  $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

# ---------------------------------------------------------------- 1. 物理内存
Write-Host "`n[1] 可用物理内存" -ForegroundColor Yellow
$os = Get-CimInstance Win32_OperatingSystem
$freeGB = [math]::Round($os.FreePhysicalMemory / 1MB, 2)
$totalGB = [math]::Round($os.TotalVisibleMemorySize / 1MB, 2)
Write-Check -Name "可用内存 $freeGB GB / 共 $totalGB GB" `
    -Ok ($freeGB -ge $MinFreeMemoryGB) `
    -Fix "关掉 IDEA / 浏览器 / 多余的 llama-server，再重试（部署手册 Q5）"

# ------------------------------------------------------------------- 2. MySQL
Write-Host "`n[2] MySQL（$MysqlHost`:$MysqlPort / 库 $MysqlDatabase）" -ForegroundColor Yellow
$mysqlOk = Test-PortOpen -TargetHost $MysqlHost -Port $MysqlPort
Write-Check -Name "端口 $MysqlPort 监听" -Ok $mysqlOk `
    -Fix "启动 MySQL：按部署手册第五节，或运行一键启动脚本 start-ai-helper.ps1"

$mysqlExePath = Find-Exe -Explicit $MysqlExe -ExeName "mysql.exe" -ListeningPort $MysqlPort `
    -SearchRoots @("D:\mysql", "D:\tools\mysql8", "C:\tools\mysql8", "C:\Program Files\MySQL")
if ($mysqlOk -and $mysqlExePath) {
    $expected = @("defense_answers","defense_questions","defense_records","defense_score_record",
                  "defense_student_questions","defense_topics","system_settings","users","voice_responses")
    # 用数组展开传参：PowerShell 5.1 对「-p$变量」这类拼接 token 传给原生命令时处理不一致，
    # 实测会把密码传错（Access denied），@args 展开可稳定传递。
    $mysqlArgs = @("-u", $MysqlUser, "--password=$MysqlPassword", "-h", $MysqlHost,
                   "-P", "$MysqlPort", "-N", "-B", "-e", "SHOW TABLES FROM $MysqlDatabase;")
    $raw = & $mysqlExePath @mysqlArgs 2>&1
    $tables = @($raw | Where-Object { $_ -and $_ -notmatch "Warning|ERROR" })
    if ($LASTEXITCODE -eq 0 -and $tables.Count -gt 0) {
        $missing = @($expected | Where-Object { $tables -notcontains $_ })
        Write-Check -Name "表结构检查（必需 9 张，库中共 $($tables.Count) 张）" -Ok ($missing.Count -eq 0) `
            -Detail $(if ($missing.Count -gt 0) { "缺少: " + ($missing -join ", ") } else { "9 张必需表齐全" }) `
            -Fix "导入表结构：mysql -uroot -p < docs/schema.sql（注意会先删表，仅空库可用）"

        $scoreArgs = @("-u", $MysqlUser, "--password=$MysqlPassword", "-h", $MysqlHost,
                       "-P", "$MysqlPort", "-N", "-B", "-e",
                       "SELECT COUNT(*) FROM $MysqlDatabase.defense_score_record;")
        $scoreRaw = & $mysqlExePath @scoreArgs 2>&1
        # 只取纯数字行，滤掉 mysql 客户端的密码 warning
        $scoreCount = @($scoreRaw | Where-Object { "$_" -match '^\s*\d+\s*$' } | Select-Object -First 1) -join ""
        if (-not $scoreCount) { $scoreCount = "?" }
        if ($LASTEXITCODE -eq 0) {
            Write-Check -Name "评分表可读（defense_score_record，现有 $scoreCount 行）" -Ok $true
        } else {
            Write-Check -Name "评分表可读" -Ok $false `
                -Fix "执行迁移脚本 docs/migration-20260928-score-record-unique.sql（含表结构核验）"
        }
    } else {
        Write-Check -Name "连接并查询 $MysqlDatabase" -Ok $false `
            -Detail ($raw -join " / ") `
            -Fix "核对账号密码与库名；应用启动时也会连它"
    }
} elseif ($mysqlOk) {
    Write-Check -Name "mysql.exe 未找到，跳过表结构检查" -Ok $false -WarnOnly `
        -Fix "用 -MysqlExe 指定 mysql.exe 路径，或把 MySQL bin 目录加入 PATH"
}

# ------------------------------------------------------------------- 3. Redis
Write-Host "`n[3] Redis（$RedisHost`:$RedisPort）" -ForegroundColor Yellow
$redisOk = Test-PortOpen -TargetHost $RedisHost -Port $RedisPort
Write-Check -Name "端口 $RedisPort 监听" -Ok $redisOk `
    -Fix "启动 Redis（必须用带 requirepass 的配置文件，见 CLAUDE.md 第四节）"

$redisCliPath = Find-Exe -Explicit $RedisCli -ExeName "redis-cli.exe" -ListeningPort $RedisPort `
    -SearchRoots @("F:\杂七杂八\Redis2", "D:\tools\redis", "C:\tools\redis", "C:\Program Files\Redis")
if ($redisOk -and $redisCliPath) {
    $redisArgs = @("-h", $RedisHost, "-p", "$RedisPort", "-a", $RedisPassword, "--no-auth-warning", "ping")
    $pong = & $redisCliPath @redisArgs 2>&1
    $redisAuth = ($pong -join "") -match "PONG"
    Write-Check -Name "带密码 PING 返回 PONG" -Ok $redisAuth `
        -Detail ($pong -join " / ") `
        -Fix "密码不匹配：确认 redis 配置里的 requirepass 与 -RedisPassword 一致（application.yml 里也有同一密码）"
} elseif ($redisOk) {
    Write-Check -Name "redis-cli 未找到，跳过密码校验" -Ok $false -WarnOnly `
        -Fix "用 -RedisCli 指定 redis-cli.exe 路径"
}

# ------------------------------------------------------------------ 4. Ollama
Write-Host "`n[4] Ollama（$OllamaUrl）" -ForegroundColor Yellow
$ollamaProc = Get-Process ollama*, llama-server* -ErrorAction SilentlyContinue
$ollamaUp = $false
try {
    $tags = Invoke-RestMethod -Uri "$OllamaUrl/api/tags" -TimeoutSec 8 -ErrorAction Stop
    $ollamaUp = $true
    $modelNames = @($tags.models | ForEach-Object { $_.name })
    $hasModel = [bool]($modelNames | Where-Object { $_ -eq $RequiredModel -or $_ -like "$RequiredModel*" })
    Write-Check -Name "服务在跑（/api/tags 可访问，进程数 $(@($ollamaProc).Count)）" -Ok $ollamaUp
    Write-Check -Name "模型 $RequiredModel 已 pull" -Ok $hasModel `
        -Detail ("本机模型: " + ($modelNames -join ", ")) `
        -Fix "ollama pull qwen2.5:3b，再 ollama create qwen2.5:3b-16k -f .\Modelfile-qwen25"
} catch {
    Write-Check -Name "服务在跑（/api/tags 可访问）" -Ok $false `
        -Fix "启动 Ollama：ollama serve（低功耗环境变量见部署手册 7.2 节）"
}

if ($ollamaUp) {
    try {
        $ps = Invoke-RestMethod -Uri "$OllamaUrl/api/ps" -TimeoutSec 8 -ErrorAction Stop
        $loaded = @($ps.models | ForEach-Object { $_.name })
        if ($loaded.Count -gt 0) {
            Write-Check -Name "模型已加载进显存/内存（首轮不会卡在冷启动）" -Ok $true -Detail ($loaded -join ", ")
        } else {
            Write-Check -Name "模型尚未加载（答辩第 1 轮会多等几秒冷启动）" -Ok $false -WarnOnly `
                -Fix "可先随便问一句预热，或忽略（首轮 2~5 秒内可接受）"
        }
    } catch {
        Write-Check -Name "查询已加载模型（/api/ps）" -Ok $false -WarnOnly
    }
    $loadErr = @(Get-Process ollama*, llama-server* -ErrorAction SilentlyContinue |
        Where-Object { $_.WorkingSet64 -gt 3GB })
    if ($loadErr.Count -gt 0) {
        Write-Check -Name "存在占用超过 3GB 的模型进程（可能有多实例残留）" -Ok $false -WarnOnly `
            -Fix "Get-Process ollama, llama-server | Stop-Process -Force 后重新拉起（部署手册第十四节）"
    }
}

# ------------------------------------------------------- 5. GPU / 后端端口
Write-Host "`n[5] GPU 与后端端口" -ForegroundColor Yellow
$nvidia = & nvidia-smi --query-gpu=name,memory.total,memory.used,memory.free --format=csv,noheader 2>&1
if ($LASTEXITCODE -eq 0) {
    Write-Check -Name "GPU 可用" -Ok $true -Detail ($nvidia -join " | ")
} else {
    Write-Check -Name "nvidia-smi 不可用（无独显机器可忽略）" -Ok $false -WarnOnly
}

$backend = Get-NetTCPConnection -LocalPort $BackendPort -State Listen -ErrorAction SilentlyContinue |
    Select-Object -First 1
if ($backend) {
    $owner = Get-Process -Id $backend.OwningProcess -ErrorAction SilentlyContinue
    Write-Check -Name "后端 $BackendPort 已监听（PID $($backend.OwningProcess) $($owner.ProcessName)）" -Ok $true
} else {
    Write-Check -Name "后端 $BackendPort 未启动" -Ok $false `
        -Fix "mvn spring-boot:run -Dspring-boot.run.jvmArguments=-Xmx512m（部署手册第九节）"
}

# ------------------------------------------------------------ 6. 环境变量
Write-Host "`n[6] Ollama 环境变量（用户级；缺失只提示、不阻塞，按机型对照）" -ForegroundColor Yellow
$lowPowerVars = [ordered]@{
    "OLLAMA_KV_CACHE_TYPE"     = "KV 缓存量化，内存需求减半（8G 机型必需，部署手册 7.2）"
    "OLLAMA_NUM_PARALLEL"      = "并发压到 1，避免多份 KV 缓存（8G 机型必需）"
    "OLLAMA_MAX_LOADED_MODELS" = "同时只保留 1 个模型（8G 机型必需）"
    "OLLAMA_NUM_THREADS"       = "CPU 线程数（8G 机型建议 4）"
    "OLLAMA_MODELS"            = "模型存放目录（决定模型放哪个盘）"
}
$devVars = [ordered]@{
    "OLLAMA_KEEP_ALIVE"      = "模型常驻，避免重复冷启动（独显机器常用）"
    "OLLAMA_NUM_CTX"         = "上下文长度（独显机器常用）"
    "OLLAMA_NUM_GPU"         = "GPU 层数（独显机器常用）"
    "OLLAMA_FLASH_ATTENTION" = "FlashAttention 加速（独显机器常用）"
}
foreach ($group in @(
        @{ Title = "8G 低功耗标准";   Items = $lowPowerVars },
        @{ Title = "本机（独显）常用"; Items = $devVars })) {
    Write-Host "  -- $($group.Title) --" -ForegroundColor Gray
    foreach ($name in $group.Items.Keys) {
        $val = Get-EnvValue -Name $name
        $ok = [bool]$val
        Write-Host $(if ($ok) { "  [OK] " } else { "  [!!] " }) -NoNewline
        Write-Host "$name = $(if ($val) { $val } else { '(未设置)' })" `
            -ForegroundColor $(if ($ok) { "Green" } else { "Yellow" })
        if (-not $ok) { Write-Host "       作用: $($group.Items[$name])" -ForegroundColor Gray }
    }
}

# --------------------------------------------------------- 7. 推理速度抽测
if (-not $SkipInferenceTest) {
    Write-Host "`n[7] 推理速度抽测（模型 $RequiredModel）" -ForegroundColor Yellow
    if ($ollamaUp) {
        $body = @{
            model   = $RequiredModel
            prompt  = "用中文回答：什么是Java？10字以内"
            stream  = $false
            options = @{ num_predict = 24 }
        } | ConvertTo-Json -Depth 4
        $sw = [Diagnostics.Stopwatch]::StartNew()
        try {
            $resp = Invoke-RestMethod -Uri "$OllamaUrl/api/generate" -Method Post `
                -Body ([System.Text.Encoding]::UTF8.GetBytes($body)) `
                -ContentType "application/json" -TimeoutSec 300 -ErrorAction Stop
            $sw.Stop()
            $sec = [math]::Round($sw.Elapsed.TotalSeconds, 1)
            Write-Check -Name "单轮推理 $sec 秒" -Ok ($sec -le 20) `
                -Detail ("回复: " + ($resp.response -replace "\s+", " ")) `
                -Fix "超过 20 秒：确认走的是 GPU（日志 library=cuda）、无多实例残留；纯 CPU 机器属正常"
        } catch {
            $sw.Stop()
            Write-Check -Name "推理调用失败" -Ok $false -Detail $_.Exception.Message `
                -Fix "确认模型名与 Ollama 服务；换更大模型前先按显存实测评估"
        }
    } else {
        Write-Check -Name "跳过（Ollama 未就绪）" -Ok $false -WarnOnly
    }
}

Write-Host "`n========================================" -ForegroundColor Cyan
if ($script:FailedCount -eq 0) {
    Write-Host "  自检完成：全部通过，可以开始答辩" -ForegroundColor Green
} else {
    Write-Host "  自检完成：$($script:FailedCount) 项未通过（❌），请先修复" -ForegroundColor Red
}
Write-Host "========================================" -ForegroundColor Cyan

exit $(if ($script:FailedCount -eq 0) { 0 } else { 1 })

