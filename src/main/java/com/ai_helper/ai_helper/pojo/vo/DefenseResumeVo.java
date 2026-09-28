package com.ai_helper.ai_helper.pojo.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 「开始 / 继续答辩」的结果（2026-09-28 N1）。
 *
 * <p>学生中途退出后再进答辩页时：若存在「已作答但未完成（pending）」的记录，
 * 不再新建记录，而是返回本对象描述如何续答 —— 已答多少轮、当前该答哪道题、
 * 以及前几轮的历史（供回灌 Redis 会话记忆，让模型有上下文）。
 * 没有任何作答时按原有逻辑清理空壳并新建记录，{@link #resumed} 为 false。</p>
 */
@Data
public class DefenseResumeVo {

    /** true = 已续答既有记录；false = 全新答辩（调用方按原逻辑从第 1 题开始） */
    private boolean resumed;

    /** true = 存在「可续答」的场次（只读探测接口用，此时尚未真正续答） */
    private boolean resumable;

    /** 答辩记录 ID（新建时为新记录的 ID） */
    private Integer defenseId;

    /** 已完成作答的轮数 */
    private int answeredCount;

    /** 当前待答轮次（= answeredCount + 1） */
    private int roundNum;

    /** 本场总轮次（预设题数 + 追问额度） */
    private int totalRounds;

    /** 当前待答的题目文本；取不到时为 null（前端提示「继续作答」即可，不阻塞流程） */
    private String currentQuestion;

    /** 历史轮次（按作答顺序），续答时用于回灌会话记忆 */
    private List<Round> rounds = new ArrayList<>();

    /** 单轮历史 */
    @Data
    public static class Round {
        private int roundNum;
        /** 本轮题目（预设题取题库；追问取登记过的追问题） */
        private String question;
        /** 学生本轮回答 */
        private String studentAnswer;
        /** 本轮点评（服务端最终文案，已去标记） */
        private String comment;
        /** 总分 = 五维之和 */
        private Double totalScore;
        private Double expression;
        private Double logic;
        private Double professional;
        private Double adaptability;
        private Double innovation;
    }
}
