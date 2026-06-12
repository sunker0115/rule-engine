package com.sstlfsj.rule.kernel.internal.codec;

import com.sstlfsj.rule.kernel.api.model.RuleKind;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SnapshotAssemblerScriptTest {

    private final SnapshotAssembler assembler = new SnapshotAssembler();

    @Test
    void assemblesScriptRowWithNullConditionAst() {
        // 脚本行:condition_ast 为 null,script_source 非空,kind=EXPRESSION_SCRIPT
        RuleVersionRow row = new RuleVersionRow(
                1L, "scene", 100L,
                null,                       // conditionAstJson
                "[]", "[]", "[]",
                RuleKind.EXPRESSION_SCRIPT.tag(), "HIGHEST_PRIORITY",
                "[]", "[]", "R1", 1L,
                "{\"source\":\"payload.amount > 10000 ? 'REVIEW' : 'PASS'\",\"lang\":\"CEL\"}");

        RuleVersionSnapshot snap = assembler.assembleAll(java.util.List.of(row)).get(0);

        assertThat(snap.conditionAst()).isNull();
        assertThat(snap.script()).isNotNull();
        assertThat(snap.script().source()).isEqualTo("payload.amount > 10000 ? 'REVIEW' : 'PASS'");
        assertThat(snap.script().lang()).isEqualTo("CEL");
        assertThat(snap.kind()).isEqualTo(RuleKind.EXPRESSION_SCRIPT.tag());
    }

    @Test
    void astRowHasNullScript() {
        RuleVersionRow row = new RuleVersionRow(
                2L, "scene", 100L,
                "{\"type\":\"AndNode\",\"children\":[]}",
                "[]", "[]", "[]", RuleKind.AST_BOOLEAN.tag(), "HIGHEST_PRIORITY",
                "[]", "[]", "R2", 1L, null);

        RuleVersionSnapshot snap = assembler.assembleAll(java.util.List.of(row)).get(0);

        assertThat(snap.script()).isNull();
        assertThat(snap.conditionAst()).isNotNull();
    }
}
