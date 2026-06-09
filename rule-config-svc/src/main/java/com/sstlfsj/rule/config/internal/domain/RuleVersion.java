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

    /**
     * 构造首版草稿（version=1，status=DRAFT）。conditionAst 为 null 时兜底为空 AndNode，
     * 各 list 为 null 时兜底为空列表，metricDependencies 固定为空（草稿尚未冻结依赖）。
     *
     * @param ruleDefinitionId  所属规则定义 id
     * @param conditionAst      条件 AST，null 视为空 AndNode
     * @param decisionBindings  决策绑定列表，null 视为空
     * @param preGates          前置门控列表，null 视为空
     * @param triggerEventTypes 触发事件类型列表，null 视为空
     * @param kind              规则类型标签
     * @return 首版草稿 RuleVersion（id 由插入时回填）
     */
    public static RuleVersion draftV1(Long ruleDefinitionId, AstNode conditionAst,
                                      List<DecisionBinding> decisionBindings, List<PreGateConfig> preGates,
                                      List<String> triggerEventTypes, String kind) {
        RuleVersion rv = new RuleVersion();
        rv.setRuleDefinitionId(ruleDefinitionId);
        rv.setVersion(1L);
        rv.setConditionAst(conditionAst != null ? conditionAst
                : new com.sstlfsj.rule.kernel.api.model.ast.AndNode(List.of(), null, null));
        rv.setDecisionBindings(decisionBindings != null ? decisionBindings : List.of());
        rv.setPreGates(preGates != null ? preGates : List.of());
        rv.setKind(kind);
        rv.setTriggerEventTypes(triggerEventTypes != null ? triggerEventTypes : List.of());
        rv.setMetricDependencies(List.of());
        rv.setStatus(RuleVersionStatus.DRAFT.name());
        rv.setCreatedAt(java.time.LocalDateTime.now());
        return rv;
    }
}
