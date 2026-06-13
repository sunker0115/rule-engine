package com.sstlfsj.rule.config.internal.publish;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PayloadDataTypeMapperTest {
    @Test
    void mapsSchemaTypeToDataTypeTag() {
        assertEquals("DECIMAL", PayloadDataTypeMapper.toDataTypeTag("NUMBER"));
        assertEquals("LONG", PayloadDataTypeMapper.toDataTypeTag("INTEGER"));
        assertEquals("STRING", PayloadDataTypeMapper.toDataTypeTag("STRING"));
        assertEquals("BOOLEAN", PayloadDataTypeMapper.toDataTypeTag("BOOLEAN"));
        assertEquals("LIST", PayloadDataTypeMapper.toDataTypeTag("ARRAY"));
        assertEquals("UNKNOWN", PayloadDataTypeMapper.toDataTypeTag("OBJECT"));
    }

    @Test
    void nullTypeThrows() {
        assertThrows(IllegalArgumentException.class, () -> PayloadDataTypeMapper.toDataTypeTag(null));
    }

    @Test
    void illegalTypeThrows() {
        assertThrows(IllegalArgumentException.class, () -> PayloadDataTypeMapper.toDataTypeTag("FOO"));
    }

    @Test
    void inputIsCaseInsensitive() {
        assertEquals("DECIMAL", PayloadDataTypeMapper.toDataTypeTag("number"));
        assertEquals("LIST", PayloadDataTypeMapper.toDataTypeTag("array"));
    }
}
