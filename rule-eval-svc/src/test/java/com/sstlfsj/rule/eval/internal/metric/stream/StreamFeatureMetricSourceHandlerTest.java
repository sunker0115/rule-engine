package com.sstlfsj.rule.eval.internal.metric.stream;

import com.sstlfsj.rule.kernel.api.model.MetricQuery;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StreamFeatureMetricSourceHandlerTest {

    private StringRedisTemplate redis;
    private HashOperations<String, Object, Object> hashOps;
    private StreamFeatureMetricSourceHandler handler;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        hashOps = mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn(hashOps);
        handler = new StreamFeatureMetricSourceHandler(redis);
    }

    private static MetricQuery query(String subjectId, String feature) {
        return new MetricQuery("rt_state", "9100", subjectId,
                Map.of("feature", feature), Map.of(), Instant.now(), Map.of());
    }

    @Test
    void fetch_returnsFeatureValue() {
        when(hashOps.get("rt:feat:customer-1", "rt_state")).thenReturn("RT_WATCH");

        MetricValue v = handler.fetch(query("customer-1", "rt_state"));

        assertThat(v.isError()).isFalse();
        assertThat(v.value()).isEqualTo("RT_WATCH");
    }

    @Test
    void fetch_missingFeature_returnsError() {
        when(hashOps.get("rt:feat:customer-1", "rt_state")).thenReturn(null);

        MetricValue v = handler.fetch(query("customer-1", "rt_state"));

        assertThat(v.isError()).isTrue();
        assertThat(v.errorCode()).isEqualTo("STREAM_FEATURE_MISSING");
    }

    @Test
    void fetch_missingFeatureParam_returnsError() {
        MetricQuery q = new MetricQuery("rt_state", "9100", "customer-1",
                Map.of(), Map.of(), Instant.now(), Map.of());

        MetricValue v = handler.fetch(q);

        assertThat(v.isError()).isTrue();
        assertThat(v.errorCode()).isEqualTo("STREAM_PARAM_MISSING");
    }

    @Test
    void fetch_redisException_returnsError() {
        when(hashOps.get("rt:feat:customer-1", "rt_state"))
                .thenThrow(new RuntimeException("connection timeout"));

        MetricValue v = handler.fetch(query("customer-1", "rt_state"));

        assertThat(v.isError()).isTrue();
        assertThat(v.errorCode()).isEqualTo("STREAM_REDIS_ERROR");
    }
}
