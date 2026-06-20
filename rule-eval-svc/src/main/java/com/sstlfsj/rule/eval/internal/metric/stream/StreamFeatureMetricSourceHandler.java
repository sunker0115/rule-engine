package com.sstlfsj.rule.eval.internal.metric.stream;

import com.sstlfsj.rule.kernel.api.annotation.MetricSourceType;
import com.sstlfsj.rule.kernel.api.model.MetricQuery;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import com.sstlfsj.rule.kernel.api.model.SourceType;
import com.sstlfsj.rule.kernel.api.model.ValueSource;
import com.sstlfsj.rule.kernel.api.spi.metric.MetricSourceHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 流式预计算特征取数 handler（SourceType=STREAM）。
 *
 * <p>从 Redis Hash 按 subjectId(=customerId) + feature 字段名读预计算特征值。
 * key 规范 {@code rt:feat:{subjectId}}，field = {@code query.params().get("feature")}。
 * 写侧为外部 Flink 作业（rule-stream-rt），handler 只读不写。
 * 新鲜度校验留 P1。
 */
@Component
@MetricSourceType(SourceType.STREAM)
public class StreamFeatureMetricSourceHandler implements MetricSourceHandler {

    private static final Logger log = LoggerFactory.getLogger(StreamFeatureMetricSourceHandler.class);

    private final StringRedisTemplate redis;

    public StreamFeatureMetricSourceHandler(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public MetricValue fetch(MetricQuery query) {
        Object featureObj = query.params().get("feature");
        if (featureObj == null) {
            return MetricValue.error("STREAM_PARAM_MISSING");
        }
        String redisKey = "rt:feat:" + query.subjectId();
        String field = featureObj.toString();
        Object raw = null;
        try {
            raw = redis.opsForHash().get(redisKey, field);
        } catch (RuntimeException e) {
            log.warn("STREAM handler Redis 取数失败 key={} field={}", redisKey, field, e);
            return MetricValue.error("STREAM_REDIS_ERROR");
        }
        if (raw == null) {
            return MetricValue.error("STREAM_FEATURE_MISSING");
        }
        return new MetricValue(raw, "UNKNOWN", ValueSource.FETCHED.tag());
    }
}
