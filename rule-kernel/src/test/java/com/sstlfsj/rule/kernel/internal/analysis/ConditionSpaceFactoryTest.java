package com.sstlfsj.rule.kernel.internal.analysis;

import com.sstlfsj.rule.kernel.api.model.ConditionParams;
import com.sstlfsj.rule.kernel.api.model.ConditionTypes;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** ConditionSpaceFactory：叶子条件 → 取值空间映射的行为测试。 */
class ConditionSpaceFactoryTest {

    private static ConditionNode node(String type, Map<String, Object> params) {
        return new ConditionNode(type, "amount", null, params, 0.0);
    }

    // ---------- 精确建模：数值比较 ----------

    @Test
    void gt_maps_to_open_lower_bound_range() {
        ConditionSpace s = ConditionSpaceFactory.from(
                node(ConditionTypes.GT, Map.of(ConditionParams.THRESHOLD, 100)));
        // (100, +inf)：含 100.5，不含 100，不含 99
        assertThat(s.subsumes(ConditionSpace.eq(100.5))).isEqualTo(Tri.TRUE);
        assertThat(s.subsumes(ConditionSpace.eq(100.0))).isEqualTo(Tri.FALSE);
        assertThat(s.subsumes(ConditionSpace.eq(99.0))).isEqualTo(Tri.FALSE);
    }

    @Test
    void gte_includes_boundary() {
        ConditionSpace s = ConditionSpaceFactory.from(
                node(ConditionTypes.GTE, Map.of(ConditionParams.THRESHOLD, 100)));
        assertThat(s.subsumes(ConditionSpace.eq(100.0))).isEqualTo(Tri.TRUE);
        assertThat(s.subsumes(ConditionSpace.eq(99.999))).isEqualTo(Tri.FALSE);
    }

    @Test
    void lt_maps_to_open_upper_bound_range() {
        ConditionSpace s = ConditionSpaceFactory.from(
                node(ConditionTypes.LT, Map.of(ConditionParams.THRESHOLD, 50)));
        assertThat(s.subsumes(ConditionSpace.eq(49.0))).isEqualTo(Tri.TRUE);
        assertThat(s.subsumes(ConditionSpace.eq(50.0))).isEqualTo(Tri.FALSE);
    }

    @Test
    void lte_includes_boundary() {
        ConditionSpace s = ConditionSpaceFactory.from(
                node(ConditionTypes.LTE, Map.of(ConditionParams.THRESHOLD, 50)));
        assertThat(s.subsumes(ConditionSpace.eq(50.0))).isEqualTo(Tri.TRUE);
        assertThat(s.subsumes(ConditionSpace.eq(50.001))).isEqualTo(Tri.FALSE);
    }

    @Test
    void threshold_as_numeric_string_is_parsed() {
        ConditionSpace s = ConditionSpaceFactory.from(
                node(ConditionTypes.GT, Map.of(ConditionParams.THRESHOLD, "100")));
        assertThat(s.isUnknown()).isFalse();
        assertThat(s.subsumes(ConditionSpace.eq(101.0))).isEqualTo(Tri.TRUE);
    }

    // ---------- 精确建模：BETWEEN ----------

    @Test
    void between_maps_to_closed_interval() {
        ConditionSpace s = ConditionSpaceFactory.from(
                node(ConditionTypes.BETWEEN, Map.of(ConditionParams.MIN, 10, ConditionParams.MAX, 20)));
        // [10, 20]：含端点，不含 9 / 21
        assertThat(s.subsumes(ConditionSpace.eq(10.0))).isEqualTo(Tri.TRUE);
        assertThat(s.subsumes(ConditionSpace.eq(20.0))).isEqualTo(Tri.TRUE);
        assertThat(s.subsumes(ConditionSpace.eq(15.0))).isEqualTo(Tri.TRUE);
        assertThat(s.subsumes(ConditionSpace.eq(9.0))).isEqualTo(Tri.FALSE);
        assertThat(s.subsumes(ConditionSpace.eq(21.0))).isEqualTo(Tri.FALSE);
    }

