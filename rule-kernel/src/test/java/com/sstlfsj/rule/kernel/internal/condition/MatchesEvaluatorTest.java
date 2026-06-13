package com.sstlfsj.rule.kernel.internal.condition;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import com.sstlfsj.rule.kernel.api.model.Subject;
import com.sstlfsj.rule.kernel.api.model.SubjectType;
import com.sstlfsj.rule.kernel.api.model.ConditionParams;
import com.sstlfsj.rule.kernel.api.model.ConditionTypes;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class MatchesEvaluatorTest {

    private final MatchesEvaluator evaluator = new MatchesEvaluator();

    private EvalContext ctx(String metric, Object value) {
        RuleEvent event = new RuleEvent("e1", "t1", "s1", "sub1", "EVT",
                Instant.now(), Map.of(), Map.of(), com.sstlfsj.rule.kernel.api.model.EventSource.HTTP);
        return new EvalContext("t1", event, new Subject("sub1", SubjectType.USER, Map.of()),
                Map.of(metric, new MetricValue(value, "UNKNOWN", "PROVIDED")), Instant.parse("2026-06-01T00:00:00Z"));
    }

    private ConditionNode node(String metric, String regex) {
        return new ConditionNode("MATCHES", metric, "", Map.of("regex", regex), 0.0);
    }

    @Test
    void regexMatches_returnsTrue() {
        assertThat(evaluator.evaluate(node("phone", "\\d{11}"), ctx("phone", "13800138000"))).isTrue();
    }

    @Test
    void regexNotMatches_returnsFalse() {
        assertThat(evaluator.evaluate(node("phone", "\\d{11}"), ctx("phone", "abc"))).isFalse();
    }

    @Test
    void invalidRegex_returnsFalse() {
        assertThat(evaluator.evaluate(node("v", "[invalid"), ctx("v", "test"))).isFalse();
    }

    @Test
    void sameRegexReused_acrossEvals_staysCorrect() {
        // 同一正则多次评估(命中编译缓存)结果不串味
        ConditionNode n = node("phone", "\\d{11}");
        assertThat(evaluator.evaluate(n, ctx("phone", "13800138000"))).isTrue();
        assertThat(evaluator.evaluate(n, ctx("phone", "abc"))).isFalse();
        assertThat(evaluator.evaluate(n, ctx("phone", "13900139000"))).isTrue();
    }

    @Test
    void invalidRegex_reused_staysFalse() {
        // 非法正则负缓存：多次评估均 false，不抛
        ConditionNode n = node("v", "[invalid");
        assertThat(evaluator.evaluate(n, ctx("v", "test"))).isFalse();
        assertThat(evaluator.evaluate(n, ctx("v", "other"))).isFalse();
    }

    @Test
    void redos_pathologicalPattern_completesLinearly() {
        // 病态正则 + 不匹配长输入：回溯引擎(java.util.regex)会灾难性回溯卡死，RE2J 线性时间秒回 false
        String evil = "(a+)+$";
        String input = "a".repeat(40) + "!";
        assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> assertThat(evaluator.evaluate(node("v", evil), ctx("v", input))).isFalse());
    }

    @Test
    void metricMissing_returnsFalse() {
        RuleEvent event = new RuleEvent("e1", "t1", "s1", "sub1", "EVT",
                Instant.now(), Map.of(), Map.of(), com.sstlfsj.rule.kernel.api.model.EventSource.HTTP);
        EvalContext emptyCtx = new EvalContext("t1", event,
                new Subject("sub1", SubjectType.USER, Map.of()), Map.of(), Instant.parse("2026-06-01T00:00:00Z"));
        assertThat(evaluator.evaluate(node("phone", "\\d+"), emptyCtx)).isFalse();
    }

    @Test
    void spec_describesOperator() {
        var spec = evaluator.spec().orElseThrow();
        assertThat(spec.code()).isEqualTo(ConditionTypes.MATCHES);
        assertThat(spec.requiredParamKeys()).isEqualTo(Set.of(ConditionParams.REGEX));
        assertThat(spec.requiresMetric()).isTrue();
    }
}
