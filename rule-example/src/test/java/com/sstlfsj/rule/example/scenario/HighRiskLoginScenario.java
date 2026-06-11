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
        return Map.of("type", "AND", "children", List.of());
    }

    // ---- PULL evaluate + webhook 通知 ----

    @Test
    void abnormalLogin_pullEval_hitAndAlertWebhook() {
        stubFor(post(urlPathEqualTo("/webhook/alert"))
                .willReturn(aResponse().withStatus(200)));

        createScene("login-anti", "反欺诈登录检测", "PUSH", "USER", List.of("login"));
        createDecision("SEC_ALERT", "安全告警", 100,
                List.of(Map.of("actionId", "a1", "actionType", "SEND_ALERT",
                        "sortOrder", 0, "params", Map.of("level", "critical"))));

        Map<String, Object> rule = createRule("login-anti", "high-risk-login", "高风险登录",
                alwaysTrueAst(),
                List.of(Map.of("decisionCode", "SEC_ALERT")),
                List.of("login"), "AST_BOOLEAN");
        long ruleId = ((Number) rule.get("ruleDefinitionId")).longValue();
        long versionId = ((Number) rule.get("ruleVersionId")).longValue();
        publishRule(ruleId);

        Map<String, Object> result = evaluate("login-anti", "login", "u1",
                Map.of("userId", "u1", "ip", "192.168.99.1"));

        assertThat(result.get("ruleHit")).isEqualTo(true);
        assertThat(countRows("evaluation_session")).isEqualTo(1);
        assertThat(countRows("action_execution")).isEqualTo(1);

        List<Map<String, Object>> actions = query(
                "SELECT action_type, status FROM action_execution");
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
        assertThat(countRows("dry_run_session")).isEqualTo(1);
        assertThat(countRows("dry_run_node_trace")).isGreaterThan(0);
        assertThat(countRows("evaluation_session")).isEqualTo(0);
        assertThat(countRows("action_execution")).isEqualTo(0);
    }

    @Test
    void abnormalLogin_dryRun_byRuleId_draftVersion() {
        createScene("login-dry2", "登录风控(Dry2)", "PULL", "USER", List.of("login"));
        createDecision("BLOCK", "拦截", 100, List.of());

        Map<String, Object> rule = createRule("login-dry2", "draft-rule2", "草稿规则",
                alwaysTrueAst(),
                List.of(Map.of("decisionCode", "BLOCK")),
                List.of("login"), "AST_BOOLEAN");
        long ruleId = ((Number) rule.get("ruleDefinitionId")).longValue();

        Map<String, Object> result = dryRun(null, ruleId, "login-dry2", "login", "u1", Map.of());

        assertThat(result.get("ruleHit")).isEqualTo(true);
        assertThat(countRows("dry_run_session")).isEqualTo(1);
        assertThat(countRows("evaluation_session")).isEqualTo(0);
    }

    // ---- PUSH 异步事件 ----

    @Test
    void abnormalLogin_pushEvent_asyncAlertDelivered() {
        stubFor(post(urlPathEqualTo("/webhook/alert"))
                .willReturn(aResponse().withStatus(200)));

        createScene("login-push", "登录风控(Push)", "PUSH", "USER", List.of("login"));
        createDecision("SEC_ALERT", "安全告警", 100,
                List.of(Map.of("actionId", "a1", "actionType", "SEND_ALERT",
                        "sortOrder", 0, "params", Map.of("level", "high"))));

        Map<String, Object> rule = createRule("login-push", "push-alert", "推送告警",
                alwaysTrueAst(),
                List.of(Map.of("decisionCode", "SEC_ALERT")),
                List.of("login"), "AST_BOOLEAN");
        publishRule(((Number) rule.get("ruleDefinitionId")).longValue());

        Map<String, Object> pushResp = pushEvent("login-push", "login", "u2",
                Map.of("userId", "u2", "ip", "10.10.10.10"));
        assertThat(pushResp.get("accepted")).isEqualTo(true);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(countRows("evaluation_session")).isEqualTo(1);
        });

        List<Map<String, Object>> sessions = query(
                "SELECT status, mode FROM evaluation_session");
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
        createDecision("SEC_ALERT", "安全告警", 100,
                List.of(Map.of("actionId", "a1", "actionType", "SEND_ALERT",
                        "sortOrder", 0, "params", Map.of())));

        Map<String, Object> rule = createRule("login-fail", "fail-alert", "失败告警",
                alwaysTrueAst(),
                List.of(Map.of("decisionCode", "SEC_ALERT")),
                List.of("login"), "AST_BOOLEAN");
        publishRule(((Number) rule.get("ruleDefinitionId")).longValue());

        evaluate("login-fail", "login", "u1", Map.of("userId", "u1"));

        List<Map<String, Object>> actions = query(
                "SELECT status FROM action_execution");
        assertThat(actions.get(0).get("status").toString()).isEqualTo("FAILED");
    }
}
