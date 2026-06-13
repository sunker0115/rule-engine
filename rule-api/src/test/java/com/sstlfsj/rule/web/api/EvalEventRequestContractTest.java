package com.sstlfsj.rule.web.api;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sstlfsj.rule.web.api.dto.EvalEventRequest;
import org.junit.jupiter.api.Test;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;

class EvalEventRequestContractTest {
    @Test
    void hasNoProvidedMetricsComponent() {
        assertThat(java.util.Arrays.stream(EvalEventRequest.class.getRecordComponents())
                .map(RecordComponent::getName))
                .doesNotContain("providedMetrics");
    }

    @Test
    void deserializesOptionalAsOfFromIso8601() throws Exception {
        JsonMapper mapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
        // 携带 asOf：反序列化为 ISO-8601 Instant
        EvalEventRequest withAsOf = mapper.readValue(
                "{\"tenantCode\":\"acme\",\"asOf\":\"2020-01-01T00:00:00Z\"}", EvalEventRequest.class);
        assertThat(withAsOf.asOf()).isEqualTo(Instant.parse("2020-01-01T00:00:00Z"));
        // 省略 asOf：为 null（引擎降级用 Instant.now()）
        EvalEventRequest withoutAsOf = mapper.readValue(
                "{\"tenantCode\":\"acme\"}", EvalEventRequest.class);
        assertThat(withoutAsOf.asOf()).isNull();
    }
}
