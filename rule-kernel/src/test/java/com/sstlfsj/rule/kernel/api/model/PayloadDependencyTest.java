package com.sstlfsj.rule.kernel.api.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PayloadDependencyTest {
    @Test
    void holdsNameDataTypeRequired() {
        PayloadDependency d = new PayloadDependency("amount", "DECIMAL", true);
        assertEquals("amount", d.name());
        assertEquals("DECIMAL", d.dataType());
        assertTrue(d.required());
    }
}
