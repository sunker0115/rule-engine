package com.sstlfsj.rule.kernel.api.operator;

import com.sstlfsj.rule.kernel.api.model.ConditionParams;
import com.sstlfsj.rule.kernel.api.model.DataType;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

class OperatorSpecTest {
    @Test
    void builder_roundTrip() {
        OperatorSpec spec = OperatorSpec.builder()
                .code("GT").displayName("大于")
                .requiredParamKeys(Set.of(ConditionParams.THRESHOLD))
                .allowedDataTypes(Set.of(DataType.LONG.tag()))
                .requiresMetric(true).build();
        assertThat(spec.code()).isEqualTo("GT");
        assertThat(spec.requiresMetric()).isTrue();
        assertThat(spec.requiredParamKeys()).containsExactly(ConditionParams.THRESHOLD);
    }
}
