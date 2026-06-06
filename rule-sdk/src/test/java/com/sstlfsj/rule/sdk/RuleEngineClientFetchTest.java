package com.sstlfsj.rule.sdk;

import com.sstlfsj.rule.kernel.api.annotation.MetricSourceType;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.MetricDescriptor;
import com.sstlfsj.rule.kernel.api.model.MetricQuery;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.ast.AndNode;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.spi.metric.MetricSourceHandler;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuleEngineClientFetchTest {

    /** 宿主自带 handler，按 @MetricSourceType 归类；返回固定值模拟取数。 */
    @MetricSourceType("TEST")
    static class TestHandler implements MetricSourceHandler {
        @Override
        public MetricValue fetch(MetricQuery query) {
            return new MetricValue(90, "LONG", "FETCHED");
        }
    }

    private static RuleVersionSnapshot riskScoreGt80() {
        return RuleVersionSnapshot.builder()
                .ruleVersionId(1L).tenantId("t1").sceneCode("fraud")
                .conditionAst(new AndNode(List.of(
                        new ConditionNode("GT", "risk.score", null,
                                Map.of("threshold", 80), 0.0)), null, null))
                .addTriggerEventType("TRANSACTION")
                .addDecisionBinding("BLOCK", 100)
                .addMetricDependency("risk.score")
                .build();
    }

    @Test
    void localFetch_descriptorAndHandler_fetchedMetricDrivesHit() {
        MetricDescriptor riskScore =
                new MetricDescriptor("risk.score", "TEST", "LONG", false, 0, Map.of());

        try (RuleEngineClient client = RuleEngineClient.builder()
                .localSnapshot(riskScoreGt80())
                .localMetric("t1", riskScore)
                .metricSourceHandler(new TestHandler())
                .build()) {
            RuleEvent event = new RuleEvent("t1", "fraud", "TRANSACTION", "sub1",
                    UUID.randomUUID().toString(), Instant.now(), Map.of(), Map.of());
            EvalResult result = client.evaluate(event);
            assertThat(result.ruleHit()).isTrue();
            assertThat(result.finalDecision().code()).isEqualTo("BLOCK");
        }
    }

    @Test
    void noHandler_providedMetricsOnly_defaultBehaviorUnchanged() {
        // 不注入 handler → 退化 providedMetrics-only：provided risk.score=90 直接生效
        try (RuleEngineClient client = RuleEngineClient.builder()
                .localSnapshot(riskScoreGt80())
                .build()) {
            RuleEvent event = new RuleEvent("t1", "fraud", "TRANSACTION", "sub1",
                    UUID.randomUUID().toString(), Instant.now(), Map.of(),
                    Map.of("risk.score", 90));
            assertThat(client.evaluate(event).ruleHit()).isTrue();
        }
    }

    @Test
    void fetchHandler_allowProvidedFalse_ignoresProvidedAndFetches() {
        // allowProvided=false：即便调用方推送 risk.score=10（应 miss），仍走 fetch 拿 90（应 hit）
        MetricDescriptor riskScore =
                new MetricDescriptor("risk.score", "TEST", "LONG", false, 0, Map.of());
        try (RuleEngineClient client = RuleEngineClient.builder()
                .localSnapshot(riskScoreGt80())
                .localMetric("t1", riskScore)
                .metricSourceHandler(new TestHandler())
                .build()) {
            RuleEvent event = new RuleEvent("t1", "fraud", "TRANSACTION", "sub1",
                    UUID.randomUUID().toString(), Instant.now(), Map.of(),
                    Map.of("risk.score", 10));
            EvalResult result = client.evaluate(event);
            assertThat(result.ruleHit()).isTrue();
            assertThat(result.finalDecision().code()).isEqualTo("BLOCK");
        }
    }

    @Test
    void fetchHandler_allowProvidedTrue_usesProvidedValueOverFetch() {
        // allowProvided=true：调用方推送 risk.score=10 被采纳（10<80 → miss），不走 fetch
        MetricDescriptor riskScore =
                new MetricDescriptor("risk.score", "TEST", "LONG", true, 0, Map.of());
        try (RuleEngineClient client = RuleEngineClient.builder()
                .localSnapshot(riskScoreGt80())
                .localMetric("t1", riskScore)
                .metricSourceHandler(new TestHandler())
                .build()) {
            RuleEvent event = new RuleEvent("t1", "fraud", "TRANSACTION", "sub1",
                    UUID.randomUUID().toString(), Instant.now(), Map.of(),
                    Map.of("risk.score", 10));
            assertThat(client.evaluate(event).ruleHit()).isFalse();
        }
    }

    @Test
    void build_metricDefinitionsWithoutHandler_throws() {
        // 配置了 localMetric 但无 metricSourceHandler → 定义会被静默丢弃，build() 应提前失败
        assertThatThrownBy(() -> RuleEngineClient.builder()
                .localSnapshot(riskScoreGt80())
                .localMetric("t1", new MetricDescriptor("risk.score", "TEST", "LONG", false, 0, Map.of()))
                .build())
                .isInstanceOf(IllegalArgumentException.class);
    }
}
