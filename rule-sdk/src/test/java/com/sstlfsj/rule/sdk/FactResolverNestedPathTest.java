package com.sstlfsj.rule.sdk;

import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.sdk.annotation.Fact;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FactResolverNestedPathTest {

    static class Holder {
        public void m(@Fact("order.amount") Integer amount,
                      @Fact("order.missing") Integer missing) {}
    }

    @Test
    void resolvesNestedPayloadPath_andNullWhenBroken() throws Exception {
        Method m = Holder.class.getMethod("m", Integer.class, Integer.class);
        RuleEvent e = RuleEvent.builder().tenantId("t").sceneCode("s").eventType("evt")
                .subjectId("u").eventId("e1").occurredAt(Instant.now())
                .payload(Map.of("order", Map.of("amount", 8000))).source(EventSource.SDK).build();
        EvalContext ctx = new EvalContext("t", e, null, Map.of(), Instant.now());

        Object[] args = new FactResolver().resolve(m.getParameters(), ctx, null);
        assertThat(args[0]).isEqualTo(8000);  // order.amount 下钻命中
        assertThat(args[1]).isNull();         // order.missing 断链 → null
    }
}
