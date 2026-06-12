package com.sstlfsj.rule.sdk.source;

import com.sstlfsj.rule.kernel.api.model.MetricDescriptor;
import com.sstlfsj.rule.kernel.api.model.MetricQuery;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import com.sstlfsj.rule.sdk.MetricQueryResolver;
import com.sstlfsj.rule.sdk.annotation.Fact;
import com.sstlfsj.rule.sdk.annotation.MetricSource;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class AnnotatedMetricScannerTest {

    static class Metrics {
        @MetricSource(value = "recent_txn_count", cacheTtlSeconds = 60)
        public long recent(@Fact("subjectId") String subjectId) {
            return "frequent-user".equals(subjectId) ? 5 : 1;
        }
    }

    static class Dup {
        @MetricSource("recent_txn_count") public long a() { return 1; }
        @MetricSource("recent_txn_count") public long b() { return 2; }
    }

    @Test
    void scan_buildsSyntheticHandlerAndDescriptor() {
        AnnotatedMetricScanner.ScanResult r =
                new AnnotatedMetricScanner(new MetricQueryResolver(), "t1").scan(List.of(new Metrics()));

        assertThat(r.descriptors()).hasSize(1);
        MetricDescriptor d = r.descriptors().get(0);
        assertThat(d.metricCode()).isEqualTo("recent_txn_count");
        assertThat(d.sourceType()).isEqualTo("__anno_metric:recent_txn_count");
        assertThat(d.dataType()).isEqualTo("LONG");
        assertThat(d.cacheTtlSeconds()).isEqualTo(60);

        // 合成 handler 按 query 反射调方法
        var handler = r.handlers().get("__anno_metric:recent_txn_count");
        MetricValue v = handler.fetch(new MetricQuery("recent_txn_count", "t1", "frequent-user",
                Map.of(), Map.of(), Instant.now()));
        assertThat(v.value()).isEqualTo(5L);
        assertThat(v.valueSource()).isEqualTo("FETCHED");
    }

    @Test
    void scan_rejectsDuplicateMetricCode() {
        assertThatThrownBy(() ->
                new AnnotatedMetricScanner(new MetricQueryResolver(), "t1").scan(List.of(new Dup())))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("recent_txn_count");
    }
}
