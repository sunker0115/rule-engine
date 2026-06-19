package com.sstlfsj.rule.eval.api.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** 决策结果标签回灌（B32）。业务侧把真实结局按 eventId 回灌，供效果聚合。 */
public interface OutcomeService {

    /**
     * 单条回灌标签。outcomeLabel 为业务自定义串，引擎不解释；labeledAt 为业务真值确定时刻。
     *
     * @param eventId      业务事件 id（关联 evaluation_session）
     * @param outcomeLabel 业务结果标签
     * @param outcomeValue 可选数值（如损失额），无则 null
     * @param labeledAt    标签时刻
     * @param source       回灌方标识，可空
     * @param note         备注，可空
     */
    record OutcomeRecord(String eventId, String outcomeLabel, BigDecimal outcomeValue,
                         Instant labeledAt, String source, String note) {}

    /**
     * 批量回灌结果标签，按 (tenantId, eventId) 幂等 upsert（重复覆盖）。
     * 不校验对应 evaluation_session 是否已存在（标签可能早于 session 到达或 session best-effort 丢失）。
     *
     * @param tenantId 租户 id
     * @param outcomes 待回灌标签列表（空列表返回 0）
     * @return 落库接受条数
     */
    int recordOutcomes(Long tenantId, List<OutcomeRecord> outcomes);
}
