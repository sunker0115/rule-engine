package com.sstlfsj.rule.eval.internal.listener;

import com.sstlfsj.rule.config.api.event.RulePublishedEvent;
import com.sstlfsj.rule.config.api.event.SceneChangedEvent;
import com.sstlfsj.rule.kernel.internal.evaluator.RuleVersionCache;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * 索引热更后清空编译产物缓存(内存卫生)。
 * 键为不可变 ruleVersionId，陈旧条目不会错，故全清即可，下次评估惰性重编译。
 * 与 {@link RuleIndexEventListener} 独立(单一职责：只管缓存失效)。
 */
@Component
public class CompiledPredicateEvictor {

    private final RuleVersionCache cache;

    /**
     * @param cache 编译产物缓存
     */
    public CompiledPredicateEvictor(RuleVersionCache cache) {
        this.cache = cache;
    }

    /**
     * 规则发布后清空编译缓存。
     *
     * @param event 规则发布事件
     */
    @ApplicationModuleListener
    public void onRulePublished(RulePublishedEvent event) {
        cache.evictAll();
    }

    /**
     * 场景变更后清空编译缓存。
     *
     * @param event 场景变更事件
     */
    @ApplicationModuleListener
    public void onSceneChanged(SceneChangedEvent event) {
        cache.evictAll();
    }
}
