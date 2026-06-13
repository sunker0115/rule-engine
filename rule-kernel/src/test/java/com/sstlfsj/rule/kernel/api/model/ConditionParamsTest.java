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

    @Test
    void timeWindowKeys() {
        assertThat(ConditionParams.START).isEqualTo("start");
        assertThat(ConditionParams.END).isEqualTo("end");
        assertThat(ConditionParams.DATES_EXCLUDE).isEqualTo("datesExclude");
        assertThat(ConditionParams.DAYS_OF_WEEK).isEqualTo("daysOfWeek");
        assertThat(ConditionParams.OPERATOR).isEqualTo("operator");
        assertThat(ConditionParams.VALUE).isEqualTo("value");
    }
}
