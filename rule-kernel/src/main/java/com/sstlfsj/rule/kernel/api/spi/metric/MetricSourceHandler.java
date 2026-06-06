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
}
