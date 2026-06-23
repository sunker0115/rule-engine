package com.sstlfsj.rule.stream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TradeStreamJobTest {

    @Test
    void getEnv_returnsDefaultWhenUnset() {
        // 用一个几乎不会被设置的 key，验证回退到默认值
        String v = TradeStreamJob.getEnv("RULE_STREAM_RT_NONEXISTENT_KEY_FOR_TEST", "rt.suspect.customer");
        assertThat(v).isEqualTo("rt.suspect.customer");
    }
}
