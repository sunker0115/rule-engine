package com.sstlfsj.rule.config.internal.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.Jackson3TypeHandler;
import com.sstlfsj.rule.kernel.api.model.MetricDependency;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot.DecisionBinding;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot.PreGateConfig;
import com.sstlfsj.rule.kernel.api.model.ast.AstNode;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/** rule_version 表实体，不可变（发布后禁止 UPDATE/DELETE）。 */
@Getter
@Setter
@TableName(value = "rule_version", autoResultMap = true)
public class RuleVersion {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long ruleDefinitionId;
    private Long version;
    @TableField(typeHandler = Jackson3TypeHandler.class)
    private AstNode conditionAst;
    @TableField(typeHandler = Jackson3TypeHandler.class)
    private List<DecisionBinding> decisionBindings;
    @TableField(typeHandler = Jackson3TypeHandler.class)
    private List<PreGateConfig> preGates;
    private String kind;
    @TableField(typeHandler = Jackson3TypeHandler.class)
    private List<String> triggerEventTypes;
    @TableField(typeHandler = Jackson3TypeHandler.class)
    private List<MetricDependency> metricDependencies;
    private String status;
    private String publishedBy;
    private java.time.LocalDateTime publishedAt;
    private java.time.LocalDateTime createdAt;
}
