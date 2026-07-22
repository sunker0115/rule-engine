package com.sstlfsj.rule.kernel.internal.evaluator;

import com.sstlfsj.rule.kernel.api.model.Decision;
import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.EvalErrorCode;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.ExpressionLang;
import com.sstlfsj.rule.kernel.api.model.FlowNodeType;
import com.sstlfsj.rule.kernel.api.model.NodeTrace;
import com.sstlfsj.rule.kernel.api.model.RuleKind;
import com.sstlfsj.rule.kernel.api.model.FlowBody;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.flow.FlowEdge;
import com.sstlfsj.rule.kernel.api.model.flow.FlowGraph;
import com.sstlfsj.rule.kernel.api.model.flow.FlowNode;
import com.sstlfsj.rule.kernel.api.model.flow.OutputNode;
import com.sstlfsj.rule.kernel.api.model.flow.RuleRefNode;
import com.sstlfsj.rule.kernel.api.model.flow.SwitchNode;
import com.sstlfsj.rule.kernel.api.model.flow.TransformNode;
import com.sstlfsj.rule.kernel.api.spi.executor.RuleVersionExecutor;
import com.sstlfsj.rule.kernel.api.spi.expression.ExpressionEngine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DECISION_FLOW executor：从 inputNodeId 顺边遍历决策图，只做编排——
 * RuleRef 调被引规则的冻结快照（原始 ctx，守 D6 隔离，不注入 flow 变量）、
 * Switch 按表达式结果选出边、Transform 写 flow 命名空间、Output 产出决策。
 * 图内 Switch/Transform 表达式可见 metrics/payload/subject/now/flow（+ 上一步 RuleRef 的 hitDecisions）。
 * trace 复用 {@link NodeTrace} 树：编排节点用 {@link FlowNodeType} 标签，RuleRef 的 children 挂被引规则 trace 子树。
 */
public class FlowExecutor implements RuleVersionExecutor {

    /** kind → executor，用于 RuleRef 按被引规则 kind 分派；含 FlowExecutor 自身（装配期后置 put，故不 copyOf）。 */
    private final Map<String, RuleVersionExecutor> leafExecutors;
    /** lang → 表达式引擎，用于 Switch/Transform 求值。 */
    private final Map<String, ExpressionEngine> engines;

    /**
     * @param leafExecutors kind 到 executor 的映射（共享引用，含 flow 自身以支持嵌套 flow；环由发布期检测拒绝）
     * @param engines       lang 到 ExpressionEngine 的映射
     */
    public FlowExecutor(Map<String, RuleVersionExecutor> leafExecutors, Map<String, ExpressionEngine> engines) {
        this.leafExecutors = leafExecutors;
        this.engines = Map.copyOf(engines);
    }

    @Override
    public EvalResult execute(RuleVersionSnapshot snapshot, EvalContext ctx) {
        FlowGraph graph = snapshot.body() instanceof FlowBody fb ? fb.flowGraph() : null;
        if (graph == null || graph.inputNodeId() == null || graph.nodes().isEmpty()) {
            return EvalResult.error(EvalErrorCode.FLOW_GRAPH_MISSING);
        }
        return new Walker(snapshot, graph, ctx).run();
    }

    /** 单次评估的遍历状态：flow 命名空间、命中决策、trace、上一步 RuleRef 结果。 */
    private final class Walker {
        private final RuleVersionSnapshot snapshot;
        private final FlowGraph graph;
        private final EvalContext ctx;
        private final Map<String, FlowNode> byId = new HashMap<>();
        private final boolean collect = TraceScope.COLLECT.orElse(true);
        private final List<NodeTrace> traces = new ArrayList<>();
        private final Map<String, Object> flowVars = new HashMap<>();
        private final List<Decision> hitDecisions = new ArrayList<>();
        private EvalResult lastRef;
        private String errorCode;

        Walker(RuleVersionSnapshot snapshot, FlowGraph graph, EvalContext ctx) {
            this.snapshot = snapshot;
            this.graph = graph;
            this.ctx = ctx;
            for (FlowNode n : graph.nodes()) byId.put(n.id(), n);
        }

