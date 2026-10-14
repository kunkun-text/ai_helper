-- ============================================================================
-- 要点核验清单表（defense_rubric）— N7 · 2026-10-14
--
-- 用途：路线①「要点逐项核验」的清单持久化。此前只在 chatController 的进程内
--       ConcurrentHashMap 里缓存（重启即丢、多实例不共享）；落库后全体学生复用，
--       也为将来「教师手改要点」留出 source 字段。
--
-- 增量执行（存量库）：
--   mysql -uroot -p ai_helper < docs/ddl_defense_rubric.sql
-- 新库直接导入 docs/schema.sql 即可（已含本表）。
--
-- 安全性：表不存在时 chatController 会自动降级为纯内存缓存（只记日志、不报错），
--         因此本脚本可随时执行，不会阻塞答辩。
-- ============================================================================

CREATE TABLE IF NOT EXISTS `defense_rubric` (
  `question_id` int NOT NULL COMMENT '预设题ID（关联 defense_questions.question_id）',
  `rubric` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '要点清单（模型生成的 JSON 字符串数组）',
  `source` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT 'ai' COMMENT '来源：ai=模型生成 / teacher=教师手改（预留）',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`question_id`),
  CONSTRAINT `defense_rubric_ibfk_1` FOREIGN KEY (`question_id`) REFERENCES `defense_questions` (`question_id`) ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='要点核验清单（路线①要点逐项核验）';
