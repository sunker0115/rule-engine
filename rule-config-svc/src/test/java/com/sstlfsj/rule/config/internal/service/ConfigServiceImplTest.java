package com.sstlfsj.rule.config.internal.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

/** Verifies ConfigServiceImpl stub methods throw UnsupportedOperationException. */
class ConfigServiceImplTest {

    private final ConfigServiceImpl impl = new ConfigServiceImpl();

    @Test
    void publish_throwsUnsupportedOperation() {
        assertThrows(UnsupportedOperationException.class,
                () -> impl.publish("tenant1", 1L, "actor1"));
    }

    @Test
    void disable_throwsUnsupportedOperation() {
        assertThrows(UnsupportedOperationException.class,
                () -> impl.disable("tenant1", 1L, "actor1"));
    }
}
