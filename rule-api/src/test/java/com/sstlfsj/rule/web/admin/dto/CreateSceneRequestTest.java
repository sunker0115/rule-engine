package com.sstlfsj.rule.web.admin.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** CreateSceneRequest @NotBlank 约束校验测试（D13 扩展后）。 */
class CreateSceneRequestTest {

    private static Validator validator;

    @BeforeAll
    static void initValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    void valid_minimal_request_passesValidation() {
        // 只填必填三字段，其余 D13 字段为 null
        var req = new CreateSceneRequest("t1", "fraud", "欺诈检测",
                null, null, null, null, null, null);
        Set<ConstraintViolation<CreateSceneRequest>> violations = validator.validate(req);
        assertThat(violations).isEmpty();
    }

    @Test
    void valid_full_request_passesValidation() {
        // 填写所有 D13 字段
        var req = new CreateSceneRequest("t1", "payment", "支付场景",
                "描述", "PUSH", "USER",
                List.of("payment.initiated"), null, null);
        Set<ConstraintViolation<CreateSceneRequest>> violations = validator.validate(req);
        assertThat(violations).isEmpty();
    }

    @Test
    void blank_tenantId_failsValidation() {
        var req = new CreateSceneRequest("", "fraud", "欺诈检测",
                null, null, null, null, null, null);
        Set<ConstraintViolation<CreateSceneRequest>> violations = validator.validate(req);
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("tenantId"));
    }

    @Test
    void null_sceneCode_failsValidation() {
        var req = new CreateSceneRequest("t1", null, "欺诈检测",
                null, null, null, null, null, null);
        Set<ConstraintViolation<CreateSceneRequest>> violations = validator.validate(req);
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("sceneCode"));
    }

    @Test
    void blank_name_failsValidation() {
        var req = new CreateSceneRequest("t1", "fraud", "  ",
                null, null, null, null, null, null);
        Set<ConstraintViolation<CreateSceneRequest>> violations = validator.validate(req);
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("name"));
    }
}
