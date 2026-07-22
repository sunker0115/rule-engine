package com.sstlfsj.rule.web.admin.dto;

import com.sstlfsj.rule.kernel.api.model.ast.AstNode;
import com.sstlfsj.rule.kernel.api.model.ast.AndNode;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class CreateRuleRequestTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();

    @Test
    void bindsTypedConditionAst() {
        String json = """
            {"tenantId":"1","sceneCode":"s","code":"c","name":"n","kind":"AST_BOOLEAN",
             "conditionAst":{"type":"AndNode","children":[]},
             "decisionBindings":[{"decisionCode":"REVIEW"}],
             "preGates":[{"gateType":"ROLLOUT","params":{"percentage":100}}],
             "triggerEventTypes":["login"]}
            """;
        CreateRuleRequest req = mapper.readValue(json, CreateRuleRequest.class);

        assertThat(req.conditionAst()).isInstanceOf(AndNode.class);
        assertThat(req.decisionBindings()).hasSize(1);
        assertThat(req.decisionBindings().get(0).decisionCode()).isEqualTo("REVIEW");
        assertThat(req.preGates()).hasSize(1);
        assertThat(req.preGates().get(0).gateType()).isEqualTo("ROLLOUT");
        assertThat(req.preGates().get(0).params()).containsEntry("percentage", 100);
        assertThat(req.triggerEventTypes()).containsExactly("login");
    }

    @Test
    void decisionBindingsSerializeBackToSameShape() {
        CreateRuleRequest req = new CreateRuleRequest(
                1L, "s", "c", "n", "AST_BOOLEAN",
                new AndNode(java.util.List.of(), null, null),
                java.util.List.of(new DecisionBindingInput("REVIEW")),
                java.util.List.of(),
                java.util.List.of("login"),
                null, null);
        String out = mapper.writeValueAsString(req.decisionBindings());
        assertThat(out).isEqualTo("[{\"decisionCode\":\"REVIEW\"}]");
    }

    @Test
    void bindsScriptSource_conditionAstNull() {
        // EXPRESSION_SCRIPT 规则：conditionAst 缺省、script 经 {source,lang} 绑定
        String json = """
            {"tenantId":"1","sceneCode":"s","code":"c","name":"n","kind":"EXPRESSION_SCRIPT",
             "script":{"source":"payload.amount > 0 ? 'REVIEW' : 'PASS'","lang":"CEL"}}
            """;
        CreateRuleRequest req = mapper.readValue(json, CreateRuleRequest.class);

        assertThat(req.conditionAst()).isNull();
        assertThat(req.script()).isNotNull();
        assertThat(req.script().source()).isEqualTo("payload.amount > 0 ? 'REVIEW' : 'PASS'");
        assertThat(req.script().lang()).isEqualTo("CEL");
    }
}
