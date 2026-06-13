package com.sstlfsj.rule.sdk;

import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.sdk.annotation.Fact;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class FactResolverRequiredDefaultTest {

    static class Holder {
        public void m(@Fact(value = "level", defaultValue = "3") Integer level,
                      @Fact(value = "mustHave", required = true) String mustHave) {}
    }

    private EvalContext emptyCtx() {
        RuleEvent e = RuleEvent.builder().tenantId("t").sceneCode("s").eventType("evt")
                .subjectId("u").eventId("e1").occurredAt(Instant.now())
                .payload(Map.of()).source(EventSource.SDK).build();
        return new EvalContext("t", e, null, Map.of(), Instant.now());
    }

    @Test
    void defaultValue_appliedWhenMissing() throws Exception {
        Method m = Holder.class.getMethod("m", Integer.class, String.class);
        // 只解析第 0 个参数(level):用 payload 提供 mustHave 避免 required 抛错
        RuleEvent e = RuleEvent.builder().tenantId("t").sceneCode("s").eventType("evt")
                .subjectId("u").eventId("e1").occurredAt(Instant.now())
                .payload(Map.of("mustHave", "x")).source(EventSource.SDK).build();
        EvalContext ctx = new EvalContext("t", e, null, Map.of(), Instant.now());
        Object[] args = new FactResolver().resolve(m.getParameters(), ctx, null);
        assertThat(args[0]).isEqualTo(3);     // defaultValue "3" → Integer 3
        assertThat(args[1]).isEqualTo("x");
    }

    @Test
    void required_missing_throwsMissingFact() throws Exception {
        Method m = Holder.class.getMethod("m", Integer.class, String.class);
        assertThatThrownBy(() -> new FactResolver().resolve(m.getParameters(), emptyCtx(), null))
                .isInstanceOf(MissingFactException.class)
                .hasMessageContaining("mustHave");
    }
}
