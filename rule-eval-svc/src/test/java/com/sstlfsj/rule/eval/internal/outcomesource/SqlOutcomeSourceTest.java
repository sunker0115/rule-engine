package com.sstlfsj.rule.eval.internal.outcomesource;

import com.sstlfsj.rule.eval.api.service.OutcomePullResult;
import com.sstlfsj.rule.eval.api.service.OutcomeService;
import com.sstlfsj.rule.eval.api.service.SqlOutcomeSourceConfig;
import com.sstlfsj.rule.eval.internal.metric.sql.MetricDataSourceRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SqlOutcomeSourceTest {

    private final MetricDataSourceRegistry registry = mock(MetricDataSourceRegistry.class);
    private final SqlOutcomeSource source = new SqlOutcomeSource(registry);

    private static final String SQL = "select event_id, outcome_label, outcome_value, labeled_at from t";

    @Test
    void returnsRecordsAndNewWatermarkAsMaxLabeledAt() {
        Instant t1 = Instant.parse("2026-01-01T00:00:00Z");
        Instant t2 = Instant.parse("2026-01-02T00:00:00Z");
        List<OutcomeService.OutcomeRecord> rows = List.of(
                new OutcomeService.OutcomeRecord("e1", "fraud", BigDecimal.ONE, t1, "ingest:ds", null),
                new OutcomeService.OutcomeRecord("e2", "ok", null, t2, "ingest:ds", null));

        NamedParameterJdbcTemplate tpl = mock(NamedParameterJdbcTemplate.class);
        when(registry.template("ds")).thenReturn(tpl);
        when(tpl.query(eq(SQL), any(SqlParameterSource.class), any(RowMapper.class))).thenReturn(rows);

        OutcomePullResult result = source.pull(new SqlOutcomeSourceConfig("ds", SQL), null, 1L);

        assertThat(result.records()).isEqualTo(rows);
        assertThat(result.newWatermark()).isEqualTo(t2);
    }

    @Test
    void emptyBatchKeepsInputWatermark() {
        Instant input = Instant.parse("2026-01-05T00:00:00Z");
        NamedParameterJdbcTemplate tpl = mock(NamedParameterJdbcTemplate.class);
        when(registry.template("ds")).thenReturn(tpl);
        when(tpl.query(eq(SQL), any(SqlParameterSource.class), any(RowMapper.class))).thenReturn(List.of());

        OutcomePullResult result = source.pull(new SqlOutcomeSourceConfig("ds", SQL), input, 1L);

        assertThat(result.records()).isEmpty();
        assertThat(result.newWatermark()).isEqualTo(input);
    }

    @Test
    void unregisteredDatasourceThrows() {
        when(registry.template("missing")).thenReturn(null);

        assertThatThrownBy(() -> source.pull(new SqlOutcomeSourceConfig("missing", SQL), null, 1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("回灌数据源未注册: missing");
    }
}
