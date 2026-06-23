package com.sstlfsj.rule.stream.feature;

import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class RollingWindowStateTest {

    @Test
    void sumsAcrossSizes() {
        Map<Long, Long> counts = new HashMap<>();
        counts.put(100L, 3L);   // 当前秒
        counts.put(95L, 2L);    // 5 秒前 → 在 10s/30s/.. 内，不在 1s 内
        counts.put(80L, 5L);    // 20 秒前 → 在 30s/1m/2m/5m 内
        long[] s = RollingWindowState.rollingSums(counts, 100L);
        // 顺序 1s/10s/30s/1m/2m/5m
        assertThat(s[0]).isEqualTo(3);          // 1s：仅当前秒
        assertThat(s[1]).isEqualTo(5);          // 10s：100+95
        assertThat(s[2]).isEqualTo(10);         // 30s：100+95+80
        assertThat(s[5]).isEqualTo(10);         // 5m：全部
    }

    @Test
    void falloffWhenQuiet() {
        // 一波在秒 100，当前推进到秒 102（无新计数），1s 窗口应回落到 0
        Map<Long, Long> counts = new HashMap<>();
        counts.put(100L, 9L);
        long[] s = RollingWindowState.rollingSums(counts, 102L);
        assertThat(s[0]).isEqualTo(0);          // 1s：秒 102 无计数 → 回落
        assertThat(s[1]).isEqualTo(9);          // 10s：仍含秒 100
    }

    @Test
    void ignoresFutureSeconds() {
        Map<Long, Long> counts = new HashMap<>();
        counts.put(105L, 7L);                   // 比 current 晚（乱序）
        assertThat(RollingWindowState.rollingSums(counts, 100L)[5]).isEqualTo(0);
    }
}
