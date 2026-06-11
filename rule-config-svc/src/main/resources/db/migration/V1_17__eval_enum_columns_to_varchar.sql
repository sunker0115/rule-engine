-- eval 模块 ENUM 列 → VARCHAR：取值真相源上移 app 层 Java enum（按 name 与列往返）。
-- source 取值 == kernel EventSource；status 取值 == SessionStatus；mode == EvalMode；
-- action_execution.status 取值 == ActionResult.ActionStatus（写路径仅 SUCCESS/FAILED/SKIPPED，
-- 列保留 PENDING/RETRYING 容 DEFAULT 与未来写者）；dry_run_session.trigger 无实体字段，纯列改型。
ALTER TABLE evaluation_session
  MODIFY COLUMN source VARCHAR(16) NOT NULL DEFAULT 'HTTP',
  MODIFY COLUMN mode   VARCHAR(8)  NOT NULL DEFAULT 'PULL',
  MODIFY COLUMN status VARCHAR(16) NOT NULL DEFAULT 'PENDING';

ALTER TABLE action_execution
  MODIFY COLUMN status VARCHAR(16) NOT NULL DEFAULT 'PENDING';

ALTER TABLE dry_run_session
  MODIFY COLUMN status    VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  MODIFY COLUMN `trigger` VARCHAR(16) NOT NULL DEFAULT 'API';
