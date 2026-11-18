<#
.SYNOPSIS
    按顺序启动 Redis (6379) -> Ollama (11434) -> 后端 (8080)，已在运行的步骤自动跳过。

.DESCRIPTION
    - 每个步骤先用 netstat 检测端口，避免重复启动。
    - 每步最多等待 60 秒端口就绪，失败会明确输出是哪一步没起来，并以非 0 退出。
    - 后端启动命令必须显式提供（-BackendCmd 或环境变量 AI_HELPER_BACKEND_CMD）。
      logs\backend-run.log 显示历史方式为 Maven spring-boot:run，但本机 PATH 中没有 mvn，
      所以脚本不猜测可执行文件路径；无法确认时直接报错退出。

.EXAMPLE
    .\start-all.ps1

.EXAMPLE
    .\start-all.ps1 -BackendCmd '& "D:\maven\apache-maven-3.8.1\bin\mvn.cmd" -o spring-boot:run -Dspring-boot.run.jvmArguments=-Xmx512m'
#>
[CmdletBinding()]
param(
    [string]$RedisExe = 'F:\杂七杂八\Redis2\redis-server.exe',
    [string]$RedisConf = 'redis.windows.conf',
    [string]$RedisDir = 'F:\杂七杂八\Redis2',
    [string]$OllamaExe = 'F:\ollama\Ollama\ollama.exe',
    [string]$BackendCmd = '',
    [string]$RepoRoot = ''
)

$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    # scripts\ops\start-all.ps1 -> 上两级是项目根
    $RepoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
}

function Write-Step {
    param([string]$Level, [string]$Message)
    Write-Host ('[{0}] {1}' -f $Level, $Message)
}

function Test-Port {
    param([int]$Port)
    $hit = & netstat -ano | Select-String -Pattern (':' + $Port + '\s+.*LISTENING') -CaseSensitive:$false
    return [bool]$hit
}

function Wait-Port {
    param([int]$Port, [int]$TimeoutSeconds = 60)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if (Test-Port $Port) { return $true }
        Start-Sleep -Seconds 1
    }
    return (Test-Port $Port)
}

function Resolve-BackendCommand {
    if (-not [string]::IsNullOrWhiteSpace($BackendCmd)) {
        return @{ Command = $BackendCmd; Source = '参数 -BackendCmd' }
    }
    if (-not [string]::IsNullOrWhiteSpace($env:AI_HELPER_BACKEND_CMD)) {
        return @{ Command = $env:AI_HELPER_BACKEND_CMD; Source = '环境变量 AI_HELPER_BACKEND_CMD' }
    }

    # 读历史日志确认“启动方式”，但不猜测可执行文件路径
    $evidence = @()
    $methodConfirmed = $false
    $scanFiles = @()
    $mainLog = Join-Path $RepoRoot 'logs\backend-run.log'
    if (Test-Path $mainLog) { $scanFiles += $mainLog }
    $tmpRun = Join-Path $RepoRoot '.tmp\run'
    if (Test-Path $tmpRun) {
        $scanFiles += @(Get-ChildItem $tmpRun -Filter '*.log' -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName })
    }
    foreach ($file in $scanFiles) {
        try {
            $head = Get-Content $file -TotalCount 80 -ErrorAction Stop
            if (($head -join "`n") -match 'spring-boot-maven-plugin.*run|spring-boot:run') {
                $methodConfirmed = $true
            }
            $evidence += $file
        } catch {
            # 单个日志读不了不影响总体判断
        }
    }
    if ($methodConfirmed) {
        Write-Step 'INFO' '历史日志确认后端启动方式为 Maven spring-boot:run（未找到可直接复用的可执行路径）'
    } elseif ($evidence.Count -gt 0) {
        Write-Step 'INFO' ('检测到历史启动记录: ' + ($evidence -join ', ') + '（未能确认具体命令）')
    }
    return $null
}

Write-Host '========================================'
Write-Host ' AI-helper 服务启动 (Redis -> Ollama -> 后端)'
Write-Host '========================================'

