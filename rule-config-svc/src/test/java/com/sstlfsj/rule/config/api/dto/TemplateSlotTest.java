package com.sstlfsj.rule.config.api.dto;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TemplateSlotTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();

    @Test
    void blankKey_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> new TemplateSlot("  ", "label", SlotKind.VALUE, ValueDataType.LONG, true, null));
    }

    @Test
    void kindNull_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> new TemplateSlot("k", "label", null, ValueDataType.LONG, true, null));
    }

    @Test
    void valueKind_dataTypeNull_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> new TemplateSlot("k", "label", SlotKind.VALUE, null, true, null));
    }

    @Test
    void metricRefKind_dataTypeNull_isOk() {
        assertDoesNotThrow(() ->
                new TemplateSlot("m", "指标引用", SlotKind.METRIC_REF, null, false, null));
    }

    @Test
    void decisionRefKind_dataTypeNull_isOk() {
        assertDoesNotThrow(() ->
                new TemplateSlot("d", "决策引用", SlotKind.DECISION_REF, null, false, null));
    }

    @Test
    void ruleRefKind_dataTypeNull_isOk() {
        assertDoesNotThrow(() ->
                new TemplateSlot("r", "规则引用", SlotKind.RULE_REF, null, false, null));
    }

    @Test
    void noDefaultValue_field_notPresent() {
        var fields = TemplateSlot.class.getRecordComponents();
        for (var f : fields) {
            assertThat(f.getName()).isNotEqualTo("defaultValue");
        }
    }

    @Test
    void valueSlot_roundTrips() {
        TemplateSlot s = new TemplateSlot("threshold", "阈值", SlotKind.VALUE, ValueDataType.LONG, true,
                new SlotConstraint(BigDecimal.ONE, BigDecimal.TEN, null, null));
        assertThat(s.key()).isEqualTo("threshold");
        assertThat(s.kind()).isEqualTo(SlotKind.VALUE);
        assertThat(s.dataType()).isEqualTo(ValueDataType.LONG);
        assertThat(s.required()).isTrue();

        String json = mapper.writeValueAsString(s);
        TemplateSlot back = mapper.readValue(json, TemplateSlot.class);
        assertThat(back).isEqualTo(s);
    }

    @Test
    void metricRefSlot_roundTrips() {
        TemplateSlot s = new TemplateSlot("metric", "指标引用", SlotKind.METRIC_REF, null, false,
                new SlotConstraint(null, null, null, List.of("LONG", "DOUBLE")));
        assertThat(s.kind()).isEqualTo(SlotKind.METRIC_REF);
        assertThat(s.dataType()).isNull();

        String json = mapper.writeValueAsString(s);
        TemplateSlot back = mapper.readValue(json, TemplateSlot.class);
        assertThat(back).isEqualTo(s);
    }

    @Test
    void missingRequiredKey_defaultsToFalse() {
        // @JsonSetter(nulls=AS_EMPTY) 兜底：required 缺键 → false，不报 400
        String json = """
                {
                  "key": "t",
                  "label": "阈值",
                  "kind": "VALUE",
                  "dataType": "LONG"
                }
                """;
        TemplateSlot back = mapper.readValue(json, TemplateSlot.class);
        assertThat(back.key()).isEqualTo("t");
        assertThat(back.kind()).isEqualTo(SlotKind.VALUE);
        assertThat(back.dataType()).isEqualTo(ValueDataType.LONG);
        assertThat(back.required()).isFalse();
        assertThat(back.constraint()).isNull();
    }

    @Test
    void jsonContainsKindField() {
        TemplateSlot s = new TemplateSlot("t", "阈值", SlotKind.VALUE, ValueDataType.LONG, true, null);
        String json = mapper.writeValueAsString(s);
        assertThat(json).contains("\"kind\":\"VALUE\"");
        assertThat(json).contains("\"dataType\":\"LONG\"");
    }
}
