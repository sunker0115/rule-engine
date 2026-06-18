package com.sstlfsj.rule.kernel.internal.analysis;

import com.sstlfsj.rule.kernel.api.model.ValueRef;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** RuleCube：维度键 / 缺维度兜底 / 矛盾判定行为测试。 */
class RuleCubeTest {

    @Test
    void dim_key_combines_metric_code_and_value_ref() {
        ConditionNode metricNode = new ConditionNode("GT", "age", null, Map.of(), 0.0, null, ValueRef.METRIC);
        ConditionNode payloadNode = new ConditionNode("GT", "age", null, Map.of(), 0.0, null, ValueRef.PAYLOAD);
        // 同 metricCode 但不同 valueRef → 不同维度键
        assertThat(RuleCube.dimKey(metricNode)).isEqualTo("age@METRIC");
        assertThat(RuleCube.dimKey(payloadNode)).isEqualTo("age@PAYLOAD");
    }

    @Test
    void absent_dim_returns_any() {
        RuleCube cube = new RuleCube(Map.of("age@METRIC", ConditionSpace.gt(18)));
        // 缺失维度视为无约束 = 全集
        assertThat(cube.dim("missing@METRIC")).isEqualTo(ConditionSpace.any());
    }

    @Test
    void incoherent_when_any_dim_empty() {
        RuleCube cube = new RuleCube(Map.of(
                "age@METRIC", ConditionSpace.empty(),
                "amount@METRIC", ConditionSpace.gt(0)));
        assertThat(cube.isIncoherent()).isTrue();
        assertThat(cube.firstEmptyDim()).contains("age@METRIC");
    }

    @Test
    void first_empty_dim_is_stable_in_insertion_order() {
        // 两个维度都为空（各自矛盾），age 在前、amount 在后；构造器保插入序，firstEmptyDim 恒返回靠前的 age 维
        Map<String, ConditionSpace> dims = new LinkedHashMap<>();
        dims.put("age@METRIC", ConditionSpace.empty());
        dims.put("amount@METRIC", ConditionSpace.empty());
        RuleCube cube = new RuleCube(dims);
        assertThat(cube.firstEmptyDim()).contains("age@METRIC");
    }

    @Test
    void coherent_when_no_dim_empty() {
        RuleCube cube = new RuleCube(Map.of("age@METRIC", ConditionSpace.gt(18)));
        assertThat(cube.isIncoherent()).isFalse();
        assertThat(cube.firstEmptyDim()).isEmpty();
    }

    // ---------- 立方体级关系：overlaps ----------

    @Test
    void overlaps_true_when_all_shared_dims_overlap() {
        // age 维 (18,+inf) 与 (10,+inf) 相交；amount 维 [0,100] 与 [50,200] 相交 → 立方体相交
        RuleCube a = new RuleCube(Map.of(
                "age@METRIC", ConditionSpace.gt(18),
                "amount@METRIC", ConditionSpace.between(0, 100)));
        RuleCube b = new RuleCube(Map.of(
                "age@METRIC", ConditionSpace.gt(10),
                "amount@METRIC", ConditionSpace.between(50, 200)));
        assertThat(a.overlaps(b)).isEqualTo(Tri.TRUE);
    }

    @Test
    void overlaps_false_when_any_dim_disjoint() {
        // age 维相交，但 amount 维 [0,40] 与 [50,200] 不相交 → 整体必不相交（FALSE 短路）
        RuleCube a = new RuleCube(Map.of(
                "age@METRIC", ConditionSpace.gt(18),
                "amount@METRIC", ConditionSpace.between(0, 40)));
        RuleCube b = new RuleCube(Map.of(
                "age@METRIC", ConditionSpace.gt(10),
                "amount@METRIC", ConditionSpace.between(50, 200)));
        assertThat(a.overlaps(b)).isEqualTo(Tri.FALSE);
    }

