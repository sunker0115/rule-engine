package com.sstlfsj.rule.job.internal.subject;

import com.sstlfsj.rule.job.api.JobTarget;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BeanMethodRegistryTest {

    /** 模拟 @RuleJob 注解的主体查询方法。 */
    static class Probe {
        public List<JobTarget> users() {
            return List.of(JobTarget.of("u1"), JobTarget.of("u2"));
        }
    }

    @Test
    void invokesRegisteredMethod() throws Exception {
        Probe probe = new Probe();
        Method method = Probe.class.getMethod("users");
        BeanMethodRegistry registry = new BeanMethodRegistry();
        registry.register("Probe#users", probe, method);

        List<JobTarget> targets = registry.invoke("Probe#users");
        assertThat(targets).hasSize(2);
        assertThat(targets.get(0).subjectId()).isEqualTo("u1");
    }

    @Test
    void rejectsUnregisteredRef() {
        assertThatThrownBy(() -> new BeanMethodRegistry().invoke("missing#ref"))
                .isInstanceOf(IllegalStateException.class);
    }
}
