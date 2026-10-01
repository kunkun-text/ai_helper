-- =============================================================================
-- 系统反馈表（system_feedback）— 2026-10-01 新增
--
-- 【用途】学生 / 教师向系统提交反馈意见，教师端统一查看与回复。
--         借鉴 smart-medicine 的「系统反馈」模块，与答辩主链路完全隔离。
--
-- 【执行】在 ai_helper 库中执行本文件即可（幂等，可重复执行）：
--   mysql -uroot -p ai_helper < docs/ddl_system_feedback.sql
--   或在 Navicat / DataGrip 里直接整段运行
--
-- 【注意】本表为新增表，不改动任何已有表结构；不执行本文件时，
--         反馈模块的接口会在「表不存在」时报错，其它功能不受影响。
-- =============================================================================

USE `ai_helper`;

CREATE TABLE IF NOT EXISTS `system_feedback` (
  `feedback_id` int NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id`     int DEFAULT NULL COMMENT '提交人 users.user_id',
  `user_number` varchar(50)  DEFAULT NULL COMMENT '提交人学号/工号（冗余，便于展示）',
  `user_name`   varchar(255) DEFAULT NULL COMMENT '提交人姓名（冗余，便于展示）',
  `role`        varchar(20)  DEFAULT NULL COMMENT '提交人角色 student/teacher',
  `content`     text NOT NULL COMMENT '反馈内容',
  `contact`     varchar(255) DEFAULT NULL COMMENT '联系方式（可选）',
  `status`      varchar(20) NOT NULL DEFAULT 'pending' COMMENT 'pending 待处理 / resolved 已回复',
  `reply`       text COMMENT '教师回复内容',
  `created_at`  datetime DEFAULT CURRENT_TIMESTAMP COMMENT '提交时间',
  `updated_at`  datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`feedback_id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_user_number` (`user_number`),
  KEY `idx_status` (`status`),
  -- 教师端列表默认按状态过滤 + created_at 倒序分页（selectAll 的实际查询形态）
  KEY `idx_status_created` (`status`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='系统反馈表';
