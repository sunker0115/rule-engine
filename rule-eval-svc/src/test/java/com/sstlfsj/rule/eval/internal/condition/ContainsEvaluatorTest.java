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

class ContainsEvaluatorTest extends BaseEvaluatorTest {

    private final ContainsEvaluator ev = new ContainsEvaluator();

    private EvalContext ctxWithList(String metricCode, Object... items) {
        MetricValue mv = new MetricValue(List.of(items), "LIST", "PROVIDED");
        RuleEvent evt = new RuleEvent("t1","s1","E","u1","id1", Instant.now(), Map.of(), Map.of());
        return new EvalContext("t1", evt, null, Map.of(metricCode, mv));
    }

    private ConditionNode contains(Object element) {
        return new ConditionNode("CONTAINS", "m", null, Map.of("element", element), 0.0);
    }

    @Test void elementPresent_returnsTrue()       { assertTrue(ev.evaluate(contains("B"), ctxWithList("m","A","B","C"))); }
    @Test void elementAbsent_returnsFalse()       { assertFalse(ev.evaluate(contains("D"), ctxWithList("m","A","B","C"))); }
    @Test void numericElementPresent_returnsTrue(){ assertTrue(ev.evaluate(contains(2), ctxWithList("m",1,2,3))); }
    @Test void metricMissing_returnsFalse()       { assertFalse(ev.evaluate(contains("A"), emptyCtx())); }
    @Test void metricNotList_returnsFalse()       { assertFalse(ev.evaluate(contains("A"), ctxWith("m","notalist"))); }
    @Test void elementParamMissing_returnsFalse() {
        ConditionNode n = new ConditionNode("CONTAINS", "m", null, Map.of(), 0.0);
        assertFalse(ev.evaluate(n, ctxWithList("m","A")));
    }
}
