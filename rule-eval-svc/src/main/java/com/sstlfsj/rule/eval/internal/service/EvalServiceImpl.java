package com.sstlfsj.rule.eval.internal.service;

import com.sstlfsj.rule.eval.api.service.EvalService;
import com.sstlfsj.rule.eval.internal.async.EvaluationEventPublisher;
import com.sstlfsj.rule.eval.internal.dispatch.EvalActionDispatcher;
import com.sstlfsj.rule.eval.internal.session.EvalSessionWriter;
import com.sstlfsj.rule.eval.internal.snapshot.SceneSnapshotLoader;
import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.kernel.api.spi.trace.DryRunTraceWriter;
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
    private final EvalSessionWriter sessionWriter;          // 仅 dry-run 路径同步写
    private final DryRunTraceWriter dryRunTraceWriter;      // 仅 dry-run trace
    private final EvaluationEventPublisher eventPublisher;  // 主路径副作用事件化
    private final EvalActionDispatcher dispatcher;

    EvalServiceImpl(EvalEngine evalEngine,
                    SceneSnapshotLoader snapshotLoader,
                    EvalSessionWriter sessionWriter,
                    DryRunTraceWriter dryRunTraceWriter,
                    EvaluationEventPublisher eventPublisher) {
        this.evalEngine = evalEngine;
        this.snapshotLoader = snapshotLoader;
        this.sessionWriter = sessionWriter;
        this.dryRunTraceWriter = dryRunTraceWriter;
        this.eventPublisher = eventPublisher;
        // 构造器末尾创建 dispatcher，不调用 start；PUSH 异步路径以 mode=PUSH 评估
        this.dispatcher = new EvalActionDispatcher(10000, e -> doEvaluate(e, "PUSH", false, null));
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
        return doEvaluate(event, "PULL", false, null);
    }

    @Override
    public EvalResult dryRun(RuleEvent event, Long ruleVersionId) {
        return doEvaluate(event, "PULL", true, ruleVersionId);
    }

    private EvalResult doEvaluate(RuleEvent event, String mode, boolean isDryRun, Long specificVersionId) {
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
        if (candidates.isEmpty()) return EvalResult.miss();   // 无候选短路：不发事件（现状保留）

        // 异步落库需提前确定 id，供 node_trace/action 关联（snowflake，请求线程生成）
        long sessionId = com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
        EvalOutcome outcome = evalEngine.evaluateWithContext(event, candidates, evalNow);
        EvalResult result = outcome.result();

        // 副作用事件化异步：审计内存 best-effort（可丢）；action 命中有决策时持久投递（at-least-once，不丢）
        eventPublisher.publishAudit(sessionId, event, mode, candidates.size(),
                result, outcome.context(), outcome.blockedBy());
        Long tid = parseTenantId(event.tenantId());
        if (tid != null && result.ruleHit() && !result.hitDecisions().isEmpty()) {
            eventPublisher.publishActions(sessionId, tid, event.eventId(),
                    event.sceneCode(), result.hitDecisions());
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
