package com.sstlfsj.rule.kernel.internal.context;

import com.sstlfsj.rule.kernel.api.model.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EvalContextAssemblerPayloadTest {

    @Test
    void payloadFields_injectedAsPayloadSource() {
        EvalContextAssembler assembler = new EvalContextAssembler(List.of(), List.of());
        RuleEvent event = new RuleEvent("1", "demo.login", "login", "u1", "evt1",
                Instant.now(), Map.of("amount", 5000, "currency", "CNY"), Map.of(),
                EventSource.HTTP);

        EvalContext ctx = assembler.assemble(event, List.of(), new EvalEnv(Instant.now(), java.util.Map.of()));

        MetricValue amount = ctx.getMetric("amount");
        assertNotNull(amount);
        assertEquals(5000, amount.value());
        assertEquals(ValueSource.PAYLOAD.tag(), amount.valueSource());
    }

    @Test
    void providedMetric_winsOverSamePayloadKey() {
        EvalContextAssembler assembler = new EvalContextAssembler(List.of(), List.of());
        RuleEvent event = new RuleEvent("1", "demo.login", "login", "u1", "evt1",
                Instant.now(), Map.of("amount", 5000), Map.of("amount", 99),
                EventSource.HTTP);

        EvalContext ctx = assembler.assemble(event, List.of(), new EvalEnv(Instant.now(), java.util.Map.of()));

        assertEquals(99, ctx.getMetric("amount").value());
        assertEquals(ValueSource.PROVIDED.tag(), ctx.getMetric("amount").valueSource());
    }
}
