package com.sstlfsj.rule.kernel.api.spi.metric;

import com.sstlfsj.rule.kernel.api.model.MetricQuery;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MetricSourceHandlerTest {

    private static final MetricSourceHandler STUB = query ->
            new MetricValue(42.0, "DOUBLE", "FETCHED");

    private static MetricQuery buildQuery() {
        return new MetricQuery("ACCOUNT_BALANCE", "t1", "u1", Map.of(), Map.of(),
                java.time.Instant.parse("2026-06-06T00:00:00Z"));
    }

    @Test
    void fetch_returnsValue() {
        MetricValue value = STUB.fetch(buildQuery());
        assertEquals(42.0, value.value());
        assertEquals("DOUBLE", value.dataType());
        assertEquals("FETCHED", value.valueSource());
    }

    @Test
    void fetch_isFunctionalInterface() {
        // Lambda 赋值验证接口为单抽象方法的函数式接口契约。
        MetricSourceHandler handler = query -> new MetricValue(0.0, "DOUBLE", "FETCHED");
        assertNotNull(handler.fetch(buildQuery()));
    }
}
