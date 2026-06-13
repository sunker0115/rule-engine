package com.sstlfsj.rule.kernel.api.operator;

import com.sstlfsj.rule.kernel.api.model.ConditionParams;
import com.sstlfsj.rule.kernel.api.model.DataType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ParamSpecTest {

    @Test
    void numeric_thresholdAndNumericTypes() {
        assertThat(ParamSpec.NUMERIC.requiredParamKeys).containsExactly(ConditionParams.THRESHOLD);
        assertThat(ParamSpec.NUMERIC.allowedDataTypes)
                .contains(DataType.LONG.tag(), DataType.DOUBLE.tag(), DataType.DECIMAL.tag());
        assertThat(ParamSpec.NUMERIC.requiresMetric).isTrue();
    }

    @Test
    void timeWindowOp_noMetric_startEnd() {
        assertThat(ParamSpec.TIME_WINDOW_OP.requiredParamKeys)
                .contains(ConditionParams.START, ConditionParams.END);
        assertThat(ParamSpec.TIME_WINDOW_OP.allowedDataTypes).isEmpty();
        assertThat(ParamSpec.TIME_WINDOW_OP.requiresMetric).isFalse();
    }

    @Test
    void none_empty() {
        assertThat(ParamSpec.NONE.requiredParamKeys).isEmpty();
        assertThat(ParamSpec.NONE.allowedDataTypes).isEmpty();
    }
}
