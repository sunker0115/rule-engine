package com.sstlfsj.rule.job.internal.subject;

import com.sstlfsj.rule.job.api.JobTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.stream.Stream;

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
        when(registry.invoke("a#b")).thenReturn(Stream.of(JobTarget.of("u1"), JobTarget.of("u2")));
        List<JobTarget> targets = runner.query("{\"type\":\"BEAN_METHOD\",\"ref\":\"a#b\"}").toList();
        assertThat(targets).hasSize(2);
        assertThat(targets.get(0).subjectId()).isEqualTo("u1");
    }

    @Test
    void rejectsUnknownType() {
        assertThatThrownBy(() -> runner.query("{\"type\":\"SQL\"}"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void rejectsBlankConfig() {
        assertThatThrownBy(() -> runner.query(""))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
