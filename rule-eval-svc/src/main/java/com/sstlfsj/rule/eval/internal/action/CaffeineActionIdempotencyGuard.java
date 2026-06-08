package com.sstlfsj.rule.eval.internal.action;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/** 进程内 Caffeine action 幂等占坑（best-effort：重启丢 / 多实例不共享，TTL 内去重）。 */
@Component
public class CaffeineActionIdempotencyGuard implements ActionIdempotencyGuard {

    private final Cache<String, Boolean> cache;

    public CaffeineActionIdempotencyGuard(long ttlSeconds, long maxSize) {
        this.cache = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(ttlSeconds, TimeUnit.SECONDS)
                .build();
    }

    @Autowired
    public CaffeineActionIdempotencyGuard(ActionIdempotencyProperties props) {
        this(props.getTtlSeconds(), props.getMaxSize());
    }

    @Override
    public boolean claim(String key) {
        // putIfAbsent 原子：返回 null 表示本次首占（可执行）
        return cache.asMap().putIfAbsent(key, Boolean.TRUE) == null;
    }

    @Override
    public void release(String key) {
        cache.invalidate(key);
    }
}
