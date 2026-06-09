ALTER TABLE node_trace          ADD COLUMN display_label VARCHAR(256) NULL COMMENT '条件可读标签快照(displayLabel)' AFTER metric_code;
ALTER TABLE dry_run_node_trace  ADD COLUMN display_label VARCHAR(256) NULL COMMENT '条件可读标签快照(displayLabel)' AFTER metric_code;
