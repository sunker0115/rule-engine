package com.sstlfsj.rule.samples.metric;

import com.sstlfsj.rule.kernel.api.annotation.MetricSourceType;
import com.sstlfsj.rule.kernel.api.model.MetricQuery;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import com.sstlfsj.rule.kernel.api.spi.metric.MetricSourceHandler;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 模拟取数 handler:按 {@code @MetricSourceType} 路由到这里。真实实现会查 DB/缓存按 subjectId 统计
 * 近 N 分钟交易数;demo 按主体返回固定值(frequent-user=5、其余=1),以便复现 metric 驱动决策的效果。
 */
@MetricSourceType("DEMO_COUNTER")
@Component
public class RecentTxnCountHandler implements MetricSourceHandler {

    private static final Map<String, Integer> COUNT_BY_SUBJECT = Map.of("frequent-user", 5);

    @Override
    public MetricValue fetch(MetricQuery query) {
        int count = COUNT_BY_SUBJECT.getOrDefault(query.subjectId(), 1);
        return new MetricValue(count, "LONG", "FETCHED");
    }
}
