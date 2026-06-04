package com.sstlfsj.rule.kernel.internal.codec;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RuleVersionRowTest {

    @Test
    void record_fieldsAccessible() {
        RuleVersionRow row = new RuleVersionRow(1L, "scene", 2L, "{}", "[]", "[]", "[]", "AST_BOOLEAN");
        assertThat(row.ruleVersionId()).isEqualTo(1L);
        assertThat(row.sceneCode()).isEqualTo("scene");
        assertThat(row.tenantId()).isEqualTo(2L);
        assertThat(row.kind()).isEqualTo("AST_BOOLEAN");
    }

    @Test
    void record_nullKind_isAllowed() {
        RuleVersionRow row = new RuleVersionRow(1L, "scene", 2L, "{}", "[]", "[]", "[]", null);
        assertThat(row.kind()).isNull();
    }
}
