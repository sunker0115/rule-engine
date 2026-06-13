package com.sstlfsj.rule.sdk.annotation;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import static org.assertj.core.api.Assertions.assertThat;

class AnnotationPresenceTest {

    static class Sample {
        @Condition
        public boolean hit(@Fact("number") Integer n, @Metric("m") Integer m) { return true; }
        @OnDecision({"EVEN"})
        public void act(@Fact("number") Integer n) { }
    }

    @Test
    void annotations_areRuntimeVisible() throws Exception {
        Method hit = Sample.class.getMethod("hit", Integer.class, Integer.class);
        assertThat(hit.isAnnotationPresent(Condition.class)).isTrue();
        Parameter[] ps = hit.getParameters();
        assertThat(ps[0].getAnnotation(Fact.class).value()).isEqualTo("number");
        assertThat(ps[1].getAnnotation(Metric.class).value()).isEqualTo("m");
        assertThat(ps[1].getAnnotation(Metric.class).version()).isEqualTo(1);

        Method act = Sample.class.getMethod("act", Integer.class);
        assertThat(act.getAnnotation(OnDecision.class).value()).containsExactly("EVEN");
    }
}
