# AI答辩系统一键诊断脚本
# 用法: 在 PowerShell 中运行 .\diagnose.ps1

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  AI答辩系统诊断" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

# 1. Ollama 进程状态
Write-Host "`n[1] Ollama 进程" -ForegroundColor Yellow
$ollama = Get-Process ollama* -ErrorAction SilentlyContinue
if ($ollama) {
    Write-Host "  状态: 运行中 (PID: $($ollama.Id), 内存: $([math]::Round($ollama.WorkingSet64/1MB,1))MB)" -ForegroundColor Green
} else {
    Write-Host "  状态: 未运行!" -ForegroundColor Red
}

# 2. Ollama 已安装模型
Write-Host "`n[2] 已安装模型" -ForegroundColor Yellow
$models = & ollama list 2>&1
if ($LASTEXITCODE -eq 0) {
    Write-Host $models
} else {
    Write-Host "  无法获取: $models" -ForegroundColor Red
}

# 3. NVIDIA GPU 状态
Write-Host "`n[3] GPU 状态" -ForegroundColor Yellow
$nvidia = & nvidia-smi --query-gpu=name,memory.total,memory.used,memory.free,utilization.gpu --format=csv,noheader 2>&1
if ($LASTEXITCODE -eq 0) {
    Write-Host $nvidia
} else {
    Write-Host "  nvidia-smi 不可用，GPU 驱动可能未安装" -ForegroundColor Red
}

# 4. Ollama GPU 配置
Write-Host "`n[4] Ollama 环境变量" -ForegroundColor Yellow
$envVars = @(
    "OLLAMA_HOST", "OLLAMA_NUM_GPU", "OLLAMA_KEEP_ALIVE",
    "OLLAMA_FLASH_ATTENTION", "OLLAMA_CONTEXT_LENGTH"
)
foreach ($var in $envVars) {
    $val = [Environment]::GetEnvironmentVariable($var, "User")
    if ($val) { Write-Host "  $var = $val" -ForegroundColor Green }
    else { Write-Host "  $var = (未设置)" -ForegroundColor Gray }
}

# 5. 端口占用
Write-Host "`n[5] 端口 8080 / 11434" -ForegroundColor Yellow
$port8080 = netstat -ano | Select-String ":8080"
$port11434 = netstat -ano | Select-String ":11434"
if ($port8080) { Write-Host "  8080: $port8080" }
if ($port11434) { Write-Host "  11434: $port11434" }

# 6. 单次推理耗时测试
Write-Host "`n[6] 推理速度测试 (一句话回复)" -ForegroundColor Yellow
$start = Get-Date
$result = & ollama run qwen3:4b "用中文回答：什么是Java？10字以内" 2>&1
$end = Get-Date
$duration = ($end - $start).TotalSeconds
Write-Host "  回复: $result"
Write-Host "  耗时: $([math]::Round($duration,1))秒" -ForegroundColor $(if ($duration -gt 20) {"Red"} else {"Green"})

Write-Host "`n========================================" -ForegroundColor Cyan
Write-Host "  诊断完成" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
