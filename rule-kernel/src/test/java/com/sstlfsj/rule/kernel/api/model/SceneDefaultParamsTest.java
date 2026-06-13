package com.sstlfsj.rule.kernel.api.model;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SceneDefaultParamsTest {
    @Test
    void timezoneKeyValue() {
        assertThat(SceneDefaultParams.TIMEZONE).isEqualTo("timezone");
    }
}
