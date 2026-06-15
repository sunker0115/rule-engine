package com.sstlfsj.rule.kernel.api.spi.metric;

import com.sstlfsj.rule.kernel.api.model.MetricQuery;
import com.sstlfsj.rule.kernel.api.model.MetricValue;

/** 从外部数据源获取指标值的 SPI 接口。 */
public interface MetricSourceHandler {
    /**
     * 从外部数据源获取指定查询对应的指标值。
     *
     * @param query 指标查询（含 metricCode、参数、主体等）
     * @return 指标取数结果；失败时返回标记 error 的 MetricValue
     */
    MetricValue fetch(MetricQuery query);

    /**
     * 带 trace 采集的取数：在取数编排各阶段回调 collector 记录可观测信息（自助测试用）。
     * <p>默认委托 {@link #fetch(MetricQuery)}（不采集）。支持自助测试的 handler 重写本方法，
     * 把取数主体移到此处并在各阶段调用 collector——正常路径传 {@link FetchTraceCollector#noop()}，
     * 保持单一编排、零复制。</p>
     *
     * @param query     指标查询
     * @param collector 分阶段 trace 收集器
     * @return 指标取数结果；失败时返回标记 error 的 MetricValue
     */
    default MetricValue fetch(MetricQuery query, FetchTraceCollector collector) {
        return fetch(query);
    }
}
