package com.sstlfsj.rule.web.eval;

import com.sstlfsj.rule.eval.api.service.EvalService;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class EvalControllerTest {

    private MockMvc mockMvc;
    private EvalService evalService;

    private static final String EVENT_JSON = """
            {"tenantId":"t1","sceneCode":"PAYMENT","eventType":"ORDER",
             "subjectId":"u1","eventId":"evt-1","occurredAt":null,
             "payload":{},"providedMetrics":{}}
            """;

    @BeforeEach
    void setUp() {
        evalService = mock(EvalService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new EvalController(evalService)).build();
    }

    @Test
    void pushEvent_returns202_whenAccepted() throws Exception {
        when(evalService.acceptEvent(any())).thenReturn(true);

        mockMvc.perform(post("/api/v1/rule/event")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(EVENT_JSON))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.accepted").value(true));
    }

    @Test
    void evaluate_returns200_withResult() throws Exception {
        when(evalService.evaluate(any())).thenReturn(EvalResult.miss());

        mockMvc.perform(post("/api/v1/rule/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(EVENT_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void dryRun_returns200_withResult() throws Exception {
        when(evalService.dryRun(any(), isNull())).thenReturn(EvalResult.miss());

        mockMvc.perform(post("/api/v1/rule/dry-run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(EVENT_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }
}
