-- =============================================================================
-- 迁移脚本：评分表幂等约束（需求清单 N4）
-- 日期：2026-09-28
--
-- 【目的】同一场答辩的同一轮次只允许一行评分。
--   背景：前端重试会把同一轮请求重发，旧实现会重复落行 →
--   权威轮次计数（countByDefenseId）虚高 +1 → 提前一轮收尾。
--
-- 【与代码的分工】代码侧已在落库前做幂等判定
--   （ScorePersistenceServiceImpl#doSave 先查 countByDefenseIdAndRound 再插入）；
--   本脚本提供数据库侧唯一索引，兜住并发场景下「先查后插」不是原子的问题。
--
-- 【执行方式】
--   mysql -uroot -p -h 127.0.0.1 ai_helper < docs/migration-20260928-score-record-unique.sql
--   或在 Navicat / DataGrip 里整段执行。脚本可安全重复执行。
-- =============================================================================

USE `ai_helper`;

-- 1) 清理历史重复行：同一 (defense_id, round_num) 只保留 id 最小的一条
--    （2026-09-15 实测 defenseId=235 出现过 1 条同轮重复行）
DELETE t1 FROM defense_score_record t1
INNER JOIN defense_score_record t2
    ON t1.defense_id = t2.defense_id
   AND t1.round_num  = t2.round_num
   AND t1.id         > t2.id;

-- 2) 加唯一索引。若已迁移过会报 "Duplicate key name 'uk_defense_round'"，忽略即可
ALTER TABLE defense_score_record
    ADD UNIQUE KEY `uk_defense_round` (`defense_id`, `round_num`);

-- 2.1)（可选）唯一索引与旧普通索引 idx_defense_round 列完全重复，
--       可执行下面这行释放索引空间；若索引不存在会报错，忽略即可。
-- ALTER TABLE defense_score_record DROP INDEX `idx_defense_round`;

-- 3) 核验：uk_defense_round 应为 Non_unique = 0
SHOW INDEX FROM defense_score_record;
