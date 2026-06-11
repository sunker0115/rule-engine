package com.sstlfsj.rule.kernel.api.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** DataType 标签契约：tag 值 = ConditionNode.dataType / metric_definition.data_type 持久化值，不可随意改动。 */
class DataTypeTest {

    @Test
    void tag_matchesPersistedContract() {
        assertEquals("LONG", DataType.LONG.tag());
        assertEquals("DOUBLE", DataType.DOUBLE.tag());
        assertEquals("DECIMAL", DataType.DECIMAL.tag());
        assertEquals("STRING", DataType.STRING.tag());
        assertEquals("BOOLEAN", DataType.BOOLEAN.tag());
        assertEquals("DATE", DataType.DATE.tag());
        assertEquals("DATETIME", DataType.DATETIME.tag());
        assertEquals("LIST", DataType.LIST.tag());
        assertEquals("UNKNOWN", DataType.UNKNOWN.tag());
    }

    @Test
    void fromTag_roundTripsEveryConstant() {
        for (DataType d : DataType.values()) {
            assertSame(d, DataType.fromTag(d.tag()));
        }
    }

    @Test
    void fromTag_nullOrUnrecognized_isUnknown() {
        assertSame(DataType.UNKNOWN, DataType.fromTag(null));
        assertSame(DataType.UNKNOWN, DataType.fromTag(""));
        assertSame(DataType.UNKNOWN, DataType.fromTag("long"));
        assertSame(DataType.UNKNOWN, DataType.fromTag("INT"));
    }
}
