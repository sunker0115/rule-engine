package com.sstlfsj.rule.web.stub;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class StubUpstreamControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new StubUpstreamController()).build();
    }

    @Test
    void score_returnsSuccessWithDefaultScore() throws Exception {
        mockMvc.perform(get("/stub/score/u1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.score").value(88))
                .andExpect(jsonPath("$.data.level").value("LOW_RISK"));
    }

    @Test
    void score_customScore_returnsCorrectLevel() throws Exception {
        mockMvc.perform(get("/stub/score/u1").param("score", "40"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.score").value(40))
                .andExpect(jsonPath("$.data.level").value("HIGH_RISK"));
    }

    @Test
    void score_fail_returnsErrorCode() throws Exception {
        mockMvc.perform(get("/stub/score/u1").param("fail", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1))
                .andExpect(jsonPath("$.msg").value("stub error"));
    }
}
