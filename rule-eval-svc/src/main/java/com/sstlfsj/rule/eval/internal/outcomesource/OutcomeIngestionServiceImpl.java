package com.sstlfsj.rule.eval.internal.outcomesource;

import com.sstlfsj.rule.eval.api.service.IngestResult;
import com.sstlfsj.rule.eval.api.service.OutcomeIngestionService;
import com.sstlfsj.rule.eval.api.service.OutcomePullResult;
import com.sstlfsj.rule.eval.api.service.OutcomeService;
import com.sstlfsj.rule.eval.api.service.OutcomeSourceConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/** OutcomeIngestionService 实现:source 拉取 → OutcomeService upsert。 */
@Service
@RequiredArgsConstructor
public class OutcomeIngestionServiceImpl implements OutcomeIngestionService {

    private final OutcomeSourceRegistry sourceRegistry;
    private final OutcomeService outcomeService;

    @Override
    @Transactional
    public IngestResult ingest(Long tenantId, OutcomeSourceConfig source, Instant watermark) {
        OutcomePullResult pulled = sourceRegistry.pull(source, watermark, tenantId);
        int accepted = outcomeService.recordOutcomes(tenantId, pulled.records());
        return new IngestResult(accepted, pulled.newWatermark());
    }
}
