package com.sstlfsj.rule.kernel.internal.codec;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RuleVersionRowTest {

    private static final String AST_BODY = "{\"type\":\"AstBody\",\"conditionAst\":{}}";

    private RuleVersionRow row(String kind, String strategy, String metricDeps, String payloadDeps,
                               String code, long version, String defaultParams) {
        return new RuleVersionRow(1L, "scene", 2L, AST_BODY, "[]", "[]", "[]",
                kind, strategy, metricDeps, payloadDeps, code, version, defaultParams);
    }

    @Test
    void record_fieldsAccessible() {
        RuleVersionRow r = row("AST_BOOLEAN", "HIGHEST_PRIORITY", null, null, null, 0L, null);
        assertThat(r.ruleVersionId()).isEqualTo(1L);
        assertThat(r.sceneCode()).isEqualTo("scene");
        assertThat(r.tenantId()).isEqualTo(2L);
        assertThat(r.bodyJson()).isEqualTo(AST_BODY);
        assertThat(r.kind()).isEqualTo("AST_BOOLEAN");
        assertThat(r.decisionStrategy()).isEqualTo("HIGHEST_PRIORITY");
    }

    @Test
    void record_nullKind_isAllowed() {
        RuleVersionRow r = row(null, null, null, null, null, 0L, null);
        assertThat(r.kind()).isNull();
        assertThat(r.decisionStrategy()).isNull();
    }

    @Test
    void record_metricAndPayloadDependenciesJson_retained() {
        RuleVersionRow r = row("AST_BOOLEAN", "HIGHEST_PRIORITY", "[\"balance\"]", "[\"amount\"]", "code", 1L, null);
        assertThat(r.metricDependenciesJson()).isEqualTo("[\"balance\"]");
        assertThat(r.payloadDependenciesJson()).isEqualTo("[\"amount\"]");
    }

    @Test
    void record_codeAndVersion_retained() {
        RuleVersionRow r = row("AST_BOOLEAN", "HIGHEST_PRIORITY", "[]", "[]", "large-trade", 3L, null);
        assertThat(r.code()).isEqualTo("large-trade");
        assertThat(r.version()).isEqualTo(3L);
    }

    @Test
    void record_defaultParamsJson_retained() {
        RuleVersionRow r = row("AST_BOOLEAN", "HIGHEST_PRIORITY", "[]", "[]", "code", 1L,
                "{\"timezone\":\"Asia/Shanghai\"}");
        assertThat(r.defaultParamsJson()).isEqualTo("{\"timezone\":\"Asia/Shanghai\"}");
    }
}
