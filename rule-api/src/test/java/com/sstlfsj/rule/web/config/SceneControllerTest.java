package com.sstlfsj.rule.web.config;

import com.sstlfsj.rule.config.api.service.SceneService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SceneControllerTest {

    private MockMvc mockMvc;
    private SceneService sceneService;

    @BeforeEach
    void setUp() {
        sceneService = mock(SceneService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new SceneController(sceneService)).build();
    }

    @Test
    void createScene_returns200_withId() throws Exception {
        when(sceneService.createScene("t1", "PAYMENT", "支付场景", "user1")).thenReturn(42L);

        mockMvc.perform(post("/api/v1/scenes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tenantId":"t1","code":"PAYMENT","name":"支付场景"}
                                """)
                        .header("X-Actor-Id", "user1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(42));

        verify(sceneService).createScene("t1", "PAYMENT", "支付场景", "user1");
    }
}
