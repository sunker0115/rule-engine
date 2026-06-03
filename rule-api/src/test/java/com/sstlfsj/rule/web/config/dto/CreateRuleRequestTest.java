package com.sstlfsj.rule.web.config.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** CreateRuleRequest @NotBlank / @NotNull 约束校验测试。 */
class CreateRuleRequestTest {

    private static Validator validator;

    @BeforeAll
    static void initValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    void valid_request_passesValidation() {
        var req = new CreateRuleRequest("t1", 10L, "RULE_001", "欺诈规则");
        Set<ConstraintViolation<CreateRuleRequest>> violations = validator.validate(req);
        assertThat(violations).isEmpty();
    }

    @Test
    void blank_tenantId_failsValidation() {
        var req = new CreateRuleRequest("", 10L, "RULE_001", "欺诈规则");
        Set<ConstraintViolation<CreateRuleRequest>> violations = validator.validate(req);
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("tenantId"));
    }

    @Test
    void null_sceneId_failsValidation() {
        var req = new CreateRuleRequest("t1", null, "RULE_001", "欺诈规则");
        Set<ConstraintViolation<CreateRuleRequest>> violations = validator.validate(req);
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("sceneId"));
    }

    @Test
    void blank_code_failsValidation() {
        var req = new CreateRuleRequest("t1", 10L, "  ", "欺诈规则");
        Set<ConstraintViolation<CreateRuleRequest>> violations = validator.validate(req);
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("code"));
    }

    @Test
    void null_name_failsValidation() {
        var req = new CreateRuleRequest("t1", 10L, "RULE_001", null);
        Set<ConstraintViolation<CreateRuleRequest>> violations = validator.validate(req);
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("name"));
    }
}
