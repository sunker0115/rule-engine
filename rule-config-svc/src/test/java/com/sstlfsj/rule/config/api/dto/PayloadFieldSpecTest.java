package com.sstlfsj.rule.config.api.dto;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PayloadFieldSpecTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();

    @Test
    void payloadFieldSpec_serializes_名称类型required() throws Exception {
        PayloadFieldSpec field = new PayloadFieldSpec(
                "amount", "NUMBER", true, null, 0.0, 999999.0, null, "交易金额");
        String json = mapper.writeValueAsString(field);
        assertThat(json).contains("\"name\":\"amount\"");
        assertThat(json).contains("\"type\":\"NUMBER\"");
        assertThat(json).contains("\"required\":true");
        assertThat(json).contains("\"minimum\":0.0");
        assertThat(json).contains("\"maximum\":999999.0");
    }

    @Test
    void payloadFieldSpec_withEnum_序列化enum键() throws Exception {
        PayloadFieldSpec field = new PayloadFieldSpec(
                "currency", "STRING", true, List.of("CNY", "USD"), null, null, null, null);
        String json = mapper.writeValueAsString(field);
        assertThat(json).contains("\"enum\":[\"CNY\",\"USD\"]");
    }

    @Test
    void payloadFieldSpec_roundTrip反序列化() throws Exception {
        PayloadFieldSpec original = new PayloadFieldSpec(
                "userId", "STRING", true, null, null, null, "[A-Za-z0-9]{8}", "用户ID");
        String json = mapper.writeValueAsString(original);
        PayloadFieldSpec deserialized = mapper.readValue(json, PayloadFieldSpec.class);
        assertThat(deserialized.name()).isEqualTo("userId");
        assertThat(deserialized.type()).isEqualTo("STRING");
        assertThat(deserialized.required()).isTrue();
        assertThat(deserialized.pattern()).isEqualTo("[A-Za-z0-9]{8}");
    }

    @Test
    void payloadFieldSpec_list_roundTrip() throws Exception {
        List<PayloadFieldSpec> specs = List.of(
                new PayloadFieldSpec("amount", "NUMBER", true, null, 0.0, null, null, null),
                new PayloadFieldSpec("currency", "STRING", true, List.of("CNY", "USD"), null, null, null, null)
        );
        String json = mapper.writeValueAsString(specs);
        List<PayloadFieldSpec> result = mapper.readValue(json, new TypeReference<>() {});
        assertThat(result).hasSize(2);
        assertThat(result.get(0).name()).isEqualTo("amount");
        assertThat(result.get(1).enumValues()).containsExactly("CNY", "USD");
    }
}