        EvalResult run() {
            Set<String> visited = new HashSet<>();
            String cur = graph.inputNodeId();
            while (cur != null) {
                // 运行期防环兜底（发布期已拒成环 flow）
                if (!visited.add(cur)) break;
                FlowNode node = byId.get(cur);
                if (node == null) break;
                try {
                    cur = step(node);
                } catch (FlowHalt h) {
                    errorCode = h.code;
                    break;
                }
            }
            return assemble();
        }

        private String step(FlowNode node) {
            return switch (node) {
                case RuleRefNode ref -> handleRef(ref);
                case SwitchNode sw -> handleSwitch(sw);
                case TransformNode tr -> handleTransform(tr);
                case OutputNode out -> handleOutput(out);
            };
        }

        private String handleRef(RuleRefNode ref) {
            RuleVersionSnapshot refSnap = ((FlowBody) snapshot.body()).referencedSnapshots().get(ref.ruleCode());
            if (refSnap == null) throw new FlowHalt(EvalErrorCode.FLOW_REF_MISSING.name());
            RuleVersionExecutor exec = leafExecutors.get(refSnap.kind());
            if (exec == null) exec = leafExecutors.get(RuleKind.AST_BOOLEAN.tag());
            // 传原始 ctx，不合并 flow 变量——被引规则行为独立于调用方（守 D6）
            EvalResult r = exec.execute(refSnap, ctx);
            lastRef = r;
            if (collect) traces.add(refTrace(ref, r));
            if (r.errorCode() != null) throw new FlowHalt(r.errorCode());
            if (r.ruleHit()) hitDecisions.addAll(resolveRefDecisions(refSnap, r));
            return singleNext(ref.id());
        }

        private String handleSwitch(SwitchNode sw) {
            Object val = evalExpr(sw.lang(), sw.expression());
            String key = val == null ? null : String.valueOf(val);
            if (collect) traces.add(flowTrace(FlowNodeType.SWITCH, null, key, sw.expression()));
            return switchNext(sw.id(), key);
        }

        private String handleTransform(TransformNode tr) {
            Object val = evalExpr(tr.lang(), tr.expression());
            flowVars.put(tr.outputKey(), val);
            if (collect) traces.add(flowTrace(FlowNodeType.TRANSFORM, null, val, tr.outputKey()));
            return singleNext(tr.id());
        }

        private String handleOutput(OutputNode out) {
            RuleVersionSnapshot.DecisionBinding binding = findBinding(out.decisionCode());
            if (binding == null) {
                if (collect) traces.add(flowTrace(FlowNodeType.OUTPUT, false, out.decisionCode(), null));
                throw new FlowHalt(EvalErrorCode.INVALID_DECISION_CODE.name());
            }
            hitDecisions.add(new Decision(binding.decisionCode(), binding.name(), binding.priority(),
                    snapshot.ruleVersionId(), snapshot.code(), snapshot.version(), null));
            if (collect) traces.add(flowTrace(FlowNodeType.OUTPUT, true, out.decisionCode(), null));
            return singleNext(out.id());
        }

        /** 求值 Switch/Transform 表达式：bindings = metrics/payload/subject/now + flow + 上一步 RuleRef 的 hitDecisions。 */
        private Object evalExpr(ExpressionLang lang, String expression) {
            ExpressionEngine engine = engines.get(lang == null ? null : lang.tag());
            if (engine == null) throw new FlowHalt(EvalErrorCode.FLOW_NO_ENGINE.name());
            Map<String, Object> bindings = new HashMap<>(ScriptBindings.from(ctx));
            bindings.put("flow", flowVars);
            bindings.put("hitDecisions", lastRef == null ? List.of() : lastRef.hitDecisions());
            try {
                return engine.evaluate(engine.compile(expression), bindings);
            } catch (FlowHalt h) {
                throw h;
            } catch (Exception e) {
                throw new FlowHalt(EvalErrorCode.FLOW_EVAL_ERROR.name());
            }
        }

