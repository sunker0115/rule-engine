package com.sstlfsj.rule.kernel.api.model.ast;

import java.util.List;

/**
 * XOR 逻辑节点：子节点中有且仅有一个求值为 true 时，整个节点才为 true。
 * 属于 AST_BOOLEAN kind 的内置逻辑节点，不短路，全量遍历所有子节点。
 */
public record XorNode(
        List<AstNode> children,
        /** 给运营 UI 看的分组标题，评估时忽略。 */
        String displayLabel
) implements AstNode {
    public XorNode {
        children = children == null ? List.of() : List.copyOf(children);
    }
}
