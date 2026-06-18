package com.sstlfsj.rule.kernel.internal.analysis;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static com.sstlfsj.rule.kernel.internal.analysis.ConditionSpace.*;
import static org.assertj.core.api.Assertions.assertThat;

/** ConditionSpace 区间/点集三态推理单测。覆盖 NumericRange/PointSet/Any/Empty/Unknown 的 overlaps/subsumes/meet。 */
class ConditionSpaceTest {

    @Test
    void numericRange_overlaps_subsumes() {
        ConditionSpace wide = between(0, 100);   // [0,100]
        ConditionSpace narrow = between(10, 20);  // [10,20]
        assertThat(wide.overlaps(narrow)).isEqualTo(Tri.TRUE);
        assertThat(wide.subsumes(narrow)).isEqualTo(Tri.TRUE);
        assertThat(narrow.subsumes(wide)).isEqualTo(Tri.FALSE);
    }

    @Test
    void numericRange_disjoint_overlapsFalse_meetEmpty() {
        ConditionSpace gt30 = gt(30);   // (30,+inf)
        ConditionSpace lt10 = lt(10);   // (-inf,10)
        assertThat(gt30.overlaps(lt10)).isEqualTo(Tri.FALSE);
        assertThat(gt30.meet(lt10).isEmpty()).isTrue();
    }

    @Test
    void numericRange_meet_intersects() {
        ConditionSpace m = between(0, 50).meet(between(20, 80));  // [20,50]
        assertThat(m.isEmpty()).isFalse();
        assertThat(between(20, 50).subsumes(m)).isEqualTo(Tri.TRUE);
        assertThat(m.subsumes(between(20, 50))).isEqualTo(Tri.TRUE);
    }

    @Test
    void numericRange_boundaryInclusivity() {
        // [0,10] 与 [10,20] 闭端共享 10 → 相交
        assertThat(between(0, 10).overlaps(between(10, 20))).isEqualTo(Tri.TRUE);
        // [0,10) 与 [10,20] 不含 10 → 不相交
        assertThat(range(0, true, 10, false).overlaps(between(10, 20))).isEqualTo(Tri.FALSE);
    }

    @Test
    void pointSet_eq_meet_empty_isIncoherence() {
        // age==20 且 age==30 → 空集（不一致根因）
        assertThat(eq(20).meet(eq(30)).isEmpty()).isTrue();
        assertThat(eq(20).overlaps(eq(20))).isEqualTo(Tri.TRUE);
        assertThat(eq(20).overlaps(eq(30))).isEqualTo(Tri.FALSE);
    }

    @Test
    void pointSet_in_subsumes_eq() {
        assertThat(in(Set.of(1, 2, 3)).subsumes(eq(2))).isEqualTo(Tri.TRUE);
        assertThat(eq(2).subsumes(in(Set.of(1, 2, 3)))).isEqualTo(Tri.FALSE);
    }

    @Test
    void pointInRange_crossType() {
        assertThat(between(0, 100).subsumes(eq(50))).isEqualTo(Tri.TRUE);
        assertThat(between(0, 100).overlaps(eq(50))).isEqualTo(Tri.TRUE);
        assertThat(between(0, 100).overlaps(eq(200))).isEqualTo(Tri.FALSE);
        assertThat(eq(50).overlaps(between(0, 100))).isEqualTo(Tri.TRUE);
        // 非数值点集 vs 数值区间 → 无法判定
        assertThat(between(0, 100).overlaps(eq("X"))).isEqualTo(Tri.UNKNOWN);
    }

    @Test
    void any_subsumesAll_overlapsNonEmpty() {
        assertThat(any().subsumes(eq(5))).isEqualTo(Tri.TRUE);
        assertThat(any().subsumes(between(0, 10))).isEqualTo(Tri.TRUE);
        assertThat(any().overlaps(eq(5))).isEqualTo(Tri.TRUE);
        assertThat(any().overlaps(empty())).isEqualTo(Tri.FALSE);
        assertThat(any().meet(eq(5))).isEqualTo(eq(5));
    }

    @Test
    void empty_isEmpty_overlapsNothing() {
        assertThat(empty().isEmpty()).isTrue();
        assertThat(empty().overlaps(any())).isEqualTo(Tri.FALSE);
        assertThat(empty().subsumes(empty())).isEqualTo(Tri.TRUE);
        assertThat(empty().subsumes(eq(5))).isEqualTo(Tri.FALSE);
    }

