-- node_trace / dry_run_node_trace 的 value_source ENUM → VARCHAR（可空，取值 == kernel ValueSource）。
ALTER TABLE node_trace
  MODIFY COLUMN value_source VARCHAR(16) NULL;

ALTER TABLE dry_run_node_trace
  MODIFY COLUMN value_source VARCHAR(16) NULL;
