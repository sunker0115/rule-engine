package com.sstlfsj.rule.kernel.internal.condition;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DateComparisonSupport 单元测试：覆盖 DATE/DATETIME 两条解析路径及边界防御。
 * DateComparisonSupport 是 package-private，测试与实现同包，直接调用静态方法。
 */
class DateComparisonSupportTest {

    private static final Instant NOW = Instant.parse("2026-06-02T00:00:00Z");

    private EvalContext ctx(String metricCode, Object value, String dataType) {
        RuleEvent event = new RuleEvent("t1", "s1", "E", "u1", "e1", NOW, Map.of(), Map.of());
        return new EvalContext("t1", event, null,
                Map.of(metricCode, new MetricValue(value, dataType, "PROVIDED")), NOW);
    }

    private ConditionNode node(String condType, String metricCode, Object threshold, String dataType) {
        return new ConditionNode(condType, metricCode, "", Map.of("threshold", threshold), 0.0, dataType);
    }

    // ── DATE 路径（LocalDate 比较） ──────────────────────────────────────────

    @Test
    void date_before_staticString_returnsTrue() {
        // metric=2026-06-01 < threshold=2026-06-02 → before=true
        ConditionNode n = node("DATE_BEFORE", "d", "2026-06-02", "DATE");
        assertThat(DateComparisonSupport.evaluate(n, ctx("d", "2026-06-01", "DATE"), true)).isTrue();
    }

    @Test
    void date_before_staticString_returnsFalse_whenActualIsAfter() {
        // metric=2026-06-03 > threshold=2026-06-02 → before=false
        ConditionNode n = node("DATE_BEFORE", "d", "2026-06-02", "DATE");
        assertThat(DateComparisonSupport.evaluate(n, ctx("d", "2026-06-03", "DATE"), true)).isFalse();
    }

    @Test
    void date_after_placeholder_today_returnsTrue() {
        // metric=2026-06-03，threshold=$today（NOW=2026-06-02）→ 03 > 02 → after=true
        ConditionNode n = node("DATE_AFTER", "d", "$today", "DATE");
        assertThat(DateComparisonSupport.evaluate(n, ctx("d", "2026-06-03", "DATE"), false)).isTrue();
    }

    @Test
    void date_before_placeholder_today_returnsTrue() {
        // metric=2026-06-01，threshold=$today（NOW=2026-06-02）→ 01 < 02 → before=true
        ConditionNode n = node("DATE_BEFORE", "d", "$today", "DATE");
        assertThat(DateComparisonSupport.evaluate(n, ctx("d", "2026-06-01", "DATE"), true)).isTrue();
    }

    // ── DATETIME 路径（dataType=null → DATETIME fallback，保留旧 toInstant 语义） ──

    @Test
    void datetime_fallback_instantString_before_returnsTrue() {
        // dataType=null → DATETIME; metric=2022-01-01Z < threshold=2030-01-01Z → before=true
        ConditionNode n = new ConditionNode("DATE_BEFORE", "d", "",
                Map.of("threshold", "2030-01-01T00:00:00Z"), 0.0);
        assertThat(DateComparisonSupport.evaluate(n, ctx("d", "2022-01-01T00:00:00Z", "UNKNOWN"), true)).isTrue();
    }

    @Test
    void datetime_fallback_bareDate_before_returnsTrue() {
        // dataType=null → DATETIME; 裸日期字符串 → atStartOfDay(UTC)；metric=2022-01-01 < threshold=2023-01-01
        ConditionNode n = new ConditionNode("DATE_BEFORE", "d", "",
                Map.of("threshold", "2023-01-01"), 0.0);
        assertThat(DateComparisonSupport.evaluate(n, ctx("d", "2022-01-01", "UNKNOWN"), true)).isTrue();
    }

    // ── 边界防御 ──────────────────────────────────────────────────────────────

    @Test
    void metricMissing_returnsFalse() {
        RuleEvent event = new RuleEvent("t1", "s1", "E", "u1", "e1", NOW, Map.of(), Map.of());
        EvalContext emptyCtx = new EvalContext("t1", event, null, Map.of(), NOW);
        ConditionNode n = node("DATE_BEFORE", "d", "2026-06-02", "DATE");
        assertThat(DateComparisonSupport.evaluate(n, emptyCtx, true)).isFalse();
    }

    @Test
    void thresholdNull_returnsFalse() {
        // params 中无 threshold 键 → threshold=null → false
        ConditionNode n = new ConditionNode("DATE_BEFORE", "d", "", Map.of(), 0.0, "DATE");
        assertThat(DateComparisonSupport.evaluate(n, ctx("d", "2026-06-01", "DATE"), true)).isFalse();
    }

    @Test
    void invalidThreshold_returnsFalse() {
        // 无法解析的 threshold → operand=null → false
        ConditionNode n = node("DATE_BEFORE", "d", "not-a-date", "DATE");
        assertThat(DateComparisonSupport.evaluate(n, ctx("d", "2026-06-01", "DATE"), true)).isFalse();
    }
}
