-- V1_21__drop_action_retry_compensate.sql
-- Action 投递 best-effort 化:砍应用层 retry/补偿建模(未来可靠投递走 MQ,补偿走 saga,均不复用本套)
-- 保留 action_execution 主表 + uk_idempotency(落库去重)
ALTER TABLE action_execution
  DROP INDEX idx_status_retryable,
  DROP COLUMN retryable,
  DROP COLUMN retry_count,
  DROP COLUMN compensated,
  DROP COLUMN compensated_at,
  DROP COLUMN compensated_by;
