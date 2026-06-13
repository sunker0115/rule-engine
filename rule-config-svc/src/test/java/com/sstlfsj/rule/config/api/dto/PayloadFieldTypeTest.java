package com.sstlfsj.rule.config.api.dto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PayloadFieldTypeTest {
    @Test
    void fromTag_valid() {
        assertThat(PayloadFieldType.fromTag("NUMBER")).isEqualTo(PayloadFieldType.NUMBER);
        assertThat(PayloadFieldType.fromTag("string")).isEqualTo(PayloadFieldType.STRING);
    }

    @Test
    void fromTag_invalid_throws() {
        assertThatThrownBy(() -> PayloadFieldType.fromTag("STRIGN"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("STRIGN");
    }

    @Test
    void fromTag_null_throws() {
        assertThatThrownBy(() -> PayloadFieldType.fromTag(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
