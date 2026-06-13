package com.sstlfsj.rule.kernel.internal.condition;

import com.sstlfsj.rule.kernel.api.model.ConditionTypes;
import com.sstlfsj.rule.kernel.api.model.ConditionParams;
import com.sstlfsj.rule.kernel.api.operator.OperatorSpec;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ConditionTypeCatalogKernelTest {

    @Test
    void spec_gt_requiredParamKeys() {
        OperatorSpec spec = ConditionTypeCatalog.spec(ConditionTypes.GT);
        assertThat(spec).isNotNull();
        assertThat(spec.requiredParamKeys()).containsExactly(ConditionParams.THRESHOLD);
    }

    @Test
    void all_covers19Operators() {
        assertThat(ConditionTypeCatalog.all()).hasSize(19);
    }

    @Test
    void spec_timeWindow_requiresMetricFalse() {
        OperatorSpec spec = ConditionTypeCatalog.spec(ConditionTypes.TIME_WINDOW);
        assertThat(spec).isNotNull();
        assertThat(spec.requiresMetric()).isFalse();
        assertThat(spec.requiredParamKeys()).contains(ConditionParams.START, ConditionParams.END);
    }

    @Test
    void spec_unknown_returnsNull() {
        assertThat(ConditionTypeCatalog.spec("CUSTOM_OP")).isNull();
    }
}
