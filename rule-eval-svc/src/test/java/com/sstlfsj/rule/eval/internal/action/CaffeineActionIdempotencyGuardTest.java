package com.sstlfsj.rule.eval.internal.action;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CaffeineActionIdempotencyGuardTest {

    private CaffeineActionIdempotencyGuard guard() {
        return new CaffeineActionIdempotencyGuard(600, 100_000);
    }

    @Test
    void claim_firstTrue_secondFalse() {
        CaffeineActionIdempotencyGuard g = guard();
        assertThat(g.claim("k1")).isTrue();
        assertThat(g.claim("k1")).isFalse();   // TTL 内已占坑
    }

    @Test
    void release_allowsReclaim() {
        CaffeineActionIdempotencyGuard g = guard();
        assertThat(g.claim("k1")).isTrue();
        g.release("k1");
        assertThat(g.claim("k1")).isTrue();     // 释放后可重新占坑
    }

    @Test
    void differentKeys_independent() {
        CaffeineActionIdempotencyGuard g = guard();
        assertThat(g.claim("k1")).isTrue();
        assertThat(g.claim("k2")).isTrue();
    }
}