    @Test
    void unknown_propagates() {
        assertThat(unknown("regex").isUnknown()).isTrue();
        assertThat(unknown("regex").overlaps(eq(5))).isEqualTo(Tri.UNKNOWN);
        assertThat(eq(5).overlaps(unknown("regex"))).isEqualTo(Tri.UNKNOWN);
        assertThat(eq(5).subsumes(unknown("regex"))).isEqualTo(Tri.UNKNOWN);
        assertThat(unknown("regex").meet(eq(5)).isUnknown()).isTrue();
    }

    @Test
    void unbounded_ranges_gt_lt() {
        // (30,+inf) 包含 50，不含 30 自身（开端）
        assertThat(gt(30).subsumes(eq(50))).isEqualTo(Tri.TRUE);
        assertThat(gt(30).overlaps(eq(30))).isEqualTo(Tri.FALSE);
        // (-inf,10) 与 [10,20] 端点 10 一开一闭 → 不相交
        assertThat(lt(10).overlaps(between(10, 20))).isEqualTo(Tri.FALSE);
    }

    @Test
    void numericIn_vs_range() {
        // 数值 IN 与区间相交 / 包含
        assertThat(between(0, 100).overlaps(in(Set.of(50, 200)))).isEqualTo(Tri.TRUE);
        assertThat(between(0, 100).subsumes(in(Set.of(50, 200)))).isEqualTo(Tri.FALSE);
        assertThat(between(0, 100).subsumes(in(Set.of(10, 50, 90)))).isEqualTo(Tri.TRUE);
    }

    @Test
    void crossType_meet_keepsPointsInRange() {
        // 点集 ∩ 区间 → 保留区间内的点
        ConditionSpace m = in(Set.of(5, 50, 150)).meet(between(0, 100));
        assertThat(m.isEmpty()).isFalse();
        assertThat(m.subsumes(eq(5))).isEqualTo(Tri.TRUE);
        assertThat(m.subsumes(eq(50))).isEqualTo(Tri.TRUE);
        assertThat(m.overlaps(eq(150))).isEqualTo(Tri.FALSE);
        // 非数值点 ∩ 区间 → 无法判定
        assertThat(eq("X").meet(between(0, 100)).isUnknown()).isTrue();
    }

    @Test
    void pointSet_subsumesDegenerateRange_only() {
        // [10,10] 退化单点，被含 10 的点集包含
        assertThat(in(Set.of(10, 20)).subsumes(between(10, 10))).isEqualTo(Tri.TRUE);
        // 非退化区间永远不被有限点集包含
        assertThat(in(Set.of(10, 20)).subsumes(between(10, 20))).isEqualTo(Tri.FALSE);
    }

    @Test
    void degenerateRange_normalizesToEmpty() {
        // [10,10) 不含任何点 → 归一化为空集
        assertThat(range(10, true, 10, false).isEmpty()).isTrue();
        // lo>hi → 空集
        assertThat(range(20, true, 10, true).isEmpty()).isTrue();
    }

    @Test
    void meet_emptyUnknown_isCommutativeAndEmpty() {
        // ∅ ∩ 未知 = ∅，交集必须满足交换律（两向都为空集）
        assertThat(empty().meet(unknown("x")).isEmpty()).isTrue();
        assertThat(unknown("x").meet(empty()).isEmpty()).isTrue();
        // 跨类型 meet 交换律：点集 ∩ 区间 == 区间 ∩ 点集
        ConditionSpace ab = in(Set.of(5, 50, 150)).meet(between(0, 100));
        ConditionSpace ba = between(0, 100).meet(in(Set.of(5, 50, 150)));
        assertThat(ab.subsumes(ba)).isEqualTo(Tri.TRUE);
        assertThat(ba.subsumes(ab)).isEqualTo(Tri.TRUE);
    }

    @Test
    void nonFiniteStringPoint_vs_range_isUnknown() {
        // "NaN"/"Infinity"/溢出串解析为非有限值 → 视为非数值，降级 UNKNOWN（而非误判 FALSE）
        assertThat(between(0, 100).overlaps(eq("NaN"))).isEqualTo(Tri.UNKNOWN);
        assertThat(between(0, 100).overlaps(eq("Infinity"))).isEqualTo(Tri.UNKNOWN);
        assertThat(between(0, 100).overlaps(eq("-Infinity"))).isEqualTo(Tri.UNKNOWN);
        assertThat(between(0, 100).overlaps(eq("1e400"))).isEqualTo(Tri.UNKNOWN);
    }
}
