package com.sstlfsj.rule.job.internal.subject;

import com.sstlfsj.rule.job.api.JobTarget;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BeanMethodRegistryTest {

    /** 模拟 @RuleJob 注解的主体查询方法（List / Stream / 非法返回类型）。 */
    static class Probe {
        public List<JobTarget> users() {
            return List.of(JobTarget.of("u1"), JobTarget.of("u2"));
        }

        public Stream<JobTarget> streamUsers() {
            return Stream.of(JobTarget.of("s1"), JobTarget.of("s2"));
        }

        public String wrongType() {
            return "nope";
        }
    }

    @Test
    void invokesListReturningMethodAsStream() throws Exception {
        Probe probe = new Probe();
        BeanMethodRegistry registry = new BeanMethodRegistry();
        registry.register("Probe#users", probe, Probe.class.getMethod("users"));

        List<JobTarget> targets = registry.invoke("Probe#users").toList();
        assertThat(targets).hasSize(2);
        assertThat(targets.get(0).subjectId()).isEqualTo("u1");
    }

    @Test
    void invokesStreamReturningMethod() throws Exception {
        Probe probe = new Probe();
        BeanMethodRegistry registry = new BeanMethodRegistry();
        registry.register("Probe#streamUsers", probe, Probe.class.getMethod("streamUsers"));

        List<JobTarget> targets = registry.invoke("Probe#streamUsers").toList();
        assertThat(targets).hasSize(2);
        assertThat(targets.get(0).subjectId()).isEqualTo("s1");
    }

    @Test
    void rejectsUnsupportedReturnType() throws Exception {
        Probe probe = new Probe();
        BeanMethodRegistry registry = new BeanMethodRegistry();
        registry.register("Probe#wrongType", probe, Probe.class.getMethod("wrongType"));

        assertThatThrownBy(() -> registry.invoke("Probe#wrongType"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsUnregisteredRef() {
        assertThatThrownBy(() -> new BeanMethodRegistry().invoke("missing#ref"))
                .isInstanceOf(IllegalStateException.class);
    }
}
