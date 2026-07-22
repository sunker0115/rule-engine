package com.sstlfsj.rule.config.internal.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.Jackson3TypeHandler;
import com.sstlfsj.rule.kernel.api.model.MetricDependency;
import com.sstlfsj.rule.kernel.api.model.PayloadDependency;
import com.sstlfsj.rule.kernel.api.model.RuleKind;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.ScriptSource;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot.DecisionBinding;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot.PreGateConfig;
import com.sstlfsj.rule.kernel.api.model.ast.AstNode;
import com.sstlfsj.rule.kernel.api.model.flow.FlowGraph;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;

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
    private RuleKind kind;
    @TableField(typeHandler = Jackson3TypeHandler.class)
    private List<String> triggerEventTypes;
    @TableField(typeHandler = Jackson3TypeHandler.class)
    private List<MetricDependency> metricDependencies;
    @TableField(typeHandler = Jackson3TypeHandler.class)
    private List<PayloadDependency> payloadDependencies;
    /** EXPRESSION_SCRIPT 规则的脚本载体;其它 kind 为 null。 */
    @TableField(typeHandler = Jackson3TypeHandler.class)
    private ScriptSource scriptSource;
    /** DECISION_FLOW 规则的决策图;其它 kind 为 null。与 conditionAst/scriptSource 三选一。 */
    @TableField(typeHandler = Jackson3TypeHandler.class)
    private FlowGraph flowGraph;
    /** DECISION_FLOW 发布期冻结的被引规则快照(ruleCode → 冻结 snapshot);其它 kind 为 null。 */
    @TableField(typeHandler = Jackson3TypeHandler.class)
    private Map<String, RuleVersionSnapshot> referencedSnapshots;
    private RuleVersionStatus status;
    private String publishedBy;
    private java.time.LocalDateTime publishedAt;
    private java.time.LocalDateTime createdAt;
}
