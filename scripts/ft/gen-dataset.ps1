<#
  AI-helper 微调数据生成（2026-11-12）

  用途：把 docs/ft/questions-bigdata.json 的每道题，生成 5 种学生回答形态 + 标准三行标签。
  依赖：无（PowerShell 5.1 + 系统代理即可），不调用本项目后端。
  密钥：只从环境变量 DEEPSEEK_API_KEY 读取，绝不写入磁盘。

  用法：
    $env:DEEPSEEK_API_KEY = "sk-xxx"
    powershell -ExecutionPolicy Bypass -File .\scripts\ft\gen-dataset.ps1 -Limit 5     # 先试 5 道题
    powershell -ExecutionPolicy Bypass -File .\scripts\ft\gen-dataset.ps1              # 全量

  产物：
    docs/ft/raw/{questionIndex}-{form}.json   每条原始结果（存在即跳过，可断点续跑）
    docs/ft/dataset/train.jsonl               训练集（9:1 按题分组切分）
    docs/ft/dataset/val.jsonl
    docs/ft/dataset/rejected.jsonl            校验失败的样本（含原因）
    docs/ft/dataset/stats.json
#>
[CmdletBinding()]
param(
    [string]$ApiKey = $env:DEEPSEEK_API_KEY,
    [string]$Model = "deepseek-chat",
    [int]$Limit = 0,              # 0 = 全部题目
    [int]$SleepMs = 400,          # 每条之间的间隔，防限流
    [string]$Root = ""
)

$ErrorActionPreference = "Stop"
$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)

# $PSScriptRoot 在 param 默认值里取不到（PS 5.1），必须在正文里算
if ([string]::IsNullOrWhiteSpace($Root)) {
    $Root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
}

if ([string]::IsNullOrWhiteSpace($ApiKey)) {
    throw "缺少 DEEPSEEK_API_KEY。请先设置环境变量：`$env:DEEPSEEK_API_KEY = 'sk-xxx'"
}

$QuestionsFile = Join-Path $Root "docs\ft\questions-bigdata.json"
$SystemFile    = Join-Path $Root "docs\ft\system-prompt.txt"
$RawDir        = Join-Path $Root "docs\ft\raw"
$OutDir        = Join-Path $Root "docs\ft\dataset"
New-Item -ItemType Directory -Force -Path $RawDir, $OutDir | Out-Null

$SystemPrompt = [System.IO.File]::ReadAllText($SystemFile, $Utf8NoBom).TrimEnd()
$Bank = [System.IO.File]::ReadAllText($QuestionsFile, $Utf8NoBom) | ConvertFrom-Json

# 合并三组题目，统一成 {topicName, question, keyPoints}
$all = New-Object System.Collections.ArrayList
foreach ($q in $Bank.existingFromDb)   { [void]$all.Add($q) }
foreach ($q in $Bank.newQuestions)     { [void]$all.Add($q) }
foreach ($q in $Bank.extendedQuestions) { [void]$all.Add($q) }

if ($Limit -gt 0) { $all = $all[0..([Math]::Min($Limit, $all.Count) - 1)] }
Write-Host ("题目总数: {0}" -f $all.Count)

# ---------------------------------------------------------------------------
# 五种回答形态（对齐 chatController 五档分数表）
# ---------------------------------------------------------------------------
$Forms = @(
    @{ name = "fully_correct"; marker = "[切题]"; min = 35; max = 50;
       brief = "完全正确：要点齐全、原理与方法正确，允许表述朴素或不够详细" },
    @{ name = "half_right";    marker = "[错误]"; min = 15; max = 30;
       brief = "半对半错：主体思路对，但有一半以上的核心内容说反、步骤错误或关键机制错" },
    @{ name = "all_wrong_relevant"; marker = "[错误]"; min = 5; max = 14;
       brief = "相关但全错：全程使用本题领域的术语，但定义、原理、方法从头错到尾" },
    @{ name = "irrelevant";    marker = "[跑题]"; min = 0;  max = 0;
       brief = "完全不相关：通篇在答另一道题或另一领域，或纯闲聊，或复制题目原文" },
    @{ name = "mostly_wrong_few_right"; marker = "[错误]"; min = 5; max = 20;
       brief = "少量正确掺杂大量错误：只有一两个点对，其余全是错误或无关内容" }
)

