package com.sstlfsj.rule.kernel.internal.engine;

import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;
import com.sstlfsj.rule.kernel.api.spi.metric.MetricSourceHandler;
import com.sstlfsj.rule.kernel.internal.condition.KernelEvaluators;
import com.sstlfsj.rule.kernel.internal.context.EvalContextAssembler;
import com.sstlfsj.rule.kernel.internal.evaluator.InterpretedExecutor;
import com.sstlfsj.rule.kernel.internal.index.SceneRuleIndex;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** evaluateReplay：用冻结 metric（作 providedMetrics 回灌）评估，跳过取数；冻结值决定命中与否。 */
class EvalEngineReplayTest {

    private EvalEngine engine() {
        SceneRuleIndex index = new SceneRuleIndex();
        // no-resolver assembler：providedMetrics 直接进 context，不取数
        EvalContextAssembler assembler =
                new EvalContextAssembler(List.of(), List.<MetricSourceHandler>of());
        Map<String, ConditionEvaluator> evals = new HashMap<>(KernelEvaluators.defaults());
        return new EvalEngine(index, assembler, Map.of(),
                Map.of(RuleKind.AST_BOOLEAN.tag(), new InterpretedExecutor(evals)), false);
    }

    private RuleVersionSnapshot totalGt100() {
        return RuleVersionSnapshot.builder()
                .ruleVersionId(1L).tenantId("1").sceneCode("s").code("r").version(1L)
                .conditionAst(new ConditionNode("GT", "total", null, Map.of("threshold", 100), 0.0))
                .addTriggerEventType("e")
                .addDecisionBinding("HIT", 1)
                .addMetricDependency("total", 1)
                .build();
    }

    private RuleEvent event() {
        return RuleEvent.builder().tenantId("1").sceneCode("s").eventType("e")
                .subjectId("u").eventId("evt-1").occurredAt(Instant.now())
                .payload(Map.of()).source(EventSource.REPLAY).build();
    }

    @Test
    void evaluateReplay_frozenMetricAbove100_hits() {
        EvalOutcome out = engine().evaluateReplay(
                event(), List.of(totalGt100()), Map.of("total", 200), Instant.now());
        assertThat(out.result().ruleHit()).isTrue();
    }

    @Test
    void evaluateReplay_frozenMetricBelow100_misses() {
        EvalOutcome out = engine().evaluateReplay(
                event(), List.of(totalGt100()), Map.of("total", 50), Instant.now());
        assertThat(out.result().ruleHit()).isFalse();
    }
}
