package com.sstlfsj.rule.eval.internal.outcome;

import com.sstlfsj.rule.eval.api.service.OutcomeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/** OutcomeService 实现：同步事务 upsert（与评估期 C 类 async best-effort 不同，回灌须落库确认）。 */
@Service
@RequiredArgsConstructor
public class OutcomeServiceImpl implements OutcomeService {

    private final DecisionOutcomeMapper mapper;

    @Override
    @Transactional
    public int recordOutcomes(Long tenantId, List<OutcomeRecord> outcomes) {
        if (outcomes == null || outcomes.isEmpty()) return 0;
        List<DecisionOutcome> rows = outcomes.stream().map(r -> toRow(tenantId, r)).toList();
        mapper.upsertBatch(rows);
        return rows.size();
    }

    private DecisionOutcome toRow(Long tenantId, OutcomeRecord r) {
        DecisionOutcome o = new DecisionOutcome();
        o.setTenantId(tenantId);
        o.setEventId(r.eventId());
        o.setOutcomeLabel(r.outcomeLabel());
        o.setOutcomeValue(r.outcomeValue());
        o.setOutcomeNote(r.note());
        // labeledAt 转 LocalDateTime（与 evaluation_session.occurred_at 同口径：systemDefault）
        o.setLabeledAt(LocalDateTime.ofInstant(r.labeledAt(), ZoneId.systemDefault()));
        o.setSource(r.source());
        return o;
    }
}
