-- 纯决策化(D60):移除动作子系统的存储
DROP TABLE IF EXISTS action_execution;
ALTER TABLE decision_definition DROP COLUMN actions;
