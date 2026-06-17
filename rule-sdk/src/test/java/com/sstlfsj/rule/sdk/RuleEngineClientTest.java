package com.sstlfsj.rule.sdk;

import com.sstlfsj.rule.kernel.api.model.EvalErrorCode;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.EventSource;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import com.sstlfsj.rule.kernel.api.model.RuleKind;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.ScriptSource;
import com.sstlfsj.rule.kernel.api.model.ast.AndNode;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.spi.expression.CompiledExpression;
import com.sstlfsj.rule.kernel.api.spi.expression.ExpressionEngine;
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

    /** 测试用假引擎:compile 返回固定产物,evaluate 恒返回预设结果(免 CEL 依赖)。 */
    private record FakeEngine(String lang, Object result) implements ExpressionEngine {
        public CompiledExpression compile(String source) { return java.util.Set::of; }
        public Object evaluate(CompiledExpression c, Map<String, Object> b) { return result; }
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
                    UUID.randomUUID().toString(), Instant.now(), Map.of(), Map.of(), com.sstlfsj.rule.kernel.api.model.EventSource.SDK);
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
                    Instant.now(), Map.of(), Map.of(), com.sstlfsj.rule.kernel.api.model.EventSource.SDK);
            EvalResult result = client.evaluate(event);
            assertThat(result.ruleHit()).isTrue();
            assertThat(result.finalDecision().code()).isEqualTo("BLOCK");
        }
    }

    @Test
    void localMode_sdkDefault_collectsNoTrace_soCompactLogIsEmpty() {
        // SDK 默认 EvalEngine collectTrace=false：命中结果 nodeTrace 为空，evaluate 内单行 trace 日志即 "[]"
        RuleVersionSnapshot snap = RuleVersionSnapshot.builder()
                .ruleVersionId(1L).tenantId("t1").sceneCode("fraud")
                .conditionAst(alwaysTrue())
                .addDecisionBinding("BLOCK", 100)
                .build();
        try (RuleEngineClient client = RuleEngineClient.builder()
                .localSnapshot(snap)
                .build()) {
            RuleEvent event = new RuleEvent("t1", "fraud", "TRANSACTION",
                    "sub1", UUID.randomUUID().toString(),
                    Instant.now(), Map.of(), Map.of(), EventSource.SDK);
            EvalResult result = client.evaluate(event);
            assertThat(result.ruleHit()).isTrue();
            assertThat(result.nodeTrace()).isEmpty();
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
                    Instant.now(), Map.of(), Map.of("amount", 1500), com.sstlfsj.rule.kernel.api.model.EventSource.SDK);
            assertThat(client.evaluate(hit).ruleHit()).isTrue();

            RuleEvent miss = new RuleEvent("t1", "fraud", "TRANSACTION",
                    "sub1", UUID.randomUUID().toString(),
                    Instant.now(), Map.of(), Map.of("amount", 500), com.sstlfsj.rule.kernel.api.model.EventSource.SDK);
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
                    Instant.now(), Map.of(), Map.of(), com.sstlfsj.rule.kernel.api.model.EventSource.SDK);
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
                    Instant.now(), Map.of(), Map.of(), com.sstlfsj.rule.kernel.api.model.EventSource.SDK);
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
                    Instant.now(), Map.of(), Map.of(), com.sstlfsj.rule.kernel.api.model.EventSource.SDK);
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
                    Instant.now(), Map.of(), Map.of("device_id", "dev-001"), com.sstlfsj.rule.kernel.api.model.EventSource.SDK);
            assertThat(client.evaluate(hit).ruleHit()).isTrue();

            RuleEvent miss = new RuleEvent("t1", "device", "LOGIN",
                    "sub1", UUID.randomUUID().toString(),
                    Instant.now(), Map.of(), Map.of("device_id", "dev-999"), com.sstlfsj.rule.kernel.api.model.EventSource.SDK);
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
                    Instant.now(), Map.of(), Map.of("amount", 1), com.sstlfsj.rule.kernel.api.model.EventSource.SDK);  // amount=1 < 1000
            assertThat(client.evaluate(event).ruleHit()).isTrue();  // 自定义覆盖，应命中
        }
    }

    @Test
    void scriptRule_withEngine_evaluatesDecision() {
        // opt-in 表达式引擎 → 脚本规则正常评估,返回决策码
        RuleVersionSnapshot snap = RuleVersionSnapshot.builder()
                .ruleVersionId(20L).tenantId("t1").sceneCode("scripted")
                .kind(RuleKind.EXPRESSION_SCRIPT.tag())
                .script(new ScriptSource("expr", "FAKE"))
                .addTriggerEventType("TXN")
                .addDecisionBinding("REVIEW", 10)
                .build();
        try (RuleEngineClient client = RuleEngineClient.builder()
                .localSnapshot(snap)
                .expressionEngine(new FakeEngine("FAKE", "REVIEW"))
                .build()) {
            RuleEvent event = new RuleEvent("t1", "scripted", "TXN", "sub1",
                    UUID.randomUUID().toString(), Instant.now(), Map.of(), Map.of(), EventSource.SDK);
            EvalResult r = client.evaluate(event);
            assertThat(r.ruleHit()).isTrue();
            assertThat(r.finalDecision().code()).isEqualTo("REVIEW");
        }
    }

    @Test
    void scriptRule_noEngine_gracefulNoEngineError_notAstFallback() {
        // 未 opt-in 任何引擎:ScriptExecutor 始终注册,脚本规则优雅返回 SCRIPT_NO_ENGINE,
        // 不被 EvalEngine 错误回退给 AST_BOOLEAN 解释器(conditionAst=null)
        RuleVersionSnapshot snap = RuleVersionSnapshot.builder()
                .ruleVersionId(21L).tenantId("t1").sceneCode("scripted")
                .kind(RuleKind.EXPRESSION_SCRIPT.tag())
                .script(new ScriptSource("expr", "CEL"))
                .addTriggerEventType("TXN")
                .addDecisionBinding("REVIEW", 10)
                .build();
        try (RuleEngineClient client = RuleEngineClient.builder()
                .localSnapshot(snap)
                .build()) {
            RuleEvent event = new RuleEvent("t1", "scripted", "TXN", "sub1",
                    UUID.randomUUID().toString(), Instant.now(), Map.of(), Map.of(), EventSource.SDK);
            EvalResult r = client.evaluate(event);
            assertThat(r.ruleHit()).isFalse();
            assertThat(r.errorCode()).isEqualTo(EvalErrorCode.SCRIPT_NO_ENGINE.name());
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
                    UUID.randomUUID().toString(), Instant.now(), Map.of(), Map.of(), com.sstlfsj.rule.kernel.api.model.EventSource.SDK);
            client.evaluate(event);
        }
        assertThat(called[0]).isTrue();
    }

    @Test
    void evaluate_forcesSdkSource_evenWhenCallerPassesOtherChannel() {
        // 调用方即便传入 HTTP，SDK 入口也权威改写为 SDK（不信任外部渠道）
        EventSource[] seen = {null};
        try (RuleEngineClient client = RuleEngineClient.builder()
                .serverUrl("http://localhost:19999")
                .tenantId("t1")
                .pollInterval(Duration.ofHours(1))
                .evalResultListener((ev, res) -> seen[0] = ev.source())
                .build()) {
            RuleEvent event = new RuleEvent("t1", "scene1", "ORDER", "sub1",
                    UUID.randomUUID().toString(), Instant.now(), Map.of(), Map.of(), EventSource.HTTP);
            client.evaluate(event);
        }
        assertThat(seen[0]).isEqualTo(EventSource.SDK);
    }

    @Test
    void evaluate_listenerThrows_doesNotBreakEvaluate() {
        // evalResultListener 抛异常时应被吞（log.warn），不中断 evaluate() 返回结果（backup #7/listener 备注）
        RuleVersionSnapshot snap = RuleVersionSnapshot.builder()
                .ruleVersionId(1L).tenantId("t1").sceneCode("fraud")
                .conditionAst(alwaysTrue())
                .addDecisionBinding("BLOCK", 100)
                .build();
        try (RuleEngineClient client = RuleEngineClient.builder()
                .localSnapshot(snap)
                .evalResultListener((ev, res) -> { throw new RuntimeException("listener boom"); })
                .build()) {
            RuleEvent event = new RuleEvent("t1", "fraud", "TRANSACTION",
                    "sub1", UUID.randomUUID().toString(),
                    Instant.now(), Map.of(), Map.of(), EventSource.SDK);
            EvalResult result = client.evaluate(event);
            assertThat(result.ruleHit()).isTrue();   // listener 抛异常不影响 evaluate 结果
        }
    }
}
