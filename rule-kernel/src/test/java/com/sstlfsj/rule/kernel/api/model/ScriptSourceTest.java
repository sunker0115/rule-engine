package com.sstlfsj.rule.kernel.api.model;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ScriptSourceTest {

    @Test
    void twoArgConstructor_defaultsParamsToEmptyMap() {
        ScriptSource s = new ScriptSource("metrics.amount > 100", "CEL");
        assertThat(s.params()).isEmpty();
    }

    @Test
    void threeArgConstructor_preservesParams() {
        Map<String, Object> params = Map.of("threshold", 100);
        ScriptSource s = new ScriptSource("expr", "CEL", params);
        assertThat(s.params()).containsEntry("threshold", 100);
    }

    @Test
    void paramsAreImmutable() {
        Map<String, Object> p = new java.util.HashMap<>();
        p.put("k", "v");
        ScriptSource s = new ScriptSource("expr", "CEL", p);
        p.put("extra", "x");
        assertThat(s.params()).hasSize(1);
        assertThrows(UnsupportedOperationException.class, () -> s.params().put("k2", "v2"));
    }

    @Test
    void nullParams_defaultsToEmptyMap() {
        ScriptSource s = new ScriptSource("expr", "CEL", null);
        assertThat(s.params()).isEmpty();
    }

    @Test
    void recordEquality_includesParams() {
        ScriptSource a = new ScriptSource("e", "CEL", Map.of("x", 1));
        ScriptSource b = new ScriptSource("e", "CEL", Map.of("x", 1));
        assertThat(a).isEqualTo(b);
    }

    @Test
    void recordEquality_paramsDiff_notEqual() {
        ScriptSource a = new ScriptSource("e", "CEL", Map.of("x", 1));
        ScriptSource b = new ScriptSource("e", "CEL", Map.of("x", 2));
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void blankSource_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> new ScriptSource("  ", "CEL"));
    }

    @Test
    void nullLang_defaultsToCEL() {
        ScriptSource s = new ScriptSource("expr", null);
        assertThat(s.lang()).isEqualTo(ExpressionLang.CEL.tag());
    }
}
