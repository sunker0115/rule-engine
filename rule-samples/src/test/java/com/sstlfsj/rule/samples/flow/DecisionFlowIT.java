package com.sstlfsj.rule.samples.flow;

import com.sstlfsj.rule.expression.cel.CelExpressionEngine;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.EventSource;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import com.sstlfsj.rule.sdk.RuleEngineClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DECISION_FLOW 决策图端到端验证：一张交易风控图（黑名单 RuleRef → 大额 RuleRef → Switch 分档 → Output），
 * 四条路径各命中不同决策码，覆盖 RuleRef true/false 分支 + Switch high/mid 分支。
 */
class DecisionFlowIT {

    private RuleEngineClient client;

    @BeforeEach
    void setUp() {
        client = RuleEngineClient.builder()
                .expressionEngine(new CelExpressionEngine())
                .localSnapshot(DecisionFlowDemo.flowSnapshot())
                .build();
    }

    @AfterEach
    void tearDown() {
        if (client != null) client.close();
    }

    @Test
    void blacklisted_firstRefTrueBranch_blocks() {
        EvalResult r = eval(Map.of("blacklisted", true, "amount", 1000));
        assertThat(r.ruleHit()).isTrue();
        assertThat(r.finalDecision().code()).isEqualTo("BLOCK");
    }

    @Test
    void hugeAmount_switchHighBranch_rejects() {
        EvalResult r = eval(Map.of("blacklisted", false, "amount", 150000));
        assertThat(r.ruleHit()).isTrue();
        assertThat(r.finalDecision().code()).isEqualTo("REJECT");
    }

    @Test
    void midAmount_switchMidBranch_reviews() {
        EvalResult r = eval(Map.of("blacklisted", false, "amount", 80000));
        assertThat(r.ruleHit()).isTrue();
        assertThat(r.finalDecision().code()).isEqualTo("REVIEW");
    }

    @Test
    void smallAmount_secondRefFalseBranch_approves() {
        EvalResult r = eval(Map.of("blacklisted", false, "amount", 3000));
        assertThat(r.ruleHit()).isTrue();
        assertThat(r.finalDecision().code()).isEqualTo("APPROVE");
    }

    private EvalResult eval(Map<String, Object> payload) {
        RuleEvent event = RuleEvent.builder()
                .tenantId("9001").sceneCode("risk-flow").eventType("txn")
                .subjectId("s-1").eventId(UUID.randomUUID().toString())
                .occurredAt(Instant.now()).payload(payload).source(EventSource.SDK).build();
        return client.evaluate(event);
    }
}
