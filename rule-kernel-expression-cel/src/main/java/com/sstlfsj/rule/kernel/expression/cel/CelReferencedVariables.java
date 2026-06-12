package com.sstlfsj.rule.kernel.expression.cel;

import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.ast.CelExpr;
import dev.cel.common.navigation.CelNavigableAst;

import java.util.LinkedHashSet;
import java.util.Set;

/** 从 dev.cel checked AST 抽取 metrics/payload/subject 命名空间下的字段选择(点路径),供发布期冻依赖。 */
public final class CelReferencedVariables {

    private static final Set<String> NAMESPACES = Set.of("metrics", "payload", "subject");

    private CelReferencedVariables() {}

    /**
     * 抽取形如 "metrics.x" / "payload.x" / "subject.x" 的引用点路径。
     *
     * @param ast dev.cel 编译后的 AST
     * @return 引用点路径集合(命名空间外的标识/选择忽略)
     */
    public static Set<String> from(CelAbstractSyntaxTree ast) {
        Set<String> out = new LinkedHashSet<>();
        CelNavigableAst.fromAst(ast).getRoot().allNodes()
                .map(node -> node.expr())
                .filter(expr -> expr.exprKind().getKind() == CelExpr.ExprKind.Kind.SELECT)
                .forEach(expr -> {
                    CelExpr.CelSelect select = expr.select();
                    CelExpr operand = select.operand();
                    // 仅收 <namespace>.<field>:operand 是命名空间 IDENT
                    if (operand.exprKind().getKind() == CelExpr.ExprKind.Kind.IDENT
                            && NAMESPACES.contains(operand.ident().name())) {
                        out.add(operand.ident().name() + "." + select.field());
                    }
                });
        return out;
    }
}
