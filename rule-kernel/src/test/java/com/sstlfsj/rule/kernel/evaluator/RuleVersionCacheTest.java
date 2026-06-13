package com.sstlfsj.rule.kernel.evaluator;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.internal.evaluator.RuleVersionCache;
import org.junit.jupiter.api.Test;

import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

class RuleVersionCacheTest {

    private final Predicate<EvalContext> alwaysTrue = ctx -> true;
    private final Predicate<EvalContext> alwaysFalse = ctx -> false;

    @Test
    void get_missing_returnsNull() {
        assertThat(new RuleVersionCache().get(1L)).isNull();
    }

    @Test
    void putIfAbsent_thenGet_returnsSame() {
        RuleVersionCache cache = new RuleVersionCache();
        cache.putIfAbsent(1L, alwaysTrue);
        assertThat(cache.get(1L)).isSameAs(alwaysTrue);
    }

    @Test
    void putIfAbsent_doesNotOverwrite() {
        RuleVersionCache cache = new RuleVersionCache();
        cache.putIfAbsent(1L, alwaysTrue);
        cache.putIfAbsent(1L, alwaysFalse);
        assertThat(cache.get(1L)).isSameAs(alwaysTrue);
    }

    @Test
    void evictAll_clears() {
        RuleVersionCache cache = new RuleVersionCache();
        cache.putIfAbsent(1L, alwaysTrue);
        cache.putIfAbsent(2L, alwaysFalse);
        assertThat(cache.size()).isEqualTo(2);
        cache.evictAll();
        assertThat(cache.size()).isZero();
        assertThat(cache.get(1L)).isNull();
    }
}
