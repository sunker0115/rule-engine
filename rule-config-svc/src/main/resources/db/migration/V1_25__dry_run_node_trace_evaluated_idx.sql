-- 数据保留清理:dry_run_node_trace 按 evaluated_at 范围删除,补索引避免全表扫
ALTER TABLE dry_run_node_trace ADD KEY idx_evaluated_at (evaluated_at);
