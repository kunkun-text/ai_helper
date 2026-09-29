<#
  AI-helper - one-click installer for LOCAL speech recognition (whisper.cpp)
  ---------------------------------------------------------------------------
  What it does:
    1) downloads the prebuilt whisper.cpp Windows binary (no compilation)
    2) downloads a ggml model (default: small, good Chinese accuracy)
    3) puts everything under one folder, so the backend can auto-detect it

  Why needed:
    The WeChat "同声传译" plugin CANNOT be used by a personal-subject mini
    program, so speech recognition runs on this machine instead.

  Usage (run in PowerShell, in the project root):
    powershell -ExecutionPolicy Bypass -File .\scripts\install-whisper.ps1
    powershell -ExecutionPolicy Bypass -File .\scripts\install-whisper.ps1 -Model base
    powershell -ExecutionPolicy Bypass -File .\scripts\install-whisper.ps1 -InstallDir D:\whisper

  Model size guide (disk / RAM / speed on a 4-core laptop):
    tiny     ~75MB   fast    low accuracy
    base     ~142MB  fast    usable for Chinese
    small    ~466MB  medium  recommended
    medium   ~1.5GB  slow    best accuracy

  After install: restart the backend (mvn spring-boot:run) and check the log
  for the line "语音识别已就绪" (speech recognition ready).
#>
param(
    [string]$InstallDir = '',
    [ValidateSet('tiny', 'base', 'small', 'medium')]
    [string]$Model = 'small',
    [string]$Proxy = ''
)

$ErrorActionPreference = 'Stop'
# 关掉进度条：Invoke-WebRequest 画进度条会让大文件下载慢好几倍
$ProgressPreference = 'SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

function Write-Step($msg) { Write-Host "==> $msg" -ForegroundColor Cyan }
function Write-Ok($msg) { Write-Host "[OK] $msg" -ForegroundColor Green }
function Write-Warn2($msg) { Write-Host "[!] $msg" -ForegroundColor Yellow }

function Get-RemoteFile($url, $outFile, $label) {
    Write-Step "Downloading $label"
    Write-Host "    $url"
    $params = @{
        Uri             = $url
        OutFile         = $outFile
        UseBasicParsing = $true
        TimeoutSec      = 3600
    }
    if ($Proxy) { $params['Proxy'] = $Proxy }
    Invoke-WebRequest @params
}

# ---------------------------------------------------------------- 1. target dir
if (-not $InstallDir) {
    foreach ($candidate in @('F:\whisper', 'D:\whisper', 'E:\whisper', 'C:\whisper')) {
        if (Test-Path $candidate.Substring(0, 2)) { $InstallDir = $candidate; break }
    }
}
if (-not $InstallDir) { $InstallDir = Join-Path $env:USERPROFILE 'whisper' }
New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null
Write-Ok "Install directory: $InstallDir"

