package com.sstlfsj.rule.benchmark;

import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 校准测试：确保 MatchBenchmark 的数据装配正确、线性与平方级实现结果等价（基准可信前提）。 */
class MatchBenchmarkTest {

    @Test
    void linearAndQuadratic_produceSameMergedSet() {
        MatchBenchmark b = new MatchBenchmark();
        b.n = 20;
        b.setup();

        List<RuleVersionSnapshot> linear = b.match_linear();
        List<RuleVersionSnapshot> quadratic = b.match_quadratic();

        // exact id 0..19 与 wildcard id 10..29 合并去重 = 0..29 共 30 条
        assertThat(linear).hasSize(30);
        assertThat(linear).containsExactlyInAnyOrderElementsOf(quadratic);
    }
}
