package com.sstlfsj.rule.kernel.api.spi.metric;

import com.sstlfsj.rule.kernel.api.model.MetricQuery;
import com.sstlfsj.rule.kernel.api.model.MetricValue;

/** Fetches a metric value from an external data source. */
public interface MetricSourceHandler {
    MetricValue fetch(MetricQuery query);
}
