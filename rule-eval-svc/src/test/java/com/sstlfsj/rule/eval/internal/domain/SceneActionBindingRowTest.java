package com.sstlfsj.rule.eval.internal.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** SceneActionBindingRow record 定义验证：构造、访问器、等值语义。 */
class SceneActionBindingRowTest {

    @Test
    void constructor_andAccessors_roundTrip() {
        SceneActionBindingRow row = new SceneActionBindingRow("SEND_ALERT", "{\"channel\":\"sms\"}");
        assertEquals("SEND_ALERT", row.actionType());
        assertEquals("{\"channel\":\"sms\"}", row.defaultParamsJson());
    }

    @Test
    void nullParams_allowed() {
        SceneActionBindingRow row = new SceneActionBindingRow("FREEZE_ACCOUNT", null);
        assertEquals("FREEZE_ACCOUNT", row.actionType());
        assertNull(row.defaultParamsJson());
    }

    @Test
    void recordEquality_sameValues_areEqual() {
        SceneActionBindingRow a = new SceneActionBindingRow("NOTIFY", "{}");
        SceneActionBindingRow b = new SceneActionBindingRow("NOTIFY", "{}");
        assertEquals(a, b);
    }
}
