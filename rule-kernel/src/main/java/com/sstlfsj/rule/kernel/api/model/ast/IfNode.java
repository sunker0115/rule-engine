package com.sstlfsj.rule.kernel.api.model.ast;

/**
 * DECISION_TREE 分支节点：condition 为 true 走 thenBranch，否则走 elseBranch（null 表示未命中）。
 */
public record IfNode(
        AstNode condition,
        AstNode thenBranch,
        AstNode elseBranch
) implements AstNode {}
