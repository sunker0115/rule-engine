-- 增量任务运行游标:与不可变 config 分离的运行态(state-not-config,对齐 Kafka Connect offset / Airbyte state 模式)
-- run_cursor 为增量运行游标列名(非 MySQL 保留字,无需反引号转义)
ALTER TABLE scheduled_task
  ADD COLUMN run_cursor VARCHAR(64) NULL COMMENT '增量任务运行游标(opaque;OUTCOME_INGESTION 存 ISO-8601 labeled_at watermark;TRIGGER 等为 null)';
