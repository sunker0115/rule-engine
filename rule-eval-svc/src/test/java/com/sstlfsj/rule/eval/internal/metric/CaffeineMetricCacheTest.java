package com.sstlfsj.rule.eval.internal.metric;

import com.sstlfsj.rule.kernel.api.model.MetricValue;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CaffeineMetricCacheTest {

    @Test
    void put_then_get_returnsValue() {
        CaffeineMetricCache c = new CaffeineMetricCache();
        c.put("k", new MetricValue(5L, "LONG", "FETCHED"), 60);
        assertThat(c.get("k").value()).isEqualTo(5L);
    }

    @Test
    void ttlZero_notCached() {
        CaffeineMetricCache c = new CaffeineMetricCache();
        c.put("k", new MetricValue(5L, "LONG", "FETCHED"), 0);
        assertThat(c.get("k")).isNull();
    }

    @Test
    void missing_returnsNull() {
        assertThat(new CaffeineMetricCache().get("absent")).isNull();
    }
}
