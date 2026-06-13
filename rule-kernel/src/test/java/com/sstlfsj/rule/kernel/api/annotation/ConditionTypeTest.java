package com.sstlfsj.rule.kernel.api.annotation;

import com.sstlfsj.rule.kernel.api.model.ConditionParams;
import com.sstlfsj.rule.kernel.api.model.DataType;
import com.sstlfsj.rule.kernel.api.operator.ParamSpec;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

class ConditionTypeTest {

    @ConditionType("AMOUNT_GT")
    static class MinimalHandler {}

    @ConditionType(value = "AGE_LT", displayName = "年龄小于", schema = ParamSpec.NUMERIC)
    static class FullHandler {}

    @Test
    void annotation_isPresentAtRuntime() {
        assertNotNull(MinimalHandler.class.getAnnotation(ConditionType.class));
    }

    @Test
    void value_isReadCorrectly() {
        assertThat(MinimalHandler.class.getAnnotation(ConditionType.class).value())
                .isEqualTo("AMOUNT_GT");
    }

    @Test
    void defaults_schema_isNone() {
        ConditionType ann = MinimalHandler.class.getAnnotation(ConditionType.class);
        assertThat(ann.displayName()).isEmpty();
        assertThat(ann.schema()).isEqualTo(ParamSpec.NONE);
        assertThat(ann.schema().requiredParamKeys).isEmpty();
        assertThat(ann.schema().requiresMetric).isTrue();
    }

    @Test
    void full_schema_numeric() {
        ConditionType ann = FullHandler.class.getAnnotation(ConditionType.class);
        assertThat(ann.value()).isEqualTo("AGE_LT");
        assertThat(ann.displayName()).isEqualTo("年龄小于");
        assertThat(ann.schema()).isEqualTo(ParamSpec.NUMERIC);
        assertThat(ann.schema().requiredParamKeys).containsExactly(ConditionParams.THRESHOLD);
        assertThat(ann.schema().allowedDataTypes).contains(DataType.LONG.tag(), DataType.DECIMAL.tag());
        assertThat(ann.schema().requiresMetric).isTrue();
    }

    @Test
    void retentionIsRuntime() {
        assertThat(ConditionType.class.getAnnotation(Retention.class).value())
                .isEqualTo(RetentionPolicy.RUNTIME);
    }

    @Test
    void targetIsType() {
        Target target = ConditionType.class.getAnnotation(Target.class);
        assertThat(target).isNotNull();
        assertThat(target.value()).containsExactly(ElementType.TYPE);
    }

    @Test
    void isDocumentedAnnotationType() {
        assertTrue(ConditionType.class.isAnnotation());
        assertNotNull(ConditionType.class.getAnnotation(java.lang.annotation.Documented.class));
    }
}
