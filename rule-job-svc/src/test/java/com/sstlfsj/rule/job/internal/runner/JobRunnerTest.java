package com.sstlfsj.rule.job.internal.runner;

import com.sstlfsj.rule.eval.api.service.EvalService;
import com.sstlfsj.rule.job.internal.domain.JobDefinition;
import com.sstlfsj.rule.job.internal.domain.JobExecution;
import com.sstlfsj.rule.job.internal.repository.JobExecutionMapper;
import com.sstlfsj.rule.job.internal.subject.SubjectQueryRunner;
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
    PayloadTemplateRenderer payloadRenderer;
    @Mock
    EvalService evalService;
    @Mock
    JobExecutionMapper executionMapper;

    JobRunner runner;

    @BeforeEach
    void setUp() {
        runner = new JobRunner(subjectQueryRunner, payloadRenderer, evalService, executionMapper);
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
        d.setSubjectQuery("{\"type\":\"SQL\",\"sql\":\"x\"}");
        d.setCode("j1");
        return d;
    }

    @Test
    void allAcceptedResultsInSuccess() {
        when(subjectQueryRunner.query(any())).thenReturn(
                List.of(Map.of("subjectId", "u1"), Map.of("subjectId", "u2")));
        when(payloadRenderer.render(any(), any())).thenReturn(Map.of());
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
                List.of(Map.of("subjectId", "u1"), Map.of("subjectId", "u2")));
        when(payloadRenderer.render(any(), any())).thenReturn(Map.of());
        when(evalService.acceptEvent(any())).thenReturn(true, false);

        JobExecution exec = runner.run(def());

        assertEquals("PARTIAL_FAIL", exec.getStatus());
        assertEquals(1, exec.getSuccessCount());
        assertEquals(1, exec.getErrorCount());
    }

    @Test
    void allRejectedResultsInFailed() {
        when(subjectQueryRunner.query(any())).thenReturn(List.of(Map.of("subjectId", "u1")));
        when(payloadRenderer.render(any(), any())).thenReturn(Map.of());
        when(evalService.acceptEvent(any())).thenReturn(false);

        JobExecution exec = runner.run(def());

        assertEquals("FAILED", exec.getStatus());
        assertEquals(0, exec.getSuccessCount());
        assertEquals(1, exec.getErrorCount());
    }

    @Test
    void subjectQueryFailureResultsInFailedWithoutInjection() {
        when(subjectQueryRunner.query(any())).thenThrow(new IllegalArgumentException("bad sql"));

        JobExecution exec = runner.run(def());

        assertEquals("FAILED", exec.getStatus());
        assertEquals(0, exec.getSubjectCount());
        verifyNoInteractions(evalService);
    }

    @Test
    void synthesizedRuleEventHasCorrectFields() {
        when(subjectQueryRunner.query(any())).thenReturn(List.of(Map.of("subjectId", "u1")));
        when(payloadRenderer.render(any(), any())).thenReturn(Map.of("k", "v"));
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
        assertNotNull(e.occurredAt());
    }
}
