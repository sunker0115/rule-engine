package com.sstlfsj.rule.sdk.source;

import com.sstlfsj.rule.sdk.metric.MetricDefinitionRegistry;

/**
 * metric 定义来源 SPI：对称于 {@link RuleSource}——规则有几种来源，定义就有对称的几种，
 * 统一经本方法写入本地定义注册表。{@link com.sstlfsj.rule.sdk.metric.SnapshotMetricDefinitionResolver} 从注册表读，
 * 三种来源（HTTP/文件/DSL）对 resolver 透明。
 */
public interface MetricDefinitionSource {

    /**
     * 将本来源持有的 metric 定义写入注册表。
     *
     * @param registry 目标定义注册表
     */
    void loadInto(MetricDefinitionRegistry registry);
}
