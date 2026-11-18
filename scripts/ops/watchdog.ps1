<#
.SYNOPSIS
    服务守护：定期运行 health-check.ps1，异常时调用 start-all.ps1 自愈。

.DESCRIPTION
    - 默认每 60 秒检查一次（-IntervalSeconds 可调）。
    - 健康检查失败 -> 调用 start-all.ps1，并把两边输出写入 logs\ops\watchdog-YYYYMMDD.log。
    - 单次循环内的任何异常只记录日志，不退出整个循环。
    - 后端启动命令通过 -BackendCmd 或环境变量 AI_HELPER_BACKEND_CMD 传给 start-all.ps1。

.EXAMPLE
    .\watchdog.ps1 -IntervalSeconds 60

.EXAMPLE
    .\watchdog.ps1 -IntervalSeconds 30 -BackendCmd '& "D:\maven\apache-maven-3.8.1\bin\mvn.cmd" -o spring-boot:run -Dspring-boot.run.jvmArguments=-Xmx512m'
#>
[CmdletBinding()]
param(
    [ValidateRange(5, 3600)]
    [int]$IntervalSeconds = 60,
    [string]$BackendCmd = ''
)

$ErrorActionPreference = 'Stop'

$scriptDir = $PSScriptRoot
$repoRoot = Split-Path -Parent (Split-Path -Parent $scriptDir)
$healthScript = Join-Path $scriptDir 'health-check.ps1'
$startAllScript = Join-Path $scriptDir 'start-all.ps1'
$logDir = Join-Path $repoRoot 'logs\ops'

New-Item -ItemType Directory -Force -Path $logDir | Out-Null

function Write-Log {
    param([string]$Message)
    $logFile = Join-Path $logDir ('watchdog-' + (Get-Date -Format 'yyyyMMdd') + '.log')
    $line = '{0} {1}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $Message
    Write-Host $line
    Add-Content -Path $logFile -Value $line -Encoding UTF8
}

function Write-Block {
    param([string]$Tag, [string]$Text)
    if ([string]::IsNullOrWhiteSpace($Text)) { return }
    foreach ($line in ($Text -split "`r?`n")) {
        if (-not [string]::IsNullOrWhiteSpace($line)) {
            Write-Log ('  [' + $Tag + '] ' + $line.Trim())
        }
    }
}

if (-not (Test-Path $healthScript)) {
    Write-Log ('[FATAL] 找不到 health-check.ps1: ' + $healthScript)
    exit 1
}
if (-not (Test-Path $startAllScript)) {
    Write-Log ('[FATAL] 找不到 start-all.ps1: ' + $startAllScript)
    exit 1
}

$backendSource = '未提供（后端已运行时不影响）'
if (-not [string]::IsNullOrWhiteSpace($BackendCmd)) {
    $backendSource = '参数 -BackendCmd'
} elseif (-not [string]::IsNullOrWhiteSpace($env:AI_HELPER_BACKEND_CMD)) {
    $backendSource = '环境变量 AI_HELPER_BACKEND_CMD'
}

Write-Log ('[START] watchdog 已启动，间隔 ' + $IntervalSeconds + ' 秒；后端命令来源: ' + $backendSource)

while ($true) {
    try {
        # 只捕获 stdout（两个脚本的提示都走 Write-Host），避免 PS 5.1 对原生命令 2>&1 产生“流不可读”
        $healthOut = @(& powershell.exe -NoProfile -ExecutionPolicy Bypass -File $healthScript)
        $healthCode = $LASTEXITCODE

        if ($healthCode -eq 0) {
            Write-Log '[HEALTH] OK'
        } else {
            Write-Log ('[HEALTH] FAIL (exit=' + $healthCode + ')，准备调用 start-all.ps1')
            Write-Block 'HEALTH' ($healthOut -join "`r`n")

            $startArgs = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $startAllScript)
            if (-not [string]::IsNullOrWhiteSpace($BackendCmd)) {
                $startArgs += @('-BackendCmd', $BackendCmd)
            }
            $startOut = @(& powershell.exe @startArgs)
            $startCode = $LASTEXITCODE
            Write-Log ('[START-ALL] exit=' + $startCode)
            Write-Block 'START-ALL' ($startOut -join "`r`n")

            $recheckOut = @(& powershell.exe -NoProfile -ExecutionPolicy Bypass -File $healthScript)
            $recheckCode = $LASTEXITCODE
            if ($recheckCode -eq 0) {
                Write-Log '[RECHECK] OK，自愈完成'
            } else {
                Write-Log ('[RECHECK] 仍异常 (exit=' + $recheckCode + ')，等待下一轮')
                Write-Block 'RECHECK' ($recheckOut -join "`r`n")
            }
        }
    } catch {
        # 单次循环异常不能退出循环
        Write-Log ('[LOOP-ERROR] ' + $_.Exception.Message)
    }

    Start-Sleep -Seconds $IntervalSeconds
}
