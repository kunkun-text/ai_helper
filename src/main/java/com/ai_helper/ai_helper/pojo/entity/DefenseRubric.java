package com.ai_helper.ai_helper.pojo.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 要点核验清单（N7 · 2026-10-14）。表 {@code defense_rubric}。
 *
 * <p>路线①「要点逐项核验」的判分锚点：每道预设题用「题目 + 标准答案」生成一份
 * 3~5 条可独立核对的要点清单，全体学生共用同一份。此前只放在
 * {@code chatController} 的进程内 Map（重启即丢、多实例不共享），现落库持久化，
 * 内存缓存降为一级缓存。</p>
 *
 * <p>建表语句见 {@code docs/ddl_defense_rubric.sql}（以及最新 {@code docs/schema.sql}）。
 * 表不存在时代码自动降级为纯内存缓存，不影响答辩。</p>
 */
@Data
public class DefenseRubric {

    /** 预设题ID（主键；关联 defense_questions.question_id，题目删除时级联删除） */
    private Integer questionId;

    /** 要点清单文本（模型输出的 JSON 字符串数组） */
    private String rubric;

    /** 来源：ai = 模型生成；teacher = 教师手改（预留） */
    private String source;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
