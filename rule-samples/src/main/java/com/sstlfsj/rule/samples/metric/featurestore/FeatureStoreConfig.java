package com.sstlfsj.rule.samples.metric.featurestore;

import com.sstlfsj.rule.kernel.api.model.DataType;
import com.sstlfsj.rule.kernel.api.model.MetricDescriptor;
import com.sstlfsj.rule.sdk.source.DslMetricDefinitionSource;
import com.sstlfsj.rule.sdk.source.MetricDefinitionSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/** 两个 metric 共享 sourceType=FEATURE_STORE,各一行定义;加特征只加定义、不改 handler。 */
@Configuration
public class FeatureStoreConfig {

    @Bean
    MetricDefinitionSource featureStoreMetrics(@Value("${rule.sdk.tenant-id:}") String tenant) {
        return new DslMetricDefinitionSource(tenant, List.of(
                new MetricDescriptor("account_age_days", "FEATURE_STORE", DataType.LONG.tag(), false, 300, Map.of()),
                new MetricDescriptor("device_risk_score", "FEATURE_STORE", DataType.LONG.tag(), false, 300, Map.of())));
    }
}
