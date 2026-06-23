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

    /** 配了 maxStalenessSeconds 且特征新鲜（age ≤ 阈值）→ 正常返回。 */
    @Test
    void fetch_freshWithinStaleness_returnsValue() {
        Instant now = Instant.parse("2026-06-20T07:00:30Z");          // now = 1781938830s
        when(hashOps.get("rt:feat:customer-1", "rt_state")).thenReturn("RT_WATCH");
        when(hashOps.get("rt:feat:customer-1", "updated_at")).thenReturn("1781938820");  // 10s 前

        MetricQuery q = new MetricQuery("rt_state", "9100", "customer-1",
                Map.of("feature", "rt_state", "maxStalenessSeconds", "30"), Map.of(), now, Map.of());

        MetricValue v = handler.fetch(q);

        assertThat(v.isError()).isFalse();
        assertThat(v.value()).isEqualTo("RT_WATCH");
    }

    /** 配了 maxStalenessSeconds 且特征陈旧（age > 阈值）→ STREAM_FEATURE_STALE。 */
    @Test
    void fetch_staleBeyondThreshold_returnsStaleError() {
        Instant now = Instant.parse("2026-06-20T07:10:00Z");
        when(hashOps.get("rt:feat:customer-1", "rt_state")).thenReturn("RT_WATCH");
        when(hashOps.get("rt:feat:customer-1", "updated_at")).thenReturn("1781938820");  // 约 580s 前

        MetricQuery q = new MetricQuery("rt_state", "9100", "customer-1",
                Map.of("feature", "rt_state", "maxStalenessSeconds", "30"), Map.of(), now, Map.of());

        MetricValue v = handler.fetch(q);

        assertThat(v.isError()).isTrue();
        assertThat(v.errorCode()).isEqualTo("STREAM_FEATURE_STALE");
    }

    /** 配了 maxStalenessSeconds 但缺 updated_at → 按陈旧降级。 */
    @Test
    void fetch_missingUpdatedAt_returnsStaleError() {
        when(hashOps.get("rt:feat:customer-1", "rt_state")).thenReturn("RT_WATCH");
        when(hashOps.get("rt:feat:customer-1", "updated_at")).thenReturn(null);

        MetricQuery q = new MetricQuery("rt_state", "9100", "customer-1",
                Map.of("feature", "rt_state", "maxStalenessSeconds", "30"), Map.of(), Instant.now(), Map.of());

        MetricValue v = handler.fetch(q);

        assertThat(v.isError()).isTrue();
        assertThat(v.errorCode()).isEqualTo("STREAM_FEATURE_STALE");
    }

    /** 未配 maxStalenessSeconds → 不校验新鲜度（不读 updated_at），直接返回。 */
    @Test
    void fetch_noStalenessConfig_skipsFreshnessCheck() {
        when(hashOps.get("rt:feat:customer-1", "rt_state")).thenReturn("RT_WATCH");

        MetricValue v = handler.fetch(query("customer-1", "rt_state"));

        assertThat(v.isError()).isFalse();
        assertThat(v.value()).isEqualTo("RT_WATCH");
    }

    /** dataType=LONG：Redis 读回 String "9" → 强转 long 9（否则数值比较按字符串出错）。 */
    @Test
    void fetch_coercesStringToLongByDataType() {
        when(hashOps.get("rt:feat:customer-1", "rtm_mwr_1s")).thenReturn("9");

        MetricQuery q = new MetricQuery("rtm_mwr_1s", "9100", "customer-1",
                Map.of("feature", "rtm_mwr_1s", "dataType", "LONG"), Map.of(), Instant.now(), Map.of());

        MetricValue v = handler.fetch(q);

        assertThat(v.isError()).isFalse();
        assertThat(v.value()).isEqualTo(9L);          // long，非 String "9"
        assertThat(v.dataType()).isEqualTo("LONG");
    }

    /** dataType=DOUBLE：String "0.7" → double 0.7。 */
    @Test
    void fetch_coercesStringToDoubleByDataType() {
        when(hashOps.get("rt:feat:customer-1", "sus_score")).thenReturn("0.7");

        MetricQuery q = new MetricQuery("sus_score", "9100", "customer-1",
                Map.of("feature", "sus_score", "dataType", "DOUBLE"), Map.of(), Instant.now(), Map.of());

        MetricValue v = handler.fetch(q);

        assertThat(v.isError()).isFalse();
        assertThat(v.value()).isEqualTo(0.7);
        assertThat(v.dataType()).isEqualTo("DOUBLE");
    }

    /** dataType=LONG 但值非数字 → 强转失败 STREAM_TYPE_MISMATCH。 */
    @Test
    void fetch_typeMismatch_returnsError() {
        when(hashOps.get("rt:feat:customer-1", "rtm_mwr_1s")).thenReturn("not-a-number");

        MetricQuery q = new MetricQuery("rtm_mwr_1s", "9100", "customer-1",
                Map.of("feature", "rtm_mwr_1s", "dataType", "LONG"), Map.of(), Instant.now(), Map.of());

        MetricValue v = handler.fetch(q);

        assertThat(v.isError()).isTrue();
        assertThat(v.errorCode()).isEqualTo("STREAM_TYPE_MISMATCH");
    }
}
