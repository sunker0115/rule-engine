package com.sstlfsj.rule.config.internal.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** rule_version 表实体，不可变（发布后禁止 UPDATE/DELETE）。 */
@Getter
@Setter
@TableName("rule_version")
public class RuleVersion {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long ruleDefinitionId;
    private Long version;
    private String conditionAst;
    private String decisionBindings;
    private String preGates;
    private String rollout;
    private String kind;
    private String triggerEventTypes;
    private String metricDependencies;
    private String status;
    private String publishedBy;
    private java.time.LocalDateTime publishedAt;
    private java.time.LocalDateTime createdAt;
}
