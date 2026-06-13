package com.sstlfsj.rule.eval.internal.validate;

import com.sstlfsj.rule.kernel.api.model.PayloadDependency;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class PayloadInputValidatorTest {
    @Test
    void missingRequired_throwsMissingRequiredInput() {
        var deps = List.of(new PayloadDependency("amount", "DECIMAL", true));
        assertThatThrownBy(() -> PayloadInputValidator.validate(deps, Map.of("country", "CN")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MISSING_REQUIRED_INPUT").hasMessageContaining("amount");
    }
    @Test
    void typeMismatch_throwsInputTypeMismatch() {
        var deps = List.of(new PayloadDependency("amount", "DECIMAL", true));
        assertThatThrownBy(() -> PayloadInputValidator.validate(deps, Map.of("amount", "not-a-number")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INPUT_TYPE_MISMATCH").hasMessageContaining("amount");
    }
    @Test
    void allPresentCorrectType_passes_andIgnoresExtra() {
        var deps = List.of(new PayloadDependency("amount", "DECIMAL", true));
        assertThatCode(() -> PayloadInputValidator.validate(deps, Map.of("amount", 5000, "extra", "x")))
                .doesNotThrowAnyException();
    }
    @Test
    void optionalMissing_passes() {
        var deps = List.of(new PayloadDependency("note", "STRING", false));
        assertThatCode(() -> PayloadInputValidator.validate(deps, Map.of())).doesNotThrowAnyException();
    }

    @Test
    void enumViolation_throws() {
        var deps = List.of(PayloadDependency.builder()
                .name("channel").dataType("STRING").required(true).enumValues(List.of("APP", "WEB")).build());
        assertThatThrownBy(() -> PayloadInputValidator.validate(deps, Map.of("channel", "SMS")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("INPUT_ENUM_VIOLATION");
    }

    @Test
    void enumOk_passes() {
        var deps = List.of(PayloadDependency.builder()
                .name("channel").dataType("STRING").required(true).enumValues(List.of("APP", "WEB")).build());
        assertThatCode(() -> PayloadInputValidator.validate(deps, Map.of("channel", "APP")))
                .doesNotThrowAnyException();
    }

    @Test
    void rangeViolation_throws() {
        var deps = List.of(PayloadDependency.builder()
                .name("amount").dataType("DECIMAL").required(true).minimum(0.0).maximum(100.0).build());
        assertThatThrownBy(() -> PayloadInputValidator.validate(deps, Map.of("amount", 200)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("INPUT_RANGE_VIOLATION");
    }

    @Test
    void patternViolation_throws() {
        var deps = List.of(PayloadDependency.builder()
                .name("phone").dataType("STRING").required(true).pattern("\\d{11}").build());
        assertThatThrownBy(() -> PayloadInputValidator.validate(deps, Map.of("phone", "abc")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("INPUT_PATTERN_VIOLATION");
    }

    @Test
    void patternOk_passes() {
        var deps = List.of(PayloadDependency.builder()
                .name("phone").dataType("STRING").required(true).pattern("\\d{11}").build());
        assertThatCode(() -> PayloadInputValidator.validate(deps, Map.of("phone", "13800138000")))
                .doesNotThrowAnyException();
    }
}
