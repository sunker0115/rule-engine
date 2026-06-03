package com.sstlfsj.rule.web.config;

import com.sstlfsj.rule.config.api.service.ConfigService;
import com.sstlfsj.rule.web.common.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** RuleController 单元测试：publish / disable / createDraft。 */
class RuleControllerTest {

    private MockMvc mockMvc;
    private ConfigService configService;

    @BeforeEach
    void setUp() {
        configService = mock(ConfigService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new RuleController(configService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void publish_returns200_andCallsService() throws Exception {
        when(configService.publish(any(), any(), any())).thenReturn(null);

        mockMvc.perform(post("/api/v1/rules/1/publish")
                        .param("tenantId", "t1")
                        .header("X-Actor-Id", "user1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(configService).publish("t1", 1L, "user1");
    }

    @Test
    void disable_returns200_andCallsService() throws Exception {
        doNothing().when(configService).disable(any(), any(), any());

        mockMvc.perform(post("/api/v1/rules/2/disable")
                        .param("tenantId", "t1")
                        .header("X-Actor-Id", "user1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(configService).disable("t1", 2L, "user1");
    }

    @Test
    void createDraft_returns501_notImplemented() throws Exception {
        mockMvc.perform(post("/api/v1/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Actor-Id", "user1")
                        .content("""
                            {"tenantId":"t1","sceneId":1,"code":"r1","name":"规则1"}
                            """))
                .andExpect(status().isNotImplemented());
    }
}