$GeneratorRules = @"
你在为一个大模型微调任务造数据。请扮演一名**本科学生**回答答辩题目。

硬性要求：
1. 只用学生口吻：可以有口语、口头禅、轻微的错别字或半截话，不要写成教科书段落，不要分点罗列得像标准答案。
2. 答案长度 60~300 字，必须像真人现场作答（不要加"回答："前缀，不要用 Markdown）。
3. 严格按指定的"正确/错误比例"造答案，不要偷偷写对。
4. 同时给出这道回答的**标准评判**（这是训练标签，必须与回答内容一致）：
   - comment：**连标记在内不超过 60 个汉字，越短越好**（超长会被丢弃），以指定标记开头，必须引用回答里出现过的具体名词/步骤/数据；不得写"思路清晰"这类放之四海皆准的空话。
     示例长度：[切题]以城市+年月为key、Combiner预聚合正确，未提缺失值过滤。（32字）
   - score_total：按指定分数区间给整数。
   - dims：表达/逻辑/专业/应变/创新五个 0~10 整数，**五维之和必须等于 score_total**。
   - next_question：30 字以内、与本题相关、不要重复本题原文。
5. 只输出 JSON，不要解释。格式：
{"answer":"...","comment":"...","score_total":0,"dims":[0,0,0,0,0],"next_question":"..."}
"@

function New-Prompt([object]$q, [hashtable]$form) {
    $kp = ($q.keyPoints -join "；")
    return @"
【课题】$($q.topicName)
【题目】$($q.question)
【本题正确要点】$kp
【本样本要造的回答形态】$($form.name)：$($form.brief)
【标记要求】$($form.marker)
【总分要求】$($form.min) ~ $($form.max)（区间内取整数；五维相加必须等于总分）
"@
}

function Invoke-DeepSeek([string]$prompt) {
    $body = @{
        model = $Model
        temperature = 0.7
        max_tokens = 900
        response_format = @{ type = "json_object" }
        messages = @(
            @{ role = "system"; content = $GeneratorRules },
            @{ role = "user";   content = $prompt }
        )
    } | ConvertTo-Json -Depth 6

    $headers = @{ "Authorization" = "Bearer $ApiKey" }
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($body)
    # 【坑】PS 5.1 的 Invoke-RestMethod 在响应头没有 charset 时按 Latin-1 解码，
    # 中文会变成 "æä»¬" 这种乱码，导致 [切题] 校验永远不过。
    # 改用 Invoke-WebRequest 取原始字节，显式按 UTF-8 解码。
    $wr = Invoke-WebRequest -Uri "https://api.deepseek.com/chat/completions" -Method Post `
        -Headers $headers -ContentType "application/json; charset=utf-8" -Body $bytes `
        -TimeoutSec 120 -UseBasicParsing
    $json = [System.Text.Encoding]::UTF8.GetString($wr.RawContentStream.ToArray())
    $resp = $json | ConvertFrom-Json
    return $resp.choices[0].message.content
}

function Test-Sample([object]$s, [hashtable]$form) {
    $errs = @()
    if (-not $s.answer -or $s.answer.Trim().Length -lt 20) { $errs += "answer 过短" }
    if (-not $s.comment) { $errs += "comment 为空" }
    elseif (-not $s.comment.StartsWith($form.marker)) { $errs += "comment 未以 $($form.marker) 开头" }
    elseif ($s.comment.Length -gt 80) { $errs += "comment 超长(" + $s.comment.Length + "字)" }
    if ($null -eq $s.dims -or $s.dims.Count -ne 5) { $errs += "dims 不是 5 个" }
    else {
        foreach ($d in $s.dims) { if ($d -lt 0 -or $d -gt 10) { $errs += "dims 越界" } }
        $sum = ($s.dims | Measure-Object -Sum).Sum
        if ($sum -ne [int]$s.score_total) { $errs += "五维之和($sum) != 总分($($s.score_total))" }
    }
    if ($s.score_total -lt $form.min -or $s.score_total -gt $form.max) {
        $errs += "总分 $($s.score_total) 不在 $($form.min)~$($form.max)"
    }
    if ($form.name -eq "irrelevant") {
        if ([int]$s.score_total -ne 0) { $errs += "跑题必须 0 分" }
    }
    if (-not $s.next_question -or $s.next_question.Trim().Length -lt 4) { $errs += "next_question 为空" }
    return $errs
}

