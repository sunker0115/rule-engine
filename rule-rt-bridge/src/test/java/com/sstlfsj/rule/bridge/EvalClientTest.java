package com.sstlfsj.rule.bridge;

import com.sstlfsj.rule.bridge.model.SuspectPayload;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class EvalClientTest {

    @Test
    void postsEvalRequestAndExtractsDecision() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://rule-api");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        // ApiResponse<EvalResult> envelope，decision 在 data.finalDecision.code
        server.expect(requestTo("http://rule-api/api/v1/rule/evaluate"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(jsonPath("$.tenantCode").value("t1"))
                .andExpect(jsonPath("$.subjectId").value("c1"))
                .andExpect(jsonPath("$.eventId").value("c1-100"))
                .andRespond(withSuccess(
                        "{\"success\":true,\"data\":{\"finalDecision\":{\"code\":\"HIGH\"}}}",
                        MediaType.APPLICATION_JSON));

        EvalClient client = new EvalClient(builder.build(), "t1", "trading.scene_b", "trade.suspect");
        SuspectPayload p = new SuspectPayload("c1", 9, 9, 9, 9, 500.0, 1.0, 0.7, "SHORT_ALPHA",
                "c1-100", Instant.parse("2026-06-20T07:00:00Z"));

        String decision = client.evaluate(p);
        assertThat(decision).isEqualTo("HIGH");
        server.verify();
    }
}
