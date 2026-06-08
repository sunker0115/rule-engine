package com.sstlfsj.rule.kernel.engine;

import com.sstlfsj.rule.kernel.api.model.EventSource;
import com.sstlfsj.rule.kernel.api.model.EvalOutcome;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.SceneExecutionStrategy;
import com.sstlfsj.rule.kernel.api.model.ast.AstNode;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;
import com.sstlfsj.rule.kernel.api.spi.executor.RuleVersionExecutor;
import com.sstlfsj.rule.kernel.internal.condition.KernelEvaluators;
import com.sstlfsj.rule.kernel.internal.context.EvalContextAssembler;
import com.sstlfsj.rule.kernel.internal.engine.EvalEngine;
import com.sstlfsj.rule.kernel.internal.evaluator.InterpretedExecutor;
import com.sstlfsj.rule.kernel.internal.index.SceneRuleIndex;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EvalEngineTest {

    private static final String ALWAYS_TRUE = "ALWAYS_TRUE";

    // 恒真条件：无指标依赖，命中布尔在收集/不收集 trace 两种模式下完全一致
    private static final AstNode ALWAYS_TRUE_AST =
            new ConditionNode(ALWAYS_TRUE, null, null, Map.of(), 0.0);

    private static RuleEvent event() {
        return new RuleEvent("t1", "fraud", "ORDER", "sub1", "evt-1",
                Instant.now(), Map.of(), Map.of(), EventSource.HTTP);
    }

    private static RuleVersionSnapshot snapshot() {
        return new RuleVersionSnapshot(1L, "fraud", "t1", ALWAYS_TRUE_AST, List.of(),
                List.of(new RuleVersionSnapshot.DecisionBinding("BLOCK", 10)),
                List.of(), "AST_BOOLEAN");
    }

    private static EvalEngine engine() {
        // KernelEvaluators.defaults() 叠加一个恒真算子，免去指标装配即可稳定命中
        Map<String, ConditionEvaluator> evaluators = new HashMap<>(KernelEvaluators.defaults());
        evaluators.put(ALWAYS_TRUE, (node, ctx) -> true);
        RuleVersionExecutor interpreted = new InterpretedExecutor(evaluators);
        SceneRuleIndex index = new SceneRuleIndex();
        index.setStrategy("t1", "fraud", SceneExecutionStrategy.HIGHEST_PRIORITY);
        return new EvalEngine(index, new EvalContextAssembler(List.of(), List.of()),
                Map.of(), Map.of("AST_BOOLEAN", interpreted), true);
    }

    @Test
    void collectTraceParam_togglesNodeTrace_butKeepsSameRuleHit() {
        EvalEngine engine = engine();
        RuleEvent event = event();
        List<RuleVersionSnapshot> candidates = List.of(snapshot());
        Instant now = Instant.now();

        EvalOutcome off = engine.evaluateWithContext(event, candidates,
                SceneExecutionStrategy.HIGHEST_PRIORITY, now, false);
        EvalOutcome on = engine.evaluateWithContext(event, candidates,
                SceneExecutionStrategy.HIGHEST_PRIORITY, now, true);

        assertThat(off.result().nodeTrace()).isEmpty();
        assertThat(on.result().nodeTrace()).isNotEmpty();
        assertThat(off.result().ruleHit()).isEqualTo(on.result().ruleHit());
    }
}
