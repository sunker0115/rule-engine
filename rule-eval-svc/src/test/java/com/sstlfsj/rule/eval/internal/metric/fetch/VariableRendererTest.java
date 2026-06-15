package com.sstlfsj.rule.eval.internal.metric.fetch;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class VariableRendererTest {

    private final VariableRenderer renderer = new VariableRenderer();

    private VariableRenderer.Context ctx() {
        return new VariableRenderer.Context(
                "sub1", "t1", Instant.parse("2026-06-01T00:00:00Z"),
                Map.of("ip", "1.2.3.4"),          // payload
                Map.of("q", "v"),                  // params
                Map.of("uid", "u9"),               // vars
                Map.of("level", "VIP"));           // subjectAttributes
    }

    @Test
    void rendersBracePlaceholdersWithUrlEncoding() {
        String out = renderer.renderTemplate("/u/{payload.ip}/{vars.uid}/{subject.level}", ctx());
        assertThat(out).isEqualTo("/u/1.2.3.4/u9/VIP");
    }

    @Test
    void resolvesEachNamespace() {
        VariableRenderer.Context c = ctx();
        assertThat(renderer.resolve("payload", "ip", c)).isEqualTo("1.2.3.4");
        assertThat(renderer.resolve("params", "q", c)).isEqualTo("v");
        assertThat(renderer.resolve("vars", "uid", c)).isEqualTo("u9");
        assertThat(renderer.resolve("subject", "level", c)).isEqualTo("VIP");
        assertThat(renderer.resolve("subjectId", null, c)).isEqualTo("sub1");
        assertThat(renderer.resolve("tenantId", null, c)).isEqualTo("t1");
    }

    @Test
    void referencesSubjectDetectsSubjectNamespace() {
        assertThat(renderer.referencesSubject("/a/{subject.level}")).isTrue();
        assertThat(renderer.referencesSubject("/a/{payload.ip}")).isFalse();
    }
}
