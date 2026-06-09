package com.sstlfsj.rule.web.admin.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** ActionBindingItemDto @NotBlank actionType 约束校验测试。 */
class ActionBindingItemDtoTest {

    private static Validator validator;

    @BeforeAll
    static void initValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    void valid_request_passesValidation() {
        var dto = new ActionBindingItemDto("BLOCK_TX", Map.of("reason", "risk"), null);
        Set<ConstraintViolation<ActionBindingItemDto>> violations = validator.validate(dto);
        assertThat(violations).isEmpty();
    }

    @Test
    void nullableParams_passesValidation() {
        var dto = new ActionBindingItemDto("SEND_ALERT", null, null);
        Set<ConstraintViolation<ActionBindingItemDto>> violations = validator.validate(dto);
        assertThat(violations).isEmpty();
    }

    @Test
    void blank_actionType_failsValidation() {
        var dto = new ActionBindingItemDto("  ", null, null);
        Set<ConstraintViolation<ActionBindingItemDto>> violations = validator.validate(dto);
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("actionType"));
    }
}
