-- ============================================================================
-- 报告评析：抽取全文落库（defense_records 加 1 列 report_text）— 2026-10-16
--
-- 用途：「模型到底看到了什么」。此前抽取出的文本**用完即弃**（DOC-2 原拍板为不落库），
--       代价是评分依据无法事后复核——学生申诉时说不出「当时模型收到的是哪份内容」。
--       对一份要当成绩单用的结果，这是可追溯性的短板，故补上。
--
-- 增量执行（存量库）：
--   mysql -uroot -p ai_helper < docs/ddl_report_text.sql
-- 新库直接导入 docs/schema.sql 即可（已含本列）。
--
-- ⚠️ 为什么用 MEDIUMTEXT 而不是 TEXT：
--    抽取上限 `app.report-doc.max-chars` 默认 60000 **字符**，中文 UTF-8 每字 3 字节
--    → 最坏约 180KB。而 MySQL 的 TEXT 上限是 65535 **字节**，会**静默截断**
--    （不报错、不告警），恰好把要复核的尾部内容切掉，违背本列的存在意义。
--    MEDIUMTEXT 上限 16MB，余量充足。
--
-- ⚠️ MySQL 的 ADD COLUMN **不支持 IF NOT EXISTS**，本脚本只能执行一次；
--    重复执行会报 Duplicate column name，属预期（说明已经加过了）。
--
-- 存储估算：单场最坏约 180KB（60k 中文字）。实测常见报告 3~10KB，
--    千场量级约 3~10MB，对 8G 低功耗机可接受。
-- ============================================================================

USE `ai_helper`;

ALTER TABLE `defense_records`
  ADD COLUMN `report_text` mediumtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci
      NULL COMMENT '从上传报告中抽取出的全文（供评分依据复核；抽取上限见 app.report-doc.max-chars）'
      AFTER `report_doc_analysis`;
