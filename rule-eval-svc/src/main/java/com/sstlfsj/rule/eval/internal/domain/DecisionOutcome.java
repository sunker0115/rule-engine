package com.sstlfsj.rule.eval.internal.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** decision_outcome 表实体（B32 标签回灌写模型，eval-svc 自有）。 */
@Getter
@Setter
@TableName("decision_outcome")
public class DecisionOutcome {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String eventId;
    private String outcomeLabel;
    private BigDecimal outcomeValue;
    private String outcomeNote;
    private LocalDateTime labeledAt;
    private String source;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
