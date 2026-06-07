package com.sstlfsj.rule.job.internal.subject;

import com.sstlfsj.rule.job.api.JobTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BeanMethodSubjectQueryRunnerTest {

    @Mock
    BeanMethodRegistry registry;

    BeanMethodSubjectQueryRunner runner;

    @BeforeEach
    void setUp() {
        runner = new BeanMethodSubjectQueryRunner(registry, JsonMapper.builder().build());
    }

    @Test
    void invokesRegisteredMethodAndReturnsTargets() {
        when(registry.invoke("a#b")).thenReturn(
                List.of(JobTarget.of("u1"), JobTarget.of("u2")));
        List<JobTarget> targets = runner.query("{\"type\":\"BEAN_METHOD\",\"ref\":\"a#b\"}");
        assertThat(targets).hasSize(2);
        assertThat(targets.get(0).subjectId()).isEqualTo("u1");
    }

    @Test
    void rejectsNonBeanMethodType() {
        assertThatThrownBy(() -> runner.query("{\"type\":\"SQL\"}"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMissingRef() {
        assertThatThrownBy(() -> runner.query("{\"type\":\"BEAN_METHOD\"}"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBlankConfig() {
        assertThatThrownBy(() -> runner.query("")).isInstanceOf(IllegalArgumentException.class);
    }
}
