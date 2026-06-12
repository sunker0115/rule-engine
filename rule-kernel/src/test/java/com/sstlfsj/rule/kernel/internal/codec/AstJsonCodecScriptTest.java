package com.sstlfsj.rule.kernel.internal.codec;

import com.sstlfsj.rule.kernel.api.model.ScriptSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AstJsonCodecScriptTest {

    private final AstJsonCodec codec = new AstJsonCodec();

    @Test
    void deserializesScriptSource() throws Exception {
        ScriptSource s = codec.deserializeScriptSource("{\"source\":\"payload.amount > 10\",\"lang\":\"CEL\"}");
        assertThat(s.source()).isEqualTo("payload.amount > 10");
        assertThat(s.lang()).isEqualTo("CEL");
    }

    @Test
    void nullOrBlankReturnsNull() throws Exception {
        assertThat(codec.deserializeScriptSource(null)).isNull();
        assertThat(codec.deserializeScriptSource("  ")).isNull();
    }
}
