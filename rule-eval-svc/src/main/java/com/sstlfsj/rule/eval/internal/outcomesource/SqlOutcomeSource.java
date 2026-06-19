package com.sstlfsj.rule.eval.internal.outcomesource;

import com.sstlfsj.rule.eval.api.service.OutcomePullResult;
import com.sstlfsj.rule.eval.api.service.OutcomeService;
import com.sstlfsj.rule.eval.api.service.OutcomeSource;
import com.sstlfsj.rule.eval.api.service.SqlOutcomeSourceConfig;
import com.sstlfsj.rule.eval.internal.metric.sql.MetricDataSourceRegistry;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * SQL-direct 标签源实现：复用 MetricDataSourceRegistry 的只读数据源，按固定列别名拉标签行。
 * sql 中可绑定 :tenantId 与 :watermark（首次全量时 :watermark 为 null）。
 */
@Component
public class SqlOutcomeSource implements OutcomeSource<SqlOutcomeSourceConfig> {

    private final MetricDataSourceRegistry registry;

    public SqlOutcomeSource(MetricDataSourceRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Class<SqlOutcomeSourceConfig> configType() {
        return SqlOutcomeSourceConfig.class;
    }

    @Override
    public OutcomePullResult pull(SqlOutcomeSourceConfig source, Instant watermark, Long tenantId) {
        NamedParameterJdbcTemplate tpl = registry.template(source.datasource());
        if (tpl == null) {
            throw new IllegalStateException("回灌数据源未注册: " + source.datasource());
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("watermark", watermark == null ? null : Timestamp.from(watermark));

        List<OutcomeService.OutcomeRecord> records = tpl.query(source.sql(), params, (rs, rowNum) -> {
            String eventId = rs.getString("event_id");
            String label = rs.getString("outcome_label");
            java.math.BigDecimal value = rs.getBigDecimal("outcome_value");
            Timestamp labeledTs = rs.getTimestamp("labeled_at");
            Instant labeledAt = labeledTs == null ? null : labeledTs.toInstant();
            return new OutcomeService.OutcomeRecord(eventId, label, value, labeledAt,
                    "ingest:" + source.datasource(), null);
        });

        // 新水位取本批 max labeledAt；空批则原样返回入参 watermark，避免水位回退
        Instant newWatermark = watermark;
        for (OutcomeService.OutcomeRecord r : records) {
            if (r.labeledAt() != null && (newWatermark == null || r.labeledAt().isAfter(newWatermark))) {
                newWatermark = r.labeledAt();
            }
        }
        return new OutcomePullResult(records, newWatermark);
    }
}
