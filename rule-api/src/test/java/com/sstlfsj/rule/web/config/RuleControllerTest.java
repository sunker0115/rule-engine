package com.sstlfsj.rule.web.config;

import com.sstlfsj.rule.config.api.service.ConfigService;
import com.sstlfsj.rule.web.common.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

    @Test
    void createDraft_withoutBody_stillReturns501() throws Exception {
        // 移除 @RequestBody 后，无请求体也应直接返回 501，不触发 400
        mockMvc.perform(post("/api/v1/rules")
                        .header("X-Actor-Id", "user1"))
                .andExpect(status().isNotImplemented());
    }

    @Test
    void listRules_returns200_withPageResult() throws Exception {
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<
                com.sstlfsj.rule.config.api.dto.RuleListItemVO> page =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 20, 1);
        page.setRecords(java.util.List.of(
                new com.sstlfsj.rule.config.api.dto.RuleListItemVO(
                        10L, "rule.a", "规则A", "PUBLISHED", 42L,
                        java.time.LocalDateTime.of(2026, 6, 1, 0, 0))
        ));
        when(configService.listRules("t1", "risk.transfer", "PUBLISHED", 1, 20)).thenReturn(page);

        mockMvc.perform(get("/api/v1/rules")
                        .param("tenantId", "t1")
                        .param("sceneCode", "risk.transfer")
                        .param("status", "PUBLISHED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].ruleDefinitionId").value(10))
                .andExpect(jsonPath("$.data.records[0].code").value("rule.a"))
                .andExpect(jsonPath("$.data.records[0].status").value("PUBLISHED"));

        verify(configService).listRules("t1", "risk.transfer", "PUBLISHED", 1, 20);
    }

    @Test
    void listRules_withoutOptionalParams_usesDefaults() throws Exception {
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<
                com.sstlfsj.rule.config.api.dto.RuleListItemVO> emptyPage =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 20, 0);
        emptyPage.setRecords(java.util.List.of());
        when(configService.listRules("t1", null, null, 1, 20)).thenReturn(emptyPage);

        mockMvc.perform(get("/api/v1/rules")
                        .param("tenantId", "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.total").value(0));

        verify(configService).listRules("t1", null, null, 1, 20);
    }
}
