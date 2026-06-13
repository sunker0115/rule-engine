package com.sstlfsj.rule.sdk;

import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.kernel.api.model.ast.AndNode;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RuleEngineClientMetricSourceTest {

    private static RuleVersionSnapshot scoreGt80() {
        return RuleVersionSnapshot.builder()
                .ruleVersionId(1L).tenantId("t1").sceneCode("fraud")
                .conditionAst(new AndNode(List.of(
                        new ConditionNode("GT", "risk.score", null, Map.of("threshold", 80), 0.0)), null, null))
                .addTriggerEventType("TXN").addDecisionBinding("BLOCK", 100)
                .addMetricDependency("risk.score", 1).build();
    }

    @Test
    void explicitSourceHandler_andDescriptor_driveFetch() {
        MetricDescriptor def = new MetricDescriptor("risk.score", "SYN", "LONG", false, 0, Map.of());
        try (RuleEngineClient client = RuleEngineClient.builder()
                .localSnapshot(scoreGt80())
                .localMetric("t1", def)
                .addMetricSourceHandler("SYN", q -> new MetricValue(90, "LONG", "FETCHED"))
                .build()) {
            RuleEvent e = new RuleEvent("t1", "fraud", "TXN", "s1",
                    UUID.randomUUID().toString(), Instant.now(), Map.of(), Map.of(), EventSource.SDK);
            EvalResult r = client.evaluate(e);
            assertThat(r.ruleHit()).isTrue();
            assertThat(r.finalDecision().code()).isEqualTo("BLOCK");
        }
    }
}
