ALTER TABLE evaluation_session
    ADD COLUMN context_snapshot JSON
        COMMENT 'EvalContext metrics 取数快照，{metricCode: value}；构建失败时为 null（排障 / dry-run 重放用）'
        AFTER eval_duration_ms;

ALTER TABLE dry_run_session
    ADD COLUMN context_snapshot JSON
        COMMENT 'dry-run 试算时 EvalContext metrics 取数快照（排障 / 重放对比用）'
        AFTER eval_duration_ms;
