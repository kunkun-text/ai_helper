-- ============================================================================
-- 2026-10-18 · 报告 AI 综合评价列（report_doc_summary）
--
-- 背景：报告评析此前只有五维成绩单文本；教师端「查看反馈」需要一段
--       AI 对整份报告的整体评价（约 400~700 字）。该文本由评析任务的
--       第三次模型调用生成，与五维成绩单分开落库。
--
-- 执行：mysql -uroot -p ai_helper < docs/ddl_report_doc_summary.sql
-- 注意：MySQL 的 ADD COLUMN 不支持 IF NOT EXISTS，本脚本**只需执行一次**。
--       重复执行会报 1060 Duplicate column name，属正常现象。
-- ============================================================================

ALTER TABLE `defense_records`
    ADD COLUMN `report_doc_summary` TEXT
        CHARACTER SET utf8mb4
        COLLATE utf8mb4_0900_ai_ci
        NULL
        COMMENT '基于报告全文的AI整体综合评价（约400~700字，教师端「查看反馈」展示）'
        AFTER `report_doc_analysis`;

-- 复核：
-- SHOW COLUMNS FROM ai_helper.defense_records LIKE 'report_doc_summary';
-- 然后运行 diagnose.ps1，应显示「答辩记录扩展列（...）6 列齐全」。