        private EvalResult assemble() {
            List<NodeTrace> finalTraces = collect ? traces : List.of();
            if (errorCode != null) return EvalResult.error(errorCode, finalTraces);
            if (hitDecisions.isEmpty()) return EvalResult.miss(finalTraces);
            // flow 作为一条规则：finalDecision 取最高优先级，hitDecisions 全带回参与 Scene 合成
            Decision winner = hitDecisions.get(0);
            for (Decision d : hitDecisions) {
                if (d.priority() > winner.priority()) winner = d;
            }
            return new EvalResult(true, winner, List.copyOf(hitDecisions), finalTraces,
                    null, null, winner.category(), null);
        }

        /** 非 Switch 节点的唯一出边目标；无出边返回 null（终止遍历）。 */
        private String singleNext(String fromId) {
            for (FlowEdge e : graph.edges()) {
                if (e.from().equals(fromId)) return e.to();
            }
            return null;
        }

        /** Switch 出边：优先匹配 caseKey==key 的边；无匹配走 default（caseKey==null）；再无则终止。 */
        private String switchNext(String fromId, String key) {
            String defaultTo = null;
            for (FlowEdge e : graph.edges()) {
                if (!e.from().equals(fromId)) continue;
                if (e.caseKey() == null) {
                    defaultTo = e.to();
                } else if (e.caseKey().equals(key)) {
                    return e.to();
                }
            }
            return defaultTo;
        }

        private RuleVersionSnapshot.DecisionBinding findBinding(String decisionCode) {
            for (RuleVersionSnapshot.DecisionBinding b : snapshot.decisionBindings()) {
                if (b.decisionCode().equals(decisionCode)) return b;
            }
            return null;
        }

        /** RuleRef 节点 trace：result=被引规则是否命中，children=被引规则 trace 子树（自带 ruleCode/version 归属）。 */
        private NodeTrace refTrace(RuleRefNode ref, EvalResult r) {
            return new NodeTrace(FlowNodeType.RULEREF.tag(), null, null, r.ruleHit(), null, null,
                    r.errorCode(), r.nodeTrace(), snapshot.ruleVersionId(), snapshot.code(), snapshot.version(),
                    null, ref.ruleCode());
        }

        /** Switch/Transform/Output 编排节点 trace：actualValue=选中 case / 输出值 / 决策码；displayLabel=表达式/键/null。 */
        private NodeTrace flowTrace(FlowNodeType type, Boolean result, Object actualValue, String displayLabel) {
            return new NodeTrace(type.tag(), null, null, result, actualValue, null, null,
                    List.of(), snapshot.ruleVersionId(), snapshot.code(), snapshot.version(), null, displayLabel);
        }
    }

    /**
     * 一条命中 RuleRef 贡献的决策：被引 executor 自选了决策（hitDecisions 非空）就用它；
     * 否则回退按最高优先级 binding 赋决策（复用 EvalEngine 同款语义）。
     */
    private static List<Decision> resolveRefDecisions(RuleVersionSnapshot snap, EvalResult r) {
        if (!r.hitDecisions().isEmpty()) return r.hitDecisions();
        List<RuleVersionSnapshot.DecisionBinding> bindings = snap.decisionBindings();
        if (bindings.isEmpty()) return List.of();
        RuleVersionSnapshot.DecisionBinding best = bindings.get(0);
        for (RuleVersionSnapshot.DecisionBinding b : bindings) {
            if (b.priority() > best.priority()) best = b;
        }
        return List.of(new Decision(best.decisionCode(), best.name(), best.priority(),
                snap.ruleVersionId(), snap.code(), snap.version(), null));
    }

    /** 内部遍历中止信号，携带落 EvalResult 的错误码。 */
    private static final class FlowHalt extends RuntimeException {
        final String code;

        FlowHalt(String code) {
            super(code);
            this.code = code;
        }
    }
}
