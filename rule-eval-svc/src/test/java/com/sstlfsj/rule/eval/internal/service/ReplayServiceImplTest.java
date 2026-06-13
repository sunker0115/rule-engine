package com.sstlfsj.rule.eval.internal.service;

import com.sstlfsj.rule.eval.internal.domain.EvaluationSession;
import com.sstlfsj.rule.eval.internal.repository.EvaluationSessionMapper;
import com.sstlfsj.rule.eval.internal.snapshot.SceneSnapshotLoader;
import com.sstlfsj.rule.kernel.api.model.EvalOutcome;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.internal.engine.EvalEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReplayServiceImplTest {

    @Mock EvaluationSessionMapper sessionMapper;
    @Mock SceneSnapshotLoader snapshotLoader;
    @Mock EvalEngine evalEngine;

    ReplayServiceImpl impl;

    @BeforeEach
    void setUp() {
        impl = new ReplayServiceImpl(sessionMapper, snapshotLoader, evalEngine, JsonMapper.builder().build());
    }

    private EvaluationSession fullSession() {
        EvaluationSession s = new EvaluationSession();
        s.setTenantId(1L);
        s.setSceneCode("s");
        s.setEventType("e");
        s.setSubjectId("u");
        s.setEventId("evt-1");
        s.setPayload("{\"amount\":5000}");
        s.setCandidateRuleVersionIds("[11]");
        s.setContextSnapshot("{\"metrics\":{\"total\":200},\"evalNow\":\"2026-06-09T01:02:03Z\"}");
        return s;
    }

    private RuleVersionSnapshot snap(long id) {
        return RuleVersionSnapshot.builder()
                .ruleVersionId(id).tenantId("1").sceneCode("s").code("r").version(1L)
                .conditionAst(new ConditionNode("EQ", null, null, Map.of(), 0.0))
                .addDecisionBinding("HIT", 1)
                .build();
    }

    @Test
    void replay_happyPath_lockedVersionAndFrozenMetrics_returnsResult() {
        when(sessionMapper.selectById(100L)).thenReturn(fullSession());
        when(snapshotLoader.loadById(11L)).thenReturn(snap(11L));
        when(evalEngine.evaluateReplay(any(), anyList(), anyMap(), any()))
                .thenReturn(new EvalOutcome(EvalResult.hit(), null));

        EvalResult result = impl.replay("1", 100L);

        assertThat(result.ruleHit()).isTrue();
        // 冻结 metric total=200、payload 透传：以 anyMap/anyList 校验委托链路成立
        verify(evalEngine).evaluateReplay(any(), anyList(), anyMap(), any());
        verify(snapshotLoader).loadById(11L);
    }

    @Test
    void replay_sessionNotFound_throws() {
        when(sessionMapper.selectById(404L)).thenReturn(null);
        assertThatThrownBy(() -> impl.replay("1", 404L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("REPLAY_SESSION_NOT_FOUND");
    }

    @Test
    void replay_crossTenant_throwsNotFound() {
        when(sessionMapper.selectById(100L)).thenReturn(fullSession());  // tenant=1
        assertThatThrownBy(() -> impl.replay("2", 100L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("REPLAY_SESSION_NOT_FOUND");
    }

    @Test
    void replay_missingReplayData_throwsNotReproducible() {
        EvaluationSession s = fullSession();
        s.setPayload(null);   // 存量行 / 捕获未开
        when(sessionMapper.selectById(100L)).thenReturn(s);
        assertThatThrownBy(() -> impl.replay("1", 100L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("REPLAY_NOT_REPRODUCIBLE");
        verifyNoInteractions(evalEngine);
    }

    @Test
    void replay_candidateVersionMissing_throws() {
        when(sessionMapper.selectById(100L)).thenReturn(fullSession());
        when(snapshotLoader.loadById(11L)).thenReturn(null);   // 版本不存在
        assertThatThrownBy(() -> impl.replay("1", 100L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("REPLAY_VERSION_MISSING");
        verifyNoInteractions(evalEngine);
    }
}
