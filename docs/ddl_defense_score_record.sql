-- 执行前确保已选择 ai_helper 数据库
-- USE ai_helper;

-- 五维评分记录表
CREATE TABLE IF NOT EXISTS defense_score_record (
    id INT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    defense_id INT NOT NULL COMMENT '答辩记录ID（关联 defense_records.defense_id）',
    question_id INT NULL COMMENT '预设问题ID（关联 defense_questions.question_id，额外问题为NULL）',
    round_num INT NOT NULL COMMENT '轮次序号（1-10预设题，额外题从11开始）',
    expression_score DECIMAL(3,1) NULL COMMENT '表达能力得分（0-10）',
    logic_score DECIMAL(3,1) NULL COMMENT '逻辑思维得分（0-10）',
    professional_score DECIMAL(3,1) NULL COMMENT '专业水平得分（0-10）',
    adaptability_score DECIMAL(3,1) NULL COMMENT '应变能力得分（0-10）',
    innovation_score DECIMAL(3,1) NULL COMMENT '创新能力得分（0-10）',
    comment TEXT NULL COMMENT '本轮评语',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_defense_round (defense_id, round_num),
    INDEX idx_defense_id (defense_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='答辩五维评分记录表';
