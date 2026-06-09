package com.sstlfsj.rule.kernel.internal.evaluator;

import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.kernel.api.model.ast.*;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;
import com.sstlfsj.rule.kernel.api.spi.executor.RuleVersionExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * DECISION_TREE evaluator：递归遍历 IfNode 树，命中 DecisionLeafNode 时返回决策结果。
 * 是否收集 NodeTrace 由 ambient 的 {@link TraceScope#COLLECT} 决定：绑定为 false 时跳过全部
 * trace 构建（nodeTrace()==List.of()），命中布尔与决策在两种模式下完全一致。
 */
public class DecisionTreeExecutor implements RuleVersionExecutor {

    private final Map<String, ConditionEvaluator> evaluators;

    /**
     * @param evaluators conditionType 到 ConditionEvaluator 的映射，用于 IfNode 条件求值
     */
    public DecisionTreeExecutor(Map<String, ConditionEvaluator> evaluators) {
        this.evaluators = Map.copyOf(evaluators);
    }

    @Override
    public EvalResult execute(RuleVersionSnapshot snapshot, EvalContext ctx) {
        if (!(snapshot.conditionAst() instanceof IfNode root)) {
            return EvalResult.error(EvalErrorCode.DECISION_TREE_AST_TYPE_MISMATCH);
        }
        boolean collect = TraceScope.COLLECT.orElse(true);
        Long rvId = snapshot.ruleVersionId();
        // collect=false 时 sink 为 null，整树跳过 trace 构建（零分配契约）
        List<NodeTrace> sink = collect ? new ArrayList<>() : null;
        return evaluate(root, snapshot, ctx, sink, rvId);
    }

    private EvalResult evaluate(AstNode node, RuleVersionSnapshot snapshot, EvalContext ctx,
                                List<NodeTrace> sink, Long rvId) {
        return switch (node) {
            case IfNode ifNode -> evaluateIf(ifNode, snapshot, ctx, sink, rvId);
            case DecisionLeafNode leaf -> hit(leaf, snapshot, sink, rvId);
            default -> EvalResult.error(EvalErrorCode.DECISION_TREE_UNEXPECTED_NODE);
        };
    }

    private EvalResult evaluateIf(IfNode ifNode, RuleVersionSnapshot snapshot, EvalContext ctx,
                                  List<NodeTrace> sink, Long rvId) {
        // children = [条件子树..., 命中分支节点]；前缀为条件 trace，后缀为走入的分支
        List<NodeTrace> children = sink != null ? new ArrayList<>() : null;
        ConditionOutcome cond = evaluateCondition(ifNode.condition(), ctx, children, rvId);
        if (cond.isError()) {
            // 取数失败：不静默走 else，整规则置 ERROR + miss（避免命中错误叶子）
            if (sink != null) {
                sink.add(NodeTrace.container(NodeType.IF, false, cond.errorCode(), children, rvId));
            }
            return EvalResult.error(cond.errorCode(), traces(sink));
        }
        EvalResult branch;
        if (cond.satisfied()) {
            branch = evaluate(ifNode.thenBranch(), snapshot, ctx, children, rvId);
        } else if (ifNode.elseBranch() != null) {
            branch = evaluate(ifNode.elseBranch(), snapshot, ctx, children, rvId);
        } else {
            branch = EvalResult.miss();
        }
        if (sink != null) {
            sink.add(NodeTrace.container(NodeType.IF, cond.satisfied(), children, rvId));
        }
        // 顶层结果挂上含 IfNode 根的完整 trace；命中布尔/决策取自分支求值
        return new EvalResult(branch.ruleHit(), branch.finalDecision(), branch.hitDecisions(),
                traces(sink), branch.errorCode(), branch.actionResults(),
                branch.score(), branch.category(), branch.decision());
    }

    private ConditionOutcome evaluateCondition(AstNode node, EvalContext ctx,
                                               List<NodeTrace> sink, Long rvId) {
        return switch (node) {
            case ConditionNode c -> evalLeafCondition(c, ctx, sink, rvId);
            case AndNode and -> {
                List<NodeTrace> childTraces = sink != null ? new ArrayList<>() : null;
                ConditionOutcome result = ConditionOutcome.SATISFIED;
                for (AstNode child : and.children()) {
                    ConditionOutcome o = evaluateCondition(child, ctx, childTraces, rvId);
                    if (o.isError()) { result = o; break; }              // ERROR 传播
                    if (!o.satisfied()) { result = ConditionOutcome.NOT_SATISFIED; break; } // 短路 false
                }
                if (sink != null) {
                    sink.add(NodeTrace.container(NodeType.AND, result.satisfied(),
                            result.isError() ? result.errorCode() : null, childTraces, rvId));
                }
                yield result;
            }
            case OrNode or -> {
                List<NodeTrace> childTraces = sink != null ? new ArrayList<>() : null;
                String errCode = null;
                ConditionOutcome result = null;
                for (AstNode child : or.children()) {
                    ConditionOutcome o = evaluateCondition(child, ctx, childTraces, rvId);
                    if (o.satisfied()) { result = ConditionOutcome.SATISFIED; break; } // 命中即短路
                    if (o.isError()) errCode = o.errorCode();
                }
                if (result == null) {
                    // 全不满足；若曾有 ERROR 则整体不可判定（保守）
                    result = errCode != null ? ConditionOutcome.error(errCode) : ConditionOutcome.NOT_SATISFIED;
                }
                if (sink != null) {
                    sink.add(NodeTrace.container(NodeType.OR, result.satisfied(),
                            result.isError() ? result.errorCode() : null, childTraces, rvId));
                }
                yield result;
            }
            case NotNode not -> {
                List<NodeTrace> childTraces = sink != null ? new ArrayList<>() : null;
                ConditionOutcome o = evaluateCondition(not.child(), ctx, childTraces, rvId);
                ConditionOutcome result = o.isError() ? o : ConditionOutcome.of(!o.satisfied());
                if (sink != null) {
                    sink.add(NodeTrace.container(NodeType.NOT, result.satisfied(),
                            result.isError() ? result.errorCode() : null, childTraces, rvId));
                }
                yield result;
            }
            default -> ConditionOutcome.error(EvalErrorCode.NO_EVALUATOR);
        };
    }

    /** 叶子条件求值并填充 trace（实际值/来源/错误码），镜像 InterpretedExecutor.evalCondition。 */
    private ConditionOutcome evalLeafCondition(ConditionNode node, EvalContext ctx,
                                               List<NodeTrace> sink, Long rvId) {
        ConditionOutcome outcome = ConditionEvaluation.evaluate(node, ctx, evaluators);
        if (sink != null) {
            sink.add(new NodeTrace(NodeType.CONDITION.tag(), node.conditionType(), node.metricCode(),
                    outcome.satisfied(), outcome.resolvedValue(), outcome.valueSource(),
                    outcome.isError() ? outcome.errorCode() : null, List.of(), rvId,
                    node.params(), node.displayLabel()));
        }
        return outcome;
    }

    private EvalResult hit(DecisionLeafNode leaf, RuleVersionSnapshot snapshot,
                           List<NodeTrace> sink, Long rvId) {
        if (sink != null) {
            sink.add(NodeTrace.container(NodeType.DECISION_LEAF, true, List.of(), rvId));
        }
        Decision decision = snapshot.decisionBindings().stream()
                .filter(b -> b.decisionCode().equals(leaf.decisionCode()))
                .max(java.util.Comparator.comparingInt(RuleVersionSnapshot.DecisionBinding::priority))
                .map(b -> new Decision(b.decisionCode(), "", b.priority(), snapshot.ruleVersionId(), leaf.category()))
                .orElseGet(() -> new Decision(leaf.decisionCode(), "", 0, snapshot.ruleVersionId(), leaf.category()));
        // 叶子命中：trace 由调用方（IfNode/顶层）汇总，这里只返回命中布尔/决策
        return new EvalResult(true, decision, List.of(decision),
                List.of(), null, List.of(), null, leaf.category(), null);
    }

    /** sink 为 null（非收集模式）时返回空列表，否则返回当前 sink 内容。 */
    private static List<NodeTrace> traces(List<NodeTrace> sink) {
        return sink != null ? sink : List.of();
    }
}
