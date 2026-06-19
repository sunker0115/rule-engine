package com.sstlfsj.rule.job.internal.runner;

import com.sstlfsj.rule.job.api.SubjectTarget;
import com.sstlfsj.rule.job.api.TaskExecutionStatus;
import com.sstlfsj.rule.job.api.TaskRunResult;
import com.sstlfsj.rule.job.api.TriggerConfig;
import com.sstlfsj.rule.eval.api.service.EvalService;
import com.sstlfsj.rule.job.internal.subject.SubjectQueryRunner;
import org.junit.jupiter.api.Test;

import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TriggerExecutorTest {

    private final SubjectQueryRunner subjectRunner = mock(SubjectQueryRunner.class);
    private final EvalService evalService = mock(EvalService.class);
    // injectMaxRetry=1, backoff=0：partial-failure 用例不 sleep
    private final TriggerExecutor executor =
            new TriggerExecutor(subjectRunner, evalService, 1, 0);

    @Test
    void allSubjectsInjected_success() {
        doAnswer(inv -> {
            Consumer<SubjectTarget> sink = inv.getArgument(1);
            sink.accept(SubjectTarget.of("u1"));
            sink.accept(SubjectTarget.of("u2"));
            return null;
        }).when(subjectRunner).forEachTarget(any(), any());
        when(evalService.acceptEvent(any())).thenReturn(true);

        TaskRunResult r = executor.execute(1L, 7L, new TriggerConfig("s", "e", null));

        assertThat(r.status()).isEqualTo(TaskExecutionStatus.SUCCESS);
        assertThat(r.processedCount()).isEqualTo(2);
        assertThat(r.successCount()).isEqualTo(2);
        assertThat(r.errorCount()).isZero();
        assertThat(r.errorSummary()).isNull();
    }

    @Test
    void partialInjectFailure_partialFail() {
        doAnswer(inv -> {
            Consumer<SubjectTarget> sink = inv.getArgument(1);
            sink.accept(SubjectTarget.of("u1"));
            sink.accept(SubjectTarget.of("u2"));
            return null;
        }).when(subjectRunner).forEachTarget(any(), any());
        when(evalService.acceptEvent(any())).thenReturn(true).thenReturn(false);

        TaskRunResult r = executor.execute(1L, 7L, new TriggerConfig("s", "e", null));

        assertThat(r.status()).isEqualTo(TaskExecutionStatus.PARTIAL_FAIL);
        assertThat(r.successCount()).isEqualTo(1);
        assertThat(r.errorCount()).isEqualTo(1);
        assertThat(r.errorSummary()).contains("注入失败");
    }

    @Test
    void allInjectFailure_failed() {
        doAnswer(inv -> {
            Consumer<SubjectTarget> sink = inv.getArgument(1);
            sink.accept(SubjectTarget.of("u1"));
            return null;
        }).when(subjectRunner).forEachTarget(any(), any());
        when(evalService.acceptEvent(any())).thenReturn(false);

        TaskRunResult r = executor.execute(1L, 7L, new TriggerConfig("s", "e", null));

        assertThat(r.status()).isEqualTo(TaskExecutionStatus.FAILED);
        assertThat(r.successCount()).isZero();
        assertThat(r.errorCount()).isEqualTo(1);
    }

    @Test
    void subjectQueryThrows_failed() {
        doAnswer(inv -> {
            throw new IllegalStateException("查询炸了");
        }).when(subjectRunner).forEachTarget(any(), any());

        TaskRunResult r = executor.execute(1L, 7L, new TriggerConfig("s", "e", null));

        assertThat(r.status()).isEqualTo(TaskExecutionStatus.FAILED);
        assertThat(r.processedCount()).isZero();
        assertThat(r.errorSummary()).contains("主体查询失败");
    }
}
