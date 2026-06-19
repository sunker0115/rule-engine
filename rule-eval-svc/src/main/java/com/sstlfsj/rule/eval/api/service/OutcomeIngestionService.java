package com.sstlfsj.rule.eval.api.service;

import java.time.Instant;

/** 回灌编排:从 source 拉 watermark 之后的标签并 upsert decision_outcome。 */
public interface OutcomeIngestionService {
    /**
     * 拉取并 upsert。
     * @param tenantId  租户 id
     * @param source    标签来源配置
     * @param watermark 上次水位(null=首次全量)
     * @return 落库条数 + 新水位
     */
    IngestResult ingest(Long tenantId, OutcomeSourceConfig source, Instant watermark);
}
