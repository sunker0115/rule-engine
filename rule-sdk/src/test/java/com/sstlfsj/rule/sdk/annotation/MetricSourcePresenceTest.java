package com.sstlfsj.rule.sdk.annotation;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;
import static org.assertj.core.api.Assertions.assertThat;

class MetricSourcePresenceTest {

    static class Holder {
        @MetricSource(value = "m", cacheTtlSeconds = 60, allowProvided = true)
        public long m() { return 1; }
    }

    @Test
    void annotation_isRuntimeVisibleWithAttributes() throws Exception {
        Method m = Holder.class.getMethod("m");
        MetricSource ann = m.getAnnotation(MetricSource.class);
        assertThat(ann.value()).isEqualTo("m");
        assertThat(ann.cacheTtlSeconds()).isEqualTo(60);
        assertThat(ann.allowProvided()).isTrue();
    }
}
