package com.sstlfsj.rule.eval.internal.condition;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class NotContainsEvaluatorTest extends BaseEvaluatorTest {

    private final NotContainsEvaluator ev = new NotContainsEvaluator();

    private EvalContext ctxWithList(String metricCode, Object... items) {
        MetricValue mv = new MetricValue(List.of(items), "LIST", "PROVIDED");
        RuleEvent evt = new RuleEvent("t1","s1","E","u1","id1", Instant.now(), Map.of(), Map.of());
        return new EvalContext("t1", evt, null, Map.of(metricCode, mv));
    }

    private ConditionNode notContains(Object element) {
        return new ConditionNode("NOT_CONTAINS", "m", null, Map.of("element", element), 0.0);
    }

    @Test void elementAbsent_returnsTrue()        { assertTrue(ev.evaluate(notContains("D"), ctxWithList("m","A","B","C"))); }
    @Test void elementPresent_returnsFalse()      { assertFalse(ev.evaluate(notContains("B"), ctxWithList("m","A","B","C"))); }
    @Test void metricMissing_returnsTrue()        { assertTrue(ev.evaluate(notContains("A"), emptyCtx())); }
    @Test void metricNotList_returnsTrue()        { assertTrue(ev.evaluate(notContains("A"), ctxWith("m","notalist"))); }
    @Test void elementParamMissing_returnsTrue()  {
        ConditionNode n = new ConditionNode("NOT_CONTAINS", "m", null, Map.of(), 0.0);
        assertTrue(ev.evaluate(n, ctxWithList("m","A")));
    }
}
