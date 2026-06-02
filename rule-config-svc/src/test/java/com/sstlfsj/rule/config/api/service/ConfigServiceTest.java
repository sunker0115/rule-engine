package com.sstlfsj.rule.config.api.service;

import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies ConfigService contract: method signatures compile and a stub
 * implementation correctly propagates UnsupportedOperationException.
 */
class ConfigServiceTest {

    private final ConfigService stub = new ConfigService() {
        @Override
        public RuleVersionSnapshot publish(String tenantId, Long ruleDefinitionId, String actorId) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public void disable(String tenantId, Long ruleDefinitionId, String actorId) {
            throw new UnsupportedOperationException("stub");
        }
    };

    @Test
    void publish_stubThrowsUnsupported() {
        assertThrows(UnsupportedOperationException.class,
                () -> stub.publish("t1", 1L, "actor"));
    }

    @Test
    void disable_stubThrowsUnsupported() {
        assertThrows(UnsupportedOperationException.class,
                () -> stub.disable("t1", 1L, "actor"));
    }
}
