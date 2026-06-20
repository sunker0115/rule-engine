package com.sstlfsj.rule.eval.internal.service;

import com.sstlfsj.rule.eval.api.service.OutcomeService;
import com.sstlfsj.rule.eval.internal.repository.DecisionOutcomeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** OutcomeService 实现：同步事务边界，单表转换+upsert 收敛在 Mapper default（与评估期 C 类 async best-effort 不同，回灌须落库确认）。 */
@Service
@RequiredArgsConstructor
public class OutcomeServiceImpl implements OutcomeService {

    private final DecisionOutcomeMapper mapper;

    @Override
    public List<String> availableLabels(Long tenantId) {
        return mapper.distinctLabels(tenantId);
    }

    @Override
    @Transactional
    public int recordOutcomes(Long tenantId, List<OutcomeRecord> outcomes) {
        return mapper.upsertOutcomes(tenantId, outcomes);
    }
}
