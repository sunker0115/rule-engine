package com.sstlfsj.rule.config.internal.publish;

import com.sstlfsj.rule.kernel.api.model.ConditionParams;
import com.sstlfsj.rule.kernel.api.model.ConditionTypes;
import com.sstlfsj.rule.kernel.api.model.DataType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConditionTypeCatalogTest {

    @Test
    void gt_requiresThreshold_allowsNumeric() {
        ConditionTypeCatalog.Spec s = ConditionTypeCatalog.spec(ConditionTypes.GT);
        assertThat(s).isNotNull();
        assertThat(s.requiredParamKeys()).containsExactly(ConditionParams.THRESHOLD);
        assertThat(s.allowedDataTypes()).contains(DataType.LONG.tag(), DataType.DOUBLE.tag(), DataType.DECIMAL.tag());
    }

    @Test
    void between_requiresMinMax() {
        assertThat(ConditionTypeCatalog.spec(ConditionTypes.BETWEEN).requiredParamKeys())
                .containsExactlyInAnyOrder(ConditionParams.MIN, ConditionParams.MAX);
    }

    @Test
    void matches_requiresRegex_allowsString() {
        ConditionTypeCatalog.Spec s = ConditionTypeCatalog.spec(ConditionTypes.MATCHES);
        assertThat(s.requiredParamKeys()).containsExactly(ConditionParams.REGEX);
        assertThat(s.allowedDataTypes()).containsExactly(DataType.STRING.tag());
    }

    @Test
    void contains_requiresElement_allowsList() {
        ConditionTypeCatalog.Spec s = ConditionTypeCatalog.spec(ConditionTypes.CONTAINS);
        assertThat(s.requiredParamKeys()).containsExactly(ConditionParams.ELEMENT);
        assertThat(s.allowedDataTypes()).containsExactly(DataType.LIST.tag());
    }

    @Test
    void unknownType_returnsNull() {
        assertThat(ConditionTypeCatalog.spec("CUSTOM_OP")).isNull();
    }

    @Test
    void all_covers17Operators() {
        assertThat(ConditionTypeCatalog.all()).hasSize(17);
    }
}
