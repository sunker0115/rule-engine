package com.sstlfsj.rule.eval.internal.service;

import com.sstlfsj.rule.eval.api.service.EvalService;
import com.sstlfsj.rule.eval.internal.action.ActionDispatchService;
import com.sstlfsj.rule.eval.internal.context.EvalContextAssembler;
import com.sstlfsj.rule.eval.internal.dispatch.EvalActionDispatcher;
import com.sstlfsj.rule.eval.internal.index.SceneRuleIndex;
import com.sstlfsj.rule.eval.internal.session.EvalSessionWriter;
import com.sstlfsj.rule.eval.internal.snapshot.SceneSnapshotLoader;
import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.kernel.api.spi.executor.RuleVersionExecutor;
import com.sstlfsj.rule.kernel.api.spi.pregate.PreGate;
import com.sstlfsj.rule.kernel.api.spi.trace.DryRunTraceWriter;
import com.sstlfsj.rule.kernel.api.spi.trace.TraceWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/** EvalService 完整实现：串联 Matcher → Pre-Gate → EvalContext → AST 评估 → Session 写入（D11/D21）。 */
@Service
class EvalServiceImpl implements EvalService, InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(EvalServiceImpl.class);

    private final SceneRuleIndex index;
    private final SceneSnapshotLoader snapshotLoader;
    private final Map<String, PreGate> preGateMap;
    private final EvalContextAssembler contextAssembler;
    private final RuleVersionExecutor executor;
    private final EvalSessionWriter sessionWriter;
    private final TraceWriter traceWriter;
    private final DryRunTraceWriter dryRunTraceWriter;
    private final ActionDispatchService actionDispatchService;
    private final EvalActionDispatcher dispatcher;

    EvalServiceImpl(SceneRuleIndex index,
                    SceneSnapshotLoader snapshotLoader,
                    List<PreGate> preGates,
                    EvalContextAssembler contextAssembler,
                    RuleVersionExecutor executor,
                    EvalSessionWriter sessionWriter,
                    TraceWriter traceWriter,
                    DryRunTraceWriter dryRunTraceWriter,
                    ActionDispatchService actionDispatchService) {
        this.index = index;
        this.snapshotLoader = snapshotLoader;
        this.preGateMap = preGates == null ? Map.of()
                : preGates.stream().collect(Collectors.toMap(PreGate::gateType, g -> g));
        this.contextAssembler = contextAssembler;
        this.executor = executor;
        this.sessionWriter = sessionWriter;
        this.traceWriter = traceWriter;
        this.dryRunTraceWriter = dryRunTraceWriter;
        this.actionDispatchService = actionDispatchService;
        // 构造器末尾创建 dispatcher，不调用 start
        this.dispatcher = new EvalActionDispatcher(10000, this::evaluate);
    }

    @Override
    public void afterPropertiesSet() {
        dispatcher.start();
    }

    @Override
    public void destroy() {
        dispatcher.stop();
    }

    @Override
    public boolean acceptEvent(RuleEvent event) {
        // PUSH 模式：通过 dispatcher 异步投递，队列满时返回 false（背压信号）
        return dispatcher.submit(event);
    }

    @Override
    public EvalResult evaluate(RuleEvent event) {
        return doEvaluate(event, false, null);
    }

    @Override
    public EvalResult dryRun(RuleEvent event, Long ruleVersionId) {
        return doEvaluate(event, true, ruleVersionId);
    }

    private EvalResult doEvaluate(RuleEvent event, boolean isDryRun, Long specificVersionId) {
        // ① Matcher：从倒排索引获取候选快照
        List<RuleVersionSnapshot> candidates = resolveCandidates(event, isDryRun, specificVersionId);
        if (candidates.isEmpty()) {
            return EvalResult.miss();
        }

        // ② Pre-Gate：逐条候选按 gate 顺序检查
        List<RuleVersionSnapshot> passed = new ArrayList<>();
        String firstBlockedBy = null;
        for (RuleVersionSnapshot snap : candidates) {
            String blockedBy = applyPreGates(event, snap);
            if (blockedBy == null) {
                passed.add(snap);
            } else if (firstBlockedBy == null) {
                firstBlockedBy = blockedBy;
            }
        }

        if (passed.isEmpty()) {
            // 全部被 Pre-Gate 拦截
            if (!isDryRun) {
                sessionWriter.insertBlocked(event, firstBlockedBy, "PULL");
            }
            return EvalResult.miss();
        }

        // ③ EvalContext 装配
        EvalContext ctx = contextAssembler.assemble(event, passed);

        // ④ 写 session（PENDING），dry-run 写 dry_run_session
        Long sessionId = isDryRun
                ? sessionWriter.insertDryRunPending(event,
                    specificVersionId != null ? specificVersionId : passed.get(0).ruleVersionId())
                : sessionWriter.insertPending(event, candidates.size(), "PULL");

        // ⑤ AST 评估：逐条规则求值，收集命中 Decision 和 NodeTrace
        List<Decision> hitDecisions = new ArrayList<>();
        List<NodeTrace> allTraces = new ArrayList<>();
        String errorCode = null;

        for (RuleVersionSnapshot snap : passed) {
            try {
                EvalResult r = executor.execute(snap, ctx);
                // 收集本条规则的 NodeTrace
                allTraces.addAll(r.nodeTrace());
                if (r.ruleHit()) {
                    // priority 越大越优先，取最大值
                    snap.decisionBindings().stream()
                            .max(Comparator.comparingInt(RuleVersionSnapshot.DecisionBinding::priority))
                            .ifPresent(binding -> hitDecisions.add(
                                    new Decision(binding.decisionCode(), "", binding.priority(),
                                            snap.ruleVersionId())));
                }
                if (r.errorCode() != null && errorCode == null) {
                    errorCode = r.errorCode();
                }
            } catch (Exception e) {
                if (errorCode == null) errorCode = "CONDITION_EVAL_ERROR";
            }
        }

        // ⑥ Decision 合成（priority 越大越优先，取最大值）
        Decision finalDecision = hitDecisions.stream()
                .max(Comparator.comparingInt(Decision::priority))
                .orElse(null);

        EvalResult result = new EvalResult(
                !hitDecisions.isEmpty(),
                finalDecision,
                List.copyOf(hitDecisions),
                List.copyOf(allTraces),
                errorCode,
                List.of(),
                null
        );

        // ⑦ 更新 session 终态 + 提交 traces 到隔离写库
        if (isDryRun) {
            sessionWriter.updateDryRunFinal(sessionId, result);
            dryRunTraceWriter.write(event.tenantId(), sessionId.toString(), allTraces);
        } else {
            sessionWriter.updateFinal(sessionId, result);
            traceWriter.write(event.tenantId(), sessionId.toString(), allTraces);
        }

        // ⑧ 非 dry-run 且有命中 Decision 时派发 Action（dry-run 不派发，见设计文档 D7）
        if (!isDryRun && !hitDecisions.isEmpty()) {
            actionDispatchService.dispatch(sessionId, parseTenantId(event.tenantId()),
                    event.eventId(), event.sceneCode(), hitDecisions);
        }

        return result;
    }

    /** 将字符串租户 ID 转换为 Long，转换失败时返回 null。 */
    private static Long parseTenantId(String tenantId) {
        try {
            return Long.parseLong(tenantId);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 确定本次评估的候选快照列表。dry-run 指定 ruleVersionId 时从 DB 直接加载。 */
    private List<RuleVersionSnapshot> resolveCandidates(RuleEvent event,
                                                         boolean isDryRun,
                                                         Long specificVersionId) {
        if (isDryRun && specificVersionId != null) {
            RuleVersionSnapshot snap = snapshotLoader.loadById(specificVersionId);
            return snap != null ? List.of(snap) : List.of();
        }
        return index.match(event.tenantId(), event.sceneCode(), event.eventType());
    }

    /**
     * 对单条候选快照按配置顺序执行 Pre-Gate 检查。
     *
     * @return null 表示全部通过；非 null 为首个阻断的 Gate 类型
     */
    private String applyPreGates(RuleEvent event, RuleVersionSnapshot snap) {
        for (RuleVersionSnapshot.PreGateConfig gateConfig : snap.preGates()) {
            PreGate gate = preGateMap.get(gateConfig.gateType());
            if (gate == null) continue; // 未注册的 gate 类型跳过（fail-open）
            PreGateContext ctx = new PreGateContext(
                    event.tenantId(), event.sceneCode(), event.subjectId(),
                    event, snap.ruleVersionId(), gateConfig.params());
            PreGateResult result = gate.evaluate(ctx);
            if (!result.passed()) {
                return result.blockedBy();
            }
        }
        return null;
    }
}
