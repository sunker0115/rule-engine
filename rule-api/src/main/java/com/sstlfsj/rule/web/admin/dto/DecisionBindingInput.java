package com.sstlfsj.rule.web.admin.dto;

/**
 * 创建规则时的决策绑定入参。priority 不在此处——它属于 Decision 实体（Tenant 级，D26），
 * 发布时引擎从 decision_definition.priority 回填进快照。故创建态只引用 decisionCode。
 *
 * @param decisionCode 引用的 Decision 码（必填）
 */
public record DecisionBindingInput(String decisionCode) {}
