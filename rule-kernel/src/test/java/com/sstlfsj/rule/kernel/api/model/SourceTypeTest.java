package com.sstlfsj.rule.kernel.api.model;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** SourceType 值契约：= DB metric_definition.source_type + @MetricSourceType 注解值，不可随意改动。 */
class SourceTypeTest {

    @Test
    void values_matchPersistedContract() {
        assertEquals("ATTRIBUTE", SourceType.ATTRIBUTE);
        assertEquals("SQL_AGGREGATE", SourceType.SQL_AGGREGATE);
        assertEquals("EXTERNAL_HTTP", SourceType.EXTERNAL_HTTP);
        assertEquals("STREAM", SourceType.STREAM);
    }

    @Test
    void all_containsExactlyTheFourSourceTypes() {
        assertEquals(
                Set.of("ATTRIBUTE", "SQL_AGGREGATE", "EXTERNAL_HTTP", "STREAM"),
                SourceType.ALL);
    }
}