    // ---------- 精确建模：EQ / IN ----------

    @Test
    void eq_maps_to_single_point() {
        ConditionSpace s = ConditionSpaceFactory.from(
                node(ConditionTypes.EQ, Map.of(ConditionParams.THRESHOLD, "VIP")));
        assertThat(s.overlaps(ConditionSpace.eq("VIP"))).isEqualTo(Tri.TRUE);
        assertThat(s.overlaps(ConditionSpace.eq("REGULAR"))).isEqualTo(Tri.FALSE);
    }

    @Test
    void in_maps_to_point_set() {
        ConditionSpace s = ConditionSpaceFactory.from(
                node(ConditionTypes.IN, Map.of(ConditionParams.VALUES, List.of("A", "B", "C"))));
        assertThat(s.subsumes(ConditionSpace.in(Set.of("A", "B")))).isEqualTo(Tri.TRUE);
        assertThat(s.overlaps(ConditionSpace.eq("D"))).isEqualTo(Tri.FALSE);
        assertThat(s.overlaps(ConditionSpace.eq("B"))).isEqualTo(Tri.TRUE);
    }

    // ---------- 精确建模：日期 ----------

    @Test
    void date_before_literal_maps_to_lt_epoch_millis() {
        ConditionSpace s = ConditionSpaceFactory.from(
                node(ConditionTypes.DATE_BEFORE, Map.of(ConditionParams.THRESHOLD, "2026-01-01T00:00:00Z")));
        long epoch = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli();
        assertThat(s.isUnknown()).isFalse();
        // 早一毫秒在空间内，晚一毫秒不在
        assertThat(s.subsumes(ConditionSpace.eq((double) (epoch - 1)))).isEqualTo(Tri.TRUE);
        assertThat(s.subsumes(ConditionSpace.eq((double) epoch))).isEqualTo(Tri.FALSE);
    }

    @Test
    void date_after_literal_maps_to_gt_epoch_millis() {
        ConditionSpace s = ConditionSpaceFactory.from(
                node(ConditionTypes.DATE_AFTER, Map.of(ConditionParams.THRESHOLD, "2026-01-01T00:00:00Z")));
        long epoch = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli();
        assertThat(s.isUnknown()).isFalse();
        assertThat(s.subsumes(ConditionSpace.eq((double) (epoch + 1)))).isEqualTo(Tri.TRUE);
        assertThat(s.subsumes(ConditionSpace.eq((double) epoch))).isEqualTo(Tri.FALSE);
    }

    @Test
    void date_before_bare_date_literal_is_parsed() {
        ConditionSpace s = ConditionSpaceFactory.from(
                node(ConditionTypes.DATE_BEFORE, Map.of(ConditionParams.THRESHOLD, "2026-01-01")));
        assertThat(s.isUnknown()).isFalse();
    }

    @Test
    void date_with_dynamic_placeholder_degrades_to_unknown() {
        ConditionSpace s = ConditionSpaceFactory.from(
                node(ConditionTypes.DATE_BEFORE, Map.of(ConditionParams.THRESHOLD, "$now")));
        assertThat(s.isUnknown()).isTrue();
    }

    @Test
    void date_with_unparseable_literal_degrades_to_unknown() {
        ConditionSpace s = ConditionSpaceFactory.from(
                node(ConditionTypes.DATE_AFTER, Map.of(ConditionParams.THRESHOLD, "not-a-date")));
        assertThat(s.isUnknown()).isTrue();
    }

    // ---------- 降级：v1 不建模的算子 ----------

    @Test
    void neq_degrades_to_unknown() {
        ConditionSpace s = ConditionSpaceFactory.from(
                node(ConditionTypes.NEQ, Map.of(ConditionParams.THRESHOLD, 100)));
        assertThat(s.isUnknown()).isTrue();
    }

