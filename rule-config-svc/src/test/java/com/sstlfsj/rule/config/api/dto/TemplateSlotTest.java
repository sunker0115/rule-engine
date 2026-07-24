package com.sstlfsj.rule.config.api.dto;

import com.sstlfsj.rule.kernel.api.model.DataType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TemplateSlotTest {

    @Test
    void blankKey_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> new TemplateSlot("  ", "label", DataType.LONG, true, null));
    }

    @Test
    void nullDataType_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> new TemplateSlot("k", "label", null, true, null));
    }

    @Test
    void unknownDataType_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> new TemplateSlot("k", "label", DataType.UNKNOWN, false, null));
    }

    @Test
    void noDefaultValue_field_notPresent() {
        var fields = TemplateSlot.class.getRecordComponents();
        for (var f : fields) {
            assertThat(f.getName()).isNotEqualTo("defaultValue");
        }
    }

    @Test
    void validSlot_roundTrips() {
        TemplateSlot s = new TemplateSlot("threshold", "阈值", DataType.LONG, true,
                new SlotConstraint(java.math.BigDecimal.ONE, java.math.BigDecimal.TEN, null));
        assertThat(s.key()).isEqualTo("threshold");
        assertThat(s.dataType()).isEqualTo(DataType.LONG);
    }
}
