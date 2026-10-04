package com.ai_helper.ai_helper.Service.Impl;

import com.ai_helper.ai_helper.Service.DefenseRecordsService;
import com.ai_helper.ai_helper.Service.ScorePersistenceService;
import com.ai_helper.ai_helper.constant.AiProtocolConstants;
import com.ai_helper.ai_helper.mapper.DefenseScoreRecordMapper;
import com.ai_helper.ai_helper.pojo.entity.DefenseScoreRecord;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class ScorePersistenceServiceImpl implements ScorePersistenceService {

    @Resource
    private DefenseScoreRecordMapper scoreRecordMapper;

    @Resource
    private DefenseRecordsService defenseRecordsService;

    private static final String[] DIMENSIONS = {"表达", "逻辑", "专业", "应变", "创新"};
    private static final String[] DIM_KEYS = {"expression", "logic", "professional", "adaptability", "innovation"};

    @Override
    @Async("taskExecutor")
    public CompletableFuture<Boolean> saveRoundScoreAsync(DefenseScoreRecord record) {
        // 异步线程内执行实际写入；返回值供调用方按需感知成败（N21）
        return CompletableFuture.completedFuture(doSave(record));
    }

    @Override
    public void saveRoundScore(DefenseScoreRecord record) {
        doSave(record);
    }

    /**
     * 【N55 · 2026-10-02，B1 · 2026-10-05 强化】评分行 + 答案行同一事务。
     *
     * <p>历史实锤（2026-09-29 ROLLBACK_TEST 重演）：答案行被 CHECK 约束拒绝回滚时，
     * 异步线程的评分行已先行提交 → 「评分行落了、答案行没有」且前端无感。
     * 本方法把两行写库收进同一事务边界：评分行先写，随后执行答案写入；
     * 任一写入抛出的异常都会让整个事务回滚，保证「要么都落、要么都不落」。</p>
     *
     * <p><b>B1 强化</b>：评分行插入失败不再吞异常返回 false —— 事务方法内吞掉异常
     * 不会触发回滚，会留下「答案行落了、评分行没有」的另一半截数据；
     * 现在评分行插入失败同样抛 {@code IllegalStateException} 触发整体回滚。
     * 幂等跳过（同 defenseId+round 已存在）仍返回 true，属正常重复请求，不回滚。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean saveScoreThenAnswer(DefenseScoreRecord record, AnswerWriter answerWriter) {
        boolean scoreSaved = insertScoreRecordOrThrow(record);
        try {
            answerWriter.write();
        } catch (Exception e) {
            log.error("答案落库失败，评分行随事务一并回滚 - defenseId: {}, roundNum: {}",
                    record == null ? null : record.getDefenseId(),
                    record == null ? null : record.getRoundNum(), e);
            if (e instanceof RuntimeException re) {
                throw re;
            }
            throw new IllegalStateException("答案落库失败，评分行一并回滚", e);
        }
        return scoreSaved;
    }

    /**
     * 【B1】事务版评分行插入：失败抛异常（由事务回滚），幂等跳过返回 true。
     */
    private boolean insertScoreRecordOrThrow(DefenseScoreRecord record) {
        // 幂等：同一轮（defense_id + round_num）只落一行；并发场景由唯一索引 uk_defense_round 兜底
        if (record.getDefenseId() != null && record.getRoundNum() != null
                && scoreRecordMapper.countByDefenseIdAndRound(record.getDefenseId(), record.getRoundNum()) > 0) {
            log.warn("该轮评分已存在，跳过重复落库（幂等）- defenseId: {}, roundNum: {}",
                    record.getDefenseId(), record.getRoundNum());
            return true;
        }
        try {
            scoreRecordMapper.insertScoreRecord(record);
        } catch (Exception e) {
            log.error("保存评分失败（事务回滚，本轮评分与答案一并不落库） - defenseId: {}, roundNum: {}",
                    record.getDefenseId(), record.getRoundNum(), e);
            throw new IllegalStateException("评分落库失败，本轮评分与答案一并回滚", e);
        }
        log.info("保存评分成功 - defenseId: {}, roundNum: {}, 各维度: 表达={}, 逻辑={}, 专业={}, 应变={}, 创新={}",
                record.getDefenseId(), record.getRoundNum(),
                record.getExpressionScore(), record.getLogicScore(),
                record.getProfessionalScore(), record.getAdaptabilityScore(),
                record.getInnovationScore());
        return true;
    }

    /**
     * 实际落库。
     *
     * <p>失败不抛出（调用方可能是异步线程或答辩主流程），但必须留下 error 日志
     * 与明确的 false 返回值 —— 旧实现只打一行日志，上层完全无法感知落库失败。</p>
     */
    private boolean doSave(DefenseScoreRecord record) {
        try {
            // 【N4 · 2026-09-28】幂等：同一轮（defense_id + round_num）只落一行。
            // 前端重试会把同一轮请求重发，旧实现会重复落行 → 权威轮次计数 +1 → 提前一轮收尾。
            // 这里先查后插挡住重复；并发场景由 docs 迁移脚本里的唯一索引兜底。
            if (record.getDefenseId() != null && record.getRoundNum() != null
                    && scoreRecordMapper.countByDefenseIdAndRound(record.getDefenseId(), record.getRoundNum()) > 0) {
                log.warn("该轮评分已存在，跳过重复落库（幂等）- defenseId: {}, roundNum: {}",
                        record.getDefenseId(), record.getRoundNum());
                return true;
            }
            scoreRecordMapper.insertScoreRecord(record);
            log.info("保存评分成功 - defenseId: {}, roundNum: {}, 各维度: 表达={}, 逻辑={}, 专业={}, 应变={}, 创新={}",
                    record.getDefenseId(), record.getRoundNum(),
                    record.getExpressionScore(), record.getLogicScore(),
                    record.getProfessionalScore(), record.getAdaptabilityScore(),
                    record.getInnovationScore());
            return true;
        } catch (Exception e) {
            log.error("保存评分失败（该轮分数未落库，将影响轮次计数与总分） - defenseId: {}, roundNum: {}",
                    record.getDefenseId(), record.getRoundNum(), e);
            return false;
        }
    }

    @Override
    public Map<String, Object> parseScoresFromResponse(String aiResponse) {
        Map<String, Object> result = new HashMap<>();
        if (aiResponse == null || aiResponse.isEmpty()) {
            return result;
        }

        // 优先尝试管道格式: 得分/50|表达|逻辑|专业|应变|创新|优点|建议|下题
        if (aiResponse.contains("|") && !aiResponse.contains("【")) {
            return parsePipeScores(aiResponse, result);
        }

        // 旧格式：【表达】X分 【逻辑】X分 ...
        for (int i = 0; i < DIMENSIONS.length; i++) {
            String marker = "【" + DIMENSIONS[i] + "】";
            Double score = extractDimensionScore(aiResponse, marker);
            if (score != null) {
                result.put(DIM_KEYS[i], score);
            }
        }
        // 旧格式同样按五维之和给出总分，避免 legacyScore 回退到正则抓取无关"XX分"，与五维分叉
        if (result.containsKey("expression")) {
            double sum = 0;
            for (String k : DIM_KEYS) {
                Object v = result.get(k);
                sum += (v instanceof Number n) ? n.doubleValue() : 0.0;
            }
            result.put("totalScore", sum);
        }
        String comment = extractMarkedContent(aiResponse, "【评价】",
                new String[]{"【得分】", "【问题】", "【总结】", "【表达】", "【逻辑】", "【专业】", "【应变】", "【创新】"});
        if (comment == null || comment.isEmpty()) {
            comment = extractMarkedContent(aiResponse, "【评语】",
                    new String[]{"【表达】", "【逻辑】", "【专业】", "【应变】", "【创新】", "【问题】", "【总结】", "【总得分】"});
        }
        if (comment != null && !comment.isEmpty()) {
            result.put("comment", comment);
        }
        return result;
    }

    /** 解析管道格式：得分/50|表达|逻辑|专业|应变|创新|优点|建议|下题（或 3段式：评分:/点评:/下一题:） */
    private Map<String, Object> parsePipeScores(String aiResponse, Map<String, Object> result) {
        try {
            String[] lines = aiResponse.split("\n");
            // 先按标签提取 点评行 / 下一题行
            String commentLine = null;
            String nextLine = null;
            for (String line : lines) {
                String t = line.trim();
                if (t.startsWith(AiProtocolConstants.COMMENT_TAG) && commentLine == null) {
                    commentLine = t.substring(AiProtocolConstants.COMMENT_TAG.length()).trim();
                } else if (t.startsWith(AiProtocolConstants.NEXT_QUESTION_TAG) && nextLine == null) {
                    nextLine = t.substring(AiProtocolConstants.NEXT_QUESTION_TAG.length()).trim();
                }
            }
            // 找评分管道行（优先"评分:"前缀）
            String pipeLine = null;
            for (String line : lines) {
                String t = line.trim();
                if (t.startsWith(AiProtocolConstants.SCORE_TAG)) {
                    pipeLine = t;
                    break;
                }
            }
            if (pipeLine == null) {
                for (String line : lines) {
                    if (line.contains("|") && line.contains("/")) {
                        pipeLine = line.trim();
                        break;
                    }
                }
            }
            if (pipeLine == null) {
                for (String line : lines) {
                    if (line.contains("|")) {
                        pipeLine = line.trim();
                        break;
                    }
                }
            }
            if (pipeLine == null) {
                log.warn("管道格式解析失败：未找到管道行，返回默认值");
                return fillDefaults(result);
            }

            String[] parts = pipeLine.split("\\|");
            if (parts.length < 6) {
                log.warn("管道格式不完整，只有 {} 段，返回默认值: {}", parts.length, pipeLine);
                return fillDefaults(result);
            }

            // parts[0] = 得分/50 or 评分:得分/50
            if (parts.length >= 1) {
                String[] scoreParts = parts[0].split("/");
                if (scoreParts.length >= 1) {
                    try {
                        // 去掉"评分:"等非数字前缀
                        double score = Double.parseDouble(scoreParts[0].trim().replaceAll("[^0-9.]", ""));
                        result.put("totalScore", score);
                    } catch (NumberFormatException e) {
                        result.put("totalScore", 0.0);
                    }
                }
            }
            // parts[1-5] = 表达分|逻辑分|专业分|应变分|创新分
            String[] dims = {"expression", "logic", "professional", "adaptability", "innovation"};
            for (int i = 0; i < dims.length; i++) {
                try {
                    double val = (i + 1 < parts.length) ? Double.parseDouble(parts[i + 1].trim()) : 0.0;
                    result.put(dims[i], val);
                } catch (NumberFormatException e) {
                    result.put(dims[i], 0.0);
                }
            }
            // 强制一致性：各维度限制 0-10，总分 = 五维之和（防止 AI 乱给总分）
            double dimSum = 0;
            for (String dimKey : dims) {
                double v = ((Number) result.get(dimKey)).doubleValue();
                v = Math.max(0, Math.min(10, v));
                result.put(dimKey, v);
                dimSum += v;
            }
            result.put("totalScore", dimSum);
            // 点评：优先用"点评:"行，否则用管道内的 优点/建议 段
            if (commentLine != null && !commentLine.isEmpty()) {
                result.put("comment", commentLine);
            } else {
                StringBuilder comment = new StringBuilder();
                if (parts.length >= 7 && !parts[6].isEmpty() && !"无".equals(parts[6])) {
                    comment.append("优点:").append(parts[6]);
                }
                if (parts.length >= 8 && !parts[7].isEmpty() && !"无".equals(parts[7]) && parts[7].length() <= 10) {
                    if (comment.length() > 0) comment.append(" ");
                    comment.append("建议:").append(parts[7]);
                }
                if (comment.length() > 0) {
                    result.put("comment", comment.toString());
                } else {
                    result.put("comment", "无评价");
                }
            }
            // 下一题：优先用"下一题:"行，否则用管道第9段之后
            if (nextLine != null && !nextLine.isEmpty()) {
                result.put("question", nextLine);
            } else if (parts.length >= 9 && !parts[8].isEmpty() && !"无".equals(parts[8])) {
                StringBuilder question = new StringBuilder();
                for (int i = 8; i < parts.length; i++) {
                    if (question.length() > 0) question.append("|");
                    if (!parts[i].isEmpty() && !"无".equals(parts[i])) {
                        question.append(parts[i]);
                    }
                }
                if (question.length() > 0) {
                    result.put("question", question.toString());
                }
            }

            log.info("管道格式解析 - scores: {}", result);
        } catch (Exception e) {
            log.error("管道格式解析异常，返回默认值", e);
            return fillDefaults(result);
        }
        return result;
    }

    /** 填充默认评分，确保流程不中断 */
    private Map<String, Object> fillDefaults(Map<String, Object> result) {
        result.put("totalScore", 0.0);
        result.put("expression", 0.0);
        result.put("logic", 0.0);
        result.put("professional", 0.0);
        result.put("adaptability", 0.0);
        result.put("innovation", 0.0);
        result.put("comment", "解析失败");
        return result;
    }

    private Double extractDimensionScore(String text, String marker) {
        int idx = text.indexOf(marker);
        if (idx == -1) return null;

        int start = idx + marker.length();
        int end = Math.min(start + 10, text.length());
        String snippet = text.substring(start, end);

        Pattern p = Pattern.compile("(\\d+(?:\\.\\d+)?)");
        Matcher m = p.matcher(snippet);
        if (m.find()) {
            return Double.parseDouble(m.group(1));
        }
        return null;
    }

    private String extractMarkedContent(String text, String marker, String[] endMarkers) {
        int startIdx = text.indexOf(marker);
        if (startIdx == -1) return null;

        int contentStart = startIdx + marker.length();
        int contentEnd = text.length();

        for (String endMarker : endMarkers) {
            int idx = text.indexOf(endMarker, contentStart);
            if (idx != -1 && idx < contentEnd) {
                contentEnd = idx;
            }
        }

        return text.substring(contentStart, contentEnd).trim();
    }

    @Override
    public Map<String, Object> aggregateScores(Integer defenseId) {
        Map<String, Object> aggregated = scoreRecordMapper.aggregateScoresByDefenseId(defenseId);
        List<DefenseScoreRecord> records = scoreRecordMapper.getScoreRecordsByDefenseId(defenseId);

        if (aggregated == null) {
            aggregated = new HashMap<>();
        }
        aggregated.put("records", records);
        return aggregated;
    }

    @Override
    public String buildFinalEvaluatePrompt(Map<String, Object> aggregatedScores,
                                           List<DefenseScoreRecord> records) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是一名答辩考官，请根据以下学生答辩的量化评分数据，生成一段总体评价。\n\n");

        prompt.append("=== 各维度平均分（满分10分） ===\n");
        prompt.append("表达能力：").append(aggregatedScores.getOrDefault("avg_expression", "N/A")).append("分\n");
        prompt.append("逻辑思维：").append(aggregatedScores.getOrDefault("avg_logic", "N/A")).append("分\n");
        prompt.append("专业水平：").append(aggregatedScores.getOrDefault("avg_professional", "N/A")).append("分\n");
        prompt.append("应变能力：").append(aggregatedScores.getOrDefault("avg_adaptability", "N/A")).append("分\n");
        prompt.append("创新能力：").append(aggregatedScores.getOrDefault("avg_innovation", "N/A")).append("分\n");
        prompt.append("综合总分：").append(aggregatedScores.getOrDefault("avg_total", "N/A")).append("分\n\n");

        prompt.append("=== 各题评语汇总 ===\n");
        if (records != null) {
            for (DefenseScoreRecord r : records) {
                if (r.getComment() != null && !r.getComment().isEmpty()) {
                    String shortComment = r.getComment().length() > 80
                            ? r.getComment().substring(0, 80) + "..."
                            : r.getComment();
                    prompt.append("第").append(r.getRoundNum()).append("题：").append(shortComment).append("\n");
                }
            }
        }

        prompt.append("\n请用以下格式输出:\n");
        prompt.append("【总结】总体评价内容（100-200字）\n");
        prompt.append("【视频分析】对答辩视频表现的分析\n");
        prompt.append("【报告分析】对答辩报告的分析\n");
        prompt.append("【总得分】综合总分\n");

        return prompt.toString();
    }
}
