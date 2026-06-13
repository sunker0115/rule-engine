package com.sstlfsj.rule.samples.metric.featurestore;

import com.sstlfsj.rule.kernel.api.annotation.MetricSourceType;
import com.sstlfsj.rule.kernel.api.model.DataType;
import com.sstlfsj.rule.kernel.api.model.MetricQuery;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import com.sstlfsj.rule.kernel.api.model.ValueSource;
import com.sstlfsj.rule.kernel.api.spi.metric.MetricSourceHandler;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 一个 handler 服务多个 metric:所有 sourceType=FEATURE_STORE 的 metric 都进这里,取数代码一样
 * (查特征库),差别只是 query.metricCode() 决定取哪个特征。新增特征 = 加一行定义(见 FeatureStoreConfig),
 * 不改本类——这正是"配置驱动、共享后端"用接口式而非 @MetricSource 的场景。
 */
@MetricSourceType("FEATURE_STORE")
@Component
public class FeatureStoreHandler implements MetricSourceHandler {

    // 模拟特征库:subject → {特征名: 值}
    private static final Map<String, Map<String, Long>> STORE = Map.of(
            "vip-user", Map.of("account_age_days", 1200L, "device_risk_score", 10L),
            "new-user", Map.of("account_age_days", 3L, "device_risk_score", 80L));

    @Override
    public MetricValue fetch(MetricQuery query) {
        Map<String, Long> features = STORE.getOrDefault(query.subjectId(), Map.of());
        Long v = features.get(query.metricCode());   // ← 按 metricCode 取不同特征
        if (v == null) {
            return MetricValue.error("FEATURE_NOT_FOUND");
        }
        return new MetricValue(v, DataType.LONG.tag(), ValueSource.FETCHED.tag());
    }
}
