package com.sstlfsj.rule.example.scenario;

import com.sstlfsj.rule.example.ScenarioSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/** 信用评估：贷款调用外部信用评分接口 → 评分不足 → 拒贷。EXTERNAL_HTTP 取数。 */
class CreditEvaluationScenario extends ScenarioSupport {

    @BeforeEach
    void stubCreditApi() {
        stubFor(get(urlPathEqualTo("/api/credit/score/u_rich"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"code\":0,\"data\":{\"score\":850,\"level\":\"A\"}}")));
        stubFor(get(urlPathEqualTo("/api/credit/score/u_poor"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"code\":0,\"data\":{\"score\":350,\"level\":\"D\"}}")));
    }

    @Test
    void creditScore_belowThreshold_rejected() {
        createScene("loan", "贷款审批", "PULL", "USER", List.of("apply"));
        createDecision("REJECT", "拒贷", 100);

        // B10 连接器标准化：EXTERNAL_HTTP metric 经已注册 connector 取数（connector 持请求/响应模板）
        createConnector("credit-api-connector", "信用评分连接器", Map.of(
                "endpointRef", "credit-api",
                "request", Map.of("method", "GET", "pathTemplate", "/api/credit/score/{payload.uid}",
                        "query", List.of(), "headers", List.of()),
                "response", Map.of(
                        "successWhen", Map.of("path", "code", "op", "EQ", "value", 0),
                        "valuePath", "data.score"),
                "resilience", Map.of(
                        "connectTimeoutMs", 200, "readTimeoutMs", 300, "retries", 0, "retryOn", List.of())
        ));
        createMetric("credit-score", "信用评分", "EXTERNAL_HTTP", "LONG",
                Map.of("connector", "credit-api-connector", "vars", Map.of()), 120, false);

        Map<String, Object> conditionAst = Map.of(
                "type", "AndNode",
                "children", List.of(Map.of(
                        "type", "ConditionNode",
                        "conditionType", "LT",
                        "metricCode", "credit-score",
                        "params", Map.of("threshold", 600)
                ))
        );
        Map<String, Object> rule = createRule("loan", "low-score-reject", "低分拒贷",
                conditionAst,
                List.of(Map.of("decisionCode", "REJECT")),
                List.of("apply"), "AST_BOOLEAN");
        publishRule(((Number) rule.get("ruleDefinitionId")).longValue());

        // 高信用用户（850 分）→ 不命中拒贷规则
        Map<String, Object> goodResult = evaluate("loan", "apply", "user-1",
                Map.of("uid", "u_rich"));
        assertThat(goodResult.get("ruleHit")).isEqualTo(false);

        // 低信用用户（350 分）→ 命中断贷规则
        Map<String, Object> badResult = evaluate("loan", "apply", "user-2",
                Map.of("uid", "u_poor"));
        assertThat(badResult.get("ruleHit")).isEqualTo(true);
        Map<String, Object> decision = (Map<String, Object>) badResult.get("finalDecision");
        assertThat(decision.get("code")).isEqualTo("REJECT");

        verify(getRequestedFor(urlPathEqualTo("/api/credit/score/u_rich")));
        verify(getRequestedFor(urlPathEqualTo("/api/credit/score/u_poor")));

        // 评估审计异步落库；按本 scene 过滤，避免其他 test 异步残留干扰
        awaitRowCountWhere("evaluation_session", "scene_code='loan'", 2);
    }
}
