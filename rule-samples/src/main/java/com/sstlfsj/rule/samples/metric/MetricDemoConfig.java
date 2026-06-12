package com.sstlfsj.rule.samples.metric;

import com.sstlfsj.rule.kernel.api.model.DataType;
import com.sstlfsj.rule.kernel.api.model.MetricDescriptor;
import com.sstlfsj.rule.sdk.source.DslMetricDefinitionSource;
import com.sstlfsj.rule.sdk.source.MetricDefinitionSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/**
 * metric 定义来源:告诉引擎 {@code recent_txn_count} 走哪个 sourceType(DEMO_COUNTER)、是否允许调用方
 * 直接推值(allowProvided=false 表示恒走 fetch)、缓存 ttl。starter 自动收集 {@link MetricDefinitionSource}
 * Bean 注入 client。
 * <p>租户取 {@code rule.sdk.tenant-id}(缺省空),与注解规则装载的租户一致,保证定义按 (tenant, code) 能解析到。
 */
@Configuration
public class MetricDemoConfig {

    @Bean
    MetricDefinitionSource recentTxnCountDefinition(
            @Value("${rule.sdk.tenant-id:}") String tenant) {
        MetricDescriptor descriptor = new MetricDescriptor(
                "recent_txn_count", "DEMO_COUNTER", DataType.LONG.tag(), false, 60, Map.of());
        return new DslMetricDefinitionSource(tenant, List.of(descriptor));
    }
}
