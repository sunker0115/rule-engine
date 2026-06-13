package com.sstlfsj.rule.eval.internal.listener;

import com.sstlfsj.rule.config.api.event.RulePublishedEvent;
import com.sstlfsj.rule.config.api.event.SceneChangedEvent;
import com.sstlfsj.rule.kernel.internal.evaluator.RuleVersionCache;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CompiledPredicateEvictorTest {

    @Test
    void onRulePublished_evictsAll() {
        RuleVersionCache cache = new RuleVersionCache();
        cache.putIfAbsent(1L, ctx -> true);
        CompiledPredicateEvictor evictor = new CompiledPredicateEvictor(cache);

        evictor.onRulePublished(new RulePublishedEvent("t1", "scene1", 1L));

        assertThat(cache.size()).isZero();
    }

    @Test
    void onSceneChanged_evictsAll() {
        RuleVersionCache cache = new RuleVersionCache();
        cache.putIfAbsent(1L, ctx -> true);
        CompiledPredicateEvictor evictor = new CompiledPredicateEvictor(cache);

        evictor.onSceneChanged(new SceneChangedEvent("t1", "scene1", false));

        assertThat(cache.size()).isZero();
    }
}
