package com.sstlfsj.rule.kernel.api.model;

/** 单个指标的取数结果，valueSource 标记来源（PROVIDED / FETCHED）。 */
public record MetricValue(
        Object value,
        String dataType,
        String valueSource
) {}
