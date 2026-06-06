package com.sstlfsj.rule.eval.internal.metric;

import com.sstlfsj.rule.eval.internal.domain.MetricDefinitionRow;
import com.sstlfsj.rule.eval.internal.repository.MetricDefinitionReadMapper;
import com.sstlfsj.rule.kernel.api.model.MetricDescriptor;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DbMetricDefinitionResolverTest {

    @Mock MetricDefinitionReadMapper mapper;
    private final ObjectMapper om = JsonMapper.builder().build();

    @Test
    void resolve_buildsDescriptor_withDataTypeInParams() {
        when(mapper.findByVersion(1L, "balance", 2)).thenReturn(new MetricDefinitionRow(
                "balance", 2, "SQL_AGGREGATE", "LONG", false, 30, "{\"datasource\":\"ro\"}"));
        DbMetricDefinitionResolver r = new DbMetricDefinitionResolver(mapper, om);

        MetricDescriptor d = r.resolve("1", "balance", 2);

        assertThat(d.metricVersion()).isEqualTo(2);
        assertThat(d.sourceType()).isEqualTo("SQL_AGGREGATE");
        assertThat(d.cacheTtlSeconds()).isEqualTo(30);
        assertThat(d.params()).containsEntry("datasource", "ro").containsEntry("dataType", "LONG");
    }

    @Test
    void resolve_missing_returnsNull() {
        when(mapper.findByVersion(1L, "x", 1)).thenReturn(null);
        assertThat(new DbMetricDefinitionResolver(mapper, om).resolve("1", "x", 1)).isNull();
    }

    @Test
    void resolve_secondCall_servedFromCache() {
        when(mapper.findByVersion(1L, "balance", 1)).thenReturn(new MetricDefinitionRow(
                "balance", 1, "SQL_AGGREGATE", "LONG", false, 30, "{}"));
        DbMetricDefinitionResolver r = new DbMetricDefinitionResolver(mapper, om);
        r.resolve("1", "balance", 1);
        r.resolve("1", "balance", 1);
        verify(mapper, times(1)).findByVersion(1L, "balance", 1);
    }

    @Test
    void resolve_differentVersions_queriedSeparately() {
        // 同 code 不同版本应各自查库（缓存键含 version）
        when(mapper.findByVersion(1L, "balance", 1)).thenReturn(new MetricDefinitionRow(
                "balance", 1, "SQL_AGGREGATE", "LONG", false, 0, "{}"));
        when(mapper.findByVersion(1L, "balance", 2)).thenReturn(new MetricDefinitionRow(
                "balance", 2, "SQL_AGGREGATE", "LONG", false, 0, "{}"));
        DbMetricDefinitionResolver r = new DbMetricDefinitionResolver(mapper, om);

        MetricDescriptor d1 = r.resolve("1", "balance", 1);
        MetricDescriptor d2 = r.resolve("1", "balance", 2);

        assertThat(d1.metricVersion()).isEqualTo(1);
        assertThat(d2.metricVersion()).isEqualTo(2);
        verify(mapper, times(1)).findByVersion(1L, "balance", 1);
        verify(mapper, times(1)).findByVersion(1L, "balance", 2);
    }
}
