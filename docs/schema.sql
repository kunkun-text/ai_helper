-- =============================================================================
-- ai_helper 数据库表结构（仅表结构，不含任何数据）
--
-- 【用法】新机器首次部署：
--   1) 启动 MySQL，然后任选一种方式执行本文件：
--        mysql -uroot -p < docs/schema.sql
--      或在 Navicat / DataGrip / IDEA 里直接整段运行本文件
--   2) 本文件自带 CREATE DATABASE IF NOT EXISTS `ai_helper` 与 USE `ai_helper`，
--      不需要手工建库；库名 ai_helper 与代码/配置保持一致，不要改
--   3) 再按 src/main/resources/application.yml.example 配置数据库连接
--
-- 【注意】
--   - 本文件于 2026-09-17 用 mysqldump --no-data 导出，只含表结构，不含学生数据
--   - 文件内含 DROP TABLE IF EXISTS：对已存在的库重复执行会先删表，
--     数据会随之丢失，请不要在生产/有数据的库上重跑
--   - 三张历史备份表（*_bak_20260910 / *_bak_20260914）已剔除
--
-- 【重新导出】
--   mysqldump -uroot -p --no-data --default-character-set=utf8mb4 --set-gtid-purged=OFF ^
--     --databases ai_helper ^
--     --ignore-table=ai_helper.defense_records_bak_20260910 ^
--     --ignore-table=ai_helper.defense_topics_bak_20260914 ^
--     --ignore-table=ai_helper.defense_questions_bak_20260914 > docs/schema.sql
-- =============================================================================

-- MySQL dump 10.13  Distrib 9.6.0, for Win64 (x86_64)
--
-- Host: localhost    Database: ai_helper
-- ------------------------------------------------------
-- Server version	9.6.0

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;

--
-- Current Database: `ai_helper`
--

CREATE DATABASE /*!32312 IF NOT EXISTS*/ `ai_helper` /*!40100 DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci */ /*!80016 DEFAULT ENCRYPTION='N' */;

USE `ai_helper`;

--
-- Table structure for table `defense_answers`
--

DROP TABLE IF EXISTS `defense_answers`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `defense_answers` (
  `answer_id` int NOT NULL AUTO_INCREMENT,
  `defense_id` int NOT NULL,
  `question_id` int DEFAULT NULL,
  `sq_id` int DEFAULT NULL COMMENT '学生题目ID',
  `student_answer` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci COMMENT '学生回答',
  `score` decimal(5,2) DEFAULT NULL COMMENT 'AI评分',
  `feedback` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci COMMENT 'AI评价',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`answer_id`) USING BTREE,
  KEY `defense_id` (`defense_id`) USING BTREE,
  KEY `question_id` (`question_id`) USING BTREE,
  KEY `sq_id` (`sq_id`) USING BTREE,
  CONSTRAINT `defense_answers_ibfk_1` FOREIGN KEY (`defense_id`) REFERENCES `defense_records` (`defense_id`) ON DELETE CASCADE ON UPDATE RESTRICT,
  CONSTRAINT `defense_answers_ibfk_2` FOREIGN KEY (`question_id`) REFERENCES `defense_questions` (`question_id`) ON DELETE CASCADE ON UPDATE RESTRICT,
  CONSTRAINT `defense_answers_ibfk_3` FOREIGN KEY (`sq_id`) REFERENCES `defense_student_questions` (`sq_id`) ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE=InnoDB AUTO_INCREMENT=314 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `defense_questions`
--

DROP TABLE IF EXISTS `defense_questions`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `defense_questions` (
  `question_id` int NOT NULL AUTO_INCREMENT,
  `topic_id` int NOT NULL,
  `teacher_id` int DEFAULT NULL,
  `question_type` enum('teacher','ai') CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'teacher',
  `question` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `standard_answer` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci COMMENT '老师标准答案',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`question_id`) USING BTREE,
  KEY `topic_id` (`topic_id`) USING BTREE,
  KEY `teacher_id` (`teacher_id`) USING BTREE,
  CONSTRAINT `defense_questions_ibfk_1` FOREIGN KEY (`topic_id`) REFERENCES `defense_topics` (`topic_id`) ON DELETE CASCADE ON UPDATE RESTRICT,
  CONSTRAINT `defense_questions_ibfk_2` FOREIGN KEY (`teacher_id`) REFERENCES `users` (`user_id`) ON DELETE SET NULL ON UPDATE RESTRICT
) ENGINE=InnoDB AUTO_INCREMENT=77 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `defense_records`
--

