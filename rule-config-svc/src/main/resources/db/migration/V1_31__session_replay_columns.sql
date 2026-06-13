-- 忠实重放:存原始 payload 与当时候选规则版本 id 集(均可空,兼容存量行)
ALTER TABLE evaluation_session
    ADD COLUMN payload JSON NULL COMMENT '评估事件原始 payload(忠实重放用)',
    ADD COLUMN candidate_rule_version_ids JSON NULL COMMENT '当时候选规则版本 id 列表(忠实重放用)';
