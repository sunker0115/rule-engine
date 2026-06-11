package com.sstlfsj.rule.example.scenario;

import com.sstlfsj.rule.example.ScenarioSupport;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** 异常登录检测：从新 IP 高频登录 → 命中高风险规则 → 异步告警到安全团队 webhook。 */
class HighRiskLoginScenario extends ScenarioSupport {

    private Map<String, Object> alwaysTrueAst() {
        return Map.of("type", "AndNode", "children", List.of());
    }

    // ---- PULL evaluate + webhook 通知 ----

    @Test
    void abnormalLogin_pullEval_hitAndAlertWebhook() {
        stubFor(post(urlPathEqualTo("/webhook/alert"))
                .willReturn(aResponse().withStatus(200)));

        createScene("login-anti", "反欺诈登录检测", "PUSH", "USER", List.of("login"));
        // decision code 每个 test 独特：action_execution 无 scene_code，按 decision_code 隔离本 test 的 action
        createDecision("ALERT_PULL", "安全告警", 100,
                List.of(Map.of("actionId", "a1", "actionType", "SEND_ALERT",
                        "sortOrder", 0, "params", Map.of("level", "critical"))));

        Map<String, Object> rule = createRule("login-anti", "high-risk-login", "高风险登录",
                alwaysTrueAst(),
                List.of(Map.of("decisionCode", "ALERT_PULL")),
                List.of("login"), "AST_BOOLEAN");
        publishRule(((Number) rule.get("ruleDefinitionId")).longValue());

        Map<String, Object> result = evaluate("login-anti", "login", "u1",
                Map.of("userId", "u1", "ip", "192.168.99.1"));

        assertThat(result.get("ruleHit")).isEqualTo(true);
        // 评估审计与 action 落库异步 best-effort，按本 test 业务键等待（避免其他 test 异步残留干扰）
        awaitRowCountWhere("evaluation_session", "scene_code='login-anti'", 1);
        awaitRowCountWhereAtLeast("action_execution", "decision_code='ALERT_PULL'", 1);

        List<Map<String, Object>> actions = query(
                "SELECT status FROM action_execution WHERE decision_code='ALERT_PULL'");
        assertThat(actions.get(0).get("status").toString()).isEqualTo("SUCCESS");

        verify(postRequestedFor(urlPathEqualTo("/webhook/alert")));
    }

    // ---- dry-run（两种入口） ----

    @Test
    void abnormalLogin_dryRun_byVersionId() {
        createScene("login-dry", "登录风控(Dry)", "PUSH", "USER", List.of("login"));
        createDecision("REVIEW", "人工审核", 10, List.of());

        Map<String, Object> rule = createRule("login-dry", "dry-rule", "规则(Dry)",
                alwaysTrueAst(),
                List.of(Map.of("decisionCode", "REVIEW")),
                List.of("login"), "AST_BOOLEAN");
        long ruleId = ((Number) rule.get("ruleDefinitionId")).longValue();
        long versionId = ((Number) rule.get("ruleVersionId")).longValue();
        publishRule(ruleId);

        Map<String, Object> result = dryRun(versionId, null, "login-dry", "login", "u1", Map.of());

        assertThat(result.get("ruleHit")).isEqualTo(true);
        // dry_run_session / dry_run_node_trace 异步落库
        awaitRowCountWhere("dry_run_session", "scene_code='login-dry'", 1);
        // dry_run_node_trace 全表即可：唯一的 dry-run 场景，不会被其他 test 污染
        awaitRowCountAtLeast("dry_run_node_trace", 1);
        // dry-run 不发 AuditRecordedEvent：本 scene 不落正式 evaluation_session（按 scene 过滤避开其他 test 残留）
        assertThat(countRowsWhere("evaluation_session", "scene_code='login-dry'")).isZero();
    }

    // ---- PUSH 异步事件 ----

    @Test
    void abnormalLogin_pushEvent_asyncAlertDelivered() {
        stubFor(post(urlPathEqualTo("/webhook/alert"))
                .willReturn(aResponse().withStatus(200)));

        createScene("login-push", "登录风控(Push)", "PUSH", "USER", List.of("login"));
        createDecision("ALERT_PUSH", "安全告警", 100,
                List.of(Map.of("actionId", "a1", "actionType", "SEND_ALERT",
                        "sortOrder", 0, "params", Map.of("level", "high"))));

        Map<String, Object> rule = createRule("login-push", "push-alert", "推送告警",
                alwaysTrueAst(),
                List.of(Map.of("decisionCode", "ALERT_PUSH")),
                List.of("login"), "AST_BOOLEAN");
        publishRule(((Number) rule.get("ruleDefinitionId")).longValue());

        Map<String, Object> pushResp = pushEvent("login-push", "login", "u2",
                Map.of("userId", "u2", "ip", "10.10.10.10"));
        assertThat(pushResp.get("accepted")).isEqualTo(true);

        awaitRowCountWhere("evaluation_session", "scene_code='login-push'", 1);

        List<Map<String, Object>> sessions = query(
                "SELECT status, mode FROM evaluation_session WHERE scene_code='login-push'");
        assertThat(sessions.get(0).get("status").toString()).isEqualTo("HIT");
        assertThat(sessions.get(0).get("mode").toString()).isEqualTo("PUSH");

        verify(postRequestedFor(urlPathEqualTo("/webhook/alert")));
    }

    // ---- webhook 失败：非 2xx → action_execution=FAILED ----

    @Test
    void abnormalLogin_webhookFails_actionRecordedFailed() {
        stubFor(post(urlPathEqualTo("/webhook/alert"))
                .willReturn(aResponse().withStatus(500)));

        createScene("login-fail", "登录风控(Fail)", "PUSH", "USER", List.of("login"));
        createDecision("ALERT_FAIL", "安全告警", 100,
                List.of(Map.of("actionId", "a1", "actionType", "SEND_ALERT",
                        "sortOrder", 0, "params", Map.of())));

        Map<String, Object> rule = createRule("login-fail", "fail-alert", "失败告警",
                alwaysTrueAst(),
                List.of(Map.of("decisionCode", "ALERT_FAIL")),
                List.of("login"), "AST_BOOLEAN");
        publishRule(((Number) rule.get("ruleDefinitionId")).longValue());

        evaluate("login-fail", "login", "u1", Map.of("userId", "u1"));

        // action 派发异步，按本 test 的 decision_code 等待落库后再查状态（隔离其他 test 异步残留）
        awaitRowCountWhereAtLeast("action_execution", "decision_code='ALERT_FAIL'", 1);
        List<Map<String, Object>> actions = query(
                "SELECT status FROM action_execution WHERE decision_code='ALERT_FAIL'");
        assertThat(actions.get(0).get("status").toString()).isEqualTo("FAILED");
    }
}
