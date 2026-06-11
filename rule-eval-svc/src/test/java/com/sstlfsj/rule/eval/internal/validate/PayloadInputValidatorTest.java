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
}
