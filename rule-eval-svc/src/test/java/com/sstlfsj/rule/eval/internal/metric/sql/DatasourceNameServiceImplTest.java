package com.sstlfsj.rule.eval.internal.metric.sql;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
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

    @Test
    void tables_unknownDatasource_returnsEmptyList() {
        MetricDataSourceRegistry registry = mock(MetricDataSourceRegistry.class);
        when(registry.template("nonexistent")).thenReturn(null);

        DatasourceNameServiceImpl svc = new DatasourceNameServiceImpl(registry);

        assertThat(svc.tables("nonexistent")).isEmpty();
    }

    @Test
    void tables_knownDatasource_delegatesToJdbcQuery() {
        MetricDataSourceRegistry registry = mock(MetricDataSourceRegistry.class);
        NamedParameterJdbcTemplate tpl = mock(NamedParameterJdbcTemplate.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(registry.template("ds1")).thenReturn(tpl);
        when(tpl.getJdbcTemplate()).thenReturn(jdbc);
        when(jdbc.queryForList(anyString(), eq(String.class))).thenReturn(List.of("orders", "users"));

        DatasourceNameServiceImpl svc = new DatasourceNameServiceImpl(registry);

        assertThat(svc.tables("ds1")).containsExactly("orders", "users");
    }

    @Test
    void columns_unknownDatasource_returnsEmptyList() {
        MetricDataSourceRegistry registry = mock(MetricDataSourceRegistry.class);
        when(registry.template("nonexistent")).thenReturn(null);

        DatasourceNameServiceImpl svc = new DatasourceNameServiceImpl(registry);

        assertThat(svc.columns("nonexistent", "orders")).isEmpty();
    }

    @Test
    void columns_knownDatasource_delegatesToJdbcQuery() {
        MetricDataSourceRegistry registry = mock(MetricDataSourceRegistry.class);
        NamedParameterJdbcTemplate tpl = mock(NamedParameterJdbcTemplate.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(registry.template("ds1")).thenReturn(tpl);
        when(tpl.getJdbcTemplate()).thenReturn(jdbc);
        when(jdbc.queryForList(anyString(), eq(String.class), eq("orders")))
                .thenReturn(List.of("id", "status", "amount"));

        DatasourceNameServiceImpl svc = new DatasourceNameServiceImpl(registry);

        assertThat(svc.columns("ds1", "orders")).containsExactly("id", "status", "amount");
    }
}
