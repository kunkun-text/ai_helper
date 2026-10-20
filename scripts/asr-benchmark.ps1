<#
  AI-helper - ASR benchmark for real defense videos
  --------------------------------------------------
  Runs the SAME audio through several whisper.cpp models and reports:
    - wall-clock time
    - transcript length
    - average token confidence (from whisper JSON)
    - low-confidence segment count
    - optional CER (character error rate) against a reference transcript

  Why:
    The project currently ships ggml-small. For Chinese domain terms it is not
    enough ("文字答辩" -> "文字打变"). Before switching to medium / large-v3-turbo,
    measure accuracy and CPU cost on a real video instead of guessing.

  Usage (PowerShell session, project root):
    .\scripts\asr-benchmark.ps1 -Video "F:\path\defense.mp4"
    .\scripts\asr-benchmark.ps1 -Video "F:\path\defense.mp4" -Models small,medium,large-v3-turbo
    .\scripts\asr-benchmark.ps1 -Video "...mp4" -Reference ".\reference.txt"

  If execution policy blocks scripts, run:
    powershell -ExecutionPolicy Bypass -File .\scripts\asr-benchmark.ps1 -Video "..."
  (with `-File`, pass one model per run; use the direct form above for a model list)

  Notes:
    - Models are looked up as <InstallDir>\models\ggml-<name>.bin; a full path also works.
    - The audio filter matches app.video-analysis.audio-filter (highpass + dynaudnorm).
    - Transcripts here are RAW whisper output; the backend additionally applies
      src/main/resources/asr-corrections.txt (14 high-confidence homophone fixes).
    - Set -InstallDir if whisper is not in F:\whisper / D:\whisper / E:\whisper.
