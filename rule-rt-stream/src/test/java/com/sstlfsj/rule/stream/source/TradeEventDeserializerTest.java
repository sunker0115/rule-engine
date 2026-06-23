package com.sstlfsj.rule.stream.source;

import com.sstlfsj.rule.stream.model.TradeEvent;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.assertThat;

class TradeEventDeserializerTest {

    private final TradeEventDeserializer d = new TradeEventDeserializer();

    @Test
    void roundTrip() {
        String json = "{\"customerId\":\"cust-1\",\"instrument\":\"BTC\",\"amount\":150.00,"
                + "\"channel\":\"API\",\"occurredAt\":\"2026-06-20T10:00:00Z\",\"eventId\":\"evt-1\"}";
        TradeEvent e = d.deserialize(json.getBytes(StandardCharsets.UTF_8));
        assertThat(e.customerId()).isEqualTo("cust-1");
        assertThat(e.amount()).isEqualByComparingTo(new BigDecimal("150.00"));
        assertThat(e.channel()).isEqualTo("API");
        assertThat(e.eventId()).isEqualTo("evt-1");
    }
}
