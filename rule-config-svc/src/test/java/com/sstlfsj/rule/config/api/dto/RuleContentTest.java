package com.sstlfsj.rule.config.api.dto;

import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot.DecisionBinding;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot.PreGateConfig;
import com.sstlfsj.rule.kernel.api.model.ScriptSource;
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
        List<DecisionBinding> bindings = List.of(new DecisionBinding("D_PASS", 0));
        List<PreGateConfig> gates = List.of(new PreGateConfig("ROLLOUT", Map.of("percentage", 50)));
        List<String> triggers = List.of("ORDER_CREATED");
        ScriptSource script = new ScriptSource("amount > 100", "cel");

        RuleContent content = new RuleContent(
                "风控规则", "AST_BOOLEAN", ast, bindings, gates, triggers, script, null);

        assertThat(content.name()).isEqualTo("风控规则");
        assertThat(content.kind()).isEqualTo("AST_BOOLEAN");
        assertThat(content.conditionAst()).isSameAs(ast);
        assertThat(content.decisionBindings()).isSameAs(bindings);
        assertThat(content.preGates()).isSameAs(gates);
        assertThat(content.triggerEventTypes()).isSameAs(triggers);
        assertThat(content.script()).isSameAs(script);
    }

    @Test
    void allowsNullContentFields() {
        RuleContent content = new RuleContent(null, null, null, null, null, null, null, null);

        assertThat(content.conditionAst()).isNull();
        assertThat(content.decisionBindings()).isNull();
        assertThat(content.script()).isNull();
    }
}
