package com.sstlfsj.rule.kernel.api.model;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;
class EvalEnvTest {
    @Test void carriesFields() {
        Instant t = Instant.parse("2020-01-01T00:00:00Z");
        EvalEnv e = new EvalEnv(t, java.util.Map.of("timezone","Asia/Shanghai"));
        assertThat(e.now()).isEqualTo(t);
        assertThat(e.sceneDefaultParams()).containsEntry("timezone","Asia/Shanghai");
    }
    @Test void nullSceneParams_defaultsEmpty() {
        assertThat(new EvalEnv(Instant.now(), null).sceneDefaultParams()).isEmpty();
    }
}