    @Test
    void not_in_degrades_to_unknown() {
        ConditionSpace s = ConditionSpaceFactory.from(
                node(ConditionTypes.NOT_IN, Map.of(ConditionParams.VALUES, List.of("A", "B"))));
        assertThat(s.isUnknown()).isTrue();
    }

    @Test
    void not_between_degrades_to_unknown() {
        ConditionSpace s = ConditionSpaceFactory.from(
                node(ConditionTypes.NOT_BETWEEN, Map.of(ConditionParams.MIN, 10, ConditionParams.MAX, 20)));
        assertThat(s.isUnknown()).isTrue();
    }

    @Test
    void matches_degrades_to_unknown() {
        ConditionSpace s = ConditionSpaceFactory.from(
                node(ConditionTypes.MATCHES, Map.of(ConditionParams.REGEX, "^A.*")));
        assertThat(s.isUnknown()).isTrue();
    }

    @Test
    void contains_degrades_to_unknown() {
        ConditionSpace s = ConditionSpaceFactory.from(
                node(ConditionTypes.CONTAINS, Map.of(ConditionParams.ELEMENT, "x")));
        assertThat(s.isUnknown()).isTrue();
    }

    @Test
    void starts_with_degrades_to_unknown() {
        ConditionSpace s = ConditionSpaceFactory.from(
                node(ConditionTypes.STARTS_WITH, Map.of(ConditionParams.PREFIX, "AB")));
        assertThat(s.isUnknown()).isTrue();
    }

    @Test
    void time_window_degrades_to_unknown() {
        ConditionSpace s = ConditionSpaceFactory.from(
                node(ConditionTypes.TIME_WINDOW, Map.of(ConditionParams.START, "09:00", ConditionParams.END, "18:00")));
        assertThat(s.isUnknown()).isTrue();
    }

    @Test
    void custom_spi_condition_type_degrades_to_unknown() {
        ConditionSpace s = ConditionSpaceFactory.from(
                node("my.custom.operator", Map.of("foo", "bar")));
        assertThat(s.isUnknown()).isTrue();
    }

    // ---------- 降级：参数缺失 / 类型错误 ----------

    @Test
    void gt_missing_threshold_degrades_to_unknown() {
        ConditionSpace s = ConditionSpaceFactory.from(node(ConditionTypes.GT, Map.of()));
        assertThat(s.isUnknown()).isTrue();
    }

    @Test
    void gt_non_numeric_threshold_degrades_to_unknown() {
        ConditionSpace s = ConditionSpaceFactory.from(
                node(ConditionTypes.GT, Map.of(ConditionParams.THRESHOLD, "abc")));
        assertThat(s.isUnknown()).isTrue();
    }

    @Test
    void between_missing_max_degrades_to_unknown() {
        ConditionSpace s = ConditionSpaceFactory.from(
                node(ConditionTypes.BETWEEN, Map.of(ConditionParams.MIN, 10)));
        assertThat(s.isUnknown()).isTrue();
    }

    @Test
    void between_non_numeric_bound_degrades_to_unknown() {
        ConditionSpace s = ConditionSpaceFactory.from(
                node(ConditionTypes.BETWEEN, Map.of(ConditionParams.MIN, "lo", ConditionParams.MAX, 20)));
        assertThat(s.isUnknown()).isTrue();
    }

    @Test
    void in_missing_values_degrades_to_unknown() {
        ConditionSpace s = ConditionSpaceFactory.from(node(ConditionTypes.IN, Map.of()));
        assertThat(s.isUnknown()).isTrue();
    }

    @Test
    void eq_missing_threshold_degrades_to_unknown() {
        ConditionSpace s = ConditionSpaceFactory.from(node(ConditionTypes.EQ, Map.of()));
        assertThat(s.isUnknown()).isTrue();
    }
}
