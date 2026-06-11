package com.sstlfsj.rule.kernel.api.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SubjectTypeTest {

    @Test
    void allValues_areDefined() {
        SubjectType[] values = SubjectType.values();
        assertEquals(5, values.length);
    }

    @Test
    void valueOf_returnsCorrectConstant() {
        assertEquals(SubjectType.USER, SubjectType.valueOf("USER"));
        assertEquals(SubjectType.ACCOUNT, SubjectType.valueOf("ACCOUNT"));
        assertEquals(SubjectType.DEVICE, SubjectType.valueOf("DEVICE"));
        assertEquals(SubjectType.ORDER, SubjectType.valueOf("ORDER"));
        assertEquals(SubjectType.CUSTOM, SubjectType.valueOf("CUSTOM"));
    }
}
