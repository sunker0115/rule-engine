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
        createScene("merchant-trade", "商户交易风控", "PULL", "USER", List.of("trade"));
        createDecision("REVIEW", "人工审核", 50, List.of());

        Map<String, Object> conditionAst = Map.of(
                "type", "AND",
                "children", List.of(Map.of(
                        "type", "CONDITION",
                        "conditionType", "GT",
                        "metricCode", "amount",
                        "params", Map.of("threshold", 5000)
                ))
        );
        Map<String, Object> rule = createRule("merchant-trade", "large-trade", "大额交易",
                conditionAst,
                List.of(Map.of("decisionCode", "REVIEW")),
                List.of("trade"), "AST_BOOLEAN");
        publishRule(((Number) rule.get("ruleDefinitionId")).longValue());

        // 验证 SDK 端点可访问
        RestClient restClient = RestClient.builder().build();
        var snapshotsResp = restClient.get()
                .uri(sdkBaseUrl() + "/snapshots?tenantId=" + TENANT_ID)
                .retrieve().toEntity(List.class);
        assertThat(snapshotsResp.getStatusCode().is2xxSuccessful()).isTrue();

        // SDK 客户端：连接管理端，启动轮询
        try (RuleEngineClient client = RuleEngineClient.builder()
                .serverUrl(sdkBaseUrl())
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
    void sdkMetricDefinitions_accessibleAfterAdminCreatesMetric() {
        createScene("sdk-metric-scene", "SDK指标场景", "PULL", "USER", List.of("event"));
        createMetric("sdk-counter", "SDK计数器", "ATTRIBUTE", "LONG",
                Map.of(), 60, true);

        RestClient restClient = RestClient.builder().build();
        var resp = restClient.get()
                .uri(sdkBaseUrl() + "/metric-definitions?tenantId=" + TENANT_ID)
                .retrieve().toEntity(List.class);
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
    }
}
