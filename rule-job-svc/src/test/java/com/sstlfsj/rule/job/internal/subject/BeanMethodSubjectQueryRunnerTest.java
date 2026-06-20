package com.sstlfsj.rule.job.internal.subject;

import com.sstlfsj.rule.job.api.BeanMethodQuery;
import com.sstlfsj.rule.job.api.SubjectPage;
import com.sstlfsj.rule.job.api.SubjectQuery;
import com.sstlfsj.rule.job.api.SubjectTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BeanMethodSubjectQueryRunnerTest {

    /** 真实 @TriggerTask 方法样例（用真 registry 反射调用，覆盖无参 / 分页 / 非法 三类）。 */
    static class Probe {
        public List<SubjectTarget> all() {
            return List.of(SubjectTarget.of("u1"), SubjectTarget.of("u2"));
        }

        public List<SubjectTarget> paged(SubjectPage page) {
            // page0 两条、page1 一条、page2 空 → 框架应在空批处停
            return switch (page.pageNumber()) {
                case 0 -> List.of(SubjectTarget.of("p0a"), SubjectTarget.of("p0b"));
                case 1 -> List.of(SubjectTarget.of("p1a"));
                default -> List.of();
            };
        }

        public String wrongReturn() {
            return "nope";
        }
    }

    private BeanMethodSubjectQueryRunner runner;

    @BeforeEach
    void setUp() throws Exception {
        BeanMethodRegistry registry = new BeanMethodRegistry();
        Probe probe = new Probe();
        registry.register("p#all", probe, Probe.class.getMethod("all"));
        registry.register("p#paged", probe, Probe.class.getMethod("paged", SubjectPage.class));
        registry.register("p#wrong", probe, Probe.class.getMethod("wrongReturn"));
        runner = new BeanMethodSubjectQueryRunner(registry);
    }

    private List<String> collect(SubjectQuery query) {
        List<String> ids = new ArrayList<>();
        runner.forEachTarget(query, t -> ids.add(t.subjectId()));
        return ids;
    }

    @Test
    void noArgListPushesAllTargets() {
        assertThat(collect(new BeanMethodQuery("p#all")))
                .containsExactly("u1", "u2");
    }

    @Test
    void pagedMethodLoopsUntilEmptyBatch() {
        // page0(2) + page1(1) 全推出，page2 空批停止
        assertThat(collect(new BeanMethodQuery("p#paged")))
                .containsExactly("p0a", "p0b", "p1a");
    }

    @Test
    void rejectsNullConfig() {
        assertThatThrownBy(() -> runner.forEachTarget(null, t -> { }))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonListReturnType() {
        assertThatThrownBy(() -> runner.forEachTarget(new BeanMethodQuery("p#wrong"), t -> { }))
                .isInstanceOf(IllegalStateException.class);
    }
}
