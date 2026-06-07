package com.sstlfsj.rule.job.internal.runner;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class EventIdHasherTest {

    @Test
    void sameInputProducesStableEventId() {
        assertEquals(EventIdHasher.hash(1L, "u1"), EventIdHasher.hash(1L, "u1"));
    }

    @Test
    void differentSubjectProducesDifferentEventId() {
        assertNotEquals(EventIdHasher.hash(1L, "u1"), EventIdHasher.hash(1L, "u2"));
    }

    @Test
    void differentJobRunProducesDifferentEventId() {
        assertNotEquals(EventIdHasher.hash(1L, "u1"), EventIdHasher.hash(2L, "u1"));
    }
}
