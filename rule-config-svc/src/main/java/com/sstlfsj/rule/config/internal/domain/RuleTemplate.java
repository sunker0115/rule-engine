package com.sstlfsj.rule.config.internal.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.sstlfsj.rule.kernel.api.model.RuleKind;
import lombok.Getter;
import lombok.Setter;

/** rule_template 表身份实体（快照字段已迁至 RuleTemplateVersion）。 */
@Getter
@Setter
@TableName("rule_template")
public class RuleTemplate {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String code;
    private Long tenantId;
    private String name;
    private String description;
    private RuleKind kind;
    private TemplateStatus status;
    private String createdBy;
    private java.time.LocalDateTime createdAt;
    private String updatedBy;
    private java.time.LocalDateTime updatedAt;
}
