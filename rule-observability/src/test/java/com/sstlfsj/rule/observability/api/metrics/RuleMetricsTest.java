package com.sstlfsj.rule.observability.api.metrics;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.*;

class RuleMetricsTest {

    @Test
    void constructor_isPrivate() throws NoSuchMethodException {
        Constructor<RuleMetrics> ctor = RuleMetrics.class.getDeclaredConstructor();
        assertTrue(Modifier.isPrivate(ctor.getModifiers()), "工具类构造函数必须是 private");
    }

    @Test
    void evalMetrics_haveExpectedNames() {
        assertEquals("rule_eval_duration_seconds", RuleMetrics.EVAL_DURATION_SECONDS);
        assertEquals("rule_eval_total", RuleMetrics.EVAL_TOTAL);
        assertEquals("rule_eval_error_total", RuleMetrics.EVAL_ERROR_TOTAL);
    }

    @Test
    void metricFetchMetrics_haveExpectedNames() {
        assertEquals("rule_metric_fetch_duration_seconds", RuleMetrics.METRIC_FETCH_DURATION);
        assertEquals("rule_metric_cache_hit_total", RuleMetrics.METRIC_CACHE_HIT_TOTAL);
        assertEquals("rule_metric_cache_miss_total", RuleMetrics.METRIC_CACHE_MISS_TOTAL);
    }

    @Test
    void traceMetrics_haveExpectedNames() {
        assertEquals("rule_trace_queue_size", RuleMetrics.TRACE_QUEUE_SIZE);
        assertEquals("rule_trace_write_batch_total", RuleMetrics.TRACE_WRITE_BATCH_TOTAL);
    }

    @Test
    void allMetricNames_startWithRulePrefix() {
        String[] names = {
            RuleMetrics.EVAL_DURATION_SECONDS,
            RuleMetrics.EVAL_TOTAL,
            RuleMetrics.EVAL_ERROR_TOTAL,
            RuleMetrics.METRIC_FETCH_DURATION,
            RuleMetrics.METRIC_CACHE_HIT_TOTAL,
            RuleMetrics.METRIC_CACHE_MISS_TOTAL,
            RuleMetrics.TRACE_QUEUE_SIZE,
            RuleMetrics.TRACE_WRITE_BATCH_TOTAL,
        };
        for (String name : names) {
            assertTrue(name.startsWith("rule_"), "指标名称必须以 rule_ 开头: " + name);
        }
    }
}
