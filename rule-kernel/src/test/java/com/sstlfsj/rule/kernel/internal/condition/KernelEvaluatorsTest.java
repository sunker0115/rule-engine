package com.sstlfsj.rule.kernel.internal.condition;

import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KernelEvaluatorsTest {

    @Test
    void defaults_containsAllBuiltinOperators() {
        Map<String, ConditionEvaluator> m = KernelEvaluators.defaults();
        assertThat(m).containsKeys(
                "EQ", "NEQ", "GT", "GTE", "LT", "LTE",
                "IN", "NOT_IN", "BETWEEN", "NOT_BETWEEN",
                "CONTAINS", "NOT_CONTAINS",
                "STARTS_WITH", "ENDS_WITH", "MATCHES",
                "DATE_BEFORE", "DATE_AFTER"
        );
    }

    @Test
    void defaults_returnsImmutableMap() {
        Map<String, ConditionEvaluator> m = KernelEvaluators.defaults();
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> m.put("CUSTOM", (node, ctx) -> false));
    }

    @Test
    void defaults_eachCallReturnsNewMap() {
        assertThat(KernelEvaluators.defaults()).isNotSameAs(KernelEvaluators.defaults());
    }
}
