package com.sstlfsj.rule.eval.internal.metric.sql;

import com.sstlfsj.rule.eval.internal.metric.DataTypeCoercion;
import com.sstlfsj.rule.eval.internal.metric.fetch.MetricFetchErrorMapper;
import com.sstlfsj.rule.kernel.api.annotation.MetricSourceType;
import com.sstlfsj.rule.kernel.api.model.MetricFetchError;
import com.sstlfsj.rule.kernel.api.model.MetricQuery;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import com.sstlfsj.rule.kernel.api.model.SourceType;
import com.sstlfsj.rule.kernel.api.model.ValueSource;
import com.sstlfsj.rule.kernel.api.spi.metric.FetchTraceCollector;
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
@MetricSourceType(SourceType.SQL_AGGREGATE)
public class SqlAggregateMetricSourceHandler implements MetricSourceHandler {

    /** 占位符：:ns.field 或 :name（点号命名空间用于 payload./params.）。 */
    private static final Pattern PLACEHOLDER = Pattern.compile(":([a-zA-Z_][\\w.]*)");

    private final MetricDataSourceRegistry registry;
    // 共用脊·调用无关，无状态，直接实例化（与 DeclarativeHttpConnectorHandler 一致，不入 Spring 容器）
    private final MetricFetchErrorMapper errorMapper = new MetricFetchErrorMapper();

    public SqlAggregateMetricSourceHandler(MetricDataSourceRegistry registry) {
        this.registry = registry;
    }

    @Override
    public MetricValue fetch(MetricQuery query) {
        return fetch(query, FetchTraceCollector.noop());
    }

    @Override
    public MetricValue fetch(MetricQuery query, FetchTraceCollector collector) {
        Object dsName = query.params().get("datasource");
        Object sqlText = query.params().get("sql");
        Object dataType = query.params().get("dataType"); // 由 resolver 注入到 params
        // 配置缺失（datasource/sql 未填或数据源未注册）归为上游错误细码
        if (dsName == null || sqlText == null) return error(collector, MetricFetchError.UPSTREAM_ERROR);
        // template 已在注册表按全局超时设好 statement 超时（getQueryTimeout 秒级），此处不再重设
        NamedParameterJdbcTemplate tpl = registry.template(dsName.toString());
        if (tpl == null) return error(collector, MetricFetchError.UPSTREAM_ERROR);
        Bound bound = bind(sqlText.toString(), query.subjectId(), query.tenantId(),
                query.now(), query.eventPayload(), castParams(query.params().get("params")),
                query.subjectAttributes());
        collector.boundSql(bound.sql());
        Object raw;
        try {
            List<Object> firstCol = tpl.query(bound.sql(), bound.params(),
                    (rs, rowNum) -> rs.getObject(1));
            raw = firstCol.isEmpty() ? null : firstCol.getFirst();
        } catch (Exception e) {
            // DB 异常/超时经 mapper 归一为细码（超时→TIMEOUT，其余→UPSTREAM_ERROR）
            return error(collector, errorMapper.fromException(e));
        }
        collector.rawResponse(raw == null ? null : raw.toString());
        String dt = dataType != null ? dataType.toString() : null;
        Object coerced = DataTypeCoercion.coerce(raw, dt);
        // 取到非空原始值但强转后为 null = 类型不匹配（coerce 内吞异常返 null，故据此识别）
        if (raw != null && coerced == null) return error(collector, MetricFetchError.TYPE_MISMATCH);
        collector.mappedValue(coerced);
        return new MetricValue(coerced, dt, ValueSource.FETCHED.tag());
    }

    /** 归一错误：记 errorCode 到 collector 并返回降级 MetricValue（单一出口，避免各分支重复记录）。 */
    private static MetricValue error(FetchTraceCollector collector, MetricFetchError err) {
        collector.errorCode(err.tag());
        return MetricValue.error(err.tag());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castParams(Object raw) {
        return raw instanceof Map ? (Map<String, Object>) raw : Map.of();
    }

    /** 绑定结果：规范化后的 SQL（点号占位符→下划线）+ 参数源。 */
    public record Bound(String sql, MapSqlParameterSource params) {}

    /**
     * 把 SQL 中的命名占位符规范化为合法参数名并绑定值。
     * :subjectId/:tenantId/:now 直绑；:payload.X→:payload_X 绑 eventPayload.X；:params.X→:params_X 绑 params.X；
     * :subject.X→:subject_X 绑 subjectAttributes.X（主体属性，来自 Subject.attributes）。
     *
     * @param sql               原始 SQL（仅命名参数，禁拼接）
     * @param subjectId         主体 id
     * @param tenantId          租户 id
     * @param now               引擎统一时钟
     * @param payload           事件 payload
     * @param params            metric.params.params 子 map
     * @param subjectAttributes 主体属性（来自 Subject.attributes）
     * @return 绑定结果
     */
    public static Bound bind(String sql, String subjectId, String tenantId, Instant now,
                             Map<String, Object> payload, Map<String, Object> params,
                             Map<String, Object> subjectAttributes) {
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
                    case "subject" -> subjectAttributes.get(parts[1]);
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
