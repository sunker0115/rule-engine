package com.sstlfsj.rule.eval.internal.metric;

import com.sstlfsj.rule.eval.internal.metric.http.HttpEndpointRegistry;
import com.sstlfsj.rule.eval.internal.metric.sql.FetchResourceProperties;
import com.sstlfsj.rule.eval.internal.metric.sql.MetricDataSourceRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RegistryMetricResourceCatalogTest {

    @Test
    void exposesRegisteredNames() {
        FetchResourceProperties props = new FetchResourceProperties();
        try (MetricDataSourceRegistry ds = new MetricDataSourceRegistry(props)) {
            HttpEndpointRegistry ep = new HttpEndpointRegistry(props);
            RegistryMetricResourceCatalog cat = new RegistryMetricResourceCatalog(ds, ep);
            assertThat(cat.datasourceNames()).isEmpty();
            assertThat(cat.endpointNames()).isEmpty();
        }
    }
}
