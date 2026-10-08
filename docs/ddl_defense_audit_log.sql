-- ==========================================================================
-- 答辩场次审计日志表 defense_audit_log（N15 · 2026-10-08）
--
-- 【新库】不需要本脚本：最新 docs/schema.sql 已包含本表（共 11 张表）。
-- 【存量库】执行本脚本补表（可安全重复执行）：
--     mysql -uroot -p ai_helper < docs/ddl_defense_audit_log.sql
--
-- 用途：每场答辩「开始 / 续答 / 结束」各落一条记录，用于事后复盘
--       （例如：某场次为什么轮次对不上、什么时候被续答、结束时的总分是多少）。
--
-- 设计取舍：
--   1. defense_id 不做外键、不级联删除 —— 审计记录要在答辩记录被删后仍可追溯，
--      因此只存 id 快照，不建立外键约束。
--   2. 写入一律「尽力而为」：审计失败绝不影响答辩主流程（Service 内已 try/catch 吞掉）。
-- ==========================================================================

CREATE TABLE IF NOT EXISTS `defense_audit_log` (
  `log_id` bigint NOT NULL AUTO_INCREMENT,
  `defense_id` int DEFAULT NULL COMMENT '答辩场次 defense_records.defense_id',
  `user_id` int DEFAULT NULL COMMENT 'users.user_id（谁在答辩）',
  `topic_id` int DEFAULT NULL COMMENT '课题 ID',
  `event` varchar(32) NOT NULL COMMENT 'START=新建并开始 / RESUME=续答 / FINISH=结束并汇总',
  `round_num` int DEFAULT NULL COMMENT '事件发生时的已完成轮次（START/RESUME）或总轮次（FINISH）',
  `detail` varchar(500) DEFAULT NULL COMMENT '补充说明（如"续答"、"结束总分 30.6"）',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`log_id`) USING BTREE,
  KEY `idx_defense_id` (`defense_id`) USING BTREE,
  KEY `idx_created_at` (`created_at`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;
