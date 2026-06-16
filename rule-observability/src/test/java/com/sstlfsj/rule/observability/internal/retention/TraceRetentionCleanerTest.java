package com.sstlfsj.rule.observability.internal.retention;

import com.sstlfsj.rule.observability.internal.repository.NodeTraceMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** TraceRetentionCleaner 调度清理逻辑验证（cutoff 计算 + 分批循环终止）。 */
class TraceRetentionCleanerTest {

    private final NodeTraceMapper nodeTraceMapper = mock(NodeTraceMapper.class);

    private RetentionProperties props() {
        RetentionProperties p = new RetentionProperties();
        p.setNodeTraceDays(30);
        p.setBatchSize(1000);
        return p;
    }

    @Test
    void purge_callsMapper_withCutoffByRetentionWindow() {
        RetentionProperties props = props();
        // 单批返回 0（< batchSize）使循环只跑一轮
        when(nodeTraceMapper.purgeOlderThan(org.mockito.ArgumentMatchers.any(), anyInt())).thenReturn(0);

        LocalDateTime before = LocalDateTime.now();
        new TraceRetentionCleaner(nodeTraceMapper, props).purge();
        LocalDateTime after = LocalDateTime.now();

        ArgumentCaptor<LocalDateTime> ntCutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(nodeTraceMapper).purgeOlderThan(ntCutoff.capture(), eq(1000));
        // node_trace cutoff 应 ≈ now - 30d（落在调用前后两个边界内）
        assertThat(ntCutoff.getValue()).isBetween(before.minusDays(30), after.minusDays(30));
    }

    @Test
    void purge_loopsUntilBatchBelowLimit() {
        RetentionProperties props = props();
        // 首批满（=batchSize）→ 继续；次批不足 → 停
        when(nodeTraceMapper.purgeOlderThan(org.mockito.ArgumentMatchers.any(), anyInt()))
                .thenReturn(1000, 1000, 200);

        new TraceRetentionCleaner(nodeTraceMapper, props).purge();

        // node_trace：满批两次 + 不足一次 = 3 次调用
        verify(nodeTraceMapper, times(3)).purgeOlderThan(org.mockito.ArgumentMatchers.any(), eq(1000));
    }
}
