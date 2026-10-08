-- ==========================================================================
-- 语音答辩录音归档表 voice_responses（F11 · 2026-10-08）
--
-- 【新库】不需要本脚本：最新的 docs/schema.sql 里已经包含这张表（它是 10 张必需表之一），直接导入即可。
-- 【存量库】只有在你库里确实缺这张表时才执行（可安全重复执行）：
--     mysql -uroot -p ai_helper < docs/ddl_voice_responses.sql
-- 判断方法：SHOW TABLES FROM ai_helper LIKE 'voice_responses'; 或跑 diagnose.ps1 看是否报「缺少 voice_responses」。
--
-- 用途：学生每轮语音作答的**原始录音**归档（URL 走现有 /files/** 静态映射，可直接回放），
--       供学生本人在「答题记录 → 回答详情」以及教师在「答辩记录 → 回答详情」里回放。
--
-- 字段口径（见 docs/P0-需求-语音答辩-2026-09-28.md 第七节）：
--   response_text      = ASR 原始识别文字（学生可编辑后再发送，送去评分的那份落在 defense_answers.student_answer）
--   response_audio_url = /files/voice/xxx.mp3 这类相对 URL
-- ==========================================================================

CREATE TABLE IF NOT EXISTS `voice_responses` (
  `response_id` int NOT NULL AUTO_INCREMENT,
  `defense_id` int DEFAULT NULL COMMENT '关联 defense_records.defense_id',
  `question_id` int DEFAULT NULL COMMENT '预设题时填 defense_questions.question_id；追问为 NULL',
  `question` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT 'AI 本轮问的题（语音播报的那段文本）',
  `response_text` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci COMMENT 'ASR 原始识别文字',
  `response_audio_url` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '录音相对 URL',
  `response_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`response_id`) USING BTREE,
  KEY `defense_id` (`defense_id`) USING BTREE,
  KEY `question_id` (`question_id`) USING BTREE,
  CONSTRAINT `voice_responses_ibfk_1` FOREIGN KEY (`defense_id`) REFERENCES `defense_records` (`defense_id`) ON DELETE CASCADE ON UPDATE RESTRICT,
  CONSTRAINT `voice_responses_ibfk_2` FOREIGN KEY (`question_id`) REFERENCES `defense_questions` (`question_id`) ON DELETE SET NULL ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;
