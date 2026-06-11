-- D12 SCORECARD 评估分数审计：evaluation_session 增 score 列。
-- DECISION_TREE/TABLE 的决策已落 final_decision；scorecard 的累计分此前不落审计，本列补上。
-- greenfield 无生产数据，空表直接 ADD。
ALTER TABLE evaluation_session
    ADD COLUMN score DOUBLE NULL COMMENT 'SCORECARD 累计分；无分（AST_BOOLEAN 等）时为 NULL' AFTER hit_rule_count;
