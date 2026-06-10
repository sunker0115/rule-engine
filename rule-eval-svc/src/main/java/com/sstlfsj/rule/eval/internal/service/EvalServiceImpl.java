package com.sstlfsj.rule.eval.internal.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.sstlfsj.rule.eval.api.service.EvalService;
import com.sstlfsj.rule.eval.internal.async.ActionCommandChannel;
import com.sstlfsj.rule.eval.internal.async.DispatchActionsCommand;
import com.sstlfsj.rule.eval.internal.async.AuditRecordedEvent;
import com.sstlfsj.rule.eval.internal.async.DryRunRecordedEvent;
import com.sstlfsj.rule.eval.internal.dispatch.EvalActionDispatcher;
import com.sstlfsj.rule.eval.internal.domain.EvalMode;
import com.sstlfsj.rule.eval.internal.event.DomainEventPublisher;
import com.sstlfsj.rule.eval.internal.repository.RuleVersionReadMapper;
import com.sstlfsj.rule.eval.internal.snapshot.SceneSnapshotLoader;
import com.sstlfsj.rule.eval.internal.validate.PayloadInputValidator;
import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.kernel.internal.engine.EvalEngine;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;

/** EvalService 实现：委托 EvalEngine 做纯计算，仅经 DomainEventPublisher 发布审计/dry-run 事件、经 ActionCommandChannel 投递 action，自身不做内联持久化。 */
@Service
class EvalServiceImpl implements EvalService, InitializingBean, DisposableBean {

    private final EvalEngine evalEngine;
    private final SceneSnapshotLoader snapshotLoader;
    private final DomainEventPublisher eventPublisher;
    private final ActionCommandChannel actionDelivery;
    private final RuleVersionReadMapper ruleVersionReadMapper;
    private final EvalActionDispatcher dispatcher;

    EvalServiceImpl(EvalEngine evalEngine, SceneSnapshotLoader snapshotLoader,
                    DomainEventPublisher eventPublisher,
                    ActionCommandChannel actionDelivery,
                    RuleVersionReadMapper ruleVersionReadMapper) {
        this.evalEngine = evalEngine;
        this.snapshotLoader = snapshotLoader;
        this.eventPublisher = eventPublisher;
        this.actionDelivery = actionDelivery;
        this.ruleVersionReadMapper = ruleVersionReadMapper;
        // 构造器末尾创建 dispatcher，不调用 start；PUSH 异步路径以 mode=PUSH 评估
        this.dispatcher = new EvalActionDispatcher(10000, e -> doEvaluate(e, EvalMode.PUSH, false, null));
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
        return doEvaluate(event, EvalMode.PULL, false, null);
    }

    @Override
    public EvalResult dryRun(RuleEvent event, Long ruleId, Long ruleVersionId) {
        Long versionId = resolveDryRunVersionId(event, ruleId, ruleVersionId);
        // dry-run 永远先解析出一个版本 id：恒走 doEvaluate 的带版本单快照分支，结构上不落候选分支（根除副作用 bug）
        return doEvaluate(event, EvalMode.PULL, true, versionId);
    }

    /** 解析 dry-run 目标版本 id：ruleVersionId 优先；否则 ruleId 取最新版本；都无则抛 400。 */
    private Long resolveDryRunVersionId(RuleEvent event, Long ruleId, Long ruleVersionId) {
        if (ruleVersionId != null) {
            return ruleVersionId;
        }
        if (ruleId != null) {
            Long tid = parseTenantId(event.tenantId());
            if (tid == null) {
                // 已给 ruleId 但租户上下文非法：是租户问题，不是"缺目标"，用独立错误码避免排错误判
                throw new IllegalArgumentException("INVALID_TENANT: 无法解析租户");
            }
            Long vid = ruleVersionReadMapper.latestVersionIdByRule(tid, ruleId);
            if (vid == null) {
                throw new IllegalArgumentException("DRYRUN_RULE_NOT_FOUND: 规则无任何版本: ruleId=" + ruleId);
            }
            return vid;
        }
        throw new IllegalArgumentException("MISSING_DRYRUN_TARGET: 必须指定 ruleId 或 ruleVersionId");
    }

    private EvalResult doEvaluate(RuleEvent event, EvalMode mode, boolean isDryRun, Long specificVersionId) {
        Instant evalNow = Instant.now();
        // dry-run 路径下 specificVersionId 已由 resolveDryRunVersionId 保证非空；此处 != null 为防御性守卫，
        // 防止未来出现"isDryRun=true 但无版本 id"的新调用路径误落候选分支（有副作用）。
        if (isDryRun && specificVersionId != null) {
            RuleVersionSnapshot snap = snapshotLoader.loadById(specificVersionId);
            if (snap == null) return EvalResult.miss();
            // 单快照 dry-run：仅校验该快照的 payload 依赖
            PayloadInputValidator.validate(snap.payloadDependencies(), event.payload());
            // dry-run 始终强制收集 NodeTrace（需回传 nodeTrace），不受全局 trace 开关影响
            EvalOutcome outcome = evalEngine.evaluateWithContext(
                    event, List.of(snap), SceneExecutionStrategy.HIGHEST_PRIORITY, evalNow, true);
            // dry-run 终态事件化：请求线程生成 id（snowflake，INPUT），异步 persister 落 dry_run_session + trace
            long dryRunId = IdWorker.getId();
            int durationMs = (int) Duration.between(evalNow, Instant.now()).toMillis();
            eventPublisher.publish(new DryRunRecordedEvent(
                    dryRunId, event, specificVersionId, outcome.result(), outcome.context(), durationMs));
            return outcome.result();
        }

        List<RuleVersionSnapshot> candidates = evalEngine.match(event);
        if (candidates.isEmpty()) return EvalResult.miss();   // 无候选短路：不发事件（现状保留）

        // 无候选规则=该事件不触发任何规则,不强加输入契约,维持原 miss 行为;校验只在有候选时做。
        // 取候选快照 payload 依赖并集（按 name 去重；同 scene.payloadSchema 下同名声明一致）
        LinkedHashMap<String, PayloadDependency> union = new LinkedHashMap<>();
        for (RuleVersionSnapshot c : candidates) {
            for (PayloadDependency d : c.payloadDependencies()) {
                union.putIfAbsent(d.name(), d);
            }
        }
        PayloadInputValidator.validate(List.copyOf(union.values()), event.payload());

        // 异步落库需提前确定 id，供 node_trace/action 关联（snowflake，请求线程生成）
        long sessionId = IdWorker.getId();
        EvalOutcome outcome = evalEngine.evaluateWithContext(event, candidates, evalNow);
        EvalResult result = outcome.result();

        // 副作用事件化：审计内存 best-effort（可丢）；action 命中有决策时 best-effort fire-and-forget 派发（队列满/重启丢，不重试；可靠投递未来接 MQ）
        int durationMs = (int) Duration.between(evalNow, Instant.now()).toMillis();
        eventPublisher.publish(new AuditRecordedEvent(
                sessionId, event, mode, candidates.size(), result, outcome.context(), outcome.blockedBy(), durationMs));
        Long tid = parseTenantId(event.tenantId());
        // D27:仅派发 finalDecision 的 actions(命中且有挂载 action 才投递)
        if (tid != null && result.finalDecision() != null && !result.finalDecision().actions().isEmpty()) {
            actionDelivery.deliver(new DispatchActionsCommand(
                    sessionId, tid, event.eventId(), event.sceneCode(), result.finalDecision()));
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
