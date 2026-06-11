package com.sstlfsj.rule.config.internal.publish;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PayloadDataTypeMapperTest {
    @Test
    void mapsSchemaTypeToDataTypeTag() {
        assertEquals("DECIMAL", PayloadDataTypeMapper.toDataTypeTag("NUMBER"));
        assertEquals("LONG", PayloadDataTypeMapper.toDataTypeTag("INTEGER"));
        assertEquals("STRING", PayloadDataTypeMapper.toDataTypeTag("STRING"));
        assertEquals("BOOLEAN", PayloadDataTypeMapper.toDataTypeTag("BOOLEAN"));
        assertEquals("LIST", PayloadDataTypeMapper.toDataTypeTag("ARRAY"));
        assertEquals("UNKNOWN", PayloadDataTypeMapper.toDataTypeTag("OBJECT"));
        assertEquals("UNKNOWN", PayloadDataTypeMapper.toDataTypeTag(null));
    }

    @Test
    void inputIsCaseInsensitive() {
        assertEquals("DECIMAL", PayloadDataTypeMapper.toDataTypeTag("number"));
        assertEquals("LIST", PayloadDataTypeMapper.toDataTypeTag("array"));
    }
}
