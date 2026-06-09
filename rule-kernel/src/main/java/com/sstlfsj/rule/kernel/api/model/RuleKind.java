package com.sstlfsj.rule.kernel.api.model;

/** 规则/执行器种类(契约值,== DB rule_definition.kind / rule_version.kind ENUM,作 executor map key)。 */
public enum RuleKind {
    AST_BOOLEAN, SCORECARD, DECISION_TREE, DECISION_TABLE, EXPRESSION_SCRIPT;

    /** 持久化/序列化用的字符串标签(== 枚举名,与 DB ENUM 值一致)。 */
    public String tag() {
        return name();
    }
}
