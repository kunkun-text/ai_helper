-- ============================================================================
-- 上传报告文档的 AI 评析字段（defense_records 加 4 列）— 2026-10-16
--
-- 用途：「上传答辩报告 → AI 五维评分」第一期。此前 video_analysis / report_analysis
--       两列的内容是答辩收尾时从模型回复里按【报告分析】截字符串得到的，
--       没有任何真实文件内容作为输入（等于幻觉段落）。本次新增独立列承载
--       **基于真实文档文本**的评析结果。
--
-- 增量执行（存量库）：
--   mysql -uroot -p ai_helper < docs/ddl_report_doc_analysis.sql
-- 新库直接导入 docs/schema.sql 即可（已含这 4 列）。
--
-- ⚠️ 发布顺序硬约束：教师端答辩记录列表的查询会读 analysis_source 与
--    report_doc_score。**未执行本脚本就发代码，记录列表会整页报
--    Unknown column 'dr.analysis_source'**。请先执行本脚本、再更新后端。
--    执行后可用 `powershell -ExecutionPolicy Bypass -File .\diagnose.ps1` 复核。
--
-- ⚠️ MySQL 的 ADD COLUMN **不支持 IF NOT EXISTS**，本脚本只能执行一次；
--    重复执行会报 Duplicate column name，属预期（说明已经加过了）。
--
-- 为什么加列而不是新建表：这 4 列都是「一场答辩记录」的附属属性，
--    与本表其余列同生命周期（记录删则字段删），拆表反而要额外维护关联与级联。
-- ============================================================================

USE `ai_helper`;

ALTER TABLE `defense_records`
  ADD COLUMN `report_doc_analysis` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci
      NULL COMMENT '基于上传报告文档的AI五维评析（区别于模型凭空写的 report_analysis）'
      AFTER `report_analysis`,
  ADD COLUMN `report_doc_score` decimal(5,2)
      NULL COMMENT '上传报告AI评析总分(0-50)，供教师端排序/统计/导出'
      AFTER `report_doc_analysis`,
  ADD COLUMN `defense_mode` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci
      NULL COMMENT '答辩形式：text=文字/voice=语音；历史场次为空（不回填猜测）'
      AFTER `report_doc_score`,
  ADD COLUMN `analysis_source` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci
      NULL COMMENT '评析来源：report=基于上传报告；预留 video'
      AFTER `defense_mode`;
