package com.sstlfsj.rule.kernel.model;

import com.sstlfsj.rule.kernel.api.model.AstBody;
import com.sstlfsj.rule.kernel.api.model.RuleKind;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.ScriptBody;
import com.sstlfsj.rule.kernel.api.model.ScriptSource;
import com.sstlfsj.rule.kernel.api.model.ast.AstNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RuleVersionSnapshotScriptTest {

    @Test
    void astKind_bodyIsAstBody() {
        RuleVersionSnapshot s = new RuleVersionSnapshot(1L, "scene", "t1", (AstNode) null,
                List.of(), List.of(), List.of(), RuleKind.AST_BOOLEAN.tag());
        assertThat(s.body()).isInstanceOf(AstBody.class);
        assertThat(((AstBody) s.body()).conditionAst()).isNull();
    }

    @Test
    void builderCarriesScriptBody() {
        ScriptSource src = new ScriptSource("payload.amount > 10 ? 'REVIEW' : 'PASS'", "CEL");
        RuleVersionSnapshot s = RuleVersionSnapshot.builder()
                .sceneCode("scene").kind(RuleKind.EXPRESSION_SCRIPT.tag())
                .script(src)
                .build();
        assertThat(s.body()).isInstanceOf(ScriptBody.class);
        assertThat(((ScriptBody) s.body()).script()).isEqualTo(src);
    }
}
