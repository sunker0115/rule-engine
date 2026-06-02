package com.sstlfsj.rule.kernel.api.model.ast;

/** NOT 逻辑节点：对唯一子节点的结果取反。 */
public record NotNode(
        AstNode child
) implements AstNode {}
