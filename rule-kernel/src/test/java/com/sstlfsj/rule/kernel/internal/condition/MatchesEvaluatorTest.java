package com.sstlfsj.rule.kernel.internal.condition;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import com.sstlfsj.rule.kernel.api.model.Subject;
import com.sstlfsj.rule.kernel.api.model.SubjectType;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MatchesEvaluatorTest {

    private final MatchesEvaluator evaluator = new MatchesEvaluator();

    private EvalContext ctx(String metric, Object value) {
        RuleEvent event = new RuleEvent("e1", "t1", "s1", "sub1", "EVT",
                Instant.now(), Map.of(), Map.of());
        return new EvalContext("t1", event, new Subject("sub1", SubjectType.USER, Map.of()),
                Map.of(metric, new MetricValue(value, "UNKNOWN", "PROVIDED")));
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
    void metricMissing_returnsFalse() {
        RuleEvent event = new RuleEvent("e1", "t1", "s1", "sub1", "EVT",
                Instant.now(), Map.of(), Map.of());
        EvalContext emptyCtx = new EvalContext("t1", event,
                new Subject("sub1", SubjectType.USER, Map.of()), Map.of());
        assertThat(evaluator.evaluate(node("phone", "\\d+"), emptyCtx)).isFalse();
    }
}
