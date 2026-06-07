package com.sstlfsj.rule.job.internal.subject;

import com.sstlfsj.rule.job.api.JobPage;
import com.sstlfsj.rule.job.api.JobTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BeanMethodSubjectQueryRunnerTest {

    /** 真实 @RuleJob 方法样例（用真 registry 反射调用，覆盖无参 / 分页 / 非法 三类）。 */
    static class Probe {
        public List<JobTarget> all() {
            return List.of(JobTarget.of("u1"), JobTarget.of("u2"));
        }

        public List<JobTarget> paged(JobPage page) {
            // page0 两条、page1 一条、page2 空 → 框架应在空批处停
            return switch (page.pageNumber()) {
                case 0 -> List.of(JobTarget.of("p0a"), JobTarget.of("p0b"));
                case 1 -> List.of(JobTarget.of("p1a"));
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
        registry.register("p#paged", probe, Probe.class.getMethod("paged", JobPage.class));
        registry.register("p#wrong", probe, Probe.class.getMethod("wrongReturn"));
        runner = new BeanMethodSubjectQueryRunner(registry, JsonMapper.builder().build());
    }

    private List<String> collect(String json) {
        List<String> ids = new ArrayList<>();
        runner.forEachTarget(json, t -> ids.add(t.subjectId()));
        return ids;
    }

    @Test
    void noArgListPushesAllTargets() {
        assertThat(collect("{\"type\":\"BEAN_METHOD\",\"ref\":\"p#all\"}"))
                .containsExactly("u1", "u2");
    }

    @Test
    void pagedMethodLoopsUntilEmptyBatch() {
        // page0(2) + page1(1) 全推出，page2 空批停止
        assertThat(collect("{\"type\":\"BEAN_METHOD\",\"ref\":\"p#paged\"}"))
                .containsExactly("p0a", "p0b", "p1a");
    }

    @Test
    void rejectsUnknownType() {
        assertThatThrownBy(() -> runner.forEachTarget("{\"type\":\"SQL\"}", t -> { }))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void rejectsBlankConfig() {
        assertThatThrownBy(() -> runner.forEachTarget("", t -> { }))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonListReturnType() {
        assertThatThrownBy(() -> runner.forEachTarget("{\"type\":\"BEAN_METHOD\",\"ref\":\"p#wrong\"}", t -> { }))
                .isInstanceOf(IllegalStateException.class);
    }
}
