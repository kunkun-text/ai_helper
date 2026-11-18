<#
.SYNOPSIS
    检查 Redis(6379) / Ollama(11434) / 后端(8080) 是否健康。

.DESCRIPTION
    - 三个端口必须处于 LISTENING。
    - Ollama 额外 GET /api/tags，期望 HTTP 200。
    - 后端额外 GET /api/report/policy，期望 HTTP 401（未登录被拦截，说明服务活着）。
    - 全部正常退出 0；任一异常退出 1，并打印「哪一项、期望什么、实际什么」。
#>
[CmdletBinding()]
param(
    [string]$OllamaTagsUrl = 'http://localhost:11434/api/tags',
    [string]$BackendProbeUrl = 'http://localhost:8080/api/report/policy',
    [int]$HttpTimeoutSeconds = 5
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$script:failures = 0

function Test-Port {
    param([int]$Port)
    $hit = & netstat -ano | Select-String -Pattern (':' + $Port + '\s+.*LISTENING') -CaseSensitive:$false
    return [bool]$hit
}

function Report-Ok {
    param([string]$Item, [string]$Actual)
    Write-Host ('[OK]   ' + $Item + ' -> ' + $Actual)
}

function Report-Fail {
    param([string]$Item, [string]$Expected, [string]$Actual)
    Write-Host ('[FAIL] ' + $Item)
    Write-Host ('       期望: ' + $Expected)
    Write-Host ('       实际: ' + $Actual)
    $script:failures++
}

function Test-HttpStatus {
    param([string]$Url, [int]$Expected)
    try {
        $resp = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec $HttpTimeoutSeconds
        $code = [int]$resp.StatusCode
        if ($code -eq $Expected) {
            Report-Ok ('GET ' + $Url) ('HTTP ' + $code)
        } else {
            Report-Fail ('GET ' + $Url) ('HTTP ' + $Expected) ('HTTP ' + $code)
        }
    } catch {
        $code = $null
        if ($_.Exception.Response -ne $null) {
            $code = [int]$_.Exception.Response.StatusCode
        }
        if ($code -eq $Expected) {
            Report-Ok ('GET ' + $Url) ('HTTP ' + $code)
        } elseif ($code -ne $null) {
            Report-Fail ('GET ' + $Url) ('HTTP ' + $Expected) ('HTTP ' + $code)
        } else {
            Report-Fail ('GET ' + $Url) ('HTTP ' + $Expected) ('请求失败: ' + $_.Exception.Message)
        }
    }
}

Write-Host '========================================'
Write-Host ' AI-helper 健康检查'
Write-Host '========================================'

# ---------------- 端口 ----------------
$ports = @(
    @{ Port = 6379;  Name = 'Redis' },
    @{ Port = 11434; Name = 'Ollama' },
    @{ Port = 8080;  Name = '后端' }
)
foreach ($p in $ports) {
    if (Test-Port $p.Port) {
        Report-Ok ($p.Name + ' 端口 ' + $p.Port) 'LISTENING'
    } else {
        Report-Fail ($p.Name + ' 端口 ' + $p.Port) 'LISTENING' '未监听'
    }
}

# ---------------- Ollama HTTP ----------------
if (Test-Port 11434) {
    try {
        $r = Invoke-WebRequest -Uri $OllamaTagsUrl -UseBasicParsing -TimeoutSec $HttpTimeoutSeconds
        if ([int]$r.StatusCode -eq 200) {
            $modelCount = 0
            try {
                $json = $r.Content | ConvertFrom-Json
                if ($json.models -ne $null) { $modelCount = @($json.models).Count }
            } catch {
                # 返回 200 但 JSON 解析失败仍视为服务活着
            }
            Report-Ok ('GET ' + $OllamaTagsUrl) ('HTTP 200（模型 ' + $modelCount + ' 个）')
        } else {
            Report-Fail ('GET ' + $OllamaTagsUrl) 'HTTP 200' ('HTTP ' + [int]$r.StatusCode)
        }
    } catch {
        Report-Fail ('GET ' + $OllamaTagsUrl) 'HTTP 200' ('请求失败: ' + $_.Exception.Message)
    }
} else {
    Report-Fail ('GET ' + $OllamaTagsUrl) 'HTTP 200' 'Ollama 端口未监听，跳过 HTTP 检查（已计为失败）'
}

# ---------------- 后端 HTTP（期望 401） ----------------
if (Test-Port 8080) {
    Test-HttpStatus -Url $BackendProbeUrl -Expected 401
} else {
    Report-Fail ('GET ' + $BackendProbeUrl) 'HTTP 401' '后端端口未监听，跳过 HTTP 检查（已计为失败）'
}

Write-Host ''
if ($script:failures -eq 0) {
    Write-Host '[OK] 全部检查通过'
    exit 0
} else {
    Write-Host ('[FAIL] 共 ' + $script:failures + ' 项异常')
    exit 1
}
