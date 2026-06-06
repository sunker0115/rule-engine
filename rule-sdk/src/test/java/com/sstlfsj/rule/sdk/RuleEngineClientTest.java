package com.sstlfsj.rule.sdk;

import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.ast.AndNode;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.sdk.source.DslRuleSource;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuleEngineClientTest {

    private static AndNode alwaysTrue() {
        return new AndNode(List.of(), null, null);
    }

    private static AndNode amountGt1000() {
        return new AndNode(List.of(
                new ConditionNode("GT", "amount", null, Map.of("threshold", 1000), 0.0)
        ), null, null);
    }

    @Test
    void build_noSourceConfigured_throwsIllegalArgument() {
        assertThatThrownBy(() -> RuleEngineClient.builder()
                .tenantId("t1")
                .build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void build_missingTenantId_throwsIllegalArgument() {
        assertThatThrownBy(() -> RuleEngineClient.builder()
                .serverUrl("http://localhost:8080")
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenantId");
    }

    @Test
    void evaluate_emptyIndex_returnsMiss() {
        // SnapshotPoller 启动后连接不存在的端口，静默失败，index 保持空
        try (RuleEngineClient client = RuleEngineClient.builder()
                .serverUrl("http://localhost:19999")
                .tenantId("t1")
                .pollInterval(Duration.ofHours(1))
                .build()) {
            RuleEvent event = new RuleEvent("t1", "scene1", "ORDER", "sub1",
                    UUID.randomUUID().toString(), Instant.now(), Map.of(), Map.of());
            assertThat(client.evaluate(event).ruleHit()).isFalse();
        }
    }

    @Test
    void localMode_alwaysTrueRule_evaluatesHit() {
        RuleVersionSnapshot snap = RuleVersionSnapshot.builder()
                .ruleVersionId(1L).tenantId("t1").sceneCode("fraud")
                .conditionAst(alwaysTrue())
                .addTriggerEventType("TRANSACTION")
                .addDecisionBinding("BLOCK", 100)
                .build();

        try (RuleEngineClient client = RuleEngineClient.builder()
                .localSnapshot(snap)
                .build()) {
            RuleEvent event = new RuleEvent("t1", "fraud", "TRANSACTION",
                    "sub1", UUID.randomUUID().toString(),
                    Instant.now(), Map.of(), Map.of());
            EvalResult result = client.evaluate(event);
            assertThat(result.ruleHit()).isTrue();
            assertThat(result.finalDecision().code()).isEqualTo("BLOCK");
        }
    }

    @Test
    void localMode_conditionRule_hitWhenMetricMatches() {
        RuleVersionSnapshot snap = RuleVersionSnapshot.builder()
                .ruleVersionId(2L).tenantId("t1").sceneCode("fraud")
                .conditionAst(amountGt1000())
                .addTriggerEventType("TRANSACTION")
                .addDecisionBinding("BLOCK", 100)
                .build();

        try (RuleEngineClient client = RuleEngineClient.builder()
                .localSnapshot(snap)
                .build()) {
            RuleEvent hit = new RuleEvent("t1", "fraud", "TRANSACTION",
                    "sub1", UUID.randomUUID().toString(),
                    Instant.now(), Map.of(), Map.of("amount", 1500));
            assertThat(client.evaluate(hit).ruleHit()).isTrue();

            RuleEvent miss = new RuleEvent("t1", "fraud", "TRANSACTION",
                    "sub1", UUID.randomUUID().toString(),
                    Instant.now(), Map.of(), Map.of("amount", 500));
            assertThat(client.evaluate(miss).ruleHit()).isFalse();
        }
    }

    @Test
    void localMode_multipleSnapshots_bothEvaluated() {
        RuleVersionSnapshot snap1 = RuleVersionSnapshot.builder()
                .ruleVersionId(1L).tenantId("t1").sceneCode("fraud")
                .conditionAst(alwaysTrue())
                .addTriggerEventType("TRANSACTION")
                .addDecisionBinding("BLOCK", 100)
                .build();
        RuleVersionSnapshot snap2 = RuleVersionSnapshot.builder()
                .ruleVersionId(2L).tenantId("t1").sceneCode("fraud")
                .conditionAst(alwaysTrue())
                .addTriggerEventType("TRANSACTION")
                .addDecisionBinding("REVIEW", 50)
                .build();

        try (RuleEngineClient client = RuleEngineClient.builder()
                .localSnapshot(snap1)
                .localSnapshot(snap2)
                .build()) {
            RuleEvent event = new RuleEvent("t1", "fraud", "TRANSACTION",
                    "sub1", UUID.randomUUID().toString(),
                    Instant.now(), Map.of(), Map.of());
            EvalResult result = client.evaluate(event);
            assertThat(result.ruleHit()).isTrue();
            assertThat(result.hitDecisions()).hasSize(2);
        }
    }

    @Test
    void localMode_build_throwsIfBothServerUrlAndLocalSnapshots() {
        assertThatThrownBy(() -> RuleEngineClient.builder()
                .serverUrl("http://localhost:8080")
                .tenantId("t1")
                .localSnapshot(RuleVersionSnapshot.builder()
                        .ruleVersionId(1L).tenantId("t1").sceneCode("s")
                        .conditionAst(alwaysTrue()).build())
                .build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ruleFile_loadsFromClasspath_evaluatesHit() {
        // rules/test-rule.json：tenantId=t1, sceneCode=test, event=TEST_EVENT, alwaysTrue
        try (RuleEngineClient client = RuleEngineClient.builder()
                .ruleFile("rules/test-rule.json")
                .build()) {
            RuleEvent event = new RuleEvent("t1", "test", "TEST_EVENT",
                    "sub1", UUID.randomUUID().toString(),
                    Instant.now(), Map.of(), Map.of());
            assertThat(client.evaluate(event).ruleHit()).isTrue();
        }
    }

    @Test
    void ruleSource_dslRuleSource_evaluatesHit() {
        RuleVersionSnapshot snap = RuleVersionSnapshot.builder()
                .ruleVersionId(10L).tenantId("t1").sceneCode("scene")
                .conditionAst(alwaysTrue())
                .addTriggerEventType("EV")
                .addDecisionBinding("PASS", 10)
                .build();

        try (RuleEngineClient client = RuleEngineClient.builder()
                .ruleSource(new DslRuleSource(List.of(snap)))
                .build()) {
            RuleEvent event = new RuleEvent("t1", "scene", "EV",
                    "sub1", UUID.randomUUID().toString(),
                    Instant.now(), Map.of(), Map.of());
            assertThat(client.evaluate(event).ruleHit()).isTrue();
        }
    }

    @Test
    void addEvaluator_customOperator_evaluatedCorrectly() {
        RuleVersionSnapshot snap = RuleVersionSnapshot.builder()
                .ruleVersionId(99L).tenantId("t1").sceneCode("device")
                .conditionAst(Condition.of("BLACKLIST_HIT", "device_id",
                        Map.of("list", List.of("dev-001", "dev-002"))).toAst())
                .addTriggerEventType("LOGIN")
                .addDecisionBinding("BLOCK", 100)
                .build();

        try (RuleEngineClient client = RuleEngineClient.builder()
                .localSnapshot(snap)
                .addEvaluator("BLACKLIST_HIT", (node, ctx) -> {
                    @SuppressWarnings("unchecked")
                    List<Object> list = (List<Object>) node.params().get("list");
                    var mv = ctx.metrics().get(node.metricCode());
                    return mv != null && list.contains(mv.value());
                })
                .build()) {
            RuleEvent hit = new RuleEvent("t1", "device", "LOGIN",
                    "sub1", UUID.randomUUID().toString(),
                    Instant.now(), Map.of(), Map.of("device_id", "dev-001"));
            assertThat(client.evaluate(hit).ruleHit()).isTrue();

            RuleEvent miss = new RuleEvent("t1", "device", "LOGIN",
                    "sub1", UUID.randomUUID().toString(),
                    Instant.now(), Map.of(), Map.of("device_id", "dev-999"));
            assertThat(client.evaluate(miss).ruleHit()).isFalse();
        }
    }

    @Test
    void addEvaluator_customOverridesBuiltin() {
        RuleVersionSnapshot snap = RuleVersionSnapshot.builder()
                .ruleVersionId(98L).tenantId("t1").sceneCode("override")
                .conditionAst(Condition.gt("amount", 1000).toAst())
                .addTriggerEventType("ORDER")
                .addDecisionBinding("PASS", 10)
                .build();

        try (RuleEngineClient client = RuleEngineClient.builder()
                .localSnapshot(snap)
                .addEvaluator("GT", (node, ctx) -> true)  // 永远返回 true
                .build()) {
            RuleEvent event = new RuleEvent("t1", "override", "ORDER",
                    "sub1", UUID.randomUUID().toString(),
                    Instant.now(), Map.of(), Map.of("amount", 1));  // amount=1 < 1000
            assertThat(client.evaluate(event).ruleHit()).isTrue();  // 自定义覆盖，应命中
        }
    }

    @Test
    void build_serverUrlAndRuleFile_throwsIllegalArgument() {
        assertThatThrownBy(() -> RuleEngineClient.builder()
                .serverUrl("http://localhost:8080")
                .tenantId("t1")
                .ruleFile("rules/test-rule.json")
                .build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void evaluate_callsEvalResultListener() {
        boolean[] called = {false};
        try (RuleEngineClient client = RuleEngineClient.builder()
                .serverUrl("http://localhost:19999")
                .tenantId("t1")
                .pollInterval(Duration.ofHours(1))
                .evalResultListener((ev, res) -> called[0] = true)
                .build()) {
            RuleEvent event = new RuleEvent("t1", "scene1", "ORDER", "sub1",
                    UUID.randomUUID().toString(), Instant.now(), Map.of(), Map.of());
            client.evaluate(event);
        }
        assertThat(called[0]).isTrue();
    }
}
