package com.sstlfsj.rule.eval.internal.retention;

import com.sstlfsj.rule.eval.internal.repository.EvaluationSessionMapper;
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

/** SessionRetentionCleaner 调度清理逻辑验证（cutoff 计算 + 分批循环终止）。 */
class SessionRetentionCleanerTest {

    private final EvaluationSessionMapper evaluationSessionMapper = mock(EvaluationSessionMapper.class);

    private RetentionProperties props() {
        RetentionProperties p = new RetentionProperties();
        p.setEvaluationSessionDays(90);
        p.setBatchSize(1000);
        return p;
    }

    @Test
    void purge_callsMapper_withCutoffByRetentionWindow() {
        RetentionProperties props = props();
        // 单批返回 0（< batchSize）使循环只跑一轮
        when(evaluationSessionMapper.purgeOlderThan(org.mockito.ArgumentMatchers.any(), anyInt())).thenReturn(0);

        LocalDateTime before = LocalDateTime.now();
        new SessionRetentionCleaner(evaluationSessionMapper, props).purge();
        LocalDateTime after = LocalDateTime.now();

        ArgumentCaptor<LocalDateTime> esCutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(evaluationSessionMapper).purgeOlderThan(esCutoff.capture(), eq(1000));
        // evaluation_session cutoff 应 ≈ now - 90d（落在调用前后两个边界内）
        assertThat(esCutoff.getValue()).isBetween(before.minusDays(90), after.minusDays(90));
    }

    @Test
    void purge_loopsUntilBatchBelowLimit() {
        RetentionProperties props = props();
        // 首批满（=batchSize）→ 继续；次批不足 → 停
        when(evaluationSessionMapper.purgeOlderThan(org.mockito.ArgumentMatchers.any(), anyInt()))
                .thenReturn(1000, 1000, 200);

        new SessionRetentionCleaner(evaluationSessionMapper, props).purge();

        // evaluation_session：满批两次 + 不足一次 = 3 次调用
        verify(evaluationSessionMapper, times(3)).purgeOlderThan(org.mockito.ArgumentMatchers.any(), eq(1000));
    }
}
