package com.sstlfsj.rule.eval.internal.metric.sql;

import com.sstlfsj.rule.kernel.api.model.MetricFetchError;
import com.sstlfsj.rule.kernel.api.model.MetricQuery;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SqlAggregateMetricSourceHandlerTest {

    @Test
    void bind_replacesDottedPlaceholders_andBindsValues() {
        Instant now = Instant.parse("2026-06-06T00:00:00Z");
        SqlAggregateMetricSourceHandler.Bound b = SqlAggregateMetricSourceHandler.bind(
                "SELECT COUNT(*) FROM t WHERE uid = :subjectId AND amt > :payload.amt "
                        + "AND created_at >= :now - INTERVAL :params.win DAY",
                "u1", "1", now, Map.of("amt", 500), Map.of("win", 7), Map.of());

        assertThat(b.sql()).contains(":subjectId").contains(":payload_amt")
                .contains(":params_win").contains(":now").doesNotContain(":payload.amt");
        MapSqlParameterSource src = b.params();
        assertThat(src.getValue("subjectId")).isEqualTo("u1");
        assertThat(src.getValue("payload_amt")).isEqualTo(500);
        assertThat(src.getValue("params_win")).isEqualTo(7);
        assertThat(src.getValue("now")).isNotNull();
    }

    @Test
    void bind_replacesSubjectPlaceholder_andBindsSubjectAttribute() {
        SqlAggregateMetricSourceHandler.Bound b = SqlAggregateMetricSourceHandler.bind(
                "SELECT score FROM s WHERE level = :subject.level",
                "u1", "1", Instant.parse("2026-06-06T00:00:00Z"),
                Map.of(), Map.of(), Map.of("level", "VIP"));

        assertThat(b.sql()).contains(":subject_level").doesNotContain(":subject.level");
        assertThat(b.params().getValue("subject_level")).isEqualTo("VIP");
    }

    @Test
    @SuppressWarnings("unchecked")
    void fetch_returnsCoercedValueOnSuccess() {
        NamedParameterJdbcTemplate tpl = mock(NamedParameterJdbcTemplate.class);
        when(tpl.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of((Object) 42L));
        SqlAggregateMetricSourceHandler handler = handlerWith(tpl);

        MetricValue v = handler.fetch(query(Map.of(
                "datasource", "risk", "sql", "SELECT 1", "dataType", "LONG")));

        assertThat(v.isError()).isFalse();
        assertThat(v.value()).isEqualTo(42L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void fetch_dbExceptionMapsToUpstreamError() {
        NamedParameterJdbcTemplate tpl = mock(NamedParameterJdbcTemplate.class);
        when(tpl.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenThrow(new DataAccessResourceFailureException("db down"));
        SqlAggregateMetricSourceHandler handler = handlerWith(tpl);

        MetricValue v = handler.fetch(query(Map.of(
                "datasource", "risk", "sql", "SELECT 1", "dataType", "LONG")));

        assertThat(v.isError()).isTrue();
        assertThat(v.errorCode()).isEqualTo(MetricFetchError.UPSTREAM_ERROR.tag());
    }

    @Test
    @SuppressWarnings("unchecked")
    void fetch_coercionFailureMapsToTypeMismatch() {
        NamedParameterJdbcTemplate tpl = mock(NamedParameterJdbcTemplate.class);
        // 取到非数字字符串但 dataType=LONG → 强转失败
        when(tpl.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of((Object) "not-a-number"));
        SqlAggregateMetricSourceHandler handler = handlerWith(tpl);

        MetricValue v = handler.fetch(query(Map.of(
                "datasource", "risk", "sql", "SELECT 1", "dataType", "LONG")));

        assertThat(v.isError()).isTrue();
        assertThat(v.errorCode()).isEqualTo(MetricFetchError.TYPE_MISMATCH.tag());
    }

    @Test
    void fetch_missingDatasourceMapsToUpstreamError() {
        SqlAggregateMetricSourceHandler handler = handlerWith(mock(NamedParameterJdbcTemplate.class));
        MetricValue v = handler.fetch(query(Map.of("sql", "SELECT 1")));
        assertThat(v.isError()).isTrue();
        assertThat(v.errorCode()).isEqualTo(MetricFetchError.UPSTREAM_ERROR.tag());
    }

    private SqlAggregateMetricSourceHandler handlerWith(NamedParameterJdbcTemplate tpl) {
        MetricDataSourceRegistry registry = mock(MetricDataSourceRegistry.class);
        when(registry.template("risk")).thenReturn(tpl);
        return new SqlAggregateMetricSourceHandler(registry);
    }

    private MetricQuery query(Map<String, Object> params) {
        return new MetricQuery("m1", "1", "u1", params, Map.of(),
                Instant.parse("2026-06-06T00:00:00Z"), Map.of());
    }
}
