-- DECISION_FLOW 规则的决策图载体(FlowGraph JSON: {nodes, edges, inputNodeId})；其它 kind 为 NULL。
-- 与 condition_ast / script_source 三承载按 kind 三选一。
-- referenced_snapshots：发布期冻结的被引规则快照(ruleCode → RuleVersionSnapshot JSON)；其它 kind 为 NULL。
ALTER TABLE rule_version
    ADD COLUMN flow_graph JSON NULL COMMENT 'DECISION_FLOW 决策图 {nodes,edges,inputNodeId}，其它 kind 为 NULL',
    ADD COLUMN referenced_snapshots JSON NULL COMMENT 'DECISION_FLOW 发布期冻结的被引规则快照 ruleCode→snapshot，其它 kind 为 NULL';
