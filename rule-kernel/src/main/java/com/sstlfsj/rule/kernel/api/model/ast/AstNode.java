package com.sstlfsj.rule.kernel.api.model.ast;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * 规则条件 AST 节点，用 sealed 限定所有子类型以支持 switch pattern matching。
 * Jackson 多态配置直接标注在接口上，使全局 ObjectMapper 和 AstJsonCodec 均可正确序列化/反序列化。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = AndNode.class,           name = "AndNode"),
        @JsonSubTypes.Type(value = OrNode.class,            name = "OrNode"),
        @JsonSubTypes.Type(value = NotNode.class,           name = "NotNode"),
        @JsonSubTypes.Type(value = ConditionNode.class,     name = "ConditionNode"),
        @JsonSubTypes.Type(value = ScorecardRootNode.class, name = "ScorecardRootNode"),
        @JsonSubTypes.Type(value = XorNode.class,           name = "XorNode"),
        @JsonSubTypes.Type(value = IfNode.class,            name = "IfNode"),
        @JsonSubTypes.Type(value = DecisionLeafNode.class,  name = "DecisionLeafNode"),
        @JsonSubTypes.Type(value = DecisionTableNode.class, name = "DecisionTableNode")
})
public sealed interface AstNode
        permits AndNode, OrNode, NotNode, ConditionNode, ScorecardRootNode, XorNode,
                IfNode, DecisionLeafNode, DecisionTableNode {}
