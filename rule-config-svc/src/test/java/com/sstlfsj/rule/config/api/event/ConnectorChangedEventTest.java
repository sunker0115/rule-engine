package com.sstlfsj.rule.config.api.event;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 验证 ConnectorChangedEvent record 构造与 accessor 的正确性。 */
class ConnectorChangedEventTest {

    @Test
    void constructor_andAccessors() {
        ConnectorChangedEvent event = new ConnectorChangedEvent("tenant1", "risk-svc");

        assertEquals("tenant1", event.tenantId());
        assertEquals("risk-svc", event.connectorCode());
    }

    @Test
    void recordEquality() {
        ConnectorChangedEvent a = new ConnectorChangedEvent("t1", "C1");
        ConnectorChangedEvent b = new ConnectorChangedEvent("t1", "C1");
        assertEquals(a, b);
    }
}
