package com.sstlfsj.rule.kernel.api.spi.pregate;

import com.sstlfsj.rule.kernel.api.model.PreGateContext;
import com.sstlfsj.rule.kernel.api.model.PreGateResult;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PreGateTest {

    private static PreGateContext buildCtx(String gateType) {
        RuleEvent event = new RuleEvent("t1", "SCENE1", "PAYMENT",
                "u1", "e1", Instant.now(), Map.of(), Map.of());
        return new PreGateContext("t1", "SCENE1", "u1", event, null, null);
    }

    private static final PreGate PASS_GATE = new PreGate() {
        @Override
        public String gateType() { return "WHITELIST"; }

        @Override
        public PreGateResult evaluate(PreGateContext ctx) {
            return PreGateResult.pass();
        }
    };

    private static final PreGate BLOCK_GATE = new PreGate() {
        @Override
        public String gateType() { return "RATE_LIMIT"; }

        @Override
        public PreGateResult evaluate(PreGateContext ctx) {
            return PreGateResult.blocked("RATE_LIMIT");
        }
    };

    @Test
    void gateType_returnsConfiguredValue() {
        assertEquals("WHITELIST", PASS_GATE.gateType());
        assertEquals("RATE_LIMIT", BLOCK_GATE.gateType());
    }

    @Test
    void evaluate_pass_returnsTruePassed() {
        PreGateResult result = PASS_GATE.evaluate(buildCtx("WHITELIST"));
        assertTrue(result.passed());
        assertNull(result.blockedBy());
    }

    @Test
    void evaluate_block_returnsFalseWithGateType() {
        PreGateResult result = BLOCK_GATE.evaluate(buildCtx("RATE_LIMIT"));
        assertFalse(result.passed());
        assertEquals("RATE_LIMIT", result.blockedBy());
    }
}