    @Test
    void overlaps_treats_absent_dim_as_any() {
        // a 仅约束 age；b 仅约束 amount。两维各自 vs any 都相交 → 立方体相交
        RuleCube a = new RuleCube(Map.of("age@METRIC", ConditionSpace.gt(18)));
        RuleCube b = new RuleCube(Map.of("amount@METRIC", ConditionSpace.between(50, 200)));
        assertThat(a.overlaps(b)).isEqualTo(Tri.TRUE);
    }

    @Test
    void overlaps_unknown_degrades_when_no_dim_disjoint() {
        // age 维相交；name 维 unknown 无法判定，且无任何维 FALSE → 整体降级 UNKNOWN
        RuleCube a = new RuleCube(Map.of(
                "age@METRIC", ConditionSpace.gt(18),
                "name@METRIC", ConditionSpace.unknown("regex")));
        RuleCube b = new RuleCube(Map.of(
                "age@METRIC", ConditionSpace.gt(10),
                "name@METRIC", ConditionSpace.unknown("regex")));
        assertThat(a.overlaps(b)).isEqualTo(Tri.UNKNOWN);
    }

    @Test
    void overlaps_false_wins_over_unknown() {
        // 一维 unknown，另一维明确不相交 → FALSE 短路优先于 UNKNOWN
        RuleCube a = new RuleCube(Map.of(
                "age@METRIC", ConditionSpace.between(0, 10),
                "name@METRIC", ConditionSpace.unknown("regex")));
        RuleCube b = new RuleCube(Map.of(
                "age@METRIC", ConditionSpace.between(50, 60),
                "name@METRIC", ConditionSpace.unknown("regex")));
        assertThat(a.overlaps(b)).isEqualTo(Tri.FALSE);
    }

    // ---------- 立方体级关系：subsumes ----------

    @Test
    void subsumes_true_when_this_contains_other_on_all_dims() {
        // a 的 age (10,+inf) ⊇ b 的 [20,30]；a 的 amount [0,200] ⊇ b 的 [50,100] → a ⊇ b
        RuleCube a = new RuleCube(Map.of(
                "age@METRIC", ConditionSpace.gt(10),
                "amount@METRIC", ConditionSpace.between(0, 200)));
        RuleCube b = new RuleCube(Map.of(
                "age@METRIC", ConditionSpace.between(20, 30),
                "amount@METRIC", ConditionSpace.between(50, 100)));
        assertThat(a.subsumes(b)).isEqualTo(Tri.TRUE);
    }

    @Test
    void subsumes_false_when_other_wider_on_some_dim() {
        // a 的 amount [0,80] 不包含 b 的 [50,100] → FALSE
        RuleCube a = new RuleCube(Map.of(
                "age@METRIC", ConditionSpace.gt(10),
                "amount@METRIC", ConditionSpace.between(0, 80)));
        RuleCube b = new RuleCube(Map.of(
                "age@METRIC", ConditionSpace.between(20, 30),
                "amount@METRIC", ConditionSpace.between(50, 100)));
        assertThat(a.subsumes(b)).isEqualTo(Tri.FALSE);
    }

    @Test
    void subsumes_absent_dim_in_this_is_any_and_contains() {
        // a 不约束 amount（= any 包含 b 的任意 amount），age 维 a ⊇ b → a ⊇ b
        RuleCube a = new RuleCube(Map.of("age@METRIC", ConditionSpace.gt(10)));
        RuleCube b = new RuleCube(Map.of(
                "age@METRIC", ConditionSpace.between(20, 30),
                "amount@METRIC", ConditionSpace.between(50, 100)));
        assertThat(a.subsumes(b)).isEqualTo(Tri.TRUE);
    }

    @Test
    void subsumes_unknown_degrades() {
        // age 维 a ⊇ b 成立，但 name 维 unknown → 无法判定，降级 UNKNOWN
        RuleCube a = new RuleCube(Map.of(
                "age@METRIC", ConditionSpace.gt(10),
                "name@METRIC", ConditionSpace.unknown("regex")));
        RuleCube b = new RuleCube(Map.of(
                "age@METRIC", ConditionSpace.between(20, 30),
                "name@METRIC", ConditionSpace.unknown("regex")));
        assertThat(a.subsumes(b)).isEqualTo(Tri.UNKNOWN);
    }
}
