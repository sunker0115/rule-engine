package com.sstlfsj.rule.eval.internal.metric;

import com.sstlfsj.rule.eval.internal.metric.http.HttpEndpointRegistry;
import com.sstlfsj.rule.eval.internal.metric.sql.FetchResourceProperties;
import com.sstlfsj.rule.eval.internal.metric.sql.MetricDataSourceRegistry;
import com.sstlfsj.rule.eval.internal.repository.ConnectorDefinitionReadMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RegistryMetricResourceCatalogTest {

    @Test
    void exposesRegisteredNames() {
        FetchResourceProperties props = new FetchResourceProperties();
        try (MetricDataSourceRegistry ds = new MetricDataSourceRegistry(props)) {
            HttpEndpointRegistry ep = new HttpEndpointRegistry(props);
            ConnectorDefinitionReadMapper connectorMapper = mock(ConnectorDefinitionReadMapper.class);
            RegistryMetricResourceCatalog cat = new RegistryMetricResourceCatalog(ds, ep, connectorMapper);
            assertThat(cat.datasourceNames()).isEmpty();
            assertThat(cat.endpointNames()).isEmpty();
        }
    }

    @Test
    void connectorNames_returnsActiveCodesForTenant() {
        FetchResourceProperties props = new FetchResourceProperties();
        try (MetricDataSourceRegistry ds = new MetricDataSourceRegistry(props)) {
            HttpEndpointRegistry ep = new HttpEndpointRegistry(props);
            ConnectorDefinitionReadMapper connectorMapper = mock(ConnectorDefinitionReadMapper.class);
            when(connectorMapper.findActiveCodes(7L)).thenReturn(List.of("risk-svc", "kyc-svc"));
            RegistryMetricResourceCatalog cat = new RegistryMetricResourceCatalog(ds, ep, connectorMapper);
            assertThat(cat.connectorNames(7L)).containsExactlyInAnyOrder("risk-svc", "kyc-svc");
        }
    }
}
