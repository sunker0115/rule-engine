package com.sstlfsj.rule.config.internal.publish;

import com.sstlfsj.rule.config.internal.domain.MetricDefinition;
import com.sstlfsj.rule.kernel.api.model.SourceType;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 发布期 metric 安全校验：
 * <ul>
 *   <li>SQL_AGGREGATE：拒绝 DB 时间函数（NOW/SYSDATE/CURRENT_TIMESTAMP）与 ${} 拼接；datasource 必须已注册。</li>
 *   <li>EXTERNAL_HTTP：params.connector 引用的连接器必须已注册（ACTIVE）。</li>
 * </ul>
 * 资源名集合为 null 时跳过资源名校验（容错，如纯 config 部署无 eval registry）；SQL 文本扫描始终执行。
 */
class MetricSafetyValidator {

    // 大小写不敏感匹配 DB 时间函数调用（NOW()/SYSDATE()/CURRENT_TIMESTAMP/CURRENT_DATE）。
    private static final Pattern DB_TIME = Pattern.compile(
            "(?i)\\b(NOW|SYSDATE)\\s*\\(|(?i)\\b(CURRENT_TIMESTAMP|CURRENT_DATE)\\b");
    private static final Pattern DOLLAR_BRACE = Pattern.compile("\\$\\{");

    /**
     * 校验一批 metric 定义。
     *
     * @param metrics         规则引用的 metric 定义
     * @param datasourceNames 已注册数据源名（null = 跳过资源名校验）
     * @param connectorNames  已注册（ACTIVE）连接器编码（null = 跳过资源名校验）
     * @throws IllegalArgumentException 校验失败
     */
    void validate(List<MetricDefinition> metrics, Set<String> datasourceNames, Set<String> connectorNames) {
        for (MetricDefinition m : metrics) {
            Map<String, Object> params = m.getParams() != null ? m.getParams() : Map.of();
            switch (m.getSourceType() == null ? "" : m.getSourceType()) {
                case SourceType.SQL_AGGREGATE -> validateSql(m, params, datasourceNames);
                case SourceType.EXTERNAL_HTTP -> validateHttp(m, params, connectorNames);
                default -> { /* ATTRIBUTE/STREAM：无需 SQL/资源校验 */ }
            }
        }
    }

    private void validateSql(MetricDefinition m, Map<String, Object> params, Set<String> datasourceNames) {
        Object sql = params.get("sql");
        if (sql != null) {
            String text = sql.toString();
            if (DB_TIME.matcher(text).find()) {
                throw new IllegalArgumentException(
                        "metric=" + m.getMetricCode() + " 的 SQL 含 DB 时间函数（NOW/SYSDATE/CURRENT_TIMESTAMP/CURRENT_DATE），请用 :now");
            }
            if (DOLLAR_BRACE.matcher(text).find()) {
                throw new IllegalArgumentException(
                        "metric=" + m.getMetricCode() + " 的 SQL 含 ${} 拼接，禁止");
            }
        }
        if (datasourceNames != null) {
            Object ds = params.get("datasource");
            if (ds == null || !datasourceNames.contains(ds.toString())) {
                throw new IllegalArgumentException(
                        "metric=" + m.getMetricCode() + " 引用未注册的 datasource: " + ds);
            }
        }
    }

    private void validateHttp(MetricDefinition m, Map<String, Object> params, Set<String> connectorNames) {
        if (connectorNames != null) {
            Object connector = params.get("connector");
            if (connector == null || !connectorNames.contains(connector.toString())) {
                throw new IllegalArgumentException(
                        "metric=" + m.getMetricCode() + " 引用未注册的 connector: " + connector);
            }
        }
    }
}
