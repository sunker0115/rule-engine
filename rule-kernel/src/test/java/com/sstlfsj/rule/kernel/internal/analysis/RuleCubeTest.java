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
}
