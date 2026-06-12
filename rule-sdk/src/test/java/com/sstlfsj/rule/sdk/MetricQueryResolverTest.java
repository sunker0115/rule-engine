package com.sstlfsj.rule.sdk;

import com.sstlfsj.rule.kernel.api.model.MetricQuery;
import com.sstlfsj.rule.sdk.annotation.Fact;
import com.sstlfsj.rule.sdk.annotation.Metric;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class MetricQueryResolverTest {

    static class Holder {
        public void inject(@Fact("subjectId") String subject,
                           @Fact("amount") Integer amount,
                           MetricQuery raw) {}
        public void badMetric(@Metric("x") Integer x) {}
        public void badUnannotated(Integer x) {}
        public void badBoth(@Fact("subjectId") MetricQuery q) {}
    }

    private MetricQuery query() {
        return new MetricQuery("recent", "t", "u-1", Map.of(),
                Map.of("amount", 8000), Instant.now());
    }

    @Test
    void resolves_fact_payload_and_rawQuery() throws Exception {
        Method m = Holder.class.getMethod("inject", String.class, Integer.class, MetricQuery.class);
        Object[] args = new MetricQueryResolver().resolve(m.getParameters(), query());
        assertThat(args[0]).isEqualTo("u-1");      // @Fact subjectId 元数据
        assertThat(args[1]).isEqualTo(8000);       // @Fact amount payload
        assertThat(args[2]).isInstanceOf(MetricQuery.class);  // 逃生口
    }

    @Test
    void validate_rejectsMetricUnannotatedAndBothTagged() throws Exception {
        MetricQueryResolver r = new MetricQueryResolver();
        assertThatThrownBy(() -> r.validate(Holder.class.getMethod("badMetric", Integer.class).getParameters()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("@Metric");
        assertThatThrownBy(() -> r.validate(Holder.class.getMethod("badUnannotated", Integer.class).getParameters()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("@Fact");
        assertThatThrownBy(() -> r.validate(Holder.class.getMethod("badBoth", MetricQuery.class).getParameters()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("MetricQuery");
    }
}
