package com.sstlfsj.rule.config.internal.publish;

import com.sstlfsj.rule.config.internal.domain.MetricDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MetricSafetyValidatorTest {

    private final MetricSafetyValidator validator = new MetricSafetyValidator();

    private MetricDefinition sqlMetric(String code, Map<String, Object> params) {
        MetricDefinition m = new MetricDefinition();
        m.setMetricCode(code);
        m.setSourceType("SQL_AGGREGATE");
        m.setParams(params);
        return m;
    }

    private MetricDefinition httpMetric(String code, Map<String, Object> params) {
        MetricDefinition m = new MetricDefinition();
        m.setMetricCode(code);
        m.setSourceType("EXTERNAL_HTTP");
        m.setParams(params);
        return m;
    }

    @Test
    void rejectsDbTimeFunction() {
        MetricDefinition m = sqlMetric("balance",
                Map.of("datasource", "ro", "sql", "SELECT 1 WHERE t >= NOW() - INTERVAL 7 DAY"));
        assertThatThrownBy(() -> validator.validate(List.of(m), Set.of("ro"), Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NOW");
    }

    @Test
    void rejectsDollarBraceInterpolation() {
        MetricDefinition m = sqlMetric("balance",
                Map.of("datasource", "ro", "sql", "SELECT ${col} FROM t"));
        assertThatThrownBy(() -> validator.validate(List.of(m), Set.of("ro"), Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnregisteredDatasource() {
        MetricDefinition m = sqlMetric("balance",
                Map.of("datasource", "unknown", "sql", "SELECT 1"));
        assertThatThrownBy(() -> validator.validate(List.of(m), Set.of("ro"), Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown");
    }

    @Test
    void passesCleanSql() {
        MetricDefinition m = sqlMetric("balance",
                Map.of("datasource", "ro", "sql", "SELECT COUNT(*) FROM t WHERE created_at >= :now - INTERVAL 7 DAY"));
        assertThatCode(() -> validator.validate(List.of(m), Set.of("ro"), Set.of()))
                .doesNotThrowAnyException();
    }

    @Test
    void nullCatalogNames_skipsResourceCheck_butStillScansSql() {
        MetricDefinition clean = sqlMetric("balance", Map.of("datasource", "any", "sql", "SELECT 1"));
        // datasource 名集合为 null → 跳过资源名校验（容错），SQL 仍扫描
        assertThatCode(() -> validator.validate(List.of(clean), null, null))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsCurrentDate() {
        MetricDefinition m = sqlMetric("balance",
                Map.of("datasource", "ro", "sql", "SELECT 1 WHERE d >= CURRENT_DATE - INTERVAL 7 DAY"));
        assertThatThrownBy(() -> validator.validate(List.of(m), Set.of("ro"), Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CURRENT_DATE");
    }

    @Test
    void passesRegisteredConnector() {
        MetricDefinition m = httpMetric("risk_score",
                Map.of("connector", "risk-svc", "vars", Map.of("uid", "u1")));
        assertThatCode(() -> validator.validate(List.of(m), Set.of(), Set.of("risk-svc")))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsUnregisteredConnector() {
        MetricDefinition m = httpMetric("risk_score",
                Map.of("connector", "ghost-svc", "vars", Map.of()));
        assertThatThrownBy(() -> validator.validate(List.of(m), Set.of(), Set.of("risk-svc")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ghost-svc");
    }

    @Test
    void rejectsMissingConnector() {
        MetricDefinition m = httpMetric("risk_score", Map.of("vars", Map.of()));
        assertThatThrownBy(() -> validator.validate(List.of(m), Set.of(), Set.of("risk-svc")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("connector");
    }

    @Test
    void nullConnectorNames_skipsHttpResourceCheck() {
        MetricDefinition m = httpMetric("risk_score", Map.of("connector", "anything"));
        assertThatCode(() -> validator.validate(List.of(m), null, null))
                .doesNotThrowAnyException();
    }
}
