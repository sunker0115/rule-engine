package com.sstlfsj.rule.kernel.api.spi.metric;

import com.sstlfsj.rule.kernel.api.model.MetricValue;

/** 取数结果缓存 SPI（按 metric 各自 TTL 生效）；由 rule-eval-svc 用 Caffeine 实现。 */
public interface MetricCache {

    /**
     * 读缓存。
     *
     * @param key 缓存键
     * @return 命中的 MetricValue；未命中或已过期返回 null
     */
    MetricValue get(String key);

    /**
     * 写缓存（仅成功结果应写入）。
     *
     * @param key        缓存键
     * @param value      指标值
     * @param ttlSeconds 过期秒数；&le;0 表示不缓存
     */
    void put(String key, MetricValue value, int ttlSeconds);
}
