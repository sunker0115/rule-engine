package com.sstlfsj.rule.kernel.internal.evaluator;

import com.sstlfsj.rule.kernel.api.model.EvalContext;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * 编译产物缓存：ruleVersionId → 编译后的布尔谓词。
 * 键不可变(发布版本不可变)，故缓存永不脏；{@link #evictAll()} 仅为内存卫生
 * (发布/场景变更后清空，下次评估惰性重编译)。
 */
public final class RuleVersionCache {

    private final ConcurrentHashMap<Long, Predicate<EvalContext>> cache = new ConcurrentHashMap<>();

    /**
     * 取缓存谓词。
     *
     * @param ruleVersionId 规则版本 id
     * @return 缓存的谓词，不存在返回 null
     */
    public Predicate<EvalContext> get(long ruleVersionId) {
        return cache.get(ruleVersionId);
    }

    /**
     * 缺失时放入(并发幂等)。
     *
     * @param ruleVersionId 规则版本 id
     * @param predicate     编译产物
     */
    public void putIfAbsent(long ruleVersionId, Predicate<EvalContext> predicate) {
        cache.putIfAbsent(ruleVersionId, predicate);
    }

    /** 清空全部编译产物(发布/场景变更后调用)。 */
    public void evictAll() {
        cache.clear();
    }

    /** @return 当前缓存条目数(测试/可观测用) */
    public int size() {
        return cache.size();
    }
}
