package com.sstlfsj.rule.kernel.api.model.flow;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * 决策图编排节点，sealed 限定四种形态以支持 switch pattern matching。
 * Jackson 多态配置标注在接口上（判别字段 type == 简单类名），全局 ObjectMapper 与 AstJsonCodec 均可识别。
 * 与 {@code AstNode} 平级：flow 图只做编排，叶子逻辑由 {@link RuleRefNode} 引用的独立规则承载。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = RuleRefNode.class,   name = "RuleRefNode"),
        @JsonSubTypes.Type(value = SwitchNode.class,    name = "SwitchNode"),
        @JsonSubTypes.Type(value = TransformNode.class, name = "TransformNode"),
        @JsonSubTypes.Type(value = OutputNode.class,    name = "OutputNode")
})
public sealed interface FlowNode
        permits RuleRefNode, SwitchNode, TransformNode, OutputNode {

    /** 节点在图内的唯一 id；{@link FlowEdge} 的 from/to 引用它。 */
    String id();
}
