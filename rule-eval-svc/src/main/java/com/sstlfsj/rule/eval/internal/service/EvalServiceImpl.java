package com.sstlfsj.rule.eval.internal.service;

import com.sstlfsj.rule.eval.api.service.EvalService;
import com.sstlfsj.rule.eval.internal.action.ActionDispatchService;
import com.sstlfsj.rule.eval.internal.dispatch.EvalActionDispatcher;
import com.sstlfsj.rule.eval.internal.session.EvalSessionWriter;
import com.sstlfsj.rule.eval.internal.snapshot.SceneSnapshotLoader;
import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.kernel.api.spi.trace.DryRunTraceWriter;
import com.sstlfsj.rule.kernel.api.spi.trace.TraceWriter;
import com.sstlfsj.rule.kernel.internal.context.EvalContextAssembler;
import com.sstlfsj.rule.kernel.internal.engine.EvalEngine;
import com.sstlfsj.rule.kernel.internal.index.SceneRuleIndex;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Service;

import java.util.List;

/** EvalService 实现：委托 EvalEngine 做纯计算，负责 session 写入和 Action 派发副作用。 */
@Service
class EvalServiceImpl implements EvalService, InitializingBean, DisposableBean {

    private final EvalEngine evalEngine;
    private final SceneRuleIndex index;
    private final SceneSnapshotLoader snapshotLoader;
    private final EvalSessionWriter sessionWriter;
    private final TraceWriter traceWriter;
    private final DryRunTraceWriter dryRunTraceWriter;
    private final ActionDispatchService actionDispatchService;
    private final EvalContextAssembler contextAssembler;
    private final EvalActionDispatcher dispatcher;

    EvalServiceImpl(EvalEngine evalEngine,
                    SceneRuleIndex index,
                    SceneSnapshotLoader snapshotLoader,
                    EvalSessionWriter sessionWriter,
                    TraceWriter traceWriter,
                    DryRunTraceWriter dryRunTraceWriter,
                    ActionDispatchService actionDispatchService,
                    EvalContextAssembler contextAssembler) {
        this.evalEngine = evalEngine;
        this.index = index;
        this.snapshotLoader = snapshotLoader;
        this.sessionWriter = sessionWriter;
        this.traceWriter = traceWriter;
        this.dryRunTraceWriter = dryRunTraceWriter;
        this.actionDispatchService = actionDispatchService;
        this.contextAssembler = contextAssembler;
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
        if (isDryRun && specificVersionId != null) {
            RuleVersionSnapshot snap = snapshotLoader.loadById(specificVersionId);
            if (snap == null) return EvalResult.miss();
            EvalContext ctx = contextAssembler.assemble(event, List.of(snap));
            EvalResult result = evalEngine.evaluate(event, List.of(snap));
            Long sessionId = sessionWriter.insertDryRunPending(event, specificVersionId);
            sessionWriter.updateDryRunFinal(sessionId, result, ctx);
            dryRunTraceWriter.write(event.tenantId(), sessionId.toString(), result.nodeTrace());
            return result;
        }

        // 标准评估路径
        List<RuleVersionSnapshot> candidates = index.match(
                event.tenantId(), event.sceneCode(), event.eventType());
        if (candidates.isEmpty()) return EvalResult.miss();

        Long sessionId = sessionWriter.insertPending(event, candidates.size(), "PULL");
        EvalContext ctx = contextAssembler.assemble(event, candidates);
        EvalResult result = evalEngine.evaluate(event);

        sessionWriter.updateFinal(sessionId, result, ctx);
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