# ---------------- 1. Redis ----------------
Write-Step '1/3' 'Redis (6379)'
if (Test-Port 6379) {
    Write-Step 'SKIP' 'Redis 已在运行（6379 LISTENING），跳过'
} else {
    if (-not (Test-Path $RedisExe)) {
        Write-Step 'FAIL' ('Redis 可执行文件不存在: ' + $RedisExe)
        exit 1
    }
    $redisConfPath = Join-Path $RedisDir $RedisConf
    if (-not (Test-Path $redisConfPath)) {
        Write-Step 'FAIL' ('Redis 配置文件不存在: ' + $redisConfPath)
        exit 1
    }
    try {
        Start-Process -FilePath $RedisExe -ArgumentList $RedisConf -WorkingDirectory $RedisDir -ErrorAction Stop
    } catch {
        Write-Step 'FAIL' ('Redis 启动失败: ' + $_.Exception.Message)
        exit 1
    }
    if (Wait-Port 6379 60) {
        Write-Step 'OK' 'Redis 已启动（6379 LISTENING）'
    } else {
        Write-Step 'FAIL' 'Redis 启动后 60 秒内 6379 仍未监听'
        exit 1
    }
}

# ---------------- 2. Ollama ----------------
Write-Step '2/3' 'Ollama (11434)'
if (Test-Port 11434) {
    Write-Step 'SKIP' 'Ollama 已在运行（11434 LISTENING），跳过'
} else {
    if (-not (Test-Path $OllamaExe)) {
        Write-Step 'FAIL' ('Ollama 可执行文件不存在: ' + $OllamaExe)
        exit 1
    }
    try {
        Start-Process -FilePath $OllamaExe -ArgumentList 'serve' -ErrorAction Stop
    } catch {
        Write-Step 'FAIL' ('Ollama 启动失败: ' + $_.Exception.Message)
        exit 1
    }
    if (Wait-Port 11434 60) {
        Write-Step 'OK' 'Ollama 已启动（11434 LISTENING）'
    } else {
        Write-Step 'FAIL' 'Ollama 启动后 60 秒内 11434 仍未监听'
        exit 1
    }
}

# ---------------- 3. 后端 ----------------
Write-Step '3/3' '后端 (8080)'
if (Test-Port 8080) {
    Write-Step 'SKIP' '后端已在运行（8080 LISTENING），跳过'
} else {
    $resolved = Resolve-BackendCommand
    if ($null -eq $resolved) {
        Write-Step 'FAIL' '后端未运行，且无法确认启动命令（未传 -BackendCmd，也未设置 AI_HELPER_BACKEND_CMD）'
        Write-Step 'INFO' 'logs\backend-run.log 显示历史方式是 Maven spring-boot:run，但本机 PATH 中没有 mvn，脚本不会猜测路径。'
        Write-Step 'INFO' '请显式传入，例如：'
        Write-Step 'INFO' '  .\start-all.ps1 -BackendCmd ''& "D:\maven\apache-maven-3.8.1\bin\mvn.cmd" -o spring-boot:run -Dspring-boot.run.jvmArguments=-Xmx512m'''
        exit 1
    }

    $opsLogDir = Join-Path $RepoRoot 'logs\ops'
    New-Item -ItemType Directory -Force -Path $opsLogDir | Out-Null
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $stdout = Join-Path $opsLogDir ('backend-' + $stamp + '.out.log')
    $stderr = Join-Path $opsLogDir ('backend-' + $stamp + '.err.log')

    Write-Step 'INFO' ('后端启动命令来源: ' + $resolved.Source)
    Write-Step 'INFO' ('stdout: ' + $stdout)

    # 用 -EncodedCommand 避免中文路径/引号在 PS 5.1 参数拼接时被拆坏
    $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($resolved.Command))
    try {
        Start-Process -FilePath 'powershell.exe' `
            -ArgumentList ('-NoProfile -ExecutionPolicy Bypass -EncodedCommand ' + $encoded) `
            -WorkingDirectory $RepoRoot `
            -WindowStyle Hidden `
            -RedirectStandardOutput $stdout `
            -RedirectStandardError $stderr `
            -ErrorAction Stop
    } catch {
        Write-Step 'FAIL' ('后端启动失败: ' + $_.Exception.Message)
        exit 1
    }

    if (Wait-Port 8080 60) {
        Write-Step 'OK' '后端已启动（8080 LISTENING）'
    } else {
        Write-Step 'FAIL' '后端启动后 60 秒内 8080 仍未监听'
        if (Test-Path $stdout) {
            Write-Step 'INFO' '--- stdout 末尾 20 行 ---'
            Get-Content $stdout -Tail 20 | ForEach-Object { Write-Host ('  ' + $_) }
        }
        if (Test-Path $stderr) {
            Write-Step 'INFO' '--- stderr 末尾 20 行 ---'
            Get-Content $stderr -Tail 20 | ForEach-Object { Write-Host ('  ' + $_) }
        }
        exit 1
    }
}

Write-Host ''
Write-Host '[OK] 三服务均已就绪：Redis=6379 / Ollama=11434 / 后端=8080'
exit 0
