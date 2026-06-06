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
        when(mapper.findActive(1L, "balance")).thenReturn(new MetricDefinitionRow(
                "balance", "SQL_AGGREGATE", "LONG", false, 30, "{\"datasource\":\"ro\"}"));
        DbMetricDefinitionResolver r = new DbMetricDefinitionResolver(mapper, om);

        MetricDescriptor d = r.resolve("1", "balance");

        assertThat(d.sourceType()).isEqualTo("SQL_AGGREGATE");
        assertThat(d.cacheTtlSeconds()).isEqualTo(30);
        assertThat(d.params()).containsEntry("datasource", "ro").containsEntry("dataType", "LONG");
    }

    @Test
    void resolve_missing_returnsNull() {
        when(mapper.findActive(1L, "x")).thenReturn(null);
        assertThat(new DbMetricDefinitionResolver(mapper, om).resolve("1", "x")).isNull();
    }

    @Test
    void resolve_secondCall_servedFromCache() {
        when(mapper.findActive(1L, "balance")).thenReturn(new MetricDefinitionRow(
                "balance", "SQL_AGGREGATE", "LONG", false, 30, "{}"));
        DbMetricDefinitionResolver r = new DbMetricDefinitionResolver(mapper, om);
        r.resolve("1", "balance");
        r.resolve("1", "balance");
        verify(mapper, times(1)).findActive(1L, "balance");
    }
}
