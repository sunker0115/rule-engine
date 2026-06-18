package com.sstlfsj.rule.config.internal.publish;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TimeWindowParamsTest {

    @Test
    void from_parsesNumberAndString() {
        TimeWindowParams p = TimeWindowParams.from(
                Map.of("fromEpochMilli", 1000L, "toEpochMilli", "2000"));
        assertEquals(1000L, p.fromEpochMilli());
        assertEquals(2000L, p.toEpochMilli());
    }

    @Test
    void from_missingKeys_areNull() {
        TimeWindowParams p = TimeWindowParams.from(Map.of());
        assertNull(p.fromEpochMilli());
        assertNull(p.toEpochMilli());
    }

    @Test
    void from_singleSide_otherNull() {
        TimeWindowParams onlyFrom = TimeWindowParams.from(Map.of("fromEpochMilli", 5L));
        assertEquals(5L, onlyFrom.fromEpochMilli());
        assertNull(onlyFrom.toEpochMilli());
    }

    @Test
    void from_nullValue_isNull() {
        Map<String, Object> m = new HashMap<>();
        m.put("fromEpochMilli", null);
        assertNull(TimeWindowParams.from(m).fromEpochMilli());
    }
}
