package com.sstlfsj.rule.kernel.api.model.ast;

import java.util.List;

/** AND 逻辑节点：所有子节点均为 true 时才满足，支持短路求值。 */
public record AndNode(
        List<AstNode> children,
        String displayLabel,
        Double weight
) implements AstNode {
    public AndNode {
        children = List.copyOf(children);
    }
}
