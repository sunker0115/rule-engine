package com.sstlfsj.rule.eval.internal.pregate;

import com.sstlfsj.rule.kernel.api.model.PreGateContext;
import com.sstlfsj.rule.kernel.api.model.PreGateResult;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RolloutPreGateTest {

    private final RolloutPreGate gate = new RolloutPreGate();

    /** 构建标准 PreGateContext 辅助方法。 */
    private PreGateContext ctx(String subjectId, Long ruleVersionId, int percentage) {
        RuleEvent event = new RuleEvent("1", "scene", "EVENT", subjectId,
                "eid", Instant.now(), Map.of(), Map.of());
        return new PreGateContext("1", "scene", subjectId, event,
                ruleVersionId, Map.of("percentage", percentage));
    }

    @Test
    void gateType_isROLLOUT() {
        assertEquals("ROLLOUT", gate.gateType());
    }

    @Test
    void percentage100_alwaysPasses() {
        for (int i = 0; i < 50; i++) {
            PreGateResult result = gate.evaluate(ctx("user" + i, 1L, 100));
            assertTrue(result.passed(), "subject user" + i + " should pass with 100%");
        }
    }

    @Test
    void percentage0_alwaysBlocked() {
        for (int i = 0; i < 50; i++) {
            PreGateResult result = gate.evaluate(ctx("user" + i, 1L, 0));
            assertFalse(result.passed(), "subject user" + i + " should be blocked with 0%");
            assertEquals("ROLLOUT", result.blockedBy());
        }
    }

    @Test
    void deterministicForSameInput() {
        PreGateContext c1 = ctx("userA", 42L, 50);
        PreGateContext c2 = ctx("userA", 42L, 50);
        assertEquals(gate.evaluate(c1).passed(), gate.evaluate(c2).passed());
    }

    @Test
    void differentRuleVersions_differentRolloutBuckets() {
        // 相同 subjectId 在不同 ruleVersionId 下可能分桶不同（murmur3 含 ruleVersionId）
        // 至少要求有至少 1 个 subjectId 在两个版本下结果不同（概率极高）
        boolean anyDifference = false;
        for (int i = 0; i < 100; i++) {
            boolean r1 = gate.evaluate(ctx("user" + i, 1L, 50)).passed();
            boolean r2 = gate.evaluate(ctx("user" + i, 2L, 50)).passed();
            if (r1 != r2) {
                anyDifference = true;
                break;
            }
        }
        assertTrue(anyDifference, "不同 ruleVersionId 应产生不同分桶");
    }

    @Test
    void missingPercentage_failOpen() {
        RuleEvent event = new RuleEvent("1", "scene", "E", "u1",
                "eid", Instant.now(), Map.of(), Map.of());
        // 无 percentage 参数
        PreGateContext ctx = new PreGateContext("1", "scene", "u1", event, 1L, Map.of());
        assertTrue(gate.evaluate(ctx).passed(), "缺少 percentage 配置时 fail-open");
    }

    @Test
    void stringPercentage_parsedCorrectly() {
        // percentage 以 String 类型传入（从 JSON/YAML 反序列化场景），不应抛出异常
        RuleEvent event = new RuleEvent("1", "scene", "E", "u1",
                "eid", Instant.now(), Map.of(), Map.of());
        PreGateContext ctx100 = new PreGateContext("1", "scene", "u1", event, 1L,
                Map.of("percentage", "100"));
        PreGateContext ctx0 = new PreGateContext("1", "scene", "u1", event, 1L,
                Map.of("percentage", "0"));
        assertTrue(gate.evaluate(ctx100).passed(), "字符串 '100' 应解析为全量放行");
        assertFalse(gate.evaluate(ctx0).passed(), "字符串 '0' 应解析为全量拦截");
    }

    @Test
    void bucketAlwaysInRange() {
        // & 0x7fffffff 保证桶值在 [0, 99]，即使哈希值为 Integer.MIN_VALUE 也不越界
        for (int i = 0; i < 200; i++) {
            PreGateContext c = ctx("u" + i, (long) i, 50);
            PreGateResult r = gate.evaluate(c);
            // 结果只可能是 pass 或 blocked("ROLLOUT")，不可能抛异常
            assertTrue(r.passed() || "ROLLOUT".equals(r.blockedBy()),
                    "桶值必须在合法范围，subject=u" + i);
        }
    }

    @Test
    void experimentId_presentAndSame_sameBucketForBothVersions() {
        // 两条规则 ruleVersionId 不同但 experimentId 相同 → 同一 subject 分桶相同（互斥保证）
        RuleEvent event = new RuleEvent("1", "scene", "E", "userX",
                "eid", Instant.now(), Map.of(), Map.of());
        PreGateContext ctx1 = new PreGateContext("1", "scene", "userX", event,
                1L, Map.of("percentage", 50, "experimentId", "exp-001"));
        PreGateContext ctx2 = new PreGateContext("1", "scene", "userX", event,
                2L, Map.of("percentage", 50, "experimentId", "exp-001"));

        // 两个 ruleVersionId 下，相同 experimentId 使结果一致
        assertEquals(gate.evaluate(ctx1).passed(), gate.evaluate(ctx2).passed(),
                "同 experimentId 下同一 subject 在不同规则版本的分桶应相同");
    }

    @Test
    void experimentId_differentValues_differentBuckets() {
        // 不同 experimentId → 分桶独立（不同实验互不影响）
        // 统计 100 个 subject，至少 1 个在两个 experimentId 下结果不同
        boolean anyDifference = false;
        for (int i = 0; i < 100; i++) {
            RuleEvent event = new RuleEvent("1", "scene", "E", "user" + i,
                    "eid", Instant.now(), Map.of(), Map.of());
            PreGateContext ctxA = new PreGateContext("1", "scene", "user" + i, event,
                    1L, Map.of("percentage", 50, "experimentId", "exp-A"));
            PreGateContext ctxB = new PreGateContext("1", "scene", "user" + i, event,
                    1L, Map.of("percentage", 50, "experimentId", "exp-B"));
            if (gate.evaluate(ctxA).passed() != gate.evaluate(ctxB).passed()) {
                anyDifference = true;
                break;
            }
        }
        assertTrue(anyDifference, "不同 experimentId 应产生不同分桶");
    }

    @Test
    void experimentId_absent_behaviorUnchanged() {
        // 无 experimentId 时与 v1 行为一致：percentage=100 全量通过
        for (int i = 0; i < 20; i++) {
            PreGateResult result = gate.evaluate(ctx("user" + i, (long) i, 100));
            assertTrue(result.passed(), "无 experimentId + percentage=100 应全量放行");
        }
    }
}
