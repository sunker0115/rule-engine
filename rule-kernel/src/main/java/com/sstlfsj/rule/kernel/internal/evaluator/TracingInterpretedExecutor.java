package com.sstlfsj.rule.kernel.internal.evaluator;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.NodeTrace;
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
 * 带 NodeTrace 收集功能的 AST 解释执行器。
 * 每个节点求值后生成一条 NodeTrace，通过 EvalResult.nodeTrace() 返回，用于 dry-run 审计。
 */
public class TracingInterpretedExecutor implements RuleVersionExecutor {

    /** 已注册的条件求值器，按 conditionType 查找。 */
    private final Map<String, ConditionEvaluator> evaluators;

    /**
     * @param evaluators conditionType 到 ConditionEvaluator 的映射
     */
    public TracingInterpretedExecutor(Map<String, ConditionEvaluator> evaluators) {
        this.evaluators = Map.copyOf(evaluators);
    }

    @Override
    public EvalResult execute(RuleVersionSnapshot snapshot, EvalContext ctx) {
        // 顶层 trace 列表，每个根节点产生一条记录
        List<NodeTrace> rawTraces = new ArrayList<>();
        boolean satisfied = evalAndTrace(snapshot.conditionAst(), ctx, rawTraces);
        // 顶层 trace 打上 ruleVersionId，供 TraceWriter 写库时使用
        Long rvId = snapshot.ruleVersionId();
        List<NodeTrace> traces = rawTraces.stream()
                .map(t -> withRuleVersionId(t, rvId))
                .toList();
        return new EvalResult(satisfied, null, List.of(), traces, null, List.of(), null, null, null);
    }

    /** 递归将 ruleVersionId 注入 trace 树（顶层和所有子节点）。 */
    private static NodeTrace withRuleVersionId(NodeTrace t, Long rvId) {
        List<NodeTrace> children = t.children().stream()
                .map(c -> withRuleVersionId(c, rvId))
                .toList();
        return new NodeTrace(t.nodeType(), t.conditionType(), t.metricCode(),
                t.result(), t.actualValue(), t.valueSource(), t.errorCode(), children, rvId);
    }

    /**
     * 对 node 求值并将产生的 NodeTrace 追加到 sink 列表中，返回求值结果。
     *
     * @param node  待求值节点
     * @param ctx   执行上下文
     * @param sink  接收本节点 trace 的目标列表
     * @return      节点求值结果
     */
    private boolean evalAndTrace(AstNode node, EvalContext ctx, List<NodeTrace> sink) {
        return switch (node) {
            case AndNode and          -> traceAnd(and, ctx, sink);
            case OrNode or            -> traceOr(or, ctx, sink);
            case NotNode not          -> traceNot(not, ctx, sink);
            case ConditionNode cond   -> traceCondition(cond, ctx, sink);
            case XorNode xor          -> traceXor(xor, ctx, sink);
            // 以下节点由专属 Executor 处理，不应进入此执行器
            case ScorecardRootNode ignored ->
                    throw new IllegalStateException("ScorecardRootNode 不能由 TracingInterpretedExecutor 处理");
            case IfNode ignored ->
                    throw new IllegalStateException("IfNode 不能由 TracingInterpretedExecutor 处理，请使用 DecisionTreeExecutor");
            case DecisionLeafNode ignored ->
                    throw new IllegalStateException("DecisionLeafNode 不能由 TracingInterpretedExecutor 处理，请使用 DecisionTreeExecutor");
            case DecisionTableNode ignored ->
                    throw new IllegalStateException("DecisionTableNode 不能由 TracingInterpretedExecutor 处理，请使用 DecisionTableExecutor");
        };
    }

    /**
     * AND 节点：短路求值，遇第一个 false 停止，将已求值子节点 trace 附加到 AndNode trace 的 children 中。
     */
    private boolean traceAnd(AndNode and, EvalContext ctx, List<NodeTrace> sink) {
        List<NodeTrace> childTraces = new ArrayList<>();
        boolean result = true;
        for (AstNode child : and.children()) {
            // 逐个子节点求值，收集子节点 trace
            boolean childResult = evalAndTrace(child, ctx, childTraces);
            if (!childResult) {
                // 短路：第一个 false 后停止遍历
                result = false;
                break;
            }
        }
        sink.add(new NodeTrace("AndNode", null, null, result, null, null, null, childTraces, null));
        return result;
    }

    /**
     * OR 节点：短路求值，遇第一个 true 停止，将已求值子节点 trace 附加到 OrNode trace 的 children 中。
     */
    private boolean traceOr(OrNode or, EvalContext ctx, List<NodeTrace> sink) {
        List<NodeTrace> childTraces = new ArrayList<>();
        boolean result = false;
        for (AstNode child : or.children()) {
            // 逐个子节点求值，收集子节点 trace
            boolean childResult = evalAndTrace(child, ctx, childTraces);
            if (childResult) {
                // 短路：第一个 true 后停止遍历
                result = true;
                break;
            }
        }
        sink.add(new NodeTrace("OrNode", null, null, result, null, null, null, childTraces, null));
        return result;
    }

    /**
     * NOT 节点：对唯一子节点求值后取反，子节点 trace 附加到 NotNode trace 的 children 中。
     */
    private boolean traceNot(NotNode not, EvalContext ctx, List<NodeTrace> sink) {
        List<NodeTrace> childTraces = new ArrayList<>();
        boolean childResult = evalAndTrace(not.child(), ctx, childTraces);
        boolean result = !childResult;
        sink.add(new NodeTrace("NotNode", null, null, result, null, null, null, childTraces, null));
        return result;
    }

    /**
     * XOR 节点：全量遍历所有子节点（不短路），有且仅有一个 true 时结果为 true。
     * 所有子节点均求值并记录 trace，结果汇总到 XorNode trace 的 children 中。
     */
    private boolean traceXor(XorNode xor, EvalContext ctx, List<NodeTrace> sink) {
        List<NodeTrace> childTraces = new ArrayList<>();
        int satisfiedCount = 0;
        for (AstNode child : xor.children()) {
            // XOR 不短路，所有子节点都求值并记录 trace
            if (evalAndTrace(child, ctx, childTraces)) satisfiedCount++;
        }
        boolean result = satisfiedCount == 1;
        sink.add(new NodeTrace("XorNode", null, null, result, null, null, null, childTraces, null));
        return result;
    }

    /**
     * ConditionNode 叶子节点：查找对应 evaluator 求值；无注册 evaluator 时 result=false，errorCode="NO_EVALUATOR"。
     */
    private boolean traceCondition(ConditionNode node, EvalContext ctx, List<NodeTrace> sink) {
        ConditionEvaluator evaluator = evaluators.get(node.conditionType());
        if (evaluator == null) {
            // 未注册 evaluator，记录错误码并返回 false
            sink.add(new NodeTrace("ConditionNode", node.conditionType(), node.metricCode(),
                    false, null, null, "NO_EVALUATOR", List.of(), null));
            return false;
        }
        boolean result = evaluator.evaluate(node, ctx);
        sink.add(new NodeTrace("ConditionNode", node.conditionType(), node.metricCode(),
                result, null, null, null, List.of(), null));
        return result;
    }
}
