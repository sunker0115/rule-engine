package com.sstlfsj.rule.web.admin;

import tools.jackson.databind.json.JsonMapper;
import com.sstlfsj.rule.config.api.dto.DraftCreatedResult;
import com.sstlfsj.rule.config.api.dto.RuleDetailVO;
import com.sstlfsj.rule.config.api.service.ConfigService;
import com.sstlfsj.rule.config.internal.domain.RuleDefinition;
import com.sstlfsj.rule.web.common.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
                .standaloneSetup(new RuleController(configService))
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
    void createDraft_returns400_whenAstTypeUnknown() throws Exception {
        // conditionAst 多态 type 不存在 → typed 绑定反序列化失败 → 400（本期新失败模式）
        String badBody = """
                {"tenantId":"t1","sceneCode":"s","code":"c","name":"n",
                 "conditionAst":{"type":"NoSuchNode"}}
                """;
        mockMvc.perform(post("/admin/v1/rules")
                        .header("X-Actor-Id", "u1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(badBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_ARGUMENT"));
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
    void editDraft_returns200_andCallsService() throws Exception {
        DraftCreatedResult result = new DraftCreatedResult(10L, 20L, 1L, "DRAFT");
        when(configService.editDraft(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(result);

        mockMvc.perform(put("/admin/v1/rules/10/draft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Actor-Id", "user1")
                        .content("""
                            {
                              "tenantId": "t1",
                              "name": "改名后",
                              "kind": "AST_BOOLEAN",
                              "conditionAst": {"type":"AndNode","children":[]},
                              "decisionBindings": [],
                              "preGates": [],
                              "triggerEventTypes": []
                            }
                            """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.ruleDefinitionId").value(10))
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.status").value("DRAFT"));

        // version 不变（草稿原地编辑），透传 ruleId / tenantId / name / kind
        verify(configService).editDraft(eq("t1"), eq(10L), eq("改名后"), eq("AST_BOOLEAN"),
                any(), any(), any(), any(), any(), eq("user1"));
    }

    @Test
    void editDraft_returns400_whenTenantIdMissing() throws Exception {
        mockMvc.perform(put("/admin/v1/rules/10/draft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Actor-Id", "user1")
                        .content("""
                            {"name":"改名后","kind":"AST_BOOLEAN"}
                            """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void newVersion_returns201_andCallsService() throws Exception {
        DraftCreatedResult result = new DraftCreatedResult(10L, 30L, 3L, "DRAFT");
        when(configService.newVersion(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(result);

        mockMvc.perform(post("/admin/v1/rules/10/versions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Actor-Id", "user1")
                        .content("""
                            {
                              "tenantId": "t1",
                              "name": "v3",
                              "kind": "AST_BOOLEAN",
                              "fromVersionId": 50
                            }
                            """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.ruleDefinitionId").value(10))
                .andExpect(jsonPath("$.data.version").value(3))
                .andExpect(jsonPath("$.data.status").value("DRAFT"));

        // 透传 tenantId / ruleId / name / kind / fromVersionId / actorId
        verify(configService).newVersion(eq("t1"), eq(10L), eq("v3"), eq("AST_BOOLEAN"),
                any(), any(), any(), any(), eq(50L), any(), eq("user1"));
    }

    @Test
    void newVersion_returns400_whenTenantIdMissing() throws Exception {
        mockMvc.perform(post("/admin/v1/rules/10/versions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Actor-Id", "user1")
                        .content("""
                            {"name":"v3","kind":"AST_BOOLEAN"}
                            """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deleteRule_returns200_andCallsService() throws Exception {
        doNothing().when(configService).deleteRule(any(), any(), any());

        mockMvc.perform(delete("/admin/v1/rules/10")
                        .param("tenantId", "t1")
                        .header("X-Actor-Id", "user1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(configService).deleteRule("t1", 10L, "user1");
    }

    @Test
    void deleteDraftVersion_returns200_andCallsService() throws Exception {
        doNothing().when(configService).deleteDraftVersion(any(), any(), any(), any());

        mockMvc.perform(delete("/admin/v1/rules/10/versions/100")
                        .param("tenantId", "t1")
                        .header("X-Actor-Id", "user1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(configService).deleteDraftVersion("t1", 10L, 100L, "user1");
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
        when(configService.createDraft(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
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
                any(), any(), any(), any(), eq("SCORECARD"), any(), eq("user1"));
    }

    @Test
    void createDraft_nullJsonFields_passedAsNull() throws Exception {
        // body 未带 conditionAst/decisionBindings 等字段时，typed 入参为 null，由 service 兜底默认
        DraftCreatedResult result = new DraftCreatedResult(10L, 20L, 1L, "DRAFT");
        when(configService.createDraft(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(result);

        mockMvc.perform(post("/admin/v1/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Actor-Id", "user1")
                        .content("""
                            {"tenantId":"t1","sceneCode":"risk.transfer","code":"rule.a","name":"规则A"}
                            """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.ruleDefinitionId").value(10));

        // 未传的 typed 字段为 null（不再有 JSON 串默认值），kind 未传也为 null
        verify(configService).createDraft(eq("t1"), eq("risk.transfer"), eq("rule.a"), eq("规则A"),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), eq("user1"));
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
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<RuleDefinition> page =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 20, 1);
        // Mock domain entity returned by service
        when(configService.listRules("t1", "risk.transfer", "PUBLISHED", null, null, 1, 20)).thenReturn(page);

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

        verify(configService).listRules("t1", "risk.transfer", "PUBLISHED", null, null, 1, 20);
    }

    @Test
    void listRules_withoutOptionalParams_usesDefaults() throws Exception {
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<RuleDefinition> emptyPage =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 20, 0);
        emptyPage.setRecords(java.util.List.of());
        when(configService.listRules("t1", null, null, null, null, 1, 20)).thenReturn(emptyPage);

        mockMvc.perform(get("/admin/v1/rules")
                        .param("tenantId", "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.total").value(0));

        verify(configService).listRules("t1", null, null, null, null, 1, 20);
    }
}
