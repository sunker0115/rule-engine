package com.sstlfsj.rule.eval.internal.action;

/**
 * action 幂等占坑：claim-before-execute 同步去重。
 * 进程内实现 best-effort（重启/多实例不共享）；升级 Redis/durable 时换实现，派发方不动。
 */
public interface ActionIdempotencyGuard {

    /**
     * 原子占坑。
     *
     * @param key 幂等键
     * @return true=占到（可执行）；false=已被占（TTL 内已派发，跳过）
     */
    boolean claim(String key);

    /**
     * 释放占坑（handler 失败时调，允许后续重发重试）。
     *
     * @param key 幂等键
     */
    void release(String key);
}
