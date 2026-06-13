package com.sstlfsj.rule.samples.metric;

import com.sstlfsj.rule.sdk.annotation.Fact;
import com.sstlfsj.rule.sdk.annotation.MetricSource;
import org.springframework.stereotype.Component;

/**
 * @MetricSource 方法式取数:一个带注解的方法即"取数逻辑 + metric 定义",替代"实现 MetricSourceHandler
 * 接口 + 写 MetricDescriptor"两步。recent_txn_count 模拟按 subjectId 统计近期交易数。
 */
@Component
public class VelocityMetrics {

    @MetricSource(value = "recent_txn_count", cacheTtlSeconds = 60)
    public long recentTxnCount(@Fact("subjectId") String subjectId) {
        return "frequent-user".equals(subjectId) ? 5 : 1;
    }
}
