package com.sstlfsj.rule.kernel.api.model;

/**
 * AST trace 节点类型标签的单一来源（前端展示 + 持久化契约）。
 * tag 为落库/序列化用的字符串值，{@link NodeTrace#nodeType()} 存的就是该 tag。
 */
public enum NodeType {
    AND("AndNode"),
    OR("OrNode"),
    NOT("NotNode"),
    XOR("XorNode"),
    IF("IfNode"),
    CONDITION("ConditionNode"),
    DECISION_LEAF("DecisionLeafNode"),
    DECISION_TABLE_ROW("DecisionTableRow"),
    SCORECARD_ROOT("ScorecardRoot");

    private final String tag;

    NodeType(String tag) {
        this.tag = tag;
    }

    /** 落库/序列化用的字符串标签。 */
    public String tag() {
        return tag;
    }
}
