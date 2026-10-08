package com.ai_helper.ai_helper.pojo.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 答辩场次审计日志（N15 · 2026-10-08）。表 {@code defense_audit_log}。
 *
 * <p>每场答辩「开始 / 续答 / 结束」各落一条，用于事后复盘轮次对不上、被谁续答、结束时总分等问题。</p>
 */
@Data
public class DefenseAuditLog {

    private Long logId;
    private Integer defenseId;
    private Integer userId;
    private Integer topicId;

    /** 事件类型：START / RESUME / FINISH */
    private String event;

    /** 事件发生时的已完成轮次；FINISH 时为总轮次 */
    private Integer roundNum;

    /** 补充说明，如「结束总分 30.6」 */
    private String detail;

    private LocalDateTime createdAt;
}
