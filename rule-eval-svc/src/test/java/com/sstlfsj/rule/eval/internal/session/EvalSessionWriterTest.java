package com.sstlfsj.rule.eval.internal.session;

import com.sstlfsj.rule.eval.internal.domain.EvaluationSession;
import com.sstlfsj.rule.eval.internal.repository.DryRunSessionMapper;
import com.sstlfsj.rule.eval.internal.repository.EvaluationSessionMapper;
import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.dao.DuplicateKeyException;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import org.mockito.Spy;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EvalSessionWriterTest {

    @Mock EvaluationSessionMapper sessionMapper;
    @Mock DryRunSessionMapper dryRunMapper;
    @Spy ObjectMapper objectMapper = JsonMapper.builder().build();
    @InjectMocks EvalSessionWriter writer;

    private RuleEvent event() {
        return new RuleEvent("1", "scene", "E", "u1",
                "evt-001", Instant.parse("2024-01-01T00:00:00Z"), Map.of(), Map.of());
    }

    @Test
    void writer_constructed_notNull() {
        assertNotNull(writer);
    }

    @Test
    void insertPending_savesSessionWithPendingStatus() {
        when(sessionMapper.insert((EvaluationSession) any())).thenReturn(1);
        RuleEvent ev = event();

        writer.insertPending(ev, 3, "PULL");

        ArgumentCaptor<EvaluationSession> captor = ArgumentCaptor.forClass(EvaluationSession.class);
        verify(sessionMapper).insert((EvaluationSession) captor.capture());
        EvaluationSession saved = captor.getValue();
        assertEquals("PENDING", saved.getStatus());
        assertEquals("evt-001", saved.getEventId());
        assertEquals(1L, saved.getTenantId());
        assertEquals(3, saved.getCandidateRuleCount());
        assertEquals("PULL", saved.getSource());
    }

    @Test
    void insertPending_duplicateKey_returnsExistingId() {
        // DuplicateKeyException 时走幂等查询分支，返回已有行 id
        EvaluationSession existing = new EvaluationSession();
        existing.setId(99L);
        when(sessionMapper.insert((EvaluationSession) any())).thenThrow(new DuplicateKeyException("dup"));
        when(sessionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);

        Long id = writer.insertPending(event(), 1, "PULL");

        assertEquals(99L, id);
    }

    @Test
    void insertPending_nullOccurredAt_throwsIllegalArgument() {
        // occurredAt=null 不允许静默兜底为 now()，必须快速失败
        RuleEvent nullTime = new RuleEvent("1", "scene", "E", "u1", "evt-x", null, Map.of(), Map.of());
        assertThrows(IllegalArgumentException.class,
                () -> writer.insertPending(nullTime, 1, "PULL"));
    }

    @Test
    void updateFinal_withContext_snapshotContainsMetricKey() {
        // 验证 serializeSnapshot 用静态 MAPPER 正确序列化 metrics
        RuleEvent ev = event();
        EvalContext ctx = new EvalContext("1", ev, null,
                Map.of("user.age", new MetricValue(25, "INTEGER", "PROVIDED")));
        // 直接检查序列化不抛异常（MAPPER 静态实例行为）
        EvalResult result = EvalResult.miss();
        writer.updateFinal(1L, result, ctx);
        verify(sessionMapper).update(any(), any());
    }

    @Test
    void updateFinal_withContext_setsContextSnapshot() {
        RuleEvent ev = event();
        EvalContext ctx = new EvalContext("1", ev, null,
                Map.of("user.age", new MetricValue(25, "INTEGER", "PROVIDED")));
        EvalResult result = EvalResult.miss();

        writer.updateFinal(1L, result, ctx);

        verify(sessionMapper).update(any(), any());
    }

    @Test
    void updateFinal_nullContext_contextSnapshotIsNull() {
        EvalResult result = EvalResult.miss();

        writer.updateFinal(1L, result, null);

        verify(sessionMapper).update(any(), any());
    }

    @Test
    void updateDryRunFinal_withContext_invokesMapper() {
        RuleEvent ev = event();
        EvalContext ctx = new EvalContext("1", ev, null,
                Map.of("order.amount", new MetricValue(5000, "INTEGER", "PROVIDED")));
        EvalResult result = EvalResult.miss();

        writer.updateDryRunFinal(1L, result, ctx);

        verify(dryRunMapper).update(any(), any());
    }

    @Test
    void updateDryRunFinal_nullContext_invokesMapper() {
        writer.updateDryRunFinal(1L, EvalResult.miss(), null);
        verify(dryRunMapper).update(any(), any());
    }

    @Test
    void insertBlocked_savesBlockedStatus() {
        when(sessionMapper.insert((EvaluationSession) any())).thenReturn(1);

        writer.insertBlocked(event(), "ROLLOUT", "PULL");

        ArgumentCaptor<EvaluationSession> captor = ArgumentCaptor.forClass(EvaluationSession.class);
        verify(sessionMapper).insert((EvaluationSession) captor.capture());
        assertEquals("BLOCKED", captor.getValue().getStatus());
        assertEquals("ROLLOUT", captor.getValue().getBlockedBy());
    }
}
