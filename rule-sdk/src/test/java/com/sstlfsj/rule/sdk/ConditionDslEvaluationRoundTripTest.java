package com.sstlfsj.rule.sdk;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.EventSource;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.internal.condition.KernelEvaluators;
import com.sstlfsj.rule.kernel.internal.evaluator.InterpretedExecutor;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DSL ↔ evaluator 往返契约测试：每个 {@link Condition} 算子经 DSL 构造 → 真过解释器求值，
 * 断言"应命中的输入确实命中"。守住 SDK 生产端 param 键与 kernel evaluator 消费端键的一致性
 * （此类 bug 曾因两端各测各的、无跨缝测试而漏网）。
 */
class ConditionDslEvaluationRoundTripTest {

    private final InterpretedExecutor executor = new InterpretedExecutor(KernelEvaluators.defaults());

    /** 单条件规则在 metric=value 上求值，返回是否命中。 */
    private boolean hits(Condition condition, String metric, Object value) {
        RuleVersionSnapshot snap = new RuleVersionSnapshot(
                1L, "s1", "t1", condition.toAst(), null, null, null, "AST_BOOLEAN");
        RuleEvent event = new RuleEvent("t1", "s1", "EVT", "sub1", "evt-1",
                Instant.now(), Map.of(), Map.of(), EventSource.HTTP);
        EvalContext ctx = new EvalContext("t1", event, null,
                Map.of(metric, new MetricValue(value, "UNKNOWN", "PROVIDED")),
                Instant.parse("2026-06-01T00:00:00Z"));
        return executor.execute(snap, ctx).ruleHit();
    }

    @Test
    void eq() {
        assertThat(hits(Condition.eq("m", 100), "m", 100)).isTrue();
        assertThat(hits(Condition.eq("m", 100), "m", 101)).isFalse();
    }

    @Test
    void neq() {
        assertThat(hits(Condition.neq("m", 100), "m", 200)).isTrue();
        assertThat(hits(Condition.neq("m", 100), "m", 100)).isFalse();
    }

    @Test
    void gt_gte_lt_lte() {
        assertThat(hits(Condition.gt("m", 100), "m", 200)).isTrue();
        assertThat(hits(Condition.gte("m", 100), "m", 100)).isTrue();
        assertThat(hits(Condition.lt("m", 100), "m", 50)).isTrue();
        assertThat(hits(Condition.lte("m", 100), "m", 100)).isTrue();
    }

    @Test
    void in_notIn() {
        assertThat(hits(Condition.in("m", "A", "B"), "m", "A")).isTrue();
        assertThat(hits(Condition.notIn("m", "A", "B"), "m", "C")).isTrue();
    }

    @Test
    void between() {
        assertThat(hits(Condition.between("m", 10, 20), "m", 15)).isTrue();
        assertThat(hits(Condition.between("m", 10, 20), "m", 25)).isFalse();
    }

    @Test
    void contains() {
        // CONTAINS 是集合成员语义：metric 值为 Collection，检查是否含指定 element
        assertThat(hits(Condition.contains("m", "ab"), "m", List.of("ab", "cd"))).isTrue();
        assertThat(hits(Condition.contains("m", "ab"), "m", List.of("xy", "z"))).isFalse();
    }

    @Test
    void matches() {
        assertThat(hits(Condition.matches("m", "\\d+"), "m", "123")).isTrue();
        assertThat(hits(Condition.matches("m", "\\d+"), "m", "abc")).isFalse();
    }

    @Test
    void startsWith() {
        assertThat(hits(Condition.startsWith("m", "pre"), "m", "prefix")).isTrue();
        assertThat(hits(Condition.startsWith("m", "pre"), "m", "suffix")).isFalse();
    }

    @Test
    void endsWith() {
        assertThat(hits(Condition.endsWith("m", "fix"), "m", "prefix")).isTrue();
        assertThat(hits(Condition.endsWith("m", "fix"), "m", "prepend")).isFalse();
    }
}
