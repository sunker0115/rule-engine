package com.sstlfsj.rule.kernel.model;

import com.sstlfsj.rule.kernel.api.model.ExpressionLang;
import com.sstlfsj.rule.kernel.api.model.ScriptSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScriptSourceTest {

    @Test
    void blankLangDefaultsToCel() {
        ScriptSource s = new ScriptSource("payload.amount > 10", null);
        assertThat(s.lang()).isEqualTo(ExpressionLang.CEL.tag());
        assertThat(ExpressionLang.CEL.tag()).isEqualTo("CEL");
    }

    @Test
    void explicitLangKept() {
        assertThat(new ScriptSource("x > 1", "AVIATOR").lang()).isEqualTo("AVIATOR");
    }

    @Test
    void blankSourceRejected() {
        assertThatThrownBy(() -> new ScriptSource("  ", "CEL"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
