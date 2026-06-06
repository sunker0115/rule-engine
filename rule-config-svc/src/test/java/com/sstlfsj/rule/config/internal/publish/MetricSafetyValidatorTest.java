package com.sstlfsj.rule.config.internal.publish;

import com.sstlfsj.rule.config.internal.domain.MetricDefinition;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MetricSafetyValidatorTest {

    private final ObjectMapper om = JsonMapper.builder().build();
    private final MetricSafetyValidator validator = new MetricSafetyValidator(om);

    private MetricDefinition sqlMetric(String code, String paramsJson) {
        MetricDefinition m = new MetricDefinition();
        m.setMetricCode(code);
        m.setSourceType("SQL_AGGREGATE");
        m.setParams(paramsJson);
        return m;
    }

    @Test
    void rejectsDbTimeFunction() {
        MetricDefinition m = sqlMetric("balance",
                "{\"datasource\":\"ro\",\"sql\":\"SELECT 1 WHERE t >= NOW() - INTERVAL 7 DAY\"}");
        assertThatThrownBy(() -> validator.validate(List.of(m), Set.of("ro"), Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NOW");
    }

    @Test
    void rejectsDollarBraceInterpolation() {
        MetricDefinition m = sqlMetric("balance",
                "{\"datasource\":\"ro\",\"sql\":\"SELECT ${col} FROM t\"}");
        assertThatThrownBy(() -> validator.validate(List.of(m), Set.of("ro"), Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnregisteredDatasource() {
        MetricDefinition m = sqlMetric("balance",
                "{\"datasource\":\"unknown\",\"sql\":\"SELECT 1\"}");
        assertThatThrownBy(() -> validator.validate(List.of(m), Set.of("ro"), Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown");
    }

    @Test
    void passesCleanSql() {
        MetricDefinition m = sqlMetric("balance",
                "{\"datasource\":\"ro\",\"sql\":\"SELECT COUNT(*) FROM t WHERE created_at >= :now - INTERVAL 7 DAY\"}");
        assertThatCode(() -> validator.validate(List.of(m), Set.of("ro"), Set.of()))
                .doesNotThrowAnyException();
    }

    @Test
    void nullCatalogNames_skipsResourceCheck_butStillScansSql() {
        MetricDefinition clean = sqlMetric("balance", "{\"datasource\":\"any\",\"sql\":\"SELECT 1\"}");
        // datasource 名集合为 null → 跳过资源名校验（容错），SQL 仍扫描
        assertThatCode(() -> validator.validate(List.of(clean), null, null))
                .doesNotThrowAnyException();
    }
}
