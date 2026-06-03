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
}
