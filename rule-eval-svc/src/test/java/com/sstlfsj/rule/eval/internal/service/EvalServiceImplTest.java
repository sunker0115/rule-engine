package com.sstlfsj.rule.eval.internal.service;

import com.sstlfsj.rule.eval.internal.async.ActionCommandChannel;
import com.sstlfsj.rule.eval.internal.async.DispatchActionsCommand;
import com.sstlfsj.rule.eval.internal.async.AuditRecordedEvent;
import com.sstlfsj.rule.eval.internal.async.DryRunRecordedEvent;
import com.sstlfsj.rule.eval.internal.event.DomainEventPublisher;
import com.sstlfsj.rule.eval.internal.repository.RuleVersionReadMapper;
import com.sstlfsj.rule.eval.internal.snapshot.SceneSnapshotLoader;
import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.internal.engine.EvalEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** doEvaluate 事件驱动后的单测：PULL 主路径只经 DomainEventPublisher 发布审计、经 ActionCommandChannel 投递 action；dry-run 发 DryRunRecordedEvent 事件。 */
@ExtendWith(MockitoExtension.class)
class EvalServiceImplTest {

    @Mock EvalEngine evalEngine;
    @Mock SceneSnapshotLoader snapshotLoader;
    @Mock DomainEventPublisher eventPublisher;
    @Mock ActionCommandChannel actionDelivery;
    @Mock RuleVersionReadMapper ruleVersionReadMapper;

    EvalServiceImpl impl;

    @BeforeEach
    void setUp() {
        impl = new EvalServiceImpl(evalEngine, snapshotLoader, eventPublisher, actionDelivery, ruleVersionReadMapper);
    }

    private RuleEvent event() {
        return new RuleEvent("1", "fraud_check", "RISK_EVENT", "u1",
                "evt-001", Instant.now(), Map.of(), Map.of(),
                com.sstlfsj.rule.kernel.api.model.EventSource.HTTP);
    }

    private RuleVersionSnapshot snapshot(Long id, String decisionCode) {
        return new RuleVersionSnapshot(
                id, "fraud_check", "1",
                new ConditionNode("EQ", null, null, Map.of(), 0.0),
                List.of(),
                List.of(new RuleVersionSnapshot.DecisionBinding(decisionCode, 10)),
                null, null);
    }

    private EvalResult hitResult(String code, int priority, Long ruleVersionId) {
        Decision d = new Decision(code, "", priority, ruleVersionId);
        return new EvalResult(true, d, List.of(d), List.of(), null, List.of(), null, null, null);
    }

    private EvalContext ctx() {
        return new EvalContext("1", event(), null, Map.of(), Instant.parse("2024-01-01T00:00:00Z"));
    }

    /** PULL：stub match 返回候选 + evaluateWithContext 返回 outcome。 */
    private void stubPull(RuleVersionSnapshot snap, EvalOutcome outcome) {
        when(evalEngine.match(any(RuleEvent.class))).thenReturn(List.of(snap));
        when(evalEngine.evaluateWithContext(any(RuleEvent.class), anyList(), any(Instant.class)))
                .thenReturn(outcome);
    }

    @Test
    void evaluate_publishesAuditWithEngineContext() {
        EvalContext engineCtx = ctx();
        stubPull(snapshot(1L, "REJECT"), new EvalOutcome(EvalResult.miss(), engineCtx));

        impl.evaluate(event());

        // 复用引擎组装的上下文发审计事件（异步落 session 快照），不再同步 updateFinal
        verify(eventPublisher).publish(argThat(o ->
                o instanceof AuditRecordedEvent a
                        && a.mode() == com.sstlfsj.rule.eval.internal.domain.EvalMode.PULL
                        && a.candidateCount() == 1
                        && a.context() == engineCtx));
    }

    @Test
    void evaluate_noMatchingRules_returnsMiss_noEvents() {
        when(evalEngine.match(any(RuleEvent.class))).thenReturn(List.of());

        EvalResult result = impl.evaluate(event());

        assertFalse(result.ruleHit());
        verifyNoInteractions(eventPublisher);
        verifyNoInteractions(actionDelivery);
        verify(evalEngine, never()).evaluateWithContext(any(RuleEvent.class), anyList(), any(Instant.class));
    }

