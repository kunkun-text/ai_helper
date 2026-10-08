package com.ai_helper.ai_helper.pojo.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 语音答辩逐轮归档记录（F11 · 2026-10-08）。
 *
 * <p>对应 {@code docs/schema.sql} 里早已存在、但此前零引用的 {@code voice_responses} 表 —— 本次首次启用，**无需任何 DDL 变更**。</p>
 *
 * <p>字段口径（遵循 `docs/P0-需求-语音答辩-2026-09-28.md` 第七节）：
 * {@code response_text} 存 **ASR 原始输出**，便于事后排查识别质量；
 * 真正送去评分的文字（学生可能编辑过）仍落在 {@code defense_answers.student_answer}，两者保持可比对。</p>
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class VoiceResponse {

    private Integer responseId;

    /** 关联 defense_records.defense_id */
    private Integer defenseId;

    /** 预设题时填 defense_questions.question_id；追问为 null */
    private Integer questionId;

    /** AI 本轮问的题（即语音播报的那段文本） */
    private String question;

    /** 学生语音转出的原始文字 */
    private String responseText;

    /** 录音文件 URL（走现有 /files/** 映射，原生支持 HTTP Range，可直接播放） */
    private String responseAudioUrl;

    private LocalDateTime responseTime;
}
