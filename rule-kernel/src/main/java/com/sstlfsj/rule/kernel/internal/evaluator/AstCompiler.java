package com.sstlfsj.rule.kernel.internal.evaluator;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.ast.AndNode;
import com.sstlfsj.rule.kernel.api.model.ast.AstNode;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.model.ast.DecisionLeafNode;
import com.sstlfsj.rule.kernel.api.model.ast.DecisionTableNode;
import com.sstlfsj.rule.kernel.api.model.ast.IfNode;
import com.sstlfsj.rule.kernel.api.model.ast.NotNode;
import com.sstlfsj.rule.kernel.api.model.ast.OrNode;
import com.sstlfsj.rule.kernel.api.model.ast.ScorecardRootNode;
import com.sstlfsj.rule.kernel.api.model.ast.XorNode;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 把 AST_BOOLEAN 条件 AST 编译为嵌套 {@link Predicate} 闭包，替换解释器树遍历。
 * 语义逐一对齐 {@link InterpretedExecutor} 的布尔投影：叶子经 {@link ConditionEvaluation#satisfiesBoolean}
 * (metric ERROR / 无算子 → false)，且 evaluator 在编译期绑定(巨态分派下比每次查 map 更稳)；
 * XOR 全量求值恰一个真才命中。组合节点编译期收数组、求值期下标循环，避免 enhanced-for 的 Iterator 分配。
 */
public final class AstCompiler {

    /** conditionType → 算子映射。 */
    private final Map<String, ConditionEvaluator> evaluators;

    /**
     * @param evaluators conditionType 到 ConditionEvaluator 的映射
     */
    public AstCompiler(Map<String, ConditionEvaluator> evaluators) {
        this.evaluators = Map.copyOf(evaluators);
    }

    /**
     * 递归编译布尔节点为谓词。
     *
     * @param node 布尔 AST 节点(And/Or/Not/Xor/Condition)
     * @return 等价于解释器命中布尔的谓词
     * @throws IllegalArgumentException 遇非布尔节点(由专属 executor 处理)
     */
    public Predicate<EvalContext> compile(AstNode node) {
        return switch (node) {
            case ConditionNode c -> {
                // 编译期绑定 evaluator；satisfiesBoolean 镜像解释器布尔投影，不分配 ConditionOutcome
                ConditionEvaluator ev = evaluators.get(c.conditionType());
                yield ctx -> ConditionEvaluation.satisfiesBoolean(c, ctx, ev);
            }
            case AndNode a -> {
                Predicate<EvalContext>[] ps = compileChildren(a.children());
                yield ctx -> {
                    for (int i = 0; i < ps.length; i++) if (!ps[i].test(ctx)) return false;
                    return true;
                };
            }
            case OrNode o -> {
                Predicate<EvalContext>[] ps = compileChildren(o.children());
                yield ctx -> {
                    for (int i = 0; i < ps.length; i++) if (ps[i].test(ctx)) return true;
                    return false;
                };
            }
            case NotNode n -> {
                Predicate<EvalContext> p = compile(n.child());
                yield ctx -> !p.test(ctx);
            }
            case XorNode x -> {
                Predicate<EvalContext>[] ps = compileChildren(x.children());
                yield ctx -> {
                    int t = 0;
                    for (int i = 0; i < ps.length; i++) if (ps[i].test(ctx)) t++;
                    return t == 1;
                };
            }
            case ScorecardRootNode ignored ->
                    throw new IllegalArgumentException("ScorecardRootNode 非布尔节点，不可编译");
            case IfNode ignored ->
                    throw new IllegalArgumentException("IfNode 非布尔节点，不可编译");
            case DecisionLeafNode ignored ->
                    throw new IllegalArgumentException("DecisionLeafNode 非布尔节点，不可编译");
            case DecisionTableNode ignored ->
                    throw new IllegalArgumentException("DecisionTableNode 非布尔节点，不可编译");
        };
    }

    @SuppressWarnings("unchecked")
    private Predicate<EvalContext>[] compileChildren(List<AstNode> children) {
        Predicate<EvalContext>[] ps = new Predicate[children.size()];
        for (int i = 0; i < children.size(); i++) ps[i] = compile(children.get(i));
        return ps;
    }
}
