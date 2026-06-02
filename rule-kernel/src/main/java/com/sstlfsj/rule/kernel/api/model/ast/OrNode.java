package com.sstlfsj.rule.kernel.api.model.ast;

import java.util.List;

/** OR 逻辑节点：任一子节点为 true 即满足，支持短路求值。 */
public record OrNode(
        List<AstNode> children,
        String displayLabel,
        Double weight
) implements AstNode {
    public OrNode {
        children = List.copyOf(children);
    }
}
