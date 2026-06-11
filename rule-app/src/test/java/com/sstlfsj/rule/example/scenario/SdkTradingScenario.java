package com.sstlfsj.rule.example.scenario;

import com.sstlfsj.rule.example.ScenarioSupport;
import com.sstlfsj.rule.kernel.api.model.EventSource;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import com.sstlfsj.rule.sdk.RuleEngineClient;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** SDK 交易风控：商户端 SDK 嵌入 → 定期轮询规则 → 本地评估交易。 */
class SdkTradingScenario extends ScenarioSupport {

    private String sdkBaseUrl() {
        return "http://localhost:" + localPort + "/sdk/v1";
    }

    @Test
    void tradingAmountExceedsLimit_sdkPullsAndEvaluatesHit() {
        // scene 声明 payload 字段 amount：SDK 本地评估直接读 event.payload.amount（valueRef=PAYLOAD），
        // 无需取数源（SDK 是 zero-network 离线评估，没有 SQL/HTTP handler）
        createScene("merchant-trade", "商户交易风控", "PULL", "USER", List.of("trade"),
                List.of(Map.of("name", "amount", "type", "NUMBER", "required", true)));
        createDecision("REVIEW", "人工审核", 50, List.of());

        Map<String, Object> conditionAst = Map.of(
                "type", "AndNode",
                "children", List.of(Map.of(
                        "type", "ConditionNode",
                        "conditionType", "GT",
                        "metricCode", "amount",
                        "params", Map.of("threshold", 5000),
                        "valueRef", "PAYLOAD"
                ))
        );
        Map<String, Object> rule = createRule("merchant-trade", "large-trade", "大额交易",
                conditionAst,
                List.of(Map.of("decisionCode", "REVIEW")),
                List.of("trade"), "AST_BOOLEAN");
        publishRule(((Number) rule.get("ruleDefinitionId")).longValue());

        // SDK 客户端：serverUrl 传 base host（SnapshotPoller 内部自拼 /sdk/v1/snapshots），启动轮询本地评估
        try (RuleEngineClient client = RuleEngineClient.builder()
                .serverUrl("http://localhost:" + localPort)
                .tenantId(TENANT_ID)
                .pollInterval(Duration.ofSeconds(2))
                .build()) {

            // 等待初次拉取完成 → 大额交易 → 命中
            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                RuleEvent bigTrade = new RuleEvent(TENANT_ID, "merchant-trade", "trade",
                        "merchant-1", UUID.randomUUID().toString(),
                        Instant.now(), Map.of(), Map.of("amount", 8000),
                        EventSource.SDK);
                assertThat(client.evaluate(bigTrade).ruleHit()).isTrue();
            });

            // 小额交易 → 不命中
            RuleEvent smallTrade = new RuleEvent(TENANT_ID, "merchant-trade", "trade",
                    "merchant-1", UUID.randomUUID().toString(),
                    Instant.now(), Map.of(), Map.of("amount", 100),
                    EventSource.SDK);
            assertThat(client.evaluate(smallTrade).ruleHit()).isFalse();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void sdkMetricDefinitions_accessibleAfterAdminCreatesMetric() {
        createScene("sdk-metric-scene", "SDK指标场景", "PULL", "USER", List.of("event"));
        createMetric("sdk-counter", "SDK计数器", "ATTRIBUTE", "LONG",
                Map.of(), 60, true);

        // /sdk/v1/metric-definitions 返回 ApiResponse<List<MetricDescriptor>> 包装结构
        RestClient restClient = RestClient.builder().build();
        Map<String, Object> resp = restClient.get()
                .uri(sdkBaseUrl() + "/metric-definitions?tenantId=" + TENANT_ID)
                .retrieve().body(Map.class);
        assertThat(resp.get("success")).isEqualTo(true);
        assertThat((List<Object>) resp.get("data")).isNotEmpty();
    }
}
