package com.sstlfsj.rule.eval.internal.outcome;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** decision_outcome 写 Mapper：按 (tenant_id,event_id) 幂等 upsert。 */
@Mapper
public interface DecisionOutcomeMapper extends BaseMapper<DecisionOutcome> {

    /**
     * 批量 upsert：撞 uk_tenant_event 时覆盖 label/value/note/labeledAt/source（标签可修正），
     * updated_at 经 ON UPDATE 自动刷新。
     *
     * @param list 待回灌的结果标签（非空）
     * @return 影响行数（插入 +1 / 覆盖 +2，仅作 best-effort 日志参考）
     */
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
