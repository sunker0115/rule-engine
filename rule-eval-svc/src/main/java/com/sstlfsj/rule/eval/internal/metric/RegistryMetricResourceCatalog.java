package com.sstlfsj.rule.eval.internal.metric;

import com.sstlfsj.rule.config.api.spi.MetricResourceCatalog;
import com.sstlfsj.rule.eval.internal.metric.http.HttpEndpointRegistry;
import com.sstlfsj.rule.eval.internal.metric.sql.MetricDataSourceRegistry;
import org.springframework.stereotype.Component;

import java.util.Set;

/** 基于运行时 registry 暴露已注册资源名，供发布期校验。 */
@Component
public class RegistryMetricResourceCatalog implements MetricResourceCatalog {

    private final MetricDataSourceRegistry dataSourceRegistry;
    private final HttpEndpointRegistry endpointRegistry;

    public RegistryMetricResourceCatalog(MetricDataSourceRegistry dataSourceRegistry,
                                         HttpEndpointRegistry endpointRegistry) {
        this.dataSourceRegistry = dataSourceRegistry;
        this.endpointRegistry = endpointRegistry;
    }

    @Override
    public Set<String> datasourceNames() {
        return dataSourceRegistry.names();
    }

    @Override
    public Set<String> endpointNames() {
        return endpointRegistry.names();
    }
}
