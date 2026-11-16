<#
  行为样本生成（2026-11-16 · v2 迭代）

  只造两类样本，不调 API、零成本、格式绝对一致：
    ① give_up   ：学生答「我不会/不知道」→ 必须 [跑题] 0 分，且**不生成追问**，直接给下一道预设题
    ② final_round：最后一轮 → 第三行必须是「总结:」，禁止「下一题:」

  输入：docs/ft/dataset/train.jsonl（借 fully_correct 的答案）+ docs/ft/questions-bigdata.json
  输出：docs/ft/dataset/behavior.jsonl
#>
[CmdletBinding()]
param(
    [int]$GiveUpCount = 50,
    [int]$FinalCount = 50,
    [string]$Root = ""
)

$ErrorActionPreference = "Stop"
$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
if ([string]::IsNullOrWhiteSpace($Root)) {
    $Root = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
}

$DataDir = Join-Path $Root "docs\ft\dataset"
$BankFile = Join-Path $Root "docs\ft\questions-bigdata.json"
$SystemPrompt = [System.IO.File]::ReadAllText((Join-Path $Root "docs\ft\system-prompt.txt"), $Utf8NoBom).TrimEnd()
$OutFile = Join-Path $DataDir "behavior.jsonl"

# ---------- 题库：按 topicName 分组，用于「下一道预设题」 ----------
$Bank = [System.IO.File]::ReadAllText($BankFile, $Utf8NoBom) | ConvertFrom-Json
$all = New-Object System.Collections.ArrayList
foreach ($q in $Bank.existingFromDb)    { [void]$all.Add($q) }
foreach ($q in $Bank.newQuestions)     { [void]$all.Add($q) }
foreach ($q in $Bank.extendedQuestions) { [void]$all.Add($q) }

$byTopic = @{}
foreach ($q in $all) {
    if (-not $byTopic.ContainsKey($q.topicName)) { $byTopic[$q.topicName] = New-Object System.Collections.ArrayList }
    [void]$byTopic[$q.topicName].Add($q)
}

function Get-NextQuestion([object]$q) {
    $list = $byTopic[$q.topicName]
    for ($i = 0; $i -lt $list.Count; $i++) {
        if ($list[$i].question -eq $q.question) {
            if ($i + 1 -lt $list.Count) { return $list[$i + 1].question }
            return $list[0].question
        }
    }
    return $all[0].question
}

# ---------- ① give_up ----------
$GiveUpReplies = @("我不会", "不知道", "这个我没学过", "不太清楚", "嗯……不会", "没准备这道题")
$GiveUpComments = @(
    "[跑题]回答未涉及本题任何内容，未正面作答。",
    "[跑题]仅表示不会，未提供任何实质内容。",
    "[跑题]通篇为放弃作答的表述，与本题无关。",
    "[跑题]没有回答本题所问，仅说明不会。",
    "[跑题]未展开任何技术内容，无法评分。"
)
$rows = New-Object System.Collections.ArrayList

for ($i = 0; $i -lt [Math]::Min($GiveUpCount, $all.Count); $i++) {
    $q = $all[$i * 2 % $all.Count]
    $reply = $GiveUpReplies[$i % $GiveUpReplies.Count]
    $comment = $GiveUpComments[$i % $GiveUpComments.Count]
    $next = Get-NextQuestion $q
    $user = "【课题】$($q.topicName)`n【当前题】$($q.question)`n【进度】第 1 题/共 5 题，已追问 0/5 次`n【学生回答】$reply"
    $assistant = "点评:$comment`n评分:0/50|0|0|0|0|0`n下一题:$next"
    [void]$rows.Add([ordered]@{
        messages = @(
            @{ role = "system";    content = $SystemPrompt },
            @{ role = "user";      content = $user },
            @{ role = "assistant"; content = $assistant }
        )
        meta = @{ form = "give_up"; topicName = $q.topicName; question = $q.question }
    })
}

# ---------- ② final_round：借已生成的 fully_correct 样本 ----------
$trainLines = [System.IO.File]::ReadAllLines((Join-Path $DataDir "train.jsonl"), $Utf8NoBom)
$fully = @()
foreach ($line in $trainLines) {
    if ([string]::IsNullOrWhiteSpace($line)) { continue }
    $o = $line | ConvertFrom-Json
    if ($o.meta.form -eq "fully_correct") { $fully += $o }
}

$Summaries = @(
    "整体表达清晰，核心机制掌握较好，建议补充边界场景与量化数据。",
    "思路完整、术语使用准确，若能结合项目数据说明效果会更有说服力。",
    "主要环节都讲到了，建议加强对异常情况与性能瓶颈的分析。",
    "回答有条理，但对实现细节的说明还比较笼统，可再展开一层。",
    "基础概念扎实，建议补充与同类方案的对比及取舍理由。"
)

for ($i = 0; $i -lt [Math]::Min($FinalCount, $fully.Count); $i++) {
    $src = $fully[$i]
    $user = $src.messages[1].content + "`n【本轮为最后一轮】"
    $parts = $src.messages[2].content -split "`n"
    $commentLine = $parts[0]
    $scoreLine = $parts[1]
    $summary = $Summaries[$i % $Summaries.Count]
    $assistant = "$commentLine`n$scoreLine`n总结:$summary"
    [void]$rows.Add([ordered]@{
        messages = @(
            @{ role = "system";    content = $SystemPrompt },
            @{ role = "user";      content = $user },
            @{ role = "assistant"; content = $assistant }
        )
        meta = @{ form = "final_round"; topicName = $src.meta.topicName; question = $src.meta.question }
    })
}

$lines = foreach ($o in $rows) { $o | ConvertTo-Json -Depth 8 -Compress }
[System.IO.File]::WriteAllText($OutFile, (($lines -join "`n") + "`n"), $Utf8NoBom)

Write-Host ("[done] 行为样本 {0} 条 -> {1}" -f $rows.Count, $OutFile)
Write-Host ("       give_up {0} / final_round {1}" -f ([Math]::Min($GiveUpCount, $all.Count)), ([Math]::Min($FinalCount, $fully.Count)))
