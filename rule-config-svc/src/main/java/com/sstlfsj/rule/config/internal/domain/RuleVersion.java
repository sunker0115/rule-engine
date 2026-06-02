package com.sstlfsj.rule.config.internal.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** MyBatis-Plus entity for the {@code rule_version} table, storing the serialized AST and gate configuration. */
@TableName("rule_version")
public class RuleVersion {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private Long ruleDefinitionId;
    private String conditionAstJson;
    private String preGatesJson;
    private String decisionBindingsJson;
    private String status;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public Long getRuleDefinitionId() { return ruleDefinitionId; }
    public void setRuleDefinitionId(Long ruleDefinitionId) { this.ruleDefinitionId = ruleDefinitionId; }
    public String getConditionAstJson() { return conditionAstJson; }
    public void setConditionAstJson(String conditionAstJson) { this.conditionAstJson = conditionAstJson; }
    public String getPreGatesJson() { return preGatesJson; }
    public void setPreGatesJson(String preGatesJson) { this.preGatesJson = preGatesJson; }
    public String getDecisionBindingsJson() { return decisionBindingsJson; }
    public void setDecisionBindingsJson(String decisionBindingsJson) { this.decisionBindingsJson = decisionBindingsJson; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
