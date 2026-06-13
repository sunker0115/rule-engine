package com.sstlfsj.rule.kernel.api.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.assertj.core.api.Assertions.assertThat;

class PayloadDependencyTest {
    @Test
    void holdsNameDataTypeRequired() {
        PayloadDependency d = new PayloadDependency("amount", "DECIMAL", true);
        assertEquals("amount", d.name());
        assertEquals("DECIMAL", d.dataType());
        assertTrue(d.required());
    }

    @Test
    void builder_carriesConstraints() {
        PayloadDependency d = PayloadDependency.builder()
                .name("amount").dataType("DECIMAL").required(true)
                .enumValues(java.util.List.of(1, 2)).minimum(0.0).maximum(100.0).pattern("\\d+")
                .build();
        assertThat(d.enumValues()).containsExactly(1, 2);
        assertThat(d.minimum()).isEqualTo(0.0);
        assertThat(d.maximum()).isEqualTo(100.0);
        assertThat(d.pattern()).isEqualTo("\\d+");
    }

    @Test
    void compatConstructor_defaultsConstraintsNull() {
        PayloadDependency d = new PayloadDependency("amount", "DECIMAL", true);
        assertThat(d.enumValues()).isNull();
        assertThat(d.minimum()).isNull();
        assertThat(d.pattern()).isNull();
    }
}
