package com.sstlfsj.rule.eval.internal.condition;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class DateBeforeEvaluatorTest extends BaseEvaluatorTest {

    private final DateBeforeEvaluator ev = new DateBeforeEvaluator();

    private EvalContext ctxWithDate(String metricCode, String isoDate) {
        MetricValue mv = new MetricValue(isoDate, "DATE", "PROVIDED");
        RuleEvent evt = new RuleEvent("t1","s1","E","u1","id1", Instant.now(), Map.of(), Map.of());
        return new EvalContext("t1", evt, null, Map.of(metricCode, mv));
    }

    private ConditionNode dateBefore(String threshold) {
        return new ConditionNode("DATE_BEFORE", "m", null, Map.of("threshold", threshold), 0.0);
    }

    @Test void dateStrictlyBefore_returnsTrue()  { assertTrue(ev.evaluate(dateBefore("2024-06-01"), ctxWithDate("m","2024-01-01"))); }
    @Test void dateSameDay_returnsFalse()         { assertFalse(ev.evaluate(dateBefore("2024-01-01"), ctxWithDate("m","2024-01-01"))); }
    @Test void dateAfter_returnsFalse()           { assertFalse(ev.evaluate(dateBefore("2024-01-01"), ctxWithDate("m","2024-06-01"))); }
    @Test void isoDatetimeBefore_returnsTrue()    { assertTrue(ev.evaluate(dateBefore("2024-06-01T00:00:00Z"), ctxWithDate("m","2024-01-01T00:00:00Z"))); }
    @Test void metricMissing_returnsFalse()       { assertFalse(ev.evaluate(dateBefore("2024-01-01"), emptyCtx())); }
    @Test void invalidThreshold_returnsFalse()    { assertFalse(ev.evaluate(dateBefore("not-a-date"), ctxWithDate("m","2024-01-01"))); }
    @Test void thresholdParamMissing_returnsFalse() {
        ConditionNode n = new ConditionNode("DATE_BEFORE", "m", null, Map.of(), 0.0);
        assertFalse(ev.evaluate(n, ctxWithDate("m","2024-01-01")));
    }
}
