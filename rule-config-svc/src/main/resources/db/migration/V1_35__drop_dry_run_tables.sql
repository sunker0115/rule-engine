-- dry-run 试算不再落历史（对齐 OPA：试算只即时返回，不写库），删除试算落库两表。
-- dry-run 评估本身照常工作（即时返回 nodeTrace），仅移除异步落库链路。
DROP TABLE IF EXISTS dry_run_node_trace;
DROP TABLE IF EXISTS dry_run_session;
