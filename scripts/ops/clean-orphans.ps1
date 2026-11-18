<#
.SYNOPSIS
    清理僵尸 llama-server.exe 进程，并打印显存释放量。

.DESCRIPTION
    判定规则（满足任一即可）：
      1) Ollama 服务未运行（11434 未监听 / ollama.exe 不存在）；
      2) Ollama 服务在运行，但 ollama ps 没有任何模型，而 llama-server.exe 仍在。
    只处理 llama-server.exe，绝不碰 Word / 浏览器 / 其他用户进程。
    执行前后各打印一次 nvidia-smi 的 memory.used。
#>
[CmdletBinding()]
param(
    [string]$OllamaExe = 'F:\ollama\Ollama\ollama.exe'
)

$ErrorActionPreference = 'Stop'

function Test-Port {
    param([int]$Port)
    $hit = & netstat -ano | Select-String -Pattern (':' + $Port + '\s+.*LISTENING') -CaseSensitive:$false
    return [bool]$hit
}

function Get-GpuUsedMiB {
    $nvidia = Get-Command nvidia-smi -ErrorAction SilentlyContinue
    if ($null -eq $nvidia) {
        Write-Host '[WARN] 未找到 nvidia-smi，无法读取显存'
        return $null
    }
    try {
        $lines = & nvidia-smi --query-gpu=memory.used --format=csv,noheader,nounits
        if ($LASTEXITCODE -ne 0) {
            Write-Host '[WARN] nvidia-smi 执行失败，无法读取显存'
            return $null
        }
        $sum = 0
        $count = 0
        foreach ($line in $lines) {
            $text = ([string]$line).Trim()
            if ($text -match '^\d+$') {
                $sum += [int]$text
                $count++
            }
        }
        if ($count -eq 0) { return $null }
        return $sum
    } catch {
        Write-Host ('[WARN] 读取显存失败: ' + $_.Exception.Message)
        return $null
    }
}

function Print-Gpu {
    param([string]$Label)
    $used = Get-GpuUsedMiB
    if ($used -eq $null) {
        Write-Host ($Label + ': 不可用')
        return $null
    }
    Write-Host ($Label + ': ' + $used + ' MiB')
    return $used
}

Write-Host '========================================'
Write-Host ' 清理僵尸 llama-server.exe'
Write-Host '========================================'

$before = Print-Gpu '显存（执行前）'

$llamas = @(Get-Process -Name 'llama-server' -ErrorAction SilentlyContinue)
if ($llamas.Count -eq 0) {
    Write-Host '[OK] 未发现 llama-server.exe 进程，无需清理'
    $after = Print-Gpu '显存（执行后）'
    exit 0
}

Write-Host ('[INFO] 发现 ' + $llamas.Count + ' 个 llama-server.exe: ' + (($llamas | ForEach-Object { $_.Id }) -join ', '))

$ollamaRunning = (Test-Port 11434) -and (@(Get-Process -Name 'ollama' -ErrorAction SilentlyContinue).Count -gt 0)
$reason = ''
$shouldClean = $false

if (-not $ollamaRunning) {
    $shouldClean = $true
    $reason = 'Ollama 服务未运行，llama-server 属残留进程'
} else {
    # 三态：has=有模型 / none=明确无模型 / unknown=查询失败（保守不清理）
    $modelState = 'unknown'
    if (Test-Path $OllamaExe) {
        try {
            $psOut = & $OllamaExe ps
            if ($LASTEXITCODE -eq 0) {
                $rows = @($psOut | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) })
                # 第一行是表头；有第二行即视为有模型加载
                if ($rows.Count -gt 1) { $modelState = 'has' } else { $modelState = 'none' }
            } else {
                Write-Host '[WARN] ollama ps 执行失败，无法判定是否有模型，保守起见不清理'
            }
        } catch {
            Write-Host ('[WARN] ollama ps 调用失败: ' + $_.Exception.Message + '，无法判定，保守起见不清理')
        }
    } else {
        Write-Host ('[WARN] Ollama 可执行文件不存在: ' + $OllamaExe + '，无法判定，保守起见不清理')
    }

    if ($modelState -eq 'none') {
        $shouldClean = $true
        $reason = 'ollama ps 无任何模型，但 llama-server 仍在运行'
    } elseif ($modelState -eq 'has') {
        Write-Host '[OK] Ollama 已加载模型，llama-server 不是僵尸，保持不动'
    } else {
        Write-Host '[WARN] 无法确认 Ollama 模型状态，本次不清理任何 llama-server'
    }
}

if (-not $shouldClean) {
    $after = Print-Gpu '显存（执行后）'
    exit 0
}

Write-Host ('[INFO] 判定为僵尸：' + $reason)
$killed = 0
foreach ($proc in $llamas) {
    try {
        Stop-Process -Id $proc.Id -Force -ErrorAction Stop
        Write-Host ('[OK]   已终止 llama-server.exe PID ' + $proc.Id)
        $killed++
    } catch {
        Write-Host ('[FAIL] 终止 PID ' + $proc.Id + ' 失败: ' + $_.Exception.Message)
    }
}

Start-Sleep -Seconds 2
$after = Print-Gpu '显存（执行后）'
if (($before -ne $null) -and ($after -ne $null)) {
    $freed = $before - $after
    if ($freed -lt 0) { $freed = 0 }
    Write-Host ('[RESULT] 终止 ' + $killed + ' 个进程，显存释放 ' + $freed + ' MiB（' + $before + ' -> ' + $after + '）')
}

if ($killed -lt $llamas.Count) {
    Write-Host '[FAIL] 有进程未能终止'
    exit 1
}
exit 0
