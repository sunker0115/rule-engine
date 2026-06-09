package com.sstlfsj.rule.web.admin.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** ReplaceActionBindingsRequest 约束校验测试（tenantId 必填 + 嵌套 bindings 校验）。 */
class ReplaceActionBindingsRequestTest {

    private static Validator validator;

    @BeforeAll
    static void initValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    void valid_request_passesValidation() {
        var req = new ReplaceActionBindingsRequest("t1", List.of(
                new ActionBindingItemDto("BLOCK_TX", null, null)));
        Set<ConstraintViolation<ReplaceActionBindingsRequest>> violations = validator.validate(req);
        assertThat(violations).isEmpty();
    }

    @Test
    void emptyBindings_passesValidation() {
        var req = new ReplaceActionBindingsRequest("t1", List.of());
        Set<ConstraintViolation<ReplaceActionBindingsRequest>> violations = validator.validate(req);
        assertThat(violations).isEmpty();
    }

    @Test
    void nullBindings_passesValidation() {
        var req = new ReplaceActionBindingsRequest("t1", null);
        Set<ConstraintViolation<ReplaceActionBindingsRequest>> violations = validator.validate(req);
        assertThat(violations).isEmpty();
    }

    @Test
    void blank_tenantId_failsValidation() {
        var req = new ReplaceActionBindingsRequest("  ", List.of());
        Set<ConstraintViolation<ReplaceActionBindingsRequest>> violations = validator.validate(req);
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("tenantId"));
    }

    @Test
    void nested_blankActionType_failsValidation() {
        var req = new ReplaceActionBindingsRequest("t1", List.of(
                new ActionBindingItemDto("  ", null, null)));
        Set<ConstraintViolation<ReplaceActionBindingsRequest>> violations = validator.validate(req);
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().contains("actionType"));
    }
}
