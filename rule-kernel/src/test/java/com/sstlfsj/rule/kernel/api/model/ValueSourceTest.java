package com.sstlfsj.rule.kernel.api.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** ValueSource 标签契约：tag 值落 node_trace.value_source ENUM，不可随意改动。 */
class ValueSourceTest {

    @Test
    void tag_matchesPersistedContract() {
        assertEquals("PROVIDED", ValueSource.PROVIDED.tag());
        assertEquals("FETCHED", ValueSource.FETCHED.tag());
    }

    @Test
    void payload_tag() {
        assertEquals("PAYLOAD", ValueSource.PAYLOAD.tag());
    }
}
