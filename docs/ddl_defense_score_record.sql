-- 执行前确保已选择 ai_helper 数据库
-- USE ai_helper;

-- 五维评分记录表
CREATE TABLE IF NOT EXISTS defense_score_record (
    id INT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    defense_id INT NOT NULL COMMENT '答辩记录ID（关联 defense_records.defense_id）',
    question_id INT NULL COMMENT '预设问题ID（关联 defense_questions.question_id，追问轮为NULL）',
    round_num INT NOT NULL COMMENT '轮次序号（1-10：1~5 为预设题、6~10 为 AI 追问）',
    expression_score DECIMAL(3,1) NULL COMMENT '表达能力得分（0-10）',
    logic_score DECIMAL(3,1) NULL COMMENT '逻辑思维得分（0-10）',
    professional_score DECIMAL(3,1) NULL COMMENT '专业水平得分（0-10）',
    adaptability_score DECIMAL(3,1) NULL COMMENT '应变能力得分（0-10）',
    innovation_score DECIMAL(3,1) NULL COMMENT '创新能力得分（0-10）',
    comment TEXT NULL COMMENT '本轮评语',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    -- 幂等键（N4）：同一场答辩的同一轮次只允许一行，前端重试不会重复落行
    UNIQUE KEY uk_defense_round (defense_id, round_num),
    INDEX idx_defense_id (defense_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='答辩五维评分记录表';

-- 存量库迁移：见 docs/migration-20260928-score-record-unique.sql
-- （本文件是「新建库」用的完整建表语句；已有数据的库请执行迁移脚本，不要重跑本文件）
