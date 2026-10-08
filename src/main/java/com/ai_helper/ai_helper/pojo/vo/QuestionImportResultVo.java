package com.ai_helper.ai_helper.pojo.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 题库批量导入结果（N10 · 2026-10-08）。
 *
 * <p>面向教师端反馈：成功多少条、跳过多少条、为什么跳过。跳过原因只回传前若干条，
 * 避免一个坏文件把响应撑爆。</p>
 */
@Data
public class QuestionImportResultVo {

    /** 成功导入条数 */
    private int imported;

    /** 跳过条数（题目为空 / 超长 / 与已有题目重复等） */
    private int skipped;

    /** 是否为「覆盖导入」（覆盖 = 先清空该课题原有题目再导入） */
    private boolean replaced;

    /** 跳过原因样例，形如「第 3 行：题目为空」 */
    private List<String> skippedReasons = new ArrayList<>();
}
