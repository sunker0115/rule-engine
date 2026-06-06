package com.sstlfsj.rule.eval.internal.service;

import com.sstlfsj.rule.eval.api.service.EvalService;
import com.sstlfsj.rule.eval.internal.action.ActionDispatchService;
import com.sstlfsj.rule.eval.internal.dispatch.EvalActionDispatcher;
import com.sstlfsj.rule.eval.internal.session.EvalSessionWriter;
import com.sstlfsj.rule.eval.internal.snapshot.SceneSnapshotLoader;
import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.kernel.api.spi.trace.DryRunTraceWriter;
import com.sstlfsj.rule.kernel.api.spi.trace.TraceWriter;
import com.sstlfsj.rule.kernel.internal.engine.EvalEngine;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/** EvalService 实现：委托 EvalEngine 做纯计算，负责 session 写入和 Action 派发副作用。 */
@Service
class EvalServiceImpl implements EvalService, InitializingBean, DisposableBean {

    private final EvalEngine evalEngine;
    private final SceneSnapshotLoader snapshotLoader;
    private final EvalSessionWriter sessionWriter;
    private final TraceWriter traceWriter;
    private final DryRunTraceWriter dryRunTraceWriter;
    private final ActionDispatchService actionDispatchService;
    private final EvalActionDispatcher dispatcher;

    EvalServiceImpl(EvalEngine evalEngine,
                    SceneSnapshotLoader snapshotLoader,
                    EvalSessionWriter sessionWriter,
                    TraceWriter traceWriter,
                    DryRunTraceWriter dryRunTraceWriter,
                    ActionDispatchService actionDispatchService) {
        this.evalEngine = evalEngine;
        this.snapshotLoader = snapshotLoader;
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
        Instant evalNow = Instant.now();
        if (isDryRun && specificVersionId != null) {
            RuleVersionSnapshot snap = snapshotLoader.loadById(specificVersionId);
            if (snap == null) return EvalResult.miss();
            EvalOutcome outcome = evalEngine.evaluateWithContext(
                    event, List.of(snap), SceneExecutionStrategy.HIGHEST_PRIORITY, evalNow);
            Long sessionId = sessionWriter.insertDryRunPending(event, specificVersionId);
            sessionWriter.updateDryRunFinal(sessionId, outcome.result(), outcome.context());
            dryRunTraceWriter.write(event.tenantId(), sessionId.toString(),
                    outcome.result().nodeTrace());
            return outcome.result();
        }

        List<RuleVersionSnapshot> candidates = evalEngine.match(event);
        if (candidates.isEmpty()) return EvalResult.miss();

        Long sessionId = sessionWriter.insertPending(event, candidates.size(), "PULL");
        EvalOutcome outcome = evalEngine.evaluateWithContext(event, candidates, evalNow);
        EvalResult result = outcome.result();

        sessionWriter.updateFinal(sessionId, result, outcome.context());
        traceWriter.write(event.tenantId(), sessionId.toString(), result.nodeTrace());

        if (result.ruleHit()) {
            actionDispatchService.dispatch(sessionId, parseTenantId(event.tenantId()),
                    event.eventId(), event.sceneCode(), result.hitDecisions());
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
}
