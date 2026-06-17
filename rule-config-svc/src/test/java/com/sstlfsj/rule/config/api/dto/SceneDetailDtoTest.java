package com.sstlfsj.rule.config.api.dto;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证 SceneDetailDto record 构造与字段读取正确。 */
class SceneDetailDtoTest {

    @Test
    void constructor_and_accessors_正确() {
        List<PayloadFieldSpec> schema = List.of(
                new PayloadFieldSpec("amount", "NUMBER", true, null, null, null, null, null)
        );
        Map<String, Object> params = Map.of("timezone", "Asia/Shanghai");

        SceneDetailDto dto = new SceneDetailDto(
                1L, 100L, "PAYMENT", "支付场景",
                "描述", "PUSH", "USER",
                List.of("payment.initiated"),
                schema, params, "ACTIVE"
        );

        assertThat(dto.id()).isEqualTo(1L);
        assertThat(dto.tenantId()).isEqualTo(100L);
        assertThat(dto.sceneCode()).isEqualTo("PAYMENT");
        assertThat(dto.name()).isEqualTo("支付场景");
        assertThat(dto.description()).isEqualTo("描述");
        assertThat(dto.dominantMode()).isEqualTo("PUSH");
        assertThat(dto.subjectType()).isEqualTo("USER");
        assertThat(dto.eventTypes()).containsExactly("payment.initiated");
        assertThat(dto.payloadSchema()).hasSize(1);
        assertThat(dto.payloadSchema().get(0).name()).isEqualTo("amount");
        assertThat(dto.defaultParams()).containsEntry("timezone", "Asia/Shanghai");
        assertThat(dto.status()).isEqualTo("ACTIVE");
    }
}