    @Test
    void evaluate_ruleHit_returnsHitWithDecision_publishesAudit() {
        stubPull(snapshot(1L, "REJECT"), new EvalOutcome(hitResult("REJECT", 10, 1L), ctx()));

        EvalResult result = impl.evaluate(event());

        assertTrue(result.ruleHit());
        assertFalse(result.hitDecisions().isEmpty());
        assertEquals("REJECT", result.hitDecisions().get(0).code());
        verify(eventPublisher).publish(any(AuditRecordedEvent.class));
    }

    @Test
    void evaluate_ruleMiss_returnsMiss() {
        stubPull(snapshot(1L, "REJECT"), new EvalOutcome(EvalResult.miss(), ctx()));

        EvalResult result = impl.evaluate(event());

        assertFalse(result.ruleHit());
        assertTrue(result.hitDecisions().isEmpty());
        assertNull(result.finalDecision());
    }

    @Test
    void evaluate_multipleHits_highestPriorityWins() {
        Decision highPriority = new Decision("REJECT", "", 20, 2L);
        EvalResult engineResult = new EvalResult(true, highPriority,
                List.of(new Decision("LOW_RISK", "", 5, 1L), highPriority),
                List.of(), null, List.of(), null, null, null);
        stubPull(snapshot(1L, "REJECT"), new EvalOutcome(engineResult, ctx()));

        EvalResult result = impl.evaluate(event());

        assertTrue(result.ruleHit());
        assertEquals("REJECT", result.finalDecision().code(),
                "priority=20 的 REJECT 应优先于 priority=5 的 LOW_RISK");
    }

    @Test
    void acceptEvent_returnsTrueAndDoesNotBlock() throws Exception {
        impl.afterPropertiesSet();
        try {
            boolean accepted = impl.acceptEvent(event());
            assertTrue(accepted);
        } finally {
            impl.destroy();
        }
    }

    @Test
    void dryRun_publishesDryRunRecorded() {
        RuleVersionSnapshot snap = snapshot(42L, "PASS");
        EvalContext engineCtx = ctx();
        when(snapshotLoader.loadById(42L)).thenReturn(snap);
        when(evalEngine.evaluateWithContext(any(RuleEvent.class), anyList(),
                any(SceneExecutionStrategy.class), any(Instant.class), eq(true)))
                .thenReturn(new EvalOutcome(EvalResult.miss(), engineCtx));

        impl.dryRun(event(), null, 42L);

        // dry-run 改事件驱动：发 DryRunRecordedEvent 事件由异步 persister 落 dry_run_session
        verify(eventPublisher).publish(argThat(o ->
                o instanceof DryRunRecordedEvent d
                        && d.ruleVersionId().equals(42L)
                        && d.context() == engineCtx));
        // dry-run 始终强制收集 trace：必须以 collectTrace=true 调用引擎
        verify(evalEngine).evaluateWithContext(any(RuleEvent.class), anyList(),
                any(SceneExecutionStrategy.class), any(Instant.class), eq(true));
    }

    @Test
    void dryRun_doesNotDeliverActions() {
        RuleVersionSnapshot snap = snapshot(42L, "PASS");
        when(snapshotLoader.loadById(42L)).thenReturn(snap);
        when(evalEngine.evaluateWithContext(any(RuleEvent.class), anyList(),
                any(SceneExecutionStrategy.class), any(Instant.class), eq(true)))
                .thenReturn(new EvalOutcome(EvalResult.miss(), ctx()));

        EvalResult result = impl.dryRun(event(), null, 42L);

        assertFalse(result.ruleHit());
        verify(eventPublisher).publish(any(DryRunRecordedEvent.class));
        verifyNoInteractions(actionDelivery);
    }

