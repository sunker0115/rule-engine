package com.sstlfsj.rule.kernel.api.model.ast;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ScoreBandTest {

    @Test
    void accessors_retainConstructorValues() {
        ScoreBand band = new ScoreBand(0, 60, "REJECT", "HIGH_RISK");
        assertEquals(0, band.minScore(), 1e-9);
        assertEquals(60, band.maxScore(), 1e-9);
        assertEquals("REJECT", band.decisionCode());
        assertEquals("HIGH_RISK", band.category());
    }

    @Test
    void category_isNullable() {
        ScoreBand band = new ScoreBand(60, 80, "REVIEW", null);
        assertNull(band.category());
    }

    @Test
    void recordEquality_byValue() {
        ScoreBand a = new ScoreBand(0, 60, "REJECT", "HIGH");
        ScoreBand b = new ScoreBand(0, 60, "REJECT", "HIGH");
        assertEquals(a, b);
    }

    @Test
    void nameAndPriorityDefaultsFromCompatConstructor() {
        ScoreBand band = new ScoreBand(0, 60, "REJECT", "HIGH");
        assertThat(band.name()).isEmpty();
        assertThat(band.priority()).isEqualTo(0);
    }

    @Test
    void sixParamConstructorRetainsAllFields() {
        ScoreBand band = new ScoreBand(0, 60, "REJECT", "HIGH", "拒绝", 1);
        assertThat(band.name()).isEqualTo("拒绝");
        assertThat(band.priority()).isEqualTo(1);
        assertThat(band.decisionCode()).isEqualTo("REJECT");
        assertThat(band.category()).isEqualTo("HIGH");
    }

    @Test
    void priorityCatchesNullFromJsonViaAsEmpty() {
        // 旧 JSON 里没有 priority 键（存量数据兜底）：@JsonSetter(AS_EMPTY) 应兜底为 0
        String json = "{\"minScore\":0,\"maxScore\":60,\"decisionCode\":\"REJECT\",\"category\":\"HIGH\",\"name\":\"拒绝\"}";
        tools.jackson.databind.ObjectMapper mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        ScoreBand band = mapper.readValue(json, ScoreBand.class);
        assertThat(band.priority()).isEqualTo(0);
        assertThat(band.name()).isEqualTo("拒绝");
    }
}
