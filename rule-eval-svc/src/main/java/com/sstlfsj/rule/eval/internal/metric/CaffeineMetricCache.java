package com.sstlfsj.rule.eval.internal.metric;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import com.sstlfsj.rule.kernel.api.spi.metric.MetricCache;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/** 进程内 Caffeine 取数缓存，按 metric 各自 ttlSeconds 过期。 */
@Component
@ImportRuntimeHints(CaffeineNativeHints.class)
public class CaffeineMetricCache implements MetricCache {

    private record Entry(MetricValue value, int ttlSeconds) {}

    private final Cache<String, Entry> cache = Caffeine.newBuilder()
            .maximumSize(100_000)
            .expireAfter(new Expiry<String, Entry>() {
                @Override public long expireAfterCreate(String k, Entry e, long now) {
                    return TimeUnit.SECONDS.toNanos(e.ttlSeconds());
                }
                @Override public long expireAfterUpdate(String k, Entry e, long now, long dur) {
                    return TimeUnit.SECONDS.toNanos(e.ttlSeconds());
                }
                @Override public long expireAfterRead(String k, Entry e, long now, long dur) {
                    return dur;
                }
            })
            .build();

    @Override
    public MetricValue get(String key) {
        Entry e = cache.getIfPresent(key);
        return e == null ? null : e.value();
    }

    @Override
    public void put(String key, MetricValue value, int ttlSeconds) {
        if (ttlSeconds > 0) cache.put(key, new Entry(value, ttlSeconds));
    }
}
