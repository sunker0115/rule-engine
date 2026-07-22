package com.sstlfsj.rule.config.api.dto;

import com.sstlfsj.rule.kernel.api.model.AstBody;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot.DecisionBinding;
import com.sstlfsj.rule.kernel.api.model.ast.AndNode;
import com.sstlfsj.rule.kernel.api.model.ast.AstNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RuleDetailVOTest {

    @Test
    void exposesTypedFields() {
        AstNode ast = new AndNode(List.of(), null, null);
        List<DecisionBinding> bindings = List.of(new DecisionBinding("BLOCK", 100));
        RuleDetailVO vo = new RuleDetailVO(9100L, 10L, "rule.a", "规则A", "PUBLISHED", "AST_BOOLEAN",
                "risk.transfer", new AstBody(ast), bindings, List.of(), List.of(), 42L, List.of());
        assertThat(vo.tenantId()).isEqualTo(9100L);
        assertThat(vo.ruleDefinitionId()).isEqualTo(10L);
        assertThat(vo.code()).isEqualTo("rule.a");
        assertThat(vo.sceneCode()).isEqualTo("risk.transfer");
        assertThat(vo.currentVersionId()).isEqualTo(42L);
        assertThat(vo.body()).isInstanceOf(AstBody.class);
        assertThat(((AstBody) vo.body()).conditionAst()).isInstanceOf(AndNode.class);
        assertThat(vo.decisionBindings()).hasSize(1);
        assertThat(vo.decisionBindings().get(0).decisionCode()).isEqualTo("BLOCK");
    }
}
