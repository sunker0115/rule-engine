package com.sstlfsj.rule.kernel.api.spi.metric;

import com.sstlfsj.rule.kernel.api.model.MetricValue;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MetricCacheTest {

    /** 最小 in-memory 实现，验证 SPI 契约（ttlSeconds>0 才写入）。 */
    private static MetricCache simpleCache(Map<String, MetricValue> store) {
        return new MetricCache() {
            @Override public MetricValue get(String key) { return store.get(key); }
            @Override public void put(String key, MetricValue value, int ttlSeconds) {
                if (ttlSeconds > 0) store.put(key, value);
            }
        };
    }

    @Test
    void put_then_get() {
        Map<String, MetricValue> store = new HashMap<>();
        MetricCache cache = simpleCache(store);
        cache.put("k", new MetricValue(5L, "LONG", "FETCHED"), 60);
        assertEquals(5L, cache.get("k").value());
    }

    @Test
    void ttlZero_notStored() {
        Map<String, MetricValue> store = new HashMap<>();
        MetricCache cache = simpleCache(store);
        cache.put("k", new MetricValue(5L, "LONG", "FETCHED"), 0);
        assertNull(cache.get("k"));
    }
}
