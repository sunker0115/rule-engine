package com.sstlfsj.rule.eval.internal.condition;

import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class MatchesEvaluatorTest extends BaseEvaluatorTest {

    private final MatchesEvaluator ev = new MatchesEvaluator();

    private ConditionNode matches(String regex) {
        return new ConditionNode("MATCHES", "m", null, Map.of("regex", regex), 0.0);
    }

    @Test void fullMatch_returnsTrue()         { assertTrue(ev.evaluate(matches("\\d{3}"), ctxWith("m", "123"))); }
    @Test void partialMatch_returnsFalse()     { assertFalse(ev.evaluate(matches("\\d{3}"), ctxWith("m", "123abc"))); }
    @Test void noMatch_returnsFalse()          { assertFalse(ev.evaluate(matches("[a-z]+"), ctxWith("m", "123"))); }
    @Test void complexRegex_returnsTrue()      { assertTrue(ev.evaluate(matches("^[A-Z]{2}\\d{4}$"), ctxWith("m","AB1234"))); }
    @Test void invalidRegex_returnsFalse()     { assertFalse(ev.evaluate(matches("[invalid"), ctxWith("m","abc"))); }
    @Test void metricMissing_returnsFalse()    { assertFalse(ev.evaluate(matches(".*"), emptyCtx())); }
    @Test void regexParamMissing_returnsFalse() {
        ConditionNode n = new ConditionNode("MATCHES", "m", null, Map.of(), 0.0);
        assertFalse(ev.evaluate(n, ctxWith("m", "abc")));
    }
}
