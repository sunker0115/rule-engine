package com.sstlfsj.rule.samples.flow;

import com.sstlfsj.rule.expression.cel.CelExpressionEngine;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.EventSource;
import com.sstlfsj.rule.kernel.api.model.ExpressionLang;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import com.sstlfsj.rule.kernel.api.model.RuleKind;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.ValueRef;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.model.flow.FlowEdge;
import com.sstlfsj.rule.kernel.api.model.flow.FlowGraph;
import com.sstlfsj.rule.kernel.api.model.flow.OutputNode;
import com.sstlfsj.rule.kernel.api.model.flow.RuleRefNode;
import com.sstlfsj.rule.kernel.api.model.flow.SwitchNode;
import com.sstlfsj.rule.sdk.RuleEngineClient;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 接入姿势五：DECISION_FLOW 决策图编排（第 6 种规则形态，对标 GoRules ZEN JDM）。
 * 图只做编排，叶子逻辑由 {@code RuleRef} 引用的独立规则承载（被引快照经 {@code addReferencedSnapshot} 冻入 flow）。
 *
 * <p>本例是一张交易风控决策图，两条叶子规则 + 一个分档 Switch：
 * <pre>
 *   Input(RuleRef: 黑名单 payload.blacklisted==true)
 *     ├─true → Output(BLOCK)
 *     └─false→ RuleRef(大额 payload.amount>50000)
 *               ├─true → Switch(CEL: payload.amount>100000 ? 'high' : 'mid')
 *               │         ├─high→ Output(REJECT)
 *               │         └─mid → Output(REVIEW)
 *               └─false→ Output(APPROVE)
 * </pre>
 * <p>运行前提：无，直接跑（Switch 表达式需 CEL 引擎，已经 {@code expressionEngine} 注入）。
 * <p>怎么跑：{@code $MVN -pl rule-samples exec:java -Dexec.mainClass="com.sstlfsj.rule.samples.flow.DecisionFlowDemo"}
 */
@Slf4j
public final class DecisionFlowDemo {

    private DecisionFlowDemo() {
    }

    static final String TENANT = "9001";
    static final String SCENE = "risk-flow";
    static final String EVENT_TYPE = "txn";

    public static void main(String[] args) {
        try (RuleEngineClient client = RuleEngineClient.builder()
                .expressionEngine(new CelExpressionEngine())
                .localSnapshot(flowSnapshot())
                .build()) {

            // 1) 黑名单命中 → BLOCK（首个 RuleRef true 分支）
            log.info("[flow] 黑名单 → {}", code(client.evaluate(
                    event(Map.of("blacklisted", true, "amount", 1000)))));

            // 2) 非黑名单 + 超大额(>100000) → REJECT（Switch high 分支）
            log.info("[flow] 大额 150000 → {}", code(client.evaluate(
                    event(Map.of("blacklisted", false, "amount", 150000)))));

            // 3) 非黑名单 + 中额(50000~100000) → REVIEW（Switch mid 分支）
            log.info("[flow] 中额 80000 → {}", code(client.evaluate(
                    event(Map.of("blacklisted", false, "amount", 80000)))));

            // 4) 非黑名单 + 小额(<=50000) → APPROVE（大额 RuleRef false 分支）
            log.info("[flow] 小额 3000 → {}", code(client.evaluate(
                    event(Map.of("blacklisted", false, "amount", 3000)))));
        }
    }

    /** 组装 DECISION_FLOW 快照：图 + 两条被引叶子规则 + decisionBindings（Output 决策码须属此集）。 */
    static RuleVersionSnapshot flowSnapshot() {
        FlowGraph graph = new FlowGraph(
                List.of(
                        new RuleRefNode("n1", "blacklist-check"),
                        new OutputNode("out-block", "BLOCK"),
                        new RuleRefNode("n2", "large-amount-check"),
                        new SwitchNode("sw", ExpressionLang.CEL,
                                "payload.amount > 100000 ? 'high' : 'mid'", List.of("high", "mid")),
                        new OutputNode("out-reject", "REJECT"),
                        new OutputNode("out-review", "REVIEW"),
                        new OutputNode("out-approve", "APPROVE")),
                List.of(
                        new FlowEdge("n1", "out-block", "true"),
                        new FlowEdge("n1", "n2", "false"),
                        new FlowEdge("n2", "sw", "true"),
                        new FlowEdge("n2", "out-approve", "false"),
                        new FlowEdge("sw", "out-reject", "high"),
                        new FlowEdge("sw", "out-review", "mid")),
                "n1");

        return RuleVersionSnapshot.builder()
                .ruleVersionId(9101L).tenantId(TENANT).sceneCode(SCENE)
                .code("risk-flow").version(1)
                .kind(RuleKind.DECISION_FLOW.tag())
                .flowGraph(graph)
                .addReferencedSnapshot("blacklist-check", leaf(9102L, "blacklist-check",
                        new ConditionNode("EQ", "blacklisted", "黑名单命中",
                                Map.of("threshold", true), null, "BOOLEAN", ValueRef.PAYLOAD)))
                .addReferencedSnapshot("large-amount-check", leaf(9103L, "large-amount-check",
                        new ConditionNode("GT", "amount", "大额交易",
                                Map.of("threshold", 50000), null, "LONG", ValueRef.PAYLOAD)))
                .addTriggerEventType(EVENT_TYPE)
                .addDecisionBinding("BLOCK", 100)
                .addDecisionBinding("REJECT", 90)
                .addDecisionBinding("REVIEW", 50)
                .addDecisionBinding("APPROVE", 10)
                .build();
    }

    /** 被引叶子规则快照（AST_BOOLEAN，payload 直接引用，无 metric 依赖）。 */
    private static RuleVersionSnapshot leaf(long id, String code, ConditionNode cond) {
        return RuleVersionSnapshot.builder()
                .ruleVersionId(id).tenantId(TENANT).sceneCode(SCENE)
                .code(code).version(1)
                .kind(RuleKind.AST_BOOLEAN.tag())
                .conditionAst(cond)
                .addTriggerEventType(EVENT_TYPE)
                .build();
    }

    private static RuleEvent event(Map<String, Object> payload) {
        return RuleEvent.builder()
                .tenantId(TENANT).sceneCode(SCENE).eventType(EVENT_TYPE)
                .subjectId("subject-1").eventId(UUID.randomUUID().toString())
                .occurredAt(Instant.now())
                .payload(payload)
                .source(EventSource.SDK).build();
    }

    private static String code(EvalResult r) {
        return r.finalDecision() == null ? null : r.finalDecision().code();
    }
}
