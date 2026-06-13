package com.sstlfsj.rule.kernel.api.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 锁定 param 键字面量值（须与各 ConditionEvaluator 消费端一致）。 */
class ConditionParamsTest {

    @Test
    void keyValues() {
        assertThat(ConditionParams.THRESHOLD).isEqualTo("threshold");
        assertThat(ConditionParams.MIN).isEqualTo("min");
        assertThat(ConditionParams.MAX).isEqualTo("max");
        assertThat(ConditionParams.VALUES).isEqualTo("values");
        assertThat(ConditionParams.ELEMENT).isEqualTo("element");
        assertThat(ConditionParams.PREFIX).isEqualTo("prefix");
        assertThat(ConditionParams.SUFFIX).isEqualTo("suffix");
        assertThat(ConditionParams.REGEX).isEqualTo("regex");
        assertThat(ConditionParams.TIMEZONE).isEqualTo("timezone");
    }
}
