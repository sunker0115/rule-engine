package com.sstlfsj.rule.web.admin;

import com.sstlfsj.rule.config.api.dto.RuleBundle;
import com.sstlfsj.rule.config.api.dto.RuleImportResult;
import com.sstlfsj.rule.config.api.service.RuleBundleService;
import com.sstlfsj.rule.kernel.api.model.MetricDependency;
import com.sstlfsj.rule.web.common.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** RuleBundleController 单元测试：export（文件下载）/ import（文件上传）端点。 */
class RuleBundleControllerTest {

    private MockMvc mockMvc;
    private RuleBundleService service;

    @BeforeEach
    void setUp() {
        service = mock(RuleBundleService.class);
        JsonMapper mapper = JsonMapper.builder().build();
        // 导出返回 byte[]，需 ByteArrayHttpMessageConverter；导入结果与错误体走 Jackson 转换器
        mockMvc = MockMvcBuilders
                .standaloneSetup(new RuleBundleController(service, mapper))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new ByteArrayHttpMessageConverter(),
                        new JacksonJsonHttpMessageConverter(mapper))
                .build();
    }

    private RuleBundle sampleBundle() {
        return new RuleBundle(1, "2026-06-06T10:00:00Z", "1",
                List.of(new RuleBundle.RuleEntry("rule.a", "规则A", "AST_BOOLEAN", "risk.transfer",
                        new com.sstlfsj.rule.kernel.api.model.ast.AndNode(java.util.List.of(), null, null),
                        java.util.List.of(), java.util.List.of(), java.util.List.of(),
                        List.of(new MetricDependency("account.age", 1)), java.util.List.of())),
                List.of(new RuleBundle.SceneSnapshot("risk.transfer", "转账风控", null, "USER",
                        "PUSH", "HIGHEST_PRIORITY", java.util.List.of(), java.util.List.of(),
                        java.util.Map.of())),
                List.of(), List.of());
    }

    @Test
    void export_byRuleIds_returnsAttachmentFile() throws Exception {
        when(service.export(eq(1L), eq(List.of(1L, 2L)), eq(null))).thenReturn(sampleBundle());

        // 响应体是 Bundle JSON 文件原文（非 ApiResponse 包裹），故 jsonPath 直接落在 Bundle 字段上
        mockMvc.perform(get("/admin/v1/rules/export")
                        .param("tenantId", "1")
                        .param("ruleIds", "1,2"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        org.hamcrest.Matchers.containsString("attachment; filename=\"rule-bundle-1-")))
                .andExpect(jsonPath("$.bundleVersion").value(1))
                .andExpect(jsonPath("$.rules[0].code").value("rule.a"))
                .andExpect(jsonPath("$.scenes[0].code").value("risk.transfer"));

        verify(service).export(1L, List.of(1L, 2L), null);
    }

    @Test
    void export_bySceneId_returnsAttachmentFile() throws Exception {
        when(service.export(eq(1L), eq(null), eq(5L))).thenReturn(sampleBundle());

        mockMvc.perform(get("/admin/v1/rules/export")
                        .param("tenantId", "1")
                        .param("sceneId", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rules[0].code").value("rule.a"));

        verify(service).export(1L, null, 5L);
    }

    @Test
    void export_returns400_whenTenantIdMissing() throws Exception {
        mockMvc.perform(get("/admin/v1/rules/export"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void import_uploadFile_returns200_withResult() throws Exception {
        RuleImportResult result = new RuleImportResult(
                List.of(new RuleImportResult.ImportedRule(10L, 100L, 1L, "rule.a", "risk.transfer", false)),
                List.of("risk.transfer"), List.of(),
                List.of("account.age"), List.of(), List.of(),
                List.of("BLOCK"), List.of());
        when(service.importBundle(eq(1L), any(), eq("user1"))).thenReturn(result);

        String bundleJson = """
                {"bundleVersion":1,"exportedAt":"t","sourceTenantId":"9",
                 "rules":[{"code":"rule.a","name":"规则A","kind":"AST_BOOLEAN",
                         "sceneCode":"risk.transfer",
                         "conditionAst":{"type":"AndNode","children":[]},
                         "decisionBindings":[],"preGates":[],"triggerEventTypes":[],
                         "metricDependencies":[]}],
                 "scenes":[{"code":"risk.transfer","name":"转账风控","description":null,
                          "subjectType":"USER","dominantMode":"PUSH",
                          "decisionStrategy":"HIGHEST_PRIORITY","eventTypes":[],
                          "payloadSchema":[],"defaultParams":{}}],
                 "metricDefinitions":[],"decisionDefinitions":[]}
                """;
        MockMultipartFile file = new MockMultipartFile(
                "file", "rule-bundle.json", "application/json",
                bundleJson.getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/admin/v1/rules/import")
                        .file(file)
                        .param("tenantId", "1")
                        .header("X-Actor-Id", "user1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.rules[0].ruleDefinitionId").value(10))
                .andExpect(jsonPath("$.data.rules[0].version").value(1))
                .andExpect(jsonPath("$.data.scenesCreated[0]").value("risk.transfer"));

        verify(service).importBundle(eq(1L), any(), eq("user1"));
    }
}
