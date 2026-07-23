package com.sstlfsj.rule.config.api.dto;

import com.sstlfsj.rule.kernel.api.model.AstBody;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot.DecisionBinding;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot.PreGateConfig;
import com.sstlfsj.rule.kernel.api.model.ast.AndNode;
import com.sstlfsj.rule.kernel.api.model.ast.AstNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** RuleContent 内容载体 record 的字段保真验证。 */
class RuleContentTest {

    @Test
    void preservesAllTypedFields() {
        AstNode ast = new AndNode(List.of(), null, null);
        AstBody body = new AstBody(ast);
        List<DecisionBinding> bindings = List.of(new DecisionBinding("D_PASS", 0));
        List<PreGateConfig> gates = List.of(new PreGateConfig("ROLLOUT", Map.of("percentage", 50)));
        List<String> triggers = List.of("ORDER_CREATED");

        RuleContent content = new RuleContent(
                "风控规则", "AST_BOOLEAN", body, bindings, gates, triggers);

        assertThat(content.name()).isEqualTo("风控规则");
        assertThat(content.kind()).isEqualTo("AST_BOOLEAN");
        assertThat(content.body()).isSameAs(body);
        assertThat(((AstBody) content.body()).conditionAst()).isSameAs(ast);
        assertThat(content.decisionBindings()).isSameAs(bindings);
        assertThat(content.preGates()).isSameAs(gates);
        assertThat(content.triggerEventTypes()).isSameAs(triggers);
    }

    @Test
    void allowsNullContentFields() {
        RuleContent content = new RuleContent(null, null, null, null, null, null);

        assertThat(content.body()).isNull();
        assertThat(content.decisionBindings()).isNull();
    }
}
