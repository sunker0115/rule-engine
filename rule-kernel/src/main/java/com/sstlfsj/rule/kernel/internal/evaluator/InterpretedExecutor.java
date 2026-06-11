package com.sstlfsj.rule.kernel.internal.evaluator;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.NodeTrace;
import com.sstlfsj.rule.kernel.api.model.NodeType;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
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
import com.sstlfsj.rule.kernel.api.spi.executor.RuleVersionExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 使用已注册的 ConditionEvaluator 对 RuleVersionSnapshot AST 树进行解释执行。
 * 是否收集 NodeTrace 由 ambient 的 {@link TraceScope#COLLECT} 决定：
 * 绑定为 false 时跳过全部 trace 构建（nodeTrace()==List.of()），仅返回命中布尔；
 * 未绑定或 true 时收集完整 trace 树，供 dry-run 审计。命中布尔在两种模式下完全一致。
 */
public class InterpretedExecutor implements RuleVersionExecutor {

    /** 已注册的条件求值器，按 conditionType 查找。 */
    private final Map<String, ConditionEvaluator> evaluators;

    /**
     * @param evaluators conditionType 到 ConditionEvaluator 的映射
     */
    public InterpretedExecutor(Map<String, ConditionEvaluator> evaluators) {
        this.evaluators = Map.copyOf(evaluators);
    }

    /**
     * 对 snapshot 的 AST 求值。collect=true 时收集完整 NodeTrace 树并打上 ruleVersionId；
     * collect=false 时跳过所有 trace 构建，nodeTrace() 返回空列表，命中布尔不受影响。
     *
     * @param snapshot 规则版本快照（含 conditionAst 与 ruleVersionId）
     * @param ctx      执行上下文
     * @return         命中结果 + （按 collect 决定的）NodeTrace 列表
     */
    @Override
    public EvalResult execute(RuleVersionSnapshot snapshot, EvalContext ctx) {
        boolean collect = TraceScope.COLLECT.orElse(true);
        // 非收集模式 sink 为 null，逐节点跳过 trace 构建
        List<NodeTrace> rawTraces = collect ? new ArrayList<>() : null;
        boolean satisfied = eval(snapshot.conditionAst(), ctx, rawTraces);
        List<NodeTrace> traces;
        if (collect) {
            // 顶层 trace 打上 ruleVersionId，供 TraceWriter 写库时使用
            Long rvId = snapshot.ruleVersionId();
            traces = rawTraces.stream().map(t -> withRuleVersionId(t, rvId)).toList();
        } else {
            traces = List.of();
        }
        return new EvalResult(satisfied, null, List.of(), traces, null, List.of(), null, null, null);
    }

    /** 递归将 ruleVersionId 注入 trace 树（顶层和所有子节点）。 */
    private static NodeTrace withRuleVersionId(NodeTrace t, Long rvId) {
        List<NodeTrace> children = t.children().stream()
                .map(c -> withRuleVersionId(c, rvId))
                .toList();
        return new NodeTrace(t.nodeType(), t.conditionType(), t.metricCode(),
                t.result(), t.actualValue(), t.valueSource(), t.errorCode(), children, rvId,
                t.ruleCode(), t.ruleVersion(), t.expectedValue(), t.displayLabel());
    }

    /**
     * 对 node 求值；sink 非 null 时将产生的 NodeTrace 追加到 sink，sink 为 null 时仅算布尔不建 trace。
     * 命中布尔与短路逻辑在两种模式下完全一致。
     *
     * @param node 待求值节点
     * @param ctx  执行上下文
     * @param sink 接收本节点 trace 的目标列表；null 表示非收集模式
     * @return     节点求值结果
     */
    private boolean eval(AstNode node, EvalContext ctx, List<NodeTrace> sink) {
        return switch (node) {
            case AndNode and          -> evalAnd(and, ctx, sink);
            case OrNode or            -> evalOr(or, ctx, sink);
            case NotNode not          -> evalNot(not, ctx, sink);
            case ConditionNode cond   -> evalCondition(cond, ctx, sink);
            case XorNode xor          -> evalXor(xor, ctx, sink);
            // 以下节点由专属 Executor 处理，不应进入此执行器
            case ScorecardRootNode ignored ->
                    throw new IllegalStateException("ScorecardRootNode 不能由 InterpretedExecutor 处理");
            case IfNode ignored ->
                    throw new IllegalStateException("IfNode 不能由 InterpretedExecutor 处理，请使用 DecisionTreeExecutor");
            case DecisionLeafNode ignored ->
                    throw new IllegalStateException("DecisionLeafNode 不能由 InterpretedExecutor 处理，请使用 DecisionTreeExecutor");
            case DecisionTableNode ignored ->
                    throw new IllegalStateException("DecisionTableNode 不能由 InterpretedExecutor 处理，请使用 DecisionTableExecutor");
        };
    }

    /**
     * AND 节点：短路求值，遇第一个 false 停止，将已求值子节点 trace 附加到 AndNode trace 的 children 中。
     */
    private boolean evalAnd(AndNode and, EvalContext ctx, List<NodeTrace> sink) {
        List<NodeTrace> childTraces = sink != null ? new ArrayList<>() : null;
        boolean result = true;
        for (AstNode child : and.children()) {
            // 逐个子节点求值，收集子节点 trace
            boolean childResult = eval(child, ctx, childTraces);
            if (!childResult) {
                // 短路：第一个 false 后停止遍历
                result = false;
                break;
            }
        }
        if (sink != null) {
            sink.add(NodeTrace.container(NodeType.AND, result, childTraces, null));
        }
        return result;
    }

    /**
     * OR 节点：短路求值，遇第一个 true 停止，将已求值子节点 trace 附加到 OrNode trace 的 children 中。
     */
    private boolean evalOr(OrNode or, EvalContext ctx, List<NodeTrace> sink) {
        List<NodeTrace> childTraces = sink != null ? new ArrayList<>() : null;
        boolean result = false;
        for (AstNode child : or.children()) {
            // 逐个子节点求值，收集子节点 trace
            boolean childResult = eval(child, ctx, childTraces);
            if (childResult) {
                // 短路：第一个 true 后停止遍历
                result = true;
                break;
            }
        }
        if (sink != null) {
            sink.add(NodeTrace.container(NodeType.OR, result, childTraces, null));
        }
        return result;
    }

    /**
     * NOT 节点：对唯一子节点求值后取反，子节点 trace 附加到 NotNode trace 的 children 中。
     */
    private boolean evalNot(NotNode not, EvalContext ctx, List<NodeTrace> sink) {
        List<NodeTrace> childTraces = sink != null ? new ArrayList<>() : null;
        boolean childResult = eval(not.child(), ctx, childTraces);
        boolean result = !childResult;
        if (sink != null) {
            sink.add(NodeTrace.container(NodeType.NOT, result, childTraces, null));
        }
        return result;
    }

    /**
     * XOR 节点：全量遍历所有子节点（不短路），有且仅有一个 true 时结果为 true。
     * 所有子节点均求值并记录 trace，结果汇总到 XorNode trace 的 children 中。
     */
    private boolean evalXor(XorNode xor, EvalContext ctx, List<NodeTrace> sink) {
        List<NodeTrace> childTraces = sink != null ? new ArrayList<>() : null;
        int satisfiedCount = 0;
        for (AstNode child : xor.children()) {
            // XOR 不短路，所有子节点都求值并记录 trace
            if (eval(child, ctx, childTraces)) satisfiedCount++;
        }
        boolean result = satisfiedCount == 1;
        if (sink != null) {
            sink.add(NodeTrace.container(NodeType.XOR, result, childTraces, null));
        }
        return result;
    }

    /**
     * ConditionNode 叶子节点：查找对应 evaluator 求值；无注册 evaluator 时 result=false，errorCode="NO_EVALUATOR"。
     */
    private boolean evalCondition(ConditionNode node, EvalContext ctx, List<NodeTrace> sink) {
        ConditionOutcome outcome = ConditionEvaluation.evaluate(node, ctx, evaluators);
        if (outcome.isError()) {
            // ERROR(取数失败/无算子)：节点不命中，trace 标错码，整树继续(D15)
            if (sink != null) {
                sink.add(new NodeTrace(NodeType.CONDITION.tag(), node.conditionType(), node.metricCode(),
                        false, outcome.resolvedValue(), outcome.valueSource(), outcome.errorCode(), List.of(), null,
                        null, 0L, node.params(), node.displayLabel()));
            }
            return false;
        }
        if (sink != null) {
            sink.add(new NodeTrace(NodeType.CONDITION.tag(), node.conditionType(), node.metricCode(),
                    outcome.satisfied(), outcome.resolvedValue(), outcome.valueSource(), null, List.of(), null,
                    null, 0L, node.params(), node.displayLabel()));
        }
        return outcome.satisfied();
    }
}
