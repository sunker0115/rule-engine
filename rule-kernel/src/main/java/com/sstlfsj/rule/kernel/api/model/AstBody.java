package com.sstlfsj.rule.kernel.api.model;

import com.sstlfsj.rule.kernel.api.model.ast.AstNode;

/**
 * AST 系规则载体：AST_BOOLEAN / SCORECARD / DECISION_TREE / DECISION_TABLE 共用。
 *
 * @param conditionAst 条件 AST 根节点；可为 null（表示空 AST）
 */
public record AstBody(AstNode conditionAst) implements RuleBody {
}
