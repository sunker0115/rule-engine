package com.sstlfsj.rule.kernel.api.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ValueRefTest {
    @Test
    void tag_equalsName() {
        assertEquals("METRIC", ValueRef.METRIC.tag());
        assertEquals("PAYLOAD", ValueRef.PAYLOAD.tag());
    }
}
