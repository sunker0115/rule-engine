package com.sstlfsj.rule.eval.internal.metric.sql;

import com.sstlfsj.rule.eval.internal.metric.DataTypeCoercion;
import com.sstlfsj.rule.kernel.api.annotation.MetricSourceType;
import com.sstlfsj.rule.kernel.api.model.EvalErrorCode;
import com.sstlfsj.rule.kernel.api.model.MetricQuery;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import com.sstlfsj.rule.kernel.api.spi.metric.MetricSourceHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL_AGGREGATE 取数 handler：命名参数绑定（禁拼接）、:now 绑引擎时钟、取首行首列按 dataType 强转。
 * datasource/sql 来自 metric.params；handler 只跑只读命名数据源。
 */
@Component
@MetricSourceType("SQL_AGGREGATE")
public class SqlAggregateMetricSourceHandler implements MetricSourceHandler {

    /** 占位符：:ns.field 或 :name（点号命名空间用于 payload./params.）。 */
    private static final Pattern PLACEHOLDER = Pattern.compile(":([a-zA-Z_][\\w.]*)");

    private final MetricDataSourceRegistry registry;

    public SqlAggregateMetricSourceHandler(MetricDataSourceRegistry registry) {
        this.registry = registry;
    }

    @Override
    public MetricValue fetch(MetricQuery query) {
        Object dsName = query.params().get("datasource");
        Object sqlText = query.params().get("sql");
        Object dataType = query.params().get("dataType"); // 由 resolver 注入到 params
        if (dsName == null || sqlText == null) return MetricValue.error(EvalErrorCode.METRIC_FETCH_FAIL);
        NamedParameterJdbcTemplate tpl = registry.template(dsName.toString());
        if (tpl == null) return MetricValue.error(EvalErrorCode.METRIC_FETCH_FAIL);
        try {
            Bound bound = bind(sqlText.toString(), query.subjectId(), query.tenantId(),
                    query.now(), query.eventPayload(), castParams(query.params().get("params")));
            List<Object> firstCol = tpl.query(bound.sql(), bound.params(),
                    (rs, rowNum) -> rs.getObject(1));
            Object raw = firstCol.isEmpty() ? null : firstCol.get(0);
            String dt = dataType != null ? dataType.toString() : null;
            return new MetricValue(DataTypeCoercion.coerce(raw, dt), dt, "FETCHED");
        } catch (Exception e) {
            return MetricValue.error(EvalErrorCode.METRIC_FETCH_FAIL);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castParams(Object raw) {
        return raw instanceof Map ? (Map<String, Object>) raw : Map.of();
    }

    /** 绑定结果：规范化后的 SQL（点号占位符→下划线）+ 参数源。 */
    public record Bound(String sql, MapSqlParameterSource params) {}

    /**
     * 把 SQL 中的命名占位符规范化为合法参数名并绑定值。
     * :subjectId/:tenantId/:now 直绑；:payload.X→:payload_X 绑 eventPayload.X；:params.X→:params_X 绑 params.X。
     *
     * @param sql       原始 SQL（仅命名参数，禁拼接）
     * @param subjectId 主体 id
     * @param tenantId  租户 id
     * @param now       引擎统一时钟
     * @param payload   事件 payload
     * @param params    metric.params.params 子 map
     * @return 绑定结果
     */
    public static Bound bind(String sql, String subjectId, String tenantId, Instant now,
                             Map<String, Object> payload, Map<String, Object> params) {
        MapSqlParameterSource src = new MapSqlParameterSource();
        src.addValue("subjectId", subjectId);
        src.addValue("tenantId", tenantId);
        src.addValue("now", now == null ? null : Timestamp.from(now));
        StringBuilder out = new StringBuilder();
        Matcher m = PLACEHOLDER.matcher(sql);
        while (m.find()) {
            String token = m.group(1);
            String replacement;
            if (token.contains(".")) {
                String[] parts = token.split("\\.", 2);
                String safe = parts[0] + "_" + parts[1];
                Object value = switch (parts[0]) {
                    case "payload" -> payload.get(parts[1]);
                    case "params" -> params.get(parts[1]);
                    default -> null;
                };
                src.addValue(safe, value);
                replacement = ":" + safe;
            } else {
                replacement = ":" + token; // subjectId/tenantId/now，已绑
            }
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return new Bound(out.toString(), src);
    }
}
