package com.sstlfsj.rule.web.config.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** CreateSceneRequest @NotBlank 约束校验测试。 */
class CreateSceneRequestTest {

    private static Validator validator;

    @BeforeAll
    static void initValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    void valid_request_passesValidation() {
        var req = new CreateSceneRequest("t1", "fraud", "欺诈检测");
        Set<ConstraintViolation<CreateSceneRequest>> violations = validator.validate(req);
        assertThat(violations).isEmpty();
    }

    @Test
    void blank_tenantId_failsValidation() {
        var req = new CreateSceneRequest("", "fraud", "欺诈检测");
        Set<ConstraintViolation<CreateSceneRequest>> violations = validator.validate(req);
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("tenantId"));
    }

    @Test
    void null_sceneCode_failsValidation() {
        var req = new CreateSceneRequest("t1", null, "欺诈检测");
        Set<ConstraintViolation<CreateSceneRequest>> violations = validator.validate(req);
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("sceneCode"));
    }

    @Test
    void blank_name_failsValidation() {
        var req = new CreateSceneRequest("t1", "fraud", "  ");
        Set<ConstraintViolation<CreateSceneRequest>> violations = validator.validate(req);
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("name"));
    }
}
