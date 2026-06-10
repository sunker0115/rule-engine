package com.sstlfsj.rule.web.api;

import com.sstlfsj.rule.web.api.dto.EvalEventRequest;
import org.junit.jupiter.api.Test;
import java.lang.reflect.RecordComponent;
import static org.assertj.core.api.Assertions.assertThat;

class EvalEventRequestContractTest {
    @Test
    void hasNoProvidedMetricsComponent() {
        assertThat(java.util.Arrays.stream(EvalEventRequest.class.getRecordComponents())
                .map(RecordComponent::getName))
                .doesNotContain("providedMetrics");
    }
}
