package com.sstlfsj.rule.kernel.api.spi.metric;

import com.sstlfsj.rule.kernel.api.model.MetricDescriptor;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MetricDefinitionResolverTest {

    @Test
    void resolve_viaLambda_returnsDescriptor() {
        MetricDefinitionResolver resolver = (tenant, code, ver) ->
                new MetricDescriptor(code, ver, "SQL_AGGREGATE", "LONG", false, 60, Map.of());
        MetricDescriptor d = resolver.resolve("1", "balance", 2);
        assertNotNull(d);
        assertEquals("balance", d.metricCode());
        assertEquals(2, d.metricVersion());
    }

    @Test
    void resolve_missing_returnsNull() {
        MetricDefinitionResolver resolver = (tenant, code, ver) -> null;
        assertNull(resolver.resolve("1", "absent", 1));
    }
}
