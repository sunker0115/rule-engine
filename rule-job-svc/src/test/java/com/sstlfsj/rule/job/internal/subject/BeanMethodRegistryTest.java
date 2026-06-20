package com.sstlfsj.rule.job.internal.subject;

import com.sstlfsj.rule.job.api.SubjectPage;
import com.sstlfsj.rule.job.api.SubjectTarget;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BeanMethodRegistryTest {

    /** 模拟 @TriggerTask 注解的主体查询方法（无参 / 单 SubjectPage 参）。 */
    static class Probe {
        public List<SubjectTarget> all() {
            return List.of(SubjectTarget.of("u1"));
        }

        public List<SubjectTarget> paged(SubjectPage page) {
            return List.of(SubjectTarget.of("p" + page.pageNumber()));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void invokeNoArgReturnsResult() throws Exception {
        BeanMethodRegistry reg = new BeanMethodRegistry();
        reg.register("p#all", new Probe(), Probe.class.getMethod("all"));

        Object r = reg.invoke("p#all");
        assertThat(r).isInstanceOf(List.class);
        assertThat((List<SubjectTarget>) r).hasSize(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void invokeWithSubjectPageArg() throws Exception {
        BeanMethodRegistry reg = new BeanMethodRegistry();
        reg.register("p#paged", new Probe(), Probe.class.getMethod("paged", SubjectPage.class));

        Object r = reg.invoke("p#paged", new SubjectPage(2, 100));
        assertThat(((List<SubjectTarget>) r).get(0).subjectId()).isEqualTo("p2");
    }

    @Test
    void methodReturnsRegisteredMethodForSignatureInspection() throws Exception {
        BeanMethodRegistry reg = new BeanMethodRegistry();
        Method m = Probe.class.getMethod("paged", SubjectPage.class);
        reg.register("p#paged", new Probe(), m);

        assertThat(reg.method("p#paged")).isEqualTo(m);
        assertThat(reg.method("p#paged").getParameterTypes()[0]).isEqualTo(SubjectPage.class);
    }

    @Test
    void rejectsUnregisteredRef() {
        assertThatThrownBy(() -> new BeanMethodRegistry().invoke("missing#ref"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new BeanMethodRegistry().method("missing#ref"))
                .isInstanceOf(IllegalStateException.class);
    }
}
