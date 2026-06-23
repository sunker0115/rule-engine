package com.sstlfsj.rule.eval.internal.metric.stream;

import com.sstlfsj.rule.eval.internal.metric.DataTypeCoercion;
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
 * 写侧为外部 Flink 作业（rule-rt-stream），handler 只读不写。
 *
 * <p>新鲜度降级（opt-in，per-metric）：{@code params.maxStalenessSeconds} 配了才校验——
 * 读同 hash 的 {@code updated_at}（event-time epoch second），用引擎统一时钟 {@code query.now()}
 * 比对，超阈值视为陈旧返回 {@code STREAM_FEATURE_STALE}（D15 降级），不静默用旧值。用 {@code query.now()}
 * 而非墙钟，保 dry-run/回放可复现。未配阈值的 metric 零额外开销（不多读 updated_at）。
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
        Object raw;
        try {
            raw = redis.opsForHash().get(redisKey, field);
        } catch (RuntimeException e) {
            log.warn("STREAM handler Redis 取数失败 key={} field={}", redisKey, field, e);
            return MetricValue.error("STREAM_REDIS_ERROR");
        }
        if (raw == null) {
            return MetricValue.error("STREAM_FEATURE_MISSING");
        }

        // 新鲜度降级（opt-in）：仅当 metric 配了 maxStalenessSeconds 才校验
        Object maxStalenessObj = query.params().get("maxStalenessSeconds");
        if (maxStalenessObj != null) {
            MetricValue stale = checkFreshness(redisKey, field, maxStalenessObj, query.now());
            if (stale != null) {
                return stale;
            }
        }

        // 按 metric schema 的 dataType 强转：Redis 读回恒为 String，需按 metric_definition.data_type
        // （resolver 注入 params）coerce 成 long/double/boolean，否则数值条件按字符串比较出错（"10">"8" 为假）。
        // dataType 真相源在 metric 定义（对齐 Feast ValueType 的 registry-driven），与 SQL/HTTP handler 一致。
        Object dataTypeObj = query.params().get("dataType");
        String dataType = dataTypeObj != null ? dataTypeObj.toString() : null;
        Object coerced = DataTypeCoercion.coerce(raw, dataType);
        if (coerced == null) {
            // raw 非空但强转后 null = 类型不匹配（如 dataType=LONG 但值非数字）
            log.warn("STREAM 特征类型不匹配 key={} field={} dataType={} raw={}", redisKey, field, dataType, raw);
            return MetricValue.error("STREAM_TYPE_MISMATCH");
        }
        return new MetricValue(coerced, dataType, ValueSource.FETCHED.tag());
    }

    /**
     * 校验特征新鲜度：缺 updated_at 或超 maxStaleness 视为陈旧。
     *
     * @param redisKey      特征 hash key
     * @param field         特征字段名（仅日志）
     * @param maxStalenessObj 最大容忍秒数（来自 metric params）
     * @param now           引擎统一时钟（非墙钟，保可复现）
     * @return 陈旧时返回 error MetricValue；新鲜返回 null
     */
    private MetricValue checkFreshness(String redisKey, String field, Object maxStalenessObj, java.time.Instant now) {
        Object updatedAtRaw;
        try {
            updatedAtRaw = redis.opsForHash().get(redisKey, "updated_at");
        } catch (RuntimeException e) {
            log.warn("STREAM handler 读 updated_at 失败 key={}", redisKey, e);
            return MetricValue.error("STREAM_REDIS_ERROR");
        }
        if (updatedAtRaw == null) {
            return MetricValue.error("STREAM_FEATURE_STALE");   // 无写入时刻，无法判断新鲜度，按陈旧降级
        }
        long maxStaleness = Long.parseLong(maxStalenessObj.toString());
        long updatedAt = Long.parseLong(updatedAtRaw.toString());
        long ageSeconds = now.getEpochSecond() - updatedAt;
        if (ageSeconds > maxStaleness) {
            log.warn("STREAM 特征陈旧 key={} field={} age={}s > max={}s", redisKey, field, ageSeconds, maxStaleness);
            return MetricValue.error("STREAM_FEATURE_STALE");
        }
        return null;
    }
}