DROP TABLE IF EXISTS `defense_records`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `defense_records` (
  `defense_id` int NOT NULL AUTO_INCREMENT,
  `user_id` int DEFAULT NULL,
  `topic_id` int DEFAULT NULL,
  `video_url` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL,
  `video_ppt_words` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci,
  `video_words` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci,
  `report_url` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL,
  `status` enum('pending','completed','graded') CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'pending',
  `feedback` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci,
  `video_analysis` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci,
  `report_analysis` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci,
  `score` decimal(5,2) DEFAULT NULL,
  `defense_date` datetime DEFAULT NULL,
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`defense_id`) USING BTREE,
  KEY `user_id` (`user_id`) USING BTREE,
  KEY `topic_id` (`topic_id`) USING BTREE,
  KEY `idx_user_topic` (`user_id`,`topic_id`),
  CONSTRAINT `defense_records_ibfk_1` FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`) ON DELETE CASCADE ON UPDATE RESTRICT,
  CONSTRAINT `defense_records_ibfk_2` FOREIGN KEY (`topic_id`) REFERENCES `defense_topics` (`topic_id`) ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE=InnoDB AUTO_INCREMENT=285 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `defense_score_record`
--

DROP TABLE IF EXISTS `defense_score_record`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `defense_score_record` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `defense_id` int NOT NULL COMMENT '答辩记录ID（关联 defense_records.defense_id）',
  `question_id` int DEFAULT NULL COMMENT '预设问题ID（关联 defense_questions.question_id，额外问题为NULL）',
  `round_num` int NOT NULL COMMENT '轮次序号（1-10预设题，额外题从11开始）',
  `expression_score` decimal(3,1) DEFAULT NULL COMMENT '表达能力得分（0-10）',
  `logic_score` decimal(3,1) DEFAULT NULL COMMENT '逻辑思维得分（0-10）',
  `professional_score` decimal(3,1) DEFAULT NULL COMMENT '专业水平得分（0-10）',
  `adaptability_score` decimal(3,1) DEFAULT NULL COMMENT '应变能力得分（0-10）',
  `innovation_score` decimal(3,1) DEFAULT NULL COMMENT '创新能力得分（0-10）',
  `comment` text COMMENT '本轮评语',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_defense_round` (`defense_id`,`round_num`),
  KEY `idx_defense_id` (`defense_id`)
) ENGINE=InnoDB AUTO_INCREMENT=294 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='答辩五维评分记录表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `defense_student_questions`
--

DROP TABLE IF EXISTS `defense_student_questions`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `defense_student_questions` (
  `sq_id` int NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `defense_id` int NOT NULL COMMENT '答辩记录ID',
  `question_id` int DEFAULT NULL COMMENT '公共题库ID',
  `custom_question` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci COMMENT 'AI生成题目',
  `custom_standard_answer` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci COMMENT 'AI标准答案',
  `question_type` enum('teacher','ai') CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '题目类型',
  `sort` int NOT NULL DEFAULT '0' COMMENT '提问顺序',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`sq_id`) USING BTREE,
  KEY `defense_id` (`defense_id`) USING BTREE,
  KEY `question_id` (`question_id`) USING BTREE,
  CONSTRAINT `dsq_defense_fk` FOREIGN KEY (`defense_id`) REFERENCES `defense_records` (`defense_id`) ON DELETE CASCADE ON UPDATE RESTRICT,
  CONSTRAINT `dsq_question_fk` FOREIGN KEY (`question_id`) REFERENCES `defense_questions` (`question_id`) ON DELETE SET NULL ON UPDATE RESTRICT
) ENGINE=InnoDB AUTO_INCREMENT=284 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `defense_topics`
--

DROP TABLE IF EXISTS `defense_topics`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `defense_topics` (
  `topic_id` int NOT NULL AUTO_INCREMENT,
  `teacher_id` int DEFAULT NULL,
  `topic_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `topic_description` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci,
  `defense_time` date DEFAULT NULL,
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `question_count` int NOT NULL DEFAULT '3',
  PRIMARY KEY (`topic_id`) USING BTREE,
  KEY `teacher_id` (`teacher_id`) USING BTREE,
  CONSTRAINT `defense_topics_ibfk_1` FOREIGN KEY (`teacher_id`) REFERENCES `users` (`user_id`) ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE=InnoDB AUTO_INCREMENT=31 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `system_settings`
--

DROP TABLE IF EXISTS `system_settings`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `system_settings` (
  `setting_id` int NOT NULL AUTO_INCREMENT,
  `setting_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `setting_value` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`setting_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `users`
--

DROP TABLE IF EXISTS `users`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `users` (
  `user_id` int NOT NULL AUTO_INCREMENT,
  `name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `user_number` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `role` enum('student','teacher') CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `email` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL,
  `phone_number` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL,
  `password` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL,
  `profile_picture` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL,
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`user_id`) USING BTREE,
  UNIQUE KEY `user_number` (`user_number`) USING BTREE,
  KEY `idx_user_number` (`user_number`) USING BTREE
) ENGINE=InnoDB AUTO_INCREMENT=24 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `voice_responses`
--

DROP TABLE IF EXISTS `voice_responses`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `voice_responses` (
  `response_id` int NOT NULL AUTO_INCREMENT,
  `defense_id` int DEFAULT NULL,
  `question_id` int DEFAULT NULL,
  `question` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL,
  `response_text` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci,
  `response_audio_url` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL,
  `response_time` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`response_id`) USING BTREE,
  KEY `defense_id` (`defense_id`) USING BTREE,
  KEY `question_id` (`question_id`) USING BTREE,
  CONSTRAINT `voice_responses_ibfk_1` FOREIGN KEY (`defense_id`) REFERENCES `defense_records` (`defense_id`) ON DELETE CASCADE ON UPDATE RESTRICT,
  CONSTRAINT `voice_responses_ibfk_2` FOREIGN KEY (`question_id`) REFERENCES `defense_questions` (`question_id`) ON DELETE SET NULL ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

-- Dump completed on 2026-09-17 17:35:54