#>
param(
    [Parameter(Mandatory = $true)]
    [string]$Video,
    [string[]]$Models = @('small', 'medium'),
    [string]$InstallDir = '',
    [string]$Prompt = '学生答辩现场讲解。常见词：答辩、文字答辩、语音答辩、视频上传、报告上传、评分、教师端、学生端、数据库、系统功能。',
    [string]$Reference = '',
    [string]$OutDir = '',
    [int]$TimeoutSec = 3600
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

function Resolve-InstallDir {
    param([string]$Configured)
    if ($Configured) { return $Configured }
    foreach ($candidate in @('F:\whisper', 'D:\whisper', 'E:\whisper', 'C:\whisper',
            (Join-Path (Get-Location) 'whisper'))) {
        if (Test-Path (Join-Path $candidate 'whisper-cli.exe')) { return $candidate }
        if (Test-Path (Join-Path $candidate 'main.exe')) { return $candidate }
    }
    throw 'whisper.cpp not found. Pass -InstallDir or run scripts/install-whisper.ps1 first.'
}

function Resolve-Exe {
    param([string]$Dir)
    foreach ($name in @('whisper-cli.exe', 'main.exe')) {
        $path = Join-Path $Dir $name
        if (Test-Path $path) { return $path }
    }
    throw "whisper executable not found in $Dir"
}

function Resolve-Ffmpeg {
    param([string]$Dir)
    $local = Join-Path $Dir 'ffmpeg.exe'
    if (Test-Path $local) { return $local }
    $cmd = Get-Command ffmpeg -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    throw 'ffmpeg not found. Run scripts/install-whisper.ps1 or pass -InstallDir.'
}

function Clean-Text {
    param([string]$Text)
    if (-not $Text) { return '' }
    # 去掉空白与标点，只比较“听出来的字”
    return ($Text -replace '[\s\p{P}\p{S}]', '')
}

function Get-Levenshtein {
    param([string]$A, [string]$B)
    $n = $A.Length
    $m = $B.Length
    if ($n -eq 0) { return $m }
    if ($m -eq 0) { return $n }
    $prev = [int[]]::new($m + 1)
    $cur = [int[]]::new($m + 1)
    for ($j = 0; $j -le $m; $j++) { $prev[$j] = $j }
    for ($i = 1; $i -le $n; $i++) {
        $cur[0] = $i
        for ($j = 1; $j -le $m; $j++) {
            $cost = 1
            if ($A[$i - 1] -eq $B[$j - 1]) { $cost = 0 }
            $del = $prev[$j] + 1
            $ins = $cur[$j - 1] + 1
            $sub = $prev[$j - 1] + $cost
            $min = $del
            if ($ins -lt $min) { $min = $ins }
            if ($sub -lt $min) { $min = $sub }
            $cur[$j] = $min
        }
        $tmp = $prev
        $prev = $cur
        $cur = $tmp
    }
    return $prev[$m]
}

function Get-WhisperMetrics {
    param([string]$JsonPath)
    $result = [pscustomobject]@{ Segments = 0; SpeechMs = 0; AvgP = 0; LowSegments = 0 }
    if (-not (Test-Path $JsonPath)) { return $result }
    $raw = [System.IO.File]::ReadAllText($JsonPath, [System.Text.Encoding]::UTF8)
    $json = $raw | ConvertFrom-Json
    $pSum = 0.0
    $pCount = 0
    $low = 0
    $segments = 0
    $speechMs = 0
    foreach ($seg in $json.transcription) {
        $segments++
        $segSpeechMs = 0
        $segSum = 0.0
        $segCount = 0
        foreach ($token in $seg.tokens) {
            if (-not $token.text) { continue }
            if ($token.text -match '^\[') { continue }
            if ($null -eq $token.p) { continue }
            if ($null -ne $token.offsets) {
                $segSpeechMs += [long]$token.offsets.to - [long]$token.offsets.from
            }
            $segSum += [double]$token.p
            $segCount++
        }
        if ($segSpeechMs -gt 0) {
            $speechMs += $segSpeechMs
        }
        elseif ($null -ne $seg.offsets) {
            $speechMs += [long]$seg.offsets.to - [long]$seg.offsets.from
        }
        if ($segCount -gt 0) {
            $pSum += $segSum
            $pCount += $segCount
            if (($segSum / $segCount) -lt 0.6) { $low++ }
        }
    }
    $result.Segments = $segments
    $result.SpeechMs = $speechMs
    $result.LowSegments = $low
    if ($pCount -gt 0) { $result.AvgP = [math]::Round($pSum / $pCount, 3) }
    return $result
}

$installDir = Resolve-InstallDir $InstallDir
$exe = Resolve-Exe $installDir
$ffmpeg = Resolve-Ffmpeg $installDir
$vad = Join-Path $installDir 'models\ggml-silero-v5.1.2.bin'
if (-not (Test-Path $vad)) { $vad = '' }
if (-not $OutDir) { $OutDir = Join-Path (Get-Location) 'asr-benchmark-out' }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

Write-Host "==> whisper : $exe"
Write-Host "==> ffmpeg  : $ffmpeg"
Write-Host "==> VAD     : $(if ($vad) { $vad } else { 'not found (disabled)' })"
Write-Host "==> output  : $OutDir"

$work = Join-Path $env:TEMP ('asr-bench-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $work | Out-Null
$wav = Join-Path $work 'audio.wav'

try {
    Write-Host '==> extracting audio (highpass + dynaudnorm) ...'
    & $ffmpeg -y -i $Video -vn -ar 16000 -ac 1 -c:a pcm_s16le `
        -af 'highpass=f=80,dynaudnorm=f=150:g=15' -loglevel error $wav
    if ($LASTEXITCODE -ne 0) { throw "ffmpeg failed with exit code $LASTEXITCODE" }
    $audioSec = [math]::Round(((Get-Item $wav).Length - 44) / 32000.0, 1)
    Write-Host "==> audio duration: $audioSec s"

    $referenceText = ''
    if ($Reference) {
        if (-not (Test-Path $Reference)) { throw "reference transcript not found: $Reference" }
        $referenceText = Clean-Text (Get-Content $Reference -Raw -Encoding UTF8)
    }

    $results = @()
    foreach ($model in $Models) {
        $modelFile = $model
        if (-not (Test-Path $modelFile)) {
            $modelFile = Join-Path $installDir ('models\ggml-' + $model + '.bin')
        }
        if (-not (Test-Path $modelFile)) {
            Write-Warning "model not found, skip: $model ($modelFile)"
            continue
        }

        # 用文件名做输出前缀：$model 可能是完整路径（含冒号/反斜杠，不能直接拼文件名）
        $modelLabel = (Split-Path $model -Leaf) -replace '\.bin$', ''
        $prefix = Join-Path $OutDir ('transcript-' + $modelLabel)
        $wargs = @('-m', $modelFile, '-f', $wav, '-l', 'zh',
            '-otxt', '-ojf', '-np', '-of', $prefix)
        if ($vad) {
            $wargs += @('--vad', '--vad-model', $vad,
                '--vad-threshold', '0.5',
                '--vad-min-speech-duration-ms', '250',
                '--vad-min-silence-duration-ms', '100')
        }
        if ($Prompt) { $wargs += @('--prompt', $Prompt) }

        Write-Host "==> running $model ..."
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        & $exe @wargs | Out-Null
        $sw.Stop()

        $text = ''
        if (Test-Path ($prefix + '.txt')) {
            $text = Get-Content ($prefix + '.txt') -Raw -Encoding UTF8
        }
        $metrics = Get-WhisperMetrics ($prefix + '.json')
        $cer = $null
        if ($referenceText) {
            $hyp = Clean-Text $text
            $dist = Get-Levenshtein $referenceText $hyp
            $cer = if ($referenceText.Length -gt 0) {
                [math]::Round(100.0 * $dist / $referenceText.Length, 1)
            } else { $null }
        }

        $rtf = $null
        if ($audioSec -gt 0) {
            $rtf = [math]::Round($sw.Elapsed.TotalSeconds / $audioSec, 2)
        }
        $preview = $text
        if ($text.Length -gt 60) {
            $preview = $text.Substring(0, 60) + '...'
        }

        $results += [pscustomobject]@{
            Model     = $modelLabel
            Seconds   = [math]::Round($sw.Elapsed.TotalSeconds, 1)
            RTF       = $rtf
            Chars     = $text.Length
            AvgP      = $metrics.AvgP
            LowSegs   = $metrics.LowSegments
            SpeechSec = [math]::Round($metrics.SpeechMs / 1000.0, 1)
            CER       = $cer
            Preview   = $preview
        }
    }

    $results | Format-Table -AutoSize
    Write-Host "==> transcripts saved to: $OutDir"
}
finally {
    Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue
}
