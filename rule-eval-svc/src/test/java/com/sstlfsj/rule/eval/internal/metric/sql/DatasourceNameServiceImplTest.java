package com.sstlfsj.rule.eval.internal.metric.sql;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Set;

/** 验证 DatasourceNameServiceImpl 委托 MetricDataSourceRegistry.names()。 */
class DatasourceNameServiceImplTest {

    @Test
    void registeredNames_delegatesToRegistry() {
        MetricDataSourceRegistry registry = mock(MetricDataSourceRegistry.class);
        when(registry.names()).thenReturn(Set.of("biz", "fraud"));

        DatasourceNameServiceImpl svc = new DatasourceNameServiceImpl(registry);

        assertThat(svc.registeredNames()).containsExactlyInAnyOrder("biz", "fraud");
    }

    @Test
    void registeredNames_emptyRegistry_returnsEmpty() {
        MetricDataSourceRegistry registry = mock(MetricDataSourceRegistry.class);
        when(registry.names()).thenReturn(Set.of());

        DatasourceNameServiceImpl svc = new DatasourceNameServiceImpl(registry);

        assertThat(svc.registeredNames()).isEmpty();
    }
}