$tempDir = Join-Path $env:TEMP ('whisper-install-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $tempDir | Out-Null

try {
    # ------------------------------------------------------- 2. engine (binary)
    $engineReady = $false
    foreach ($name in @('whisper-cli.exe', 'main.exe')) {
        if (Test-Path (Join-Path $InstallDir $name)) { $engineReady = $true; break }
    }

    if ($engineReady) {
        Write-Ok "Engine already present, skip download"
    }
    else {
        Write-Step "Resolving whisper.cpp Windows build ..."
        # 注意：whisper.cpp 的最新 release（如 v1.9.4）只发源码、**不带预编译包**，
        # 所以这里遍历最近的 release，找第一个带 x64 包的版本。
        # 优先 BLAS 版（CPU 上识别更快），其次纯 CPU 版。
        $preferredZips = @('whisper-blas-bin-x64.zip', 'whisper-bin-x64.zip')
        $zipUrl = ''
        try {
            $headers = @{ 'User-Agent' = 'ai-helper-installer' }
            $releases = Invoke-RestMethod `
                -Uri 'https://api.github.com/repos/ggerganov/whisper.cpp/releases?per_page=30' `
                -Headers $headers -TimeoutSec 40
            foreach ($rel in $releases) {
                foreach ($candidateName in $preferredZips) {
                    $asset = $rel.assets | Where-Object { $_.name -eq $candidateName } | Select-Object -First 1
                    if ($asset) {
                        $zipUrl = $asset.browser_download_url
                        Write-Host "    found: $candidateName (release $($rel.tag_name))" -ForegroundColor DarkGray
                        break
                    }
                }
                if ($zipUrl) { break }
            }
        }
        catch {
            Write-Warn2 "GitHub API not reachable, falling back to a pinned version"
        }
        if (-not $zipUrl) {
            # 已知可用的固定版本兜底（v1.9.2 含 whisper-blas-bin-x64.zip）
            $zipUrl = 'https://github.com/ggerganov/whisper.cpp/releases/download/v1.9.2/whisper-blas-bin-x64.zip'
        }

        $zipFile = Join-Path $tempDir 'whisper-bin.zip'
        Get-RemoteFile $zipUrl $zipFile 'whisper.cpp engine'

        Write-Step "Extracting engine ..."
        Expand-Archive -Path $zipFile -DestinationPath $tempDir -Force

        # the archive may contain a nested folder -> move the engine + its DLLs up
        $exe = Get-ChildItem -Path $tempDir -Recurse -Include 'whisper-cli.exe', 'main.exe' -File |
            Select-Object -First 1
        if (-not $exe) { throw "whisper executable not found in the downloaded archive" }
        Write-Step "Copying engine files from $($exe.DirectoryName)"
        Copy-Item -Path (Join-Path $exe.DirectoryName '*') -Destination $InstallDir -Recurse -Force
        Write-Ok "Engine installed: $(Join-Path $InstallDir $exe.Name)"
    }

    # ------------------------------------------------------------- 3. ggml model
    $modelDir = Join-Path $InstallDir 'models'
    New-Item -ItemType Directory -Force -Path $modelDir | Out-Null
    $modelFile = Join-Path $modelDir ("ggml-$Model.bin")

    if (Test-Path $modelFile) {
        $sizeMb = [math]::Round((Get-Item $modelFile).Length / 1MB, 1)
        Write-Ok "Model already present: $modelFile ($sizeMb MB)"
    }
    else {
        $mirrors = @(
            "https://hf-mirror.com/ggerganov/whisper.cpp/resolve/main/ggml-$Model.bin",
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-$Model.bin"
        )
        $downloaded = $false
        foreach ($url in $mirrors) {
            try {
                Get-RemoteFile $url $modelFile "ggml-$Model model"
                $downloaded = $true
                break
            }
            catch {
                Write-Warn2 "Mirror failed: $url"
                if (Test-Path $modelFile) { Remove-Item $modelFile -Force }
            }
        }
        if (-not $downloaded) { throw "Failed to download the model from all mirrors" }
        $sizeMb = [math]::Round((Get-Item $modelFile).Length / 1MB, 1)
        Write-Ok "Model installed: $modelFile ($sizeMb MB)"
    }

    # ------------------------------------------- 3.5 ffmpeg (optional but needed)
    # Why: the WeChat devtool records WEBM audio (browser kernel), which Java and
    # whisper cannot decode - only ffmpeg can. Real devices record mp3/aac, which
    # ffmpeg also handles better. Prefer reusing an ffmpeg already on this machine
    # (many apps ship one, e.g. bilibili client / OBS), so no download is needed.
    $ffmpegDest = Join-Path $InstallDir 'ffmpeg.exe'
    if (Test-Path $ffmpegDest) {
        Write-Ok "ffmpeg already present, skip"
    }
    else {
        Write-Step "Looking for an existing ffmpeg on this machine ..."
        $found = @()
        $cmd = Get-Command ffmpeg -ErrorAction SilentlyContinue
        if ($cmd) { $found += $cmd.Source }
        foreach ($root in @($env:APPDATA, $env:LOCALAPPDATA)) {
            if ($root -and (Test-Path $root)) {
                $found += (Get-ChildItem -Path $root -Filter 'ffmpeg.exe' -Recurse -Depth 4 -ErrorAction SilentlyContinue |
                    Select-Object -First 3 -ExpandProperty FullName)
            }
        }
        $picked = $found | Where-Object { $_ } | Select-Object -Unique | Select-Object -First 1
        if ($picked) {
            Copy-Item $picked $ffmpegDest -Force
            Write-Ok "ffmpeg copied from: $picked"
        }
        else {
            Write-Warn2 "No local ffmpeg found"
            Write-Host "      Please copy ffmpeg.exe into: $InstallDir" -ForegroundColor Yellow
            Write-Host "      (Devtools records webm audio; without ffmpeg it cannot be recognized.)" -ForegroundColor Yellow
        }
    }

    # ------------------------------------------------------------------ 4. check
    $engineName = ''
    foreach ($name in @('whisper-cli.exe', 'main.exe')) {
        if (Test-Path (Join-Path $InstallDir $name)) { $engineName = $name; break }
    }

    Write-Host ''
    Write-Host '================ INSTALL SUMMARY ================' -ForegroundColor Green
    Write-Host "  Engine : $(Join-Path $InstallDir $engineName)"
    Write-Host "  Model  : $modelFile"
    Write-Host ''
    Write-Host '  Next step: restart the backend (mvn spring-boot:run).' -ForegroundColor Yellow
    Write-Host '  The startup log should print: [Speech recognition ready]' -ForegroundColor Yellow
    Write-Host '=================================================' -ForegroundColor Green
}
finally {
    Remove-Item $tempDir -Recurse -Force -ErrorAction SilentlyContinue
}
