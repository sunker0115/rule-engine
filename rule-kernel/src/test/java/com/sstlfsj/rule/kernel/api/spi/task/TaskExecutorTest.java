package com.sstlfsj.rule.kernel.api.spi.task;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** TaskExecutor SPI 形状 + TaskRunContext/TaskRunResult 契约(open string type + cursor 经 ctx/result 流转)。 */
class TaskExecutorTest {

    private record DemoConfig(String foo) {}

    private static final class DemoExecutor implements TaskExecutor<DemoConfig> {
        @Override public String type() { return "DEMO"; }
        @Override public Class<DemoConfig> configType() { return DemoConfig.class; }
        @Override public TaskRunResult execute(TaskRunContext ctx, DemoConfig config) {
            // 回声:把 cursor 透传到 newCursor,证明 cursor 经 ctx 入、经 result 出
            return new TaskRunResult(TaskExecutionStatus.SUCCESS, 1, 1, 0, null, ctx.cursor());
        }
    }

    @Test
    void executor_declaresOpenStringTypeAndConfigType() {
        TaskExecutor<DemoConfig> ex = new DemoExecutor();
        assertThat(ex.type()).isEqualTo("DEMO");
        assertThat(ex.configType()).isEqualTo(DemoConfig.class);
    }

    @Test
    void cursorFlowsThroughContextAndResult() {
        TaskExecutor<DemoConfig> ex = new DemoExecutor();
        TaskRunContext ctx = new TaskRunContext(1L, 2L, 7L, "2026-06-19T00:00:00Z");

        TaskRunResult r = ex.execute(ctx, new DemoConfig("x"));

        assertThat(ctx.cursor()).isEqualTo("2026-06-19T00:00:00Z");
        assertThat(r.newCursor()).isEqualTo("2026-06-19T00:00:00Z");
        assertThat(r.status()).isEqualTo(TaskExecutionStatus.SUCCESS);
    }

    @Test
    void result_carriesCountsAndNullableNewCursor() {
        TaskRunResult r = new TaskRunResult(TaskExecutionStatus.PARTIAL_FAIL, 3, 2, 1, "1 failed", null);
        assertThat(r.processedCount()).isEqualTo(3);
        assertThat(r.successCount()).isEqualTo(2);
        assertThat(r.errorCount()).isEqualTo(1);
        assertThat(r.errorSummary()).isEqualTo("1 failed");
        assertThat(r.newCursor()).isNull();
    }

    @Test
    void context_nullableCursor() {
        TaskRunContext ctx = new TaskRunContext(10L, 20L, 30L, null);
        assertThat(ctx.cursor()).isNull();
        assertThat(ctx.taskRunId()).isEqualTo(10L);
        assertThat(ctx.taskId()).isEqualTo(20L);
        assertThat(ctx.tenantId()).isEqualTo(30L);
    }
}
