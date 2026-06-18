package com.sstlfsj.rule.web.admin;

import com.sstlfsj.rule.config.api.dto.ImportDiffReport;
import com.sstlfsj.rule.config.api.dto.ImportDiffReport.RuleImportItem;
import com.sstlfsj.rule.config.api.dto.ImportDiffReport.RuleImportConflict;
import com.sstlfsj.rule.config.api.dto.ImportPolicy;
import com.sstlfsj.rule.config.api.dto.RuleBundle;
import com.sstlfsj.rule.config.api.service.RuleBundleService;
import com.sstlfsj.rule.config.internal.bundle.RuleImportService.ImportConflictException;
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

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** RuleBundleController v2 单元测试：export / import（含 dryRun、policy、ABORT 冲突）端点。 */
class RuleBundleControllerTest {

    private MockMvc mockMvc;
    private RuleBundleService service;

    private static final String BUNDLE_JSON = """
            {"formatVersion":2,"revision":"rev","exportedAt":"t","sourceTenant":"9",
             "rules":[{"code":"rule.a","name":"规则A","kind":"AST_BOOLEAN",
                     "sceneCode":"risk.transfer",
                     "conditionAst":{"type":"AndNode","children":[]},
                     "decisionBindings":[],"preGates":[],"triggerEventTypes":[],
                     "metricDependencies":[],"payloadDependencies":[],"script":null,"contentHash":"h1"}],
             "scenes":[{"code":"risk.transfer","name":"转账风控","description":null,
                      "subjectType":"USER","dominantMode":"PUSH",
                      "decisionStrategy":"HIGHEST_PRIORITY","eventTypes":[],
                      "payloadSchema":[],"defaultParams":{}}],
             "metricDefinitions":[],"decisionDefinitions":[]}
            """;

    @BeforeEach
    void setUp() {
        service = mock(RuleBundleService.class);
        JsonMapper mapper = JsonMapper.builder().build();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new RuleBundleController(service, mapper))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new ByteArrayHttpMessageConverter(),
                        new JacksonJsonHttpMessageConverter(mapper))
                .build();
    }

    private RuleBundle sampleBundle() {
        return new RuleBundle(2, "rev", "2026-06-06T10:00:00Z", "1",
                List.of(new RuleBundle.RuleEntry("rule.a", "规则A", "AST_BOOLEAN", "risk.transfer",
                        new com.sstlfsj.rule.kernel.api.model.ast.AndNode(List.of(), null, null),
                        List.of(), List.of(), List.of(),
                        List.of(new MetricDependency("account.age", 1)), List.of(),
                        null, "hash-a")),
                List.of(new RuleBundle.SceneSnapshot("risk.transfer", "转账风控", null, "USER",
                        "PUSH", "HIGHEST_PRIORITY", List.of(), List.of(), java.util.Map.of())),
                List.of(), List.of());
    }

    private ImportDiffReport okReport() {
        return new ImportDiffReport(
                List.of(new RuleImportItem("rule.a", "risk.transfer", "将新建")),
                List.of(), List.of(), List.of(), 1, 0, List.of(), 0);
    }

    private MockMultipartFile bundleFile() {
        return new MockMultipartFile("file", "bundle.json", "application/json",
                BUNDLE_JSON.getBytes(StandardCharsets.UTF_8));
    }

    // ---- export -------------------------------------------------------

    @Test
    void export_byRuleIds_returnsAttachmentFile() throws Exception {
        when(service.export(eq(1L), eq(List.of(1L, 2L)), eq(null))).thenReturn(sampleBundle());

        mockMvc.perform(get("/admin/v1/rules/export")
                        .param("tenantId", "1").param("ruleIds", "1,2"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        containsString("attachment; filename=\"rule-bundle-1-")))
                .andExpect(jsonPath("$.formatVersion").value(2))
                .andExpect(jsonPath("$.rules[0].code").value("rule.a"))
                .andExpect(jsonPath("$.rules[0].contentHash").value("hash-a"));

        verify(service).export(1L, List.of(1L, 2L), null);
    }

    @Test
    void export_returns400_whenTenantIdMissing() throws Exception {
        mockMvc.perform(get("/admin/v1/rules/export")).andExpect(status().isBadRequest());
    }

    // ---- import: SKIP default -----------------------------------------

    @Test
    void import_defaultPolicy_skipDryRunFalse_returns200WithDiffReport() throws Exception {
        when(service.importBundle(any(), any(), any(), anyBoolean(), any())).thenReturn(okReport());

        mockMvc.perform(multipart("/admin/v1/rules/import")
                        .file(bundleFile())
                        .param("tenantId", "1")
                        .header("X-Actor-Id", "u1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.willCreate[0].ruleCode").value("rule.a"))
                .andExpect(jsonPath("$.data.scenesCreated").value(1));

        verify(service).importBundle(eq(1L), any(RuleBundle.class), eq(ImportPolicy.SKIP), eq(false), eq("u1"));
    }

    // ---- import: dryRun=true ------------------------------------------

    @Test
    void import_dryRunTrue_returns200WithDiffReport_dbNotPersisted() throws Exception {
        ImportDiffReport dryRunReport = new ImportDiffReport(
                List.of(new RuleImportItem("rule.a", "risk.transfer", "将新建")),
                List.of(), List.of(), List.of(), 1, 0, List.of(), 0);
        when(service.importBundle(any(), any(), any(), anyBoolean(), any())).thenReturn(dryRunReport);

        mockMvc.perform(multipart("/admin/v1/rules/import")
                        .file(bundleFile())
                        .param("tenantId", "1")
                        .param("dryRun", "true")
                        .header("X-Actor-Id", "u1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.willCreate[0].ruleCode").value("rule.a"));
    }

    // ---- import: OVERWRITE policy -------------------------------------

    @Test
    void import_policyOverwrite_passedToService() throws Exception {
        when(service.importBundle(any(), any(), any(), anyBoolean(), any())).thenReturn(okReport());

        mockMvc.perform(multipart("/admin/v1/rules/import")
                        .file(bundleFile())
                        .param("tenantId", "1")
                        .param("policy", "OVERWRITE")
                        .header("X-Actor-Id", "u1"))
                .andExpect(status().isOk());

        verify(service).importBundle(eq(1L), any(RuleBundle.class), eq(ImportPolicy.OVERWRITE), eq(false), eq("u1"));
    }

    // ---- import: ABORT with conflicts → 422 --------------------------

    @Test
    void import_policyAbortWithConflicts_returns422() throws Exception {
        ImportDiffReport conflictReport = new ImportDiffReport(
                List.of(), List.of(), List.of(),
                List.of(new RuleImportConflict("rule.a", "risk.transfer", "CONTENT_CHANGED", "已存在")),
                0, 0, List.of(), 0);
        when(service.importBundle(any(), any(), any(), anyBoolean(), any()))
                .thenThrow(new ImportConflictException(conflictReport));

        mockMvc.perform(multipart("/admin/v1/rules/import")
                        .file(bundleFile())
                        .param("tenantId", "1")
                        .param("policy", "ABORT")
                        .header("X-Actor-Id", "u1"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("IMPORT_CONFLICT"));
    }

    // ---- import: malformed JSON → 400 --------------------------------

    @Test
    void import_malformedJson_returns400() throws Exception {
        MockMultipartFile badFile = new MockMultipartFile("file", "bad.json",
                "application/json", "not-json".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/admin/v1/rules/import")
                        .file(badFile)
                        .param("tenantId", "1")
                        .header("X-Actor-Id", "u1"))
                .andExpect(status().isBadRequest());
    }
}
