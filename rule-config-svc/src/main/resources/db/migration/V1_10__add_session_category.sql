-- D42 DECISION_TREE 主分类审计：evaluation_session 增 category 列（finalDecision 同源，单列可聚合；明细在 hit_decisions）。
-- greenfield 无生产数据，空表直接 ADD。
ALTER TABLE evaluation_session
    ADD COLUMN category VARCHAR(64) NULL COMMENT 'DECISION_TREE 主分类（finalDecision 同源）；其他 kind NULL' AFTER score;
