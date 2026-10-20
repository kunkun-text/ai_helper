# Windows built-in OCR bridge (2026-10-20).
# ASCII only on purpose: PowerShell 5.1 reads BOM-less .ps1 as ANSI,
# so any non-ASCII character here would be mangled.
#
# Usage:
#   powershell -File windows-ocr.ps1 -Probe -Out languages.txt
#   powershell -File windows-ocr.ps1 -Path image.png -Out result.txt
param(
    [string]$Path,
    [string]$Out,
    [switch]$Probe
)

$ErrorActionPreference = 'Stop'

$null = [Windows.Media.Ocr.OcrEngine, Windows.Foundation, ContentType = WindowsRuntime]
$null = [Windows.Storage.StorageFile, Windows.Storage, ContentType = WindowsRuntime]
$null = [Windows.Graphics.Imaging.BitmapDecoder, Windows.Graphics.Imaging, ContentType = WindowsRuntime]

if ($Probe) {
    $langs = [Windows.Media.Ocr.OcrEngine]::AvailableRecognizerLanguages |
        ForEach-Object { $_.LanguageTag }
    [System.IO.File]::WriteAllLines($Out, $langs, [System.Text.Encoding]::UTF8)
    exit 0
}

Add-Type -AssemblyName System.Runtime.WindowsRuntime

$asTaskGeneric = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
    $_.Name -eq 'AsTask' -and
    $_.GetParameters().Count -eq 1 -and
    $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
})[0]

function Await($WinRtTask, $ResultType) {
    $asTask = $asTaskGeneric.MakeGenericMethod($ResultType)
    $netTask = $asTask.Invoke($null, @($WinRtTask))
    $netTask.Wait(-1) | Out-Null
    $netTask.Result
}

$file = Await ([Windows.Storage.StorageFile]::GetFileFromPathAsync($Path)) ([Windows.Storage.StorageFile])
$stream = Await ($file.OpenAsync([Windows.Storage.FileAccessMode]::Read)) ([Windows.Storage.Streams.IRandomAccessStream])
$decoder = Await ([Windows.Graphics.Imaging.BitmapDecoder]::CreateAsync($stream)) ([Windows.Graphics.Imaging.BitmapDecoder])
$bitmap = Await ($decoder.GetSoftwareBitmapAsync()) ([Windows.Graphics.Imaging.SoftwareBitmap])

$engine = [Windows.Media.Ocr.OcrEngine]::TryCreateFromUserProfileLanguages()
if ($null -eq $engine) {
    exit 2
}

$result = Await ($engine.RecognizeAsync($bitmap)) ([Windows.Media.Ocr.OcrResult])
$lines = @($result.Lines | ForEach-Object { $_.Text })
[System.IO.File]::WriteAllLines($Out, $lines, [System.Text.Encoding]::UTF8)
exit 0
