package com.sstlfsj.rule.web.admin.dto;

import com.sstlfsj.rule.config.api.dto.PayloadFieldSpec;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** UpdateSceneRequest @NotBlank 约束校验测试（D13 PATCH 端点）。 */
class UpdateSceneRequestTest {

    private static Validator validator;

    @BeforeAll
    static void initValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    void valid_minimal_request_passesValidation() {
        // 只填必填 tenantId，其余字段为 null
        var req = new UpdateSceneRequest(1L, null, null, null, null, null);
        Set<ConstraintViolation<UpdateSceneRequest>> violations = validator.validate(req);
        assertThat(violations).isEmpty();
    }

    @Test
    void valid_full_request_passesValidation() {
        // 填写所有可选字段
        var req = new UpdateSceneRequest(
                1L,
                "新场景名称",
                null,
                List.of("payment.initiated", "payment.settled"),
                List.of(new PayloadFieldSpec("amount", "NUMBER", true, null, null, null, null, null)),
                Map.of("timezone", "Asia/Shanghai")
        );
        Set<ConstraintViolation<UpdateSceneRequest>> violations = validator.validate(req);
        assertThat(violations).isEmpty();
    }

    @Test
    void null_tenantId_failsValidation() {
        var req = new UpdateSceneRequest(null, "新名称", null, null, null, null);
        Set<ConstraintViolation<UpdateSceneRequest>> violations = validator.validate(req);
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("tenantId"));
    }
}
