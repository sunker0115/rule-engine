package com.sstlfsj.rule.eval.internal.service;

import com.sstlfsj.rule.eval.api.service.ReplayService;
import com.sstlfsj.rule.eval.internal.async.ContextSnapshotDeserializer;
import com.sstlfsj.rule.eval.internal.domain.EvaluationSession;
import com.sstlfsj.rule.eval.internal.repository.EvaluationSessionMapper;
import com.sstlfsj.rule.eval.internal.snapshot.SceneSnapshotLoader;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.EventSource;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.internal.engine.EvalEngine;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 忠实重放实现：读 session → 按 id 加载历史候选快照 → 回灌 metric/evalNow/payload → evaluateReplay，零落库。
 */
@Service
@RequiredArgsConstructor
public class ReplayServiceImpl implements ReplayService {

    private final EvaluationSessionMapper sessionMapper;
    private final SceneSnapshotLoader snapshotLoader;
    private final EvalEngine evalEngine;
    private final ObjectMapper objectMapper;

    @Override
    public EvalResult replay(String tenantId, Long sessionId) {
        EvaluationSession s = sessionMapper.selectById(sessionId);
        if (s == null || !String.valueOf(s.getTenantId()).equals(tenantId)) {
            throw new IllegalArgumentException("REPLAY_SESSION_NOT_FOUND: " + sessionId);
        }
        if (s.getPayload() == null || s.getCandidateRuleVersionIds() == null || s.getContextSnapshot() == null) {
            throw new IllegalArgumentException(
                    "REPLAY_NOT_REPRODUCIBLE: 缺少 payload/候选版本id/context_snapshot(存量行或捕获未开启)");
        }

        List<Long> candidateIds = objectMapper.readValue(
                s.getCandidateRuleVersionIds(), new TypeReference<List<Long>>() {});
        Map<String, Object> payload = objectMapper.readValue(
                s.getPayload(), new TypeReference<Map<String, Object>>() {});
        ContextSnapshotDeserializer.Snapshot snap =
                ContextSnapshotDeserializer.deserialize(objectMapper, s.getContextSnapshot());

        List<RuleVersionSnapshot> candidates = new ArrayList<>(candidateIds.size());
        for (Long id : candidateIds) {
            RuleVersionSnapshot rv = snapshotLoader.loadById(id);
            if (rv == null) {
                throw new IllegalArgumentException("REPLAY_VERSION_MISSING: ruleVersionId=" + id);
            }
            candidates.add(rv);
        }

        Instant evalNow = snap.evalNow() != null ? snap.evalNow() : Instant.now();
        RuleEvent event = RuleEvent.builder()
                .tenantId(String.valueOf(s.getTenantId()))
                .sceneCode(s.getSceneCode())
                .eventType(s.getEventType())
                .subjectId(s.getSubjectId())
                .eventId(s.getEventId())
                .occurredAt(evalNow)
                .payload(payload)
                .source(EventSource.REPLAY)
                .build();

        // 历史 metric(snapshot 的 {code:rawValue})作 providedMetrics 回灌；evaluateReplay 内部 degraded assembler 不取数
        return evalEngine.evaluateReplay(event, candidates, snap.metrics(), evalNow).result();
    }
}