    @Test
    void evaluate_ruleHit_deliversActions() {
        // D27:仅当 finalDecision 携带 actions 才投递派发命令
        var action = new com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot.DecisionAction(
                "a1", "SEND_ALERT", 0, java.util.Map.of());
        Decision d = new Decision("REJECT", "拒绝", 10, 1L, null, 0L, null, java.util.List.of(action));
        EvalResult r = new EvalResult(true, d, java.util.List.of(d), java.util.List.of(),
                null, java.util.List.of(), null, null, null);
        stubPull(snapshot(1L, "REJECT"), new EvalOutcome(r, ctx()));

        impl.evaluate(event());

        verify(actionDelivery).deliver(argThat(o ->
                o instanceof DispatchActionsCommand ar
                        && ar.tenantId() == 1L
                        && ar.eventId().equals("evt-001")
                        && ar.finalDecision() != null
                        && "REJECT".equals(ar.finalDecision().code())));
    }

    @Test
    void evaluate_ruleMiss_doesNotDeliverActions() {
        stubPull(snapshot(1L, "REJECT"), new EvalOutcome(EvalResult.miss(), ctx()));

        impl.evaluate(event());

        verify(actionDelivery, never()).deliver(any());
    }

    @Test
    void dryRun_hit_doesNotDeliverActions() {
        RuleVersionSnapshot snap = snapshot(42L, "PASS");
        when(snapshotLoader.loadById(42L)).thenReturn(snap);
        when(evalEngine.evaluateWithContext(any(RuleEvent.class), anyList(),
                any(SceneExecutionStrategy.class), any(Instant.class), eq(true)))
                .thenReturn(new EvalOutcome(hitResult("PASS", 10, 42L), ctx()));

        impl.dryRun(event(), null, 42L);

        verifyNoInteractions(actionDelivery);
    }

    @Test
    void dryRun_byRuleId_resolvesLatestVersion() {
        RuleVersionSnapshot snap = snapshot(77L, "PASS");
        when(ruleVersionReadMapper.latestVersionIdByRule(1L, 5L)).thenReturn(77L);
        when(snapshotLoader.loadById(77L)).thenReturn(snap);
        when(evalEngine.evaluateWithContext(any(RuleEvent.class), anyList(),
                any(SceneExecutionStrategy.class), any(Instant.class), eq(true)))
                .thenReturn(new EvalOutcome(EvalResult.miss(), ctx()));

        // 只传 ruleId：经 latestVersionIdByRule 解析出最新版本 id（77），走带版本单快照分支
        impl.dryRun(event(), 5L, null);

        verify(ruleVersionReadMapper).latestVersionIdByRule(1L, 5L);
        verify(snapshotLoader).loadById(77L);
        verify(eventPublisher).publish(argThat(o ->
                o instanceof DryRunRecordedEvent d && d.ruleVersionId().equals(77L)));
        // dry-run 永不落候选分支：不走 match、不投递 action
        verify(evalEngine, never()).match(any(RuleEvent.class));
        verifyNoInteractions(actionDelivery);
    }

    @Test
    void dryRun_ruleVersionIdTakesPrecedence_overRuleId() {
        RuleVersionSnapshot snap = snapshot(42L, "PASS");
        when(snapshotLoader.loadById(42L)).thenReturn(snap);
        when(evalEngine.evaluateWithContext(any(RuleEvent.class), anyList(),
                any(SceneExecutionStrategy.class), any(Instant.class), eq(true)))
                .thenReturn(new EvalOutcome(EvalResult.miss(), ctx()));

        // 两者都传时 ruleVersionId 优先，不查 ruleId
        impl.dryRun(event(), 5L, 42L);

        verify(snapshotLoader).loadById(42L);
        verifyNoInteractions(ruleVersionReadMapper);
    }

    @Test
    void dryRun_byRuleId_ruleNotFound_throws() {
        when(ruleVersionReadMapper.latestVersionIdByRule(1L, 999L)).thenReturn(null);

        assertThrows(IllegalArgumentException.class, () -> impl.dryRun(event(), 999L, null));
        verifyNoInteractions(snapshotLoader);
        verifyNoInteractions(actionDelivery);
    }

