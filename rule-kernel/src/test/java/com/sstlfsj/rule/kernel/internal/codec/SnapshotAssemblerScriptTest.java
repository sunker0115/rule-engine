package com.sstlfsj.rule.kernel.internal.codec;

import com.sstlfsj.rule.kernel.api.model.AstBody;
import com.sstlfsj.rule.kernel.api.model.RuleKind;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.ScriptBody;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SnapshotAssemblerScriptTest {

    private final SnapshotAssembler assembler = new SnapshotAssembler();

    @Test
    void assemblesScriptRowIntoScriptBody() {
        // 脚本行:body 为 ScriptBody,kind=EXPRESSION_SCRIPT
        String bodyJson = "{\"type\":\"ScriptBody\",\"script\":"
                + "{\"source\":\"payload.amount > 10000 ? 'REVIEW' : 'PASS'\",\"lang\":\"CEL\"}}";
        RuleVersionRow row = new RuleVersionRow(
                1L, "scene", 100L, bodyJson, "[]", "[]", "[]",
                RuleKind.EXPRESSION_SCRIPT.tag(), "HIGHEST_PRIORITY",
                "[]", "[]", "R1", 1L, null);

        RuleVersionSnapshot snap = assembler.assembleAll(java.util.List.of(row)).get(0);

        assertThat(snap.body()).isInstanceOf(ScriptBody.class);
        ScriptBody sb = (ScriptBody) snap.body();
        assertThat(sb.script().source()).isEqualTo("payload.amount > 10000 ? 'REVIEW' : 'PASS'");
        assertThat(sb.script().lang()).isEqualTo("CEL");
        assertThat(snap.kind()).isEqualTo(RuleKind.EXPRESSION_SCRIPT.tag());
    }

    @Test
    void assemblesAstRowIntoAstBody() {
        String bodyJson = "{\"type\":\"AstBody\",\"conditionAst\":{\"type\":\"AndNode\",\"children\":[]}}";
        RuleVersionRow row = new RuleVersionRow(
                2L, "scene", 100L, bodyJson, "[]", "[]", "[]",
                RuleKind.AST_BOOLEAN.tag(), "HIGHEST_PRIORITY",
                "[]", "[]", "R2", 1L, null);

        RuleVersionSnapshot snap = assembler.assembleAll(java.util.List.of(row)).get(0);

        assertThat(snap.body()).isInstanceOf(AstBody.class);
        assertThat(((AstBody) snap.body()).conditionAst()).isNotNull();
    }
}
