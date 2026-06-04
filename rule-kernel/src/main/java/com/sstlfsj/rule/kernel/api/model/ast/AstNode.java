package com.sstlfsj.rule.kernel.api.model.ast;

/** 规则条件 AST 节点，用 sealed 限定所有子类型以支持 switch pattern matching。 */
public sealed interface AstNode
        permits AndNode, OrNode, NotNode, ConditionNode, ScorecardRootNode, XorNode {}
