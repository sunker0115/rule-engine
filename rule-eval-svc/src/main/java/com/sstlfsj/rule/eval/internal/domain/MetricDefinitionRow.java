package com.sstlfsj.rule.eval.internal.domain;

/** metric_definition 只读映射载体（供 DbMetricDefinitionResolver 装配 MetricDescriptor）。 */
public record MetricDefinitionRow(
        String metricCode,
        int version,
        String sourceType,
        String dataType,
        Boolean allowProvided,
        Integer cacheTtlSeconds,
        String paramsJson
) {}
