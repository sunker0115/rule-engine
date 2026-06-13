-- rule-samples 全量清理:删掉 demo 租户的所有配置 + 运行/审计数据,恢复到"只剩租户行"的干净基线。
-- 用于 httpclient / sdkpolling demo 跑过之后彻底重置(scene/decision/audit 无删除 API,只能直连 DB 清)。
--
-- 用法(默认租户 id 9100,与 DemoConfig.TENANT_ID 默认值一致;改了 -Ddemo.tenantId 就同步改这里的 @tid):
--   mysql -uroot -p123456 rule_engine < rule-samples/cleanup.sql
--
-- 保留 tenant 行(它是 demo 运行前提);如需连租户一起删,取消最后一行注释。

SET @tid = 9100;

-- FK 安全序:子表 → 父表
DELETE FROM node_trace            WHERE tenant_id = @tid;
DELETE FROM action_execution      WHERE tenant_id = @tid;
DELETE FROM evaluation_session    WHERE tenant_id = @tid;
DELETE FROM dry_run_node_trace    WHERE tenant_id = @tid;
DELETE FROM dry_run_session       WHERE tenant_id = @tid;
DELETE FROM rule_version          WHERE rule_definition_id IN (SELECT id FROM rule_definition WHERE tenant_id = @tid);
DELETE FROM rule_definition       WHERE tenant_id = @tid;
DELETE FROM decision_definition   WHERE tenant_id = @tid;
DELETE FROM scene                 WHERE tenant_id = @tid;
DELETE FROM audit_log             WHERE tenant_id = @tid;

-- 连租户一起删(默认保留):
-- DELETE FROM tenant WHERE id = @tid;
