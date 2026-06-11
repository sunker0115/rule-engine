package com.sstlfsj.rule.kernel.internal.codec;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RuleVersionRowTest {

    @Test
    void record_fieldsAccessible() {
        RuleVersionRow row = new RuleVersionRow(1L, "scene", 2L, "{}", "[]", "[]", "[]",
                "AST_BOOLEAN", "HIGHEST_PRIORITY");
        assertThat(row.ruleVersionId()).isEqualTo(1L);
        assertThat(row.sceneCode()).isEqualTo("scene");
        assertThat(row.tenantId()).isEqualTo(2L);
        assertThat(row.kind()).isEqualTo("AST_BOOLEAN");
        assertThat(row.decisionStrategy()).isEqualTo("HIGHEST_PRIORITY");
    }

    @Test
    void record_nullKind_isAllowed() {
        RuleVersionRow row = new RuleVersionRow(1L, "scene", 2L, "{}", "[]", "[]", "[]",
                null, null);
        assertThat(row.kind()).isNull();
        assertThat(row.decisionStrategy()).isNull();
    }

    @Test
    void record_metricDependenciesJson_retained() {
        RuleVersionRow row = new RuleVersionRow(1L, "scene", 2L, "{}", "[]", "[]", "[]",
                "AST_BOOLEAN", "HIGHEST_PRIORITY", "[\"balance\"]", "[\"amount\"]", "code", 1L);
        assertThat(row.metricDependenciesJson()).isEqualTo("[\"balance\"]");
    }

    @Test
    void record_payloadDependenciesJson_retained() {
        RuleVersionRow row = new RuleVersionRow(1L, "scene", 2L, "{}", "[]", "[]", "[]",
                "AST_BOOLEAN", "HIGHEST_PRIORITY", "[\"balance\"]", "[\"amount\"]", "code", 1L);
        assertThat(row.payloadDependenciesJson()).isEqualTo("[\"amount\"]");
    }

    @Test
    void record_codeAndVersion_retained() {
        RuleVersionRow row = new RuleVersionRow(1L, "scene", 2L, "{}", "[]", "[]", "[]",
                "AST_BOOLEAN", "HIGHEST_PRIORITY", "[]", "[]", "large-trade", 3L);
        assertThat(row.code()).isEqualTo("large-trade");
        assertThat(row.version()).isEqualTo(3L);
    }

    @Test
    void record_legacyConstructor_nullMetricAndPayloadDependenciesJson() {
        RuleVersionRow row = new RuleVersionRow(1L, "scene", 2L, "{}", "[]", "[]", "[]",
                "AST_BOOLEAN", "HIGHEST_PRIORITY");
        assertThat(row.metricDependenciesJson()).isNull();
        assertThat(row.payloadDependenciesJson()).isNull();
    }

    @Test
    void record_legacyConstructor_nullCodeZeroVersion() {
        RuleVersionRow row = new RuleVersionRow(1L, "scene", 2L, "{}", "[]", "[]", "[]",
                "AST_BOOLEAN", "HIGHEST_PRIORITY");
        assertThat(row.code()).isNull();
        assertThat(row.version()).isEqualTo(0L);
    }
}
