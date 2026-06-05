package com.sstlfsj.rule.web.config.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** CreateRuleRequest @NotBlank 约束校验测试。 */
class CreateRuleRequestTest {

    private static Validator validator;

    @BeforeAll
    static void initValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    void valid_request_passesValidation() {
        var req = new CreateRuleRequest("t1", "SCENE_A", "RULE_001", "欺诈规则", null, null, null, null, null);
        Set<ConstraintViolation<CreateRuleRequest>> violations = validator.validate(req);
        assertThat(violations).isEmpty();
    }

    @Test
    void blank_tenantId_failsValidation() {
        var req = new CreateRuleRequest("", "SCENE_A", "RULE_001", "欺诈规则", null, null, null, null, null);
        Set<ConstraintViolation<CreateRuleRequest>> violations = validator.validate(req);
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("tenantId"));
    }

    @Test
    void blank_sceneCode_failsValidation() {
        var req = new CreateRuleRequest("t1", "  ", "RULE_001", "欺诈规则", null, null, null, null, null);
        Set<ConstraintViolation<CreateRuleRequest>> violations = validator.validate(req);
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("sceneCode"));
    }

    @Test
    void blank_code_failsValidation() {
        var req = new CreateRuleRequest("t1", "SCENE_A", "  ", "欺诈规则", null, null, null, null, null);
        Set<ConstraintViolation<CreateRuleRequest>> violations = validator.validate(req);
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("code"));
    }

    @Test
    void blank_name_failsValidation() {
        var req = new CreateRuleRequest("t1", "SCENE_A", "RULE_001", "", null, null, null, null, null);
        Set<ConstraintViolation<CreateRuleRequest>> violations = validator.validate(req);
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("name"));
    }

    @Test
    void kind_isOptional_allowsNull() {
        var req = new CreateRuleRequest("t1", "SCENE_A", "RULE_001", "规则", null, null, null, null, null);
        Set<ConstraintViolation<CreateRuleRequest>> violations = validator.validate(req);
        assertThat(violations).isEmpty();
    }

    @Test
    void conditionAst_acceptsMapObject() {
        // conditionAst 改为 Object 类型后，传入 Map 和 List 应能正常构造
        Map<String, Object> ast = Map.of("type", "comparison", "field", "amount", "operator", "GT", "value", 10000);
        List<Object> bindings = List.of(Map.of("decision", "BLOCK", "priority", 1));
        var req = new CreateRuleRequest("t1", "SCENE_A", "RULE_001", "大额拦截", "EXPRESSION",
                ast, bindings, List.of(), List.of("payment.initiated"));
        Set<ConstraintViolation<CreateRuleRequest>> violations = validator.validate(req);
        assertThat(violations).isEmpty();
        assertThat(req.conditionAst()).isInstanceOf(Map.class);
        assertThat(req.decisionBindings()).isInstanceOf(List.class);
    }
}
