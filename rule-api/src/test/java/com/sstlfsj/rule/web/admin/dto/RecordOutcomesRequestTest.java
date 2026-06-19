package com.sstlfsj.rule.web.admin.dto;

import com.sstlfsj.rule.web.admin.dto.RecordOutcomesRequest.OutcomeItem;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RecordOutcomesRequestTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();

    @Test
    void bindsFullBatch() {
        String json = """
            {"tenantId":1,"outcomes":[
              {"eventId":"evt-1","outcomeLabel":"FRAUD","outcomeValue":1280.50,
               "labeledAt":"2026-06-18T10:00:00Z","source":"ops","note":"chargeback"}]}
            """;
        RecordOutcomesRequest req = mapper.readValue(json, RecordOutcomesRequest.class);

        assertThat(req.tenantId()).isEqualTo(1L);
        assertThat(req.outcomes()).hasSize(1);
        OutcomeItem o = req.outcomes().get(0);
        assertThat(o.eventId()).isEqualTo("evt-1");
        assertThat(o.outcomeLabel()).isEqualTo("FRAUD");
        assertThat(o.outcomeValue()).isEqualByComparingTo(new BigDecimal("1280.50"));
        assertThat(o.labeledAt()).isEqualTo(Instant.parse("2026-06-18T10:00:00Z"));
        assertThat(o.source()).isEqualTo("ops");
        assertThat(o.note()).isEqualTo("chargeback");
    }

    @Test
    void optionalFieldsMissing_bindNull() {
        String json = """
            {"tenantId":1,"outcomes":[
              {"eventId":"evt-2","outcomeLabel":"NOT_FRAUD","labeledAt":"2026-06-18T10:00:00Z"}]}
            """;
        RecordOutcomesRequest req = mapper.readValue(json, RecordOutcomesRequest.class);

        OutcomeItem o = req.outcomes().get(0);
        assertThat(o.outcomeValue()).isNull();
        assertThat(o.source()).isNull();
        assertThat(o.note()).isNull();
    }
}
