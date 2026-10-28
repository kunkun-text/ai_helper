-- ============================================================================
-- 2026-10-28 · 视频答辩综合评价列（video_summary）
--
-- 背景：视频评分此前只有五维成绩单文本（video_analysis）；教师端/学生端
--       「回答详情」在视频场次下是空白（该场次通常 0 轮文字作答），
--       需要一段对**讲解内容**的整体总结（约 400~700 字），与五维点评不重复。
--       该文本由评分任务完成后的附加模型调用生成（云端优先，失败回落本地）；
--       生成/落库失败**不影响**五维成绩单。
--
-- 执行：mysql -uroot -p ai_helper < docs/ddl_video_summary.sql
-- 注意：MySQL 的 ADD COLUMN 不支持 IF NOT EXISTS，本脚本**只需执行一次**。
--       重复执行会报 1060 Duplicate column name，属正常现象。
--
-- ⚠️ 发布顺序硬约束：**必须先执行本 DDL，再重启后端**。
--    Mapper 已按新列查询，缺列会导致详情接口 500。
-- ============================================================================

ALTER TABLE `defense_records`
    ADD COLUMN `video_summary` text
        CHARACTER SET utf8mb4
        COLLATE utf8mb4_0900_ai_ci
        NULL
        COMMENT '基于视频转写全文的AI整体综合评价（约400~700字，教师/学生端「回答详情」展示）'
        AFTER `video_analysis`;

-- 复核：
-- SHOW COLUMNS FROM ai_helper.defense_records LIKE 'video_summary';
