package com.sstlfsj.rule.config.internal.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** decision_definition 表实体，D26/D27：Decision 是 Tenant 级实体。 */
@Getter
@Setter
@TableName(value = "decision_definition", autoResultMap = true)
public class DecisionDefinition {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String code;
    private String name;
    private Integer priority;
    private String description;
    private DecisionStatus status;
    private String createdBy;
    private java.time.LocalDateTime createdAt;
    private String updatedBy;
    private java.time.LocalDateTime updatedAt;
}
