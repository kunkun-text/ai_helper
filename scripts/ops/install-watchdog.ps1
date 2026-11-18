<#
.SYNOPSIS
    打印 watchdog 的计划任务注册/卸载命令（不会实际执行注册）。

.DESCRIPTION
    注册开机自启需要管理员权限。脚本只负责生成命令，供人工确认后执行。
    如果后端需要由守护脚本自动拉起，请先以管理员身份设置机器级环境变量
    AI_HELPER_BACKEND_CMD（见输出中的示例），watchdog 会自动读取。
#>
[CmdletBinding()]
param(
    [string]$TaskName = 'AI-Helper-Watchdog',
    [int]$IntervalSeconds = 60
)

$scriptDir = $PSScriptRoot
$watchdog = Join-Path $scriptDir 'watchdog.ps1'

if (-not (Test-Path $watchdog)) {
    Write-Host ('[FAIL] 找不到 watchdog.ps1: ' + $watchdog)
    exit 1
}

Write-Host '========================================'
Write-Host ' Watchdog 计划任务安装说明'
Write-Host '========================================'
Write-Host ''
Write-Host '[前提] 需要管理员权限（以管理员身份打开 PowerShell）。本脚本不会执行注册。'
Write-Host ''
Write-Host '[可选] 若希望后端也被自动拉起，先设置机器级环境变量（需要管理员）：'
Write-Host '  [Environment]::SetEnvironmentVariable('
Write-Host '    "AI_HELPER_BACKEND_CMD",'
Write-Host '    ''& "D:\maven\apache-maven-3.8.1\bin\mvn.cmd" -o spring-boot:run -Dspring-boot.run.jvmArguments=-Xmx512m'','
Write-Host '    "Machine")'
Write-Host ''
Write-Host '[注册] 复制并执行：'
Write-Host ('schtasks /Create /TN "' + $TaskName + '" /SC ONSTART /RU SYSTEM /RL HIGHEST /TR "powershell.exe -NoProfile -ExecutionPolicy Bypass -File \"' + $watchdog + '\" -IntervalSeconds ' + $IntervalSeconds + '" /F')
Write-Host ''
Write-Host '[查看]'
Write-Host ('schtasks /Query /TN "' + $TaskName + '" /V /FO LIST')
Write-Host ''
Write-Host '[立即运行一次（可选）]'
Write-Host ('schtasks /Run /TN "' + $TaskName + '"')
Write-Host ''
Write-Host '[卸载]'
Write-Host ('schtasks /Delete /TN "' + $TaskName + '" /F')
Write-Host ''
Write-Host '[日志] logs\ops\watchdog-YYYYMMDD.log'
exit 0
