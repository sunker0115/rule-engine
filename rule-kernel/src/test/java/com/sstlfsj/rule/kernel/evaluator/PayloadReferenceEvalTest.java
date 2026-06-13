package com.sstlfsj.rule.kernel.evaluator;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.EvalEnv;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.EventSource;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.ValueRef;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.internal.condition.KernelEvaluators;
import com.sstlfsj.rule.kernel.internal.context.EvalContextAssembler;
import com.sstlfsj.rule.kernel.internal.evaluator.InterpretedExecutor;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * payload 引用端到端求值：valueRef=PAYLOAD 的 ConditionNode，metricCode 复用为 payload 字段名，
 * 经 EvalContextAssembler 注入后由真实 GT 算子求值。
 * 验证注入链路与算子读取贯通——payload 值不手工预置进 metrics，全程走 assemble 注入。
 */
class PayloadReferenceEvalTest {

    /** GT 算子按 amount > 1000 判定，amount 走 PAYLOAD 引用、DECIMAL 精确比较。 */
    private static final ConditionNode AMOUNT_GT_1000 = new ConditionNode(
            "GT", "amount", null, Map.of("threshold", 1000), 0.0, "DECIMAL", ValueRef.PAYLOAD);

    private EvalContext assembleWithPayload(Map<String, Object> payload) {
        // 退化构造：无取数，payload 由 injectPayload 注入值 map
        EvalContextAssembler assembler = new EvalContextAssembler(List.of(), List.of());
        RuleEvent event = new RuleEvent(
                "t1", "PAY", "transfer", "u1", "evt-1", Instant.now(),
                payload, Map.of(), EventSource.HTTP);
        return assembler.assemble(event, List.of(), new EvalEnv(Instant.now(), java.util.Map.of()));
    }

    private boolean evaluateAmountGt(Map<String, Object> payload) {
        RuleVersionSnapshot snap = RuleVersionSnapshot.builder()
                .ruleVersionId(1L).sceneCode("PAY").tenantId("t1").conditionAst(AMOUNT_GT_1000)
                .build();
        EvalResult result = new InterpretedExecutor(KernelEvaluators.defaults())
                .execute(snap, assembleWithPayload(payload));
        return result.ruleHit();
    }

    @Test
    void payloadGt_hits_whenAboveThreshold() {
        // payload.amount=5000 > 1000 → 命中
        assertThat(evaluateAmountGt(Map.of("amount", 5000))).isTrue();
    }

    @Test
    void payloadGt_misses_whenBelowThreshold() {
        // payload.amount=500 < 1000 → 未命中
        assertThat(evaluateAmountGt(Map.of("amount", 500))).isFalse();
    }
}
