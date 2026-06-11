package com.sstlfsj.rule.job.internal.subject;

import com.sstlfsj.rule.job.api.JobPage;
import com.sstlfsj.rule.job.api.JobTarget;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BeanMethodRegistryTest {

    /** 模拟 @RuleJob 注解的主体查询方法（无参 / 单 JobPage 参）。 */
    static class Probe {
        public List<JobTarget> all() {
            return List.of(JobTarget.of("u1"));
        }

        public List<JobTarget> paged(JobPage page) {
            return List.of(JobTarget.of("p" + page.pageNumber()));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void invokeNoArgReturnsResult() throws Exception {
        BeanMethodRegistry reg = new BeanMethodRegistry();
        reg.register("p#all", new Probe(), Probe.class.getMethod("all"));

        Object r = reg.invoke("p#all");
        assertThat(r).isInstanceOf(List.class);
        assertThat((List<JobTarget>) r).hasSize(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void invokeWithJobPageArg() throws Exception {
        BeanMethodRegistry reg = new BeanMethodRegistry();
        reg.register("p#paged", new Probe(), Probe.class.getMethod("paged", JobPage.class));

        Object r = reg.invoke("p#paged", new JobPage(2, 100));
        assertThat(((List<JobTarget>) r).get(0).subjectId()).isEqualTo("p2");
    }

    @Test
    void methodReturnsRegisteredMethodForSignatureInspection() throws Exception {
        BeanMethodRegistry reg = new BeanMethodRegistry();
        Method m = Probe.class.getMethod("paged", JobPage.class);
        reg.register("p#paged", new Probe(), m);

        assertThat(reg.method("p#paged")).isEqualTo(m);
        assertThat(reg.method("p#paged").getParameterTypes()[0]).isEqualTo(JobPage.class);
    }

    @Test
    void rejectsUnregisteredRef() {
        assertThatThrownBy(() -> new BeanMethodRegistry().invoke("missing#ref"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new BeanMethodRegistry().method("missing#ref"))
                .isInstanceOf(IllegalStateException.class);
    }
}
