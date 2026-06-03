package com.sstlfsj.rule.kernel.internal.evaluator;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.NodeTrace;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.ast.AndNode;
import com.sstlfsj.rule.kernel.api.model.ast.AstNode;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.model.ast.NotNode;
import com.sstlfsj.rule.kernel.api.model.ast.OrNode;
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
        List<NodeTrace> traces = new ArrayList<>();
        boolean satisfied = evalAndTrace(snapshot.conditionAst(), ctx, traces);
        return new EvalResult(satisfied, null, List.of(), List.copyOf(traces), null, List.of());
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
            case AndNode and        -> traceAnd(and, ctx, sink);
            case OrNode or          -> traceOr(or, ctx, sink);
            case NotNode not        -> traceNot(not, ctx, sink);
            case ConditionNode cond -> traceCondition(cond, ctx, sink);
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
        sink.add(new NodeTrace("AndNode", null, null, result, null, null, null, childTraces));
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
        sink.add(new NodeTrace("OrNode", null, null, result, null, null, null, childTraces));
        return result;
    }

    /**
     * NOT 节点：对唯一子节点求值后取反，子节点 trace 附加到 NotNode trace 的 children 中。
     */
    private boolean traceNot(NotNode not, EvalContext ctx, List<NodeTrace> sink) {
        List<NodeTrace> childTraces = new ArrayList<>();
        boolean childResult = evalAndTrace(not.child(), ctx, childTraces);
        boolean result = !childResult;
        sink.add(new NodeTrace("NotNode", null, null, result, null, null, null, childTraces));
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
                    false, null, null, "NO_EVALUATOR", List.of()));
            return false;
        }
        boolean result = evaluator.evaluate(node, ctx);
        sink.add(new NodeTrace("ConditionNode", node.conditionType(), node.metricCode(),
                result, null, null, null, List.of()));
        return result;
    }
}
