-- Schema 线收口：scene 变更历史改走 audit_log（before/after 快照），删除专用 payloadSchema 历史表与版本列。
DROP TABLE IF EXISTS scene_payload_schema_history;
ALTER TABLE scene DROP COLUMN payload_schema_version;
