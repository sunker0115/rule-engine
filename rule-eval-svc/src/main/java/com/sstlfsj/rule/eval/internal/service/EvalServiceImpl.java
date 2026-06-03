package com.sstlfsj.rule.eval.internal.service;

import com.sstlfsj.rule.eval.api.service.EvalService;
import com.sstlfsj.rule.eval.internal.context.EvalContextAssembler;
import com.sstlfsj.rule.eval.internal.index.SceneRuleIndex;
import com.sstlfsj.rule.eval.internal.session.EvalSessionWriter;
import com.sstlfsj.rule.eval.internal.snapshot.SceneSnapshotLoader;
import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.kernel.api.spi.executor.RuleVersionExecutor;
import com.sstlfsj.rule.kernel.api.spi.pregate.PreGate;
import com.sstlfsj.rule.kernel.api.spi.trace.TraceWriter;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/** EvalService 完整实现：串联 Matcher → Pre-Gate → EvalContext → AST 评估 → Session 写入（D11/D21）。 */
@Service
class EvalServiceImpl implements EvalService {

    private final SceneRuleIndex index;
    private final SceneSnapshotLoader snapshotLoader;
    private final Map<String, PreGate> preGateMap;
    private final EvalContextAssembler contextAssembler;
    private final RuleVersionExecutor executor;
    private final EvalSessionWriter sessionWriter;
    private final TraceWriter traceWriter;

    EvalServiceImpl(SceneRuleIndex index,
                    SceneSnapshotLoader snapshotLoader,
                    List<PreGate> preGates,
                    EvalContextAssembler contextAssembler,
                    RuleVersionExecutor executor,
                    EvalSessionWriter sessionWriter,
                    TraceWriter traceWriter) {
        this.index = index;
        this.snapshotLoader = snapshotLoader;
        this.preGateMap = preGates == null ? Map.of()
                : preGates.stream().collect(Collectors.toMap(PreGate::gateType, g -> g));
        this.contextAssembler = contextAssembler;
        this.executor = executor;
        this.sessionWriter = sessionWriter;
        this.traceWriter = traceWriter;
    }

    @Override
    public boolean acceptEvent(RuleEvent event) {
        // PUSH 模式：异步投递，不阻塞调用方
        CompletableFuture.runAsync(() -> evaluate(event));
        return true;
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

        // ⑤ AST 评估：逐条规则求值，收集命中 Decision
        List<Decision> hitDecisions = new ArrayList<>();
        String errorCode = null;

        for (RuleVersionSnapshot snap : passed) {
            try {
                EvalResult r = executor.execute(snap, ctx);
                if (r.ruleHit()) {
                    // 从 decisionBindings 取最高优先级（priority 最小值）的 Decision
                    snap.decisionBindings().stream()
                            .min(Comparator.comparingInt(RuleVersionSnapshot.DecisionBinding::priority))
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

        // ⑥ Decision 合成（HIGHEST_PRIORITY = priority 值最小者）
        Decision finalDecision = hitDecisions.stream()
                .min(Comparator.comparingInt(Decision::priority))
                .orElse(null);

        EvalResult result = new EvalResult(
                !hitDecisions.isEmpty(),
                finalDecision,
                List.copyOf(hitDecisions),
                List.of(),   // trace v1 为空列表
                errorCode,
                List.of()
        );

        // ⑦ 更新 session 终态 + 提交 trace
        if (isDryRun) {
            sessionWriter.updateDryRunFinal(sessionId, result);
        } else {
            sessionWriter.updateFinal(sessionId, result);
        }
        // trace 列表 v1 为空（TraceWriter 扩展点，InterpretedExecutor 增强后填充）
        traceWriter.write(event.tenantId(), sessionId.toString(), List.of());

        return result;
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
