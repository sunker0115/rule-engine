package com.sstlfsj.rule.kernel.api.spi.metric;

import com.sstlfsj.rule.kernel.api.model.MetricQuery;
import com.sstlfsj.rule.kernel.api.model.MetricValue;

/** 从外部数据源获取指标值的 SPI 接口。 */
public interface MetricSourceHandler {
    MetricValue fetch(MetricQuery query);
}