    @Test
    void dryRun_missingTarget_throws() {
        // ruleId / ruleVersionId 都不传 → 抛 IllegalArgumentException（→ 400），不触达任何评估
        assertThrows(IllegalArgumentException.class, () -> impl.dryRun(event(), null, null));
        verifyNoInteractions(evalEngine);
        verifyNoInteractions(snapshotLoader);
        verifyNoInteractions(actionDelivery);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void dryRun_byRuleId_invalidTenant_throws() {
        // 给了 ruleId 但租户上下文非数字 → INVALID_TENANT（非 MISSING_DRYRUN_TARGET），不查 mapper、不评估
        RuleEvent badTenant = new RuleEvent("not-a-number", "fraud_check", "RISK_EVENT", "u1",
                "evt-001", Instant.now(), Map.of(), Map.of(),
                com.sstlfsj.rule.kernel.api.model.EventSource.HTTP);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> impl.dryRun(badTenant, 5L, null));
        assertTrue(ex.getMessage().contains("INVALID_TENANT"));
        verifyNoInteractions(ruleVersionReadMapper);
        verifyNoInteractions(snapshotLoader);
        verifyNoInteractions(actionDelivery);
    }

    @Test
    void evaluate_scoreFromEngine_isPropagated() {
        Decision d = new Decision("REJECT", "", 10, 1L);
        EvalResult engineResult = new EvalResult(true, d, List.of(d), List.of(), null, List.of(), 60.0, null, null);
        stubPull(snapshot(1L, "REJECT"), new EvalOutcome(engineResult, ctx()));

        EvalResult result = impl.evaluate(event());

        assertEquals(60.0, result.score());
    }

    @Test
    void evaluate_scoreIsNull_forBooleanRules() {
        stubPull(snapshot(1L, "REJECT"), new EvalOutcome(hitResult("REJECT", 10, 1L), ctx()));

        EvalResult result = impl.evaluate(event());

        assertNull(result.score());
    }

    /** 带 payload 的事件（用于校验链路测试）。 */
    private RuleEvent eventWithPayload(Map<String, Object> payload) {
        return new RuleEvent("1", "fraud_check", "RISK_EVENT", "u1",
                "evt-001", Instant.now(), payload, Map.of(),
                com.sstlfsj.rule.kernel.api.model.EventSource.HTTP);
    }

    /** 带 payload 依赖的候选快照。 */
    private RuleVersionSnapshot snapshotWithDep(String name, String dataType, boolean required) {
        return RuleVersionSnapshot.builder()
                .ruleVersionId(1L).sceneCode("fraud_check").tenantId("1")
                .conditionAst(new ConditionNode("EQ", null, null, Map.of(), 0.0))
                .addDecisionBinding("REJECT", 10)
                .addPayloadDependency(name, dataType, required)
                .build();
    }

    @Test
    void doEvaluate_rejectsMissingRequiredPayload() {
        when(evalEngine.match(any(RuleEvent.class)))
                .thenReturn(List.of(snapshotWithDep("amount", "DECIMAL", true)));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> impl.evaluate(eventWithPayload(Map.of("country", "CN"))));
        assertTrue(ex.getMessage().contains("MISSING_REQUIRED_INPUT"));
        verify(evalEngine, never()).evaluateWithContext(any(RuleEvent.class), anyList(), any(Instant.class));
    }

    @Test
    void doEvaluate_passesWhenRequiredPresent_ignoresExtra() {
        when(evalEngine.match(any(RuleEvent.class)))
                .thenReturn(List.of(snapshotWithDep("amount", "DECIMAL", true)));
        when(evalEngine.evaluateWithContext(any(RuleEvent.class), anyList(), any(Instant.class)))
                .thenReturn(new EvalOutcome(EvalResult.miss(), ctx()));

        assertDoesNotThrow(() -> impl.evaluate(eventWithPayload(Map.of("amount", 5000, "extra", "x"))));
        verify(evalEngine).evaluateWithContext(any(RuleEvent.class), anyList(), any(Instant.class));
    }
}
