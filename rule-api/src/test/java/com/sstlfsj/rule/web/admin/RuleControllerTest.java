package com.sstlfsj.rule.web.admin;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.sstlfsj.rule.config.api.dto.DraftCreatedResult;
import com.sstlfsj.rule.config.api.dto.RuleDetailVO;
import com.sstlfsj.rule.config.api.service.ConfigService;
import com.sstlfsj.rule.web.common.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** RuleController 单元测试：publish / disable / createDraft / listRules。 */
class RuleControllerTest {

    private MockMvc mockMvc;
    private ConfigService configService;

    @BeforeEach
    void setUp() {
        configService = mock(ConfigService.class);
        JsonMapper mapper = JsonMapper.builder().build();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new RuleController(configService, mapper))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new JacksonJsonHttpMessageConverter(mapper))
                .build();
    }

    @Test
    void getDetail_returns200_withRuleDetail() throws Exception {
        when(configService.getRuleDetail("t1", 10L)).thenReturn(
                new RuleDetailVO(10L, "rule.a", "规则A", "PUBLISHED", "AST_BOOLEAN",
                        "risk.transfer",
                        new com.sstlfsj.rule.kernel.api.model.ast.AndNode(java.util.List.of(), null, null),
                        java.util.List.of(), 42L));

        mockMvc.perform(get("/admin/v1/rules/10").param("tenantId", "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.ruleDefinitionId").value(10))
                .andExpect(jsonPath("$.data.sceneCode").value("risk.transfer"))
                .andExpect(jsonPath("$.data.conditionAst.type").value("AndNode"))
                .andExpect(jsonPath("$.data.currentVersionId").value(42));

        verify(configService).getRuleDetail("t1", 10L);
    }

    @Test
    void publish_returns200_andCallsService() throws Exception {
        when(configService.publish(any(), any(), any())).thenReturn(null);

        mockMvc.perform(post("/admin/v1/rules/1/publish")
                        .param("tenantId", "t1")
                        .header("X-Actor-Id", "user1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(configService).publish("t1", 1L, "user1");
    }

    @Test
    void disable_returns200_andCallsService() throws Exception {
        doNothing().when(configService).disable(any(), any(), any());

        mockMvc.perform(post("/admin/v1/rules/2/disable")
                        .param("tenantId", "t1")
                        .header("X-Actor-Id", "user1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(configService).disable("t1", 2L, "user1");
    }

    @Test
    void createDraft_returns201_withValidBody() throws Exception {
        DraftCreatedResult result = new DraftCreatedResult(10L, 20L, 1L, "DRAFT");
        when(configService.createDraft(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(result);

        mockMvc.perform(post("/admin/v1/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Actor-Id", "user1")
                        .content("""
                            {
                              "tenantId": "t1",
                              "sceneCode": "risk.transfer",
                              "code": "rule.a",
                              "name": "规则A",
                              "kind": "SCORECARD",
                              "conditionAst": {"type":"AndNode","children":[]},
                              "decisionBindings": [],
                              "preGates": [],
                              "triggerEventTypes": []
                            }
                            """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.ruleDefinitionId").value(10))
                .andExpect(jsonPath("$.data.ruleVersionId").value(20))
                .andExpect(jsonPath("$.data.status").value("DRAFT"));

        verify(configService).createDraft(eq("t1"), eq("risk.transfer"), eq("rule.a"), eq("规则A"),
                any(), any(), any(), any(), eq("SCORECARD"), eq("user1"));
    }

    @Test
    void createDraft_nullJsonFields_useDefaults() throws Exception {
        // conditionAst / decisionBindings 等 JsonNode 字段为 null 时，nodeToString 应返回默认值
        DraftCreatedResult result = new DraftCreatedResult(10L, 20L, 1L, "DRAFT");
        when(configService.createDraft(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(result);

        mockMvc.perform(post("/admin/v1/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Actor-Id", "user1")
                        .content("""
                            {"tenantId":"t1","sceneCode":"risk.transfer","code":"rule.a","name":"规则A"}
                            """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.ruleDefinitionId").value(10));

        // 验证 null JsonNode 字段传入了默认值字符串而非 null；kind 未传则为 null
        verify(configService).createDraft(eq("t1"), eq("risk.transfer"), eq("rule.a"), eq("规则A"),
                eq("{}"), eq("[]"), eq("[]"), eq("[]"), eq(null), eq("user1"));
    }

    @Test
    void createDraft_returns400_whenTenantIdMissing() throws Exception {
        mockMvc.perform(post("/admin/v1/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Actor-Id", "user1")
                        .content("""
                            {"sceneCode":"risk.transfer","code":"rule.a","name":"规则A"}
                            """))
                .andExpect(status().isBadRequest());
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

        mockMvc.perform(get("/admin/v1/rules")
                        .param("tenantId", "t1")
                        .param("sceneCode", "risk.transfer")
                        .param("status", "PUBLISHED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].ruleDefinitionId").value(10))
                .andExpect(jsonPath("$.data.items[0].code").value("rule.a"))
                .andExpect(jsonPath("$.data.items[0].status").value("PUBLISHED"));

        verify(configService).listRules("t1", "risk.transfer", "PUBLISHED", 1, 20);
    }

    @Test
    void listRules_withoutOptionalParams_usesDefaults() throws Exception {
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<
                com.sstlfsj.rule.config.api.dto.RuleListItemVO> emptyPage =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 20, 0);
        emptyPage.setRecords(java.util.List.of());
        when(configService.listRules("t1", null, null, 1, 20)).thenReturn(emptyPage);

        mockMvc.perform(get("/admin/v1/rules")
                        .param("tenantId", "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.total").value(0));

        verify(configService).listRules("t1", null, null, 1, 20);
    }
}
