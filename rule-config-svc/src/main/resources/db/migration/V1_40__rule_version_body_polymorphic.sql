-- D76 三承载平铺收敛为多态 RuleBody：condition_ast / script_source / flow_graph / referenced_snapshots
-- 四列收敛为单一 body JSON 列（多态 AstBody/ScriptBody/FlowBody，含 type 判别）。
-- 存量行按原载体转换（historical data 可丢，但转换对既有行仍生效，避免 NOT NULL 失败）。

ALTER TABLE rule_version ADD COLUMN body JSON NULL COMMENT '判定主体多态载体 RuleBody（AstBody/ScriptBody/FlowBody 三选一，type 判别）；三承载收敛（原 condition_ast/script_source/flow_graph/referenced_snapshots 四列）' AFTER version;

UPDATE rule_version SET body = CASE
    WHEN script_source IS NOT NULL THEN JSON_OBJECT('type', 'ScriptBody', 'script', script_source)
    WHEN flow_graph    IS NOT NULL THEN JSON_OBJECT('type', 'FlowBody', 'flowGraph', flow_graph, 'referencedSnapshots', COALESCE(referenced_snapshots, JSON_OBJECT()))
    ELSE JSON_OBJECT('type', 'AstBody', 'conditionAst', condition_ast)
END;

ALTER TABLE rule_version MODIFY COLUMN body JSON NOT NULL COMMENT '判定主体多态载体 RuleBody（AstBody/ScriptBody/FlowBody 三选一，type 判别）；三承载收敛（原 condition_ast/script_source/flow_graph/referenced_snapshots 四列）';

ALTER TABLE rule_version
    DROP COLUMN condition_ast,
    DROP COLUMN script_source,
    DROP COLUMN flow_graph,
    DROP COLUMN referenced_snapshots;
