package com.sstlfsj.rule.kernel.api.model;

/**
 * flow 编排节点的 trace 类型标签，与 {@link NodeType}（AST 节点词表）平级。
 * DECISION_FLOW 是 script 范式的平级独立承载，不进 AST 词表，故另立枚举；
 * {@code NodeTrace.nodeType} 为 String，原样承载本枚举 tag，前端 trace-tree 复用渲染。
 */
public enum FlowNodeType {
    RULEREF("RuleRefNode"),
    SWITCH("SwitchNode"),
    TRANSFORM("TransformNode"),
    OUTPUT("OutputNode");

    private final String tag;

    FlowNodeType(String tag) {
        this.tag = tag;
    }

    /** 落库/序列化用的字符串标签。 */
    public String tag() {
        return tag;
    }
}