function New-AssistantText([object]$s, [hashtable]$form) {
    $d = $s.dims -join "|"
    return "点评:$($s.comment)`n评分:$([int]$s.score_total)/50|$d`n下一题:$($s.next_question.Trim())"
}

$stats = @{ generated = 0; skipped = 0; rejected = 0; byForm = @{} }
$train = New-Object System.Collections.ArrayList
$val   = New-Object System.Collections.ArrayList
$rej   = New-Object System.Collections.ArrayList

for ($i = 0; $i -lt $all.Count; $i++) {
    $q = $all[$i]
    foreach ($form in $Forms) {
        $rawPath = Join-Path $RawDir ("{0:D3}-{1}.json" -f $i, $form.name)
        if (Test-Path $rawPath) { $stats.skipped++; continue }

        $prompt = New-Prompt $q $form
        $obj = $null
        $errs = @()
        for ($try = 1; $try -le 3; $try++) {
            try {
                $txt = Invoke-DeepSeek $prompt
                $cand = $txt | ConvertFrom-Json
                $e = Test-Sample $cand $form
                if ($e.Count -eq 0) { $obj = $cand; $errs = @(); break }
                $errs = $e
                Write-Warning ("[{0}/{1}] {2} 第 {3} 次校验不过: {4}" -f $i, $q.question.Substring(0, [Math]::Min(12, $q.question.Length)), $form.name, $try, ($e -join "; "))
            } catch {
                Write-Warning ("[{0}/{1}] {2} 第 {3} 次请求失败: {4}" -f $i, $q.question.Substring(0, [Math]::Min(12, $q.question.Length)), $form.name, $try, $_.Exception.Message)
            }
            Start-Sleep -Seconds (2 * $try)
        }
        if ($null -eq $obj) {
            if ($errs.Count -gt 0) {
                [void]$rej.Add([pscustomobject]@{ question = $q.question; form = $form.name; errors = $errs })
            }
            $stats.rejected++
            Start-Sleep -Milliseconds $SleepMs
            continue
        }

        [System.IO.File]::WriteAllText($rawPath, ($obj | ConvertTo-Json -Depth 6), $Utf8NoBom)
        $stats.generated++
        if (-not $stats.byForm.ContainsKey($form.name)) { $stats.byForm[$form.name] = 0 }
        $stats.byForm[$form.name]++

        $userMsg = "【课题】$($q.topicName)`n【当前题】$($q.question)`n【进度】第 1 题/共 5 题，已追问 0/5 次`n【学生回答】$($obj.answer)"
        $sample = [ordered]@{
            messages = @(
                @{ role = "system";    content = $SystemPrompt },
                @{ role = "user";      content = $userMsg },
                @{ role = "assistant"; content = (New-AssistantText $obj $form) }
            )
            meta = @{ form = $form.name; topicName = $q.topicName; question = $q.question }
        }
        # 按题分组切分：每 10 题留 1 题做验证，避免同题泄漏
        if (($i % 10) -eq 9) { [void]$val.Add($sample) } else { [void]$train.Add($sample) }

        Start-Sleep -Milliseconds $SleepMs
    }
    Write-Host ("[{0}/{1}] 完成，已生成 {2} 条，拒绝 {3} 条" -f ($i + 1), $all.Count, $stats.generated, $stats.rejected)
}

function Write-Jsonl($list, [string]$path) {
    $lines = foreach ($o in $list) { $o | ConvertTo-Json -Depth 8 -Compress }
    [System.IO.File]::WriteAllText($path, (($lines -join "`n") + "`n"), $Utf8NoBom)
}
Write-Jsonl $train (Join-Path $OutDir "train.jsonl")
Write-Jsonl $val   (Join-Path $OutDir "val.jsonl")
Write-Jsonl $rej   (Join-Path $OutDir "rejected.jsonl")
[System.IO.File]::WriteAllText((Join-Path $OutDir "stats.json"), ($stats | ConvertTo-Json -Depth 5), $Utf8NoBom)

Write-Host ""
Write-Host ("=== 完成 ===  生成 {0} / 跳过 {1} / 拒绝 {2}" -f $stats.generated, $stats.skipped, $stats.rejected)
Write-Host ("训练集 {0} 条，验证集 {1} 条" -f $train.Count, $val.Count)
Write-Host ("产物目录: {0}" -f $OutDir)
