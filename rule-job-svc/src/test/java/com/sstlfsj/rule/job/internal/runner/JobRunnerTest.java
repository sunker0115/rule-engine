package com.sstlfsj.rule.job.internal.runner;

import com.sstlfsj.rule.eval.api.service.EvalService;
import com.sstlfsj.rule.job.api.JobTarget;
import com.sstlfsj.rule.job.internal.domain.JobDefinition;
import com.sstlfsj.rule.job.internal.domain.JobExecution;
import com.sstlfsj.rule.job.internal.repository.JobExecutionMapper;
import com.sstlfsj.rule.job.internal.subject.SubjectQueryRunner;
import com.sstlfsj.rule.kernel.api.model.EventSource;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JobRunnerTest {

    @Mock
    SubjectQueryRunner subjectQueryRunner;
    @Mock
    EvalService evalService;
    @Mock
    JobExecutionMapper executionMapper;

    JobRunner runner;

    @BeforeEach
    void setUp() {
        runner = new JobRunner(subjectQueryRunner, evalService, executionMapper);
        // insert 时回填 id（jobRunId 依赖 exec.getId()）
        when(executionMapper.insert(any(JobExecution.class))).thenAnswer(inv -> {
            ((JobExecution) inv.getArgument(0)).setId(99L);
            return 1;
        });
    }

    private JobDefinition def() {
        JobDefinition d = new JobDefinition();
        d.setId(7L);
        d.setTenantId(1L);
        d.setSceneCode("s1");
        d.setEventType("trade.completed");
        d.setSubjectQuery("{\"type\":\"BEAN_METHOD\",\"ref\":\"a#b\"}");
        d.setCode("j1");
        return d;
    }

    @Test
    void allAcceptedResultsInSuccess() {
        when(subjectQueryRunner.query(any())).thenReturn(
                List.of(JobTarget.of("u1"), JobTarget.of("u2")));
        when(evalService.acceptEvent(any())).thenReturn(true);

        JobExecution exec = runner.run(def());

        assertEquals("SUCCESS", exec.getStatus());
        assertEquals(2, exec.getSubjectCount());
        assertEquals(2, exec.getSuccessCount());
        assertEquals(0, exec.getErrorCount());
        verify(executionMapper).insert(any(JobExecution.class));
        verify(executionMapper).updateById(any(JobExecution.class));
    }

    @Test
    void partialRejectionResultsInPartialFail() {
        when(subjectQueryRunner.query(any())).thenReturn(
                List.of(JobTarget.of("u1"), JobTarget.of("u2")));
        when(evalService.acceptEvent(any())).thenReturn(true, false);

        JobExecution exec = runner.run(def());

        assertEquals("PARTIAL_FAIL", exec.getStatus());
        assertEquals(1, exec.getSuccessCount());
        assertEquals(1, exec.getErrorCount());
    }

    @Test
    void allRejectedResultsInFailed() {
        when(subjectQueryRunner.query(any())).thenReturn(List.of(JobTarget.of("u1")));
        when(evalService.acceptEvent(any())).thenReturn(false);

        JobExecution exec = runner.run(def());

        assertEquals("FAILED", exec.getStatus());
        assertEquals(0, exec.getSuccessCount());
        assertEquals(1, exec.getErrorCount());
    }

    @Test
    void subjectQueryFailureResultsInFailedWithoutInjection() {
        when(subjectQueryRunner.query(any())).thenThrow(new IllegalArgumentException("bad ref"));

        JobExecution exec = runner.run(def());

        assertEquals("FAILED", exec.getStatus());
        assertEquals(0, exec.getSubjectCount());
        verifyNoInteractions(evalService);
    }

    @Test
    void synthesizedRuleEventHasCorrectFieldsAndJobSource() {
        // 目标携带 payload + providedMetrics，应原样透传进合成事件；渠道由 JobRunner 设为 JOB
        JobTarget target = JobTarget.of("u1", Map.of("k", "v"))
                .withProvidedMetrics(Map.of("score", 0.9));
        when(subjectQueryRunner.query(any())).thenReturn(List.of(target));
        when(evalService.acceptEvent(any())).thenReturn(true);

        runner.run(def());

        ArgumentCaptor<RuleEvent> cap = ArgumentCaptor.forClass(RuleEvent.class);
        verify(evalService).acceptEvent(cap.capture());
        RuleEvent e = cap.getValue();
        assertEquals("1", e.tenantId());
        assertEquals("s1", e.sceneCode());
        assertEquals("trade.completed", e.eventType());
        assertEquals("u1", e.subjectId());
        assertEquals(EventIdHasher.hash(99L, "u1"), e.eventId());
        assertEquals("v", e.payload().get("k"));
        assertEquals(0.9, e.providedMetrics().get("score"));
        assertEquals(EventSource.JOB, e.source());
        assertNotNull(e.occurredAt());
    }
}
