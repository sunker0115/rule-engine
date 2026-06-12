package com.sstlfsj.rule.kernel.model;

import com.sstlfsj.rule.kernel.api.model.RuleKind;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.ScriptSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RuleVersionSnapshotScriptTest {

    @Test
    void scriptDefaultsNullForAstKinds() {
        RuleVersionSnapshot s = new RuleVersionSnapshot(1L, "scene", "t1", null,
                List.of(), List.of(), List.of(), RuleKind.AST_BOOLEAN.tag());
        assertThat(s.script()).isNull();
    }

    @Test
    void builderCarriesScript() {
        ScriptSource src = new ScriptSource("payload.amount > 10 ? 'REVIEW' : 'PASS'", "CEL");
        RuleVersionSnapshot s = RuleVersionSnapshot.builder()
                .sceneCode("scene").kind(RuleKind.EXPRESSION_SCRIPT.tag())
                .script(src)
                .build();
        assertThat(s.script()).isEqualTo(src);
        assertThat(s.conditionAst()).isNull();
    }
}
