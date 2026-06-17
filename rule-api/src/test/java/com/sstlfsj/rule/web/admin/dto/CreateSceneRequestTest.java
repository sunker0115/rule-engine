package com.sstlfsj.rule.web.admin.dto;

import com.sstlfsj.rule.config.api.dto.PayloadFieldSpec;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** CreateSceneRequest @NotBlank 约束校验 + typed 字段绑定测试（D13 扩展后）。 */
class CreateSceneRequestTest {

    private static Validator validator;

    private final ObjectMapper mapper = JsonMapper.builder().build();

    @BeforeAll
    static void initValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    void bindsTypedPayloadSchemaAndDefaultParams() {
        String json = """
            {"tenantId":"1","sceneCode":"s","name":"n","dominantMode":"PUSH","subjectType":"USER",
             "eventTypes":["login"],
             "payloadSchema":[{"name":"amount","type":"NUMBER","required":true}],
             "defaultParams":{"timezone":"Asia/Shanghai"}}
            """;
        CreateSceneRequest req = mapper.readValue(json, CreateSceneRequest.class);

        assertThat(req.payloadSchema()).hasSize(1);
        assertThat(req.payloadSchema().get(0)).isInstanceOf(PayloadFieldSpec.class);
        assertThat(req.payloadSchema().get(0).name()).isEqualTo("amount");
        assertThat(req.payloadSchema().get(0).type()).isEqualTo("NUMBER");
        assertThat(req.payloadSchema().get(0).required()).isTrue();
        assertThat(req.defaultParams()).containsEntry("timezone", "Asia/Shanghai");
    }

    @Test
    void valid_minimal_request_passesValidation() {
        // 只填必填三字段，其余 D13 字段为 null
        var req = new CreateSceneRequest(1L, "fraud", "欺诈检测",
                null, null, null, null, null, null);
        Set<ConstraintViolation<CreateSceneRequest>> violations = validator.validate(req);
        assertThat(violations).isEmpty();
    }

    @Test
    void valid_full_request_passesValidation() {
        // 填写所有 D13 字段
        var req = new CreateSceneRequest(1L, "payment", "支付场景",
                "描述", "PUSH", "USER",
                List.of("payment.initiated"), null, null);
        Set<ConstraintViolation<CreateSceneRequest>> violations = validator.validate(req);
        assertThat(violations).isEmpty();
    }

    @Test
    void null_tenantId_failsValidation() {
        var req = new CreateSceneRequest(null, "fraud", "欺诈检测",
                null, null, null, null, null, null);
        Set<ConstraintViolation<CreateSceneRequest>> violations = validator.validate(req);
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("tenantId"));
    }

    @Test
    void null_sceneCode_failsValidation() {
        var req = new CreateSceneRequest(1L, null, "欺诈检测",
                null, null, null, null, null, null);
        Set<ConstraintViolation<CreateSceneRequest>> violations = validator.validate(req);
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("sceneCode"));
    }

    @Test
    void blank_name_failsValidation() {
        var req = new CreateSceneRequest(1L, "fraud", "  ",
                null, null, null, null, null, null);
        Set<ConstraintViolation<CreateSceneRequest>> violations = validator.validate(req);
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("name"));
    }
}
