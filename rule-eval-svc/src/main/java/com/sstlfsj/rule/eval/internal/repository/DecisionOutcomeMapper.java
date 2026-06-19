package com.sstlfsj.rule.eval.internal.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sstlfsj.rule.eval.api.service.OutcomeService.OutcomeRecord;
import com.sstlfsj.rule.eval.internal.domain.DecisionOutcome;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/** decision_outcome 写 Mapper：按 (tenant_id,event_id) 幂等 upsert。 */
@Mapper
public interface DecisionOutcomeMapper extends BaseMapper<DecisionOutcome> {

    /**
     * 把 api 层 {@link OutcomeRecord} 批量转实体并幂等 upsert（单表写逻辑收敛在 Mapper，service 不散拼）。
     * 空/ null 列表短路返回 0，不触发 DB。
     *
     * @param tenantId 租户 id
     * @param outcomes 待回灌结果标签
     * @return 落库接受条数
     */
    default int upsertOutcomes(Long tenantId, List<OutcomeRecord> outcomes) {
        if (outcomes == null || outcomes.isEmpty()) return 0;
        List<DecisionOutcome> rows = outcomes.stream().map(r -> toRow(tenantId, r)).toList();
        upsertBatch(rows);
        return rows.size();
    }

    /** OutcomeRecord → DecisionOutcome：labeledAt 的 Instant 按 systemDefault 转 LocalDateTime（与 occurred_at 同口径）。 */
    private static DecisionOutcome toRow(Long tenantId, OutcomeRecord r) {
        DecisionOutcome o = new DecisionOutcome();
        o.setTenantId(tenantId);
        o.setEventId(r.eventId());
        o.setOutcomeLabel(r.outcomeLabel());
        o.setOutcomeValue(r.outcomeValue());
        o.setOutcomeNote(r.note());
        o.setLabeledAt(LocalDateTime.ofInstant(r.labeledAt(), ZoneId.systemDefault()));
        o.setSource(r.source());
        return o;
    }

    /**
     * 批量 upsert：撞 uk_tenant_event 时覆盖 label/value/note/labeledAt/source（标签可修正），
     * updated_at 经 ON UPDATE 自动刷新。
     *
     * @param list 待回灌的结果标签（非空）
     * @return 影响行数（插入 +1 / 覆盖 +2，仅作 best-effort 日志参考）
     */
    /**
     * 查该租户已使用过的全部 outcome_label 去重列表（字母序），供前端 positiveLabels Select 候选。
     *
     * @param tenantId 租户 id
     * @return 去重后的标签列表
     */
    default List<String> distinctLabels(Long tenantId) {
        return selectObjs(new LambdaQueryWrapper<DecisionOutcome>()
                .select(DecisionOutcome::getOutcomeLabel)
                .eq(DecisionOutcome::getTenantId, tenantId)
                .groupBy(DecisionOutcome::getOutcomeLabel)
                .orderByAsc(DecisionOutcome::getOutcomeLabel))
                .stream().map(Object::toString).toList();
    }

    @Insert("""
            <script>
            INSERT INTO decision_outcome
              (tenant_id, event_id, outcome_label, outcome_value, outcome_note, labeled_at, source)
            VALUES
            <foreach collection="list" item="o" separator=",">
              (#{o.tenantId}, #{o.eventId}, #{o.outcomeLabel}, #{o.outcomeValue},
               #{o.outcomeNote}, #{o.labeledAt}, #{o.source})
            </foreach>
            ON DUPLICATE KEY UPDATE
              outcome_label = VALUES(outcome_label),
              outcome_value = VALUES(outcome_value),
              outcome_note  = VALUES(outcome_note),
              labeled_at    = VALUES(labeled_at),
              source        = VALUES(source)
            </script>
            """)
    int upsertBatch(@Param("list") List<DecisionOutcome> list);
}
