package com.sstlfsj.rule.config.internal.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.Jackson3TypeHandler;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot.DecisionAction;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

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
    /** actions 列表（含 actionId/actionType/sortOrder/params），JSON 列由 TypeHandler 转换。 */
    @TableField(typeHandler = Jackson3TypeHandler.class)
    private List<DecisionAction> actions;
    private String status;
    private String createdBy;
    private java.time.LocalDateTime createdAt;
    private String updatedBy;
    private java.time.LocalDateTime updatedAt;
}
