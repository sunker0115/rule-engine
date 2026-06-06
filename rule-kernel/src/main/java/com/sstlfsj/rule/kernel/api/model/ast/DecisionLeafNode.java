package com.sstlfsj.rule.kernel.api.model.ast;

/**
 * DECISION_TREE 叶子节点：携带命中后返回的决策码和分类标签。
 *
 * @param decisionCode 命中时返回的 Decision code，对应 DecisionBinding.decisionCode
 * @param category     分类标签（可与 decisionCode 相同，用于多标签场景；nullable）
 */
public record DecisionLeafNode(
        String decisionCode,
        String category
) implements AstNode {}
