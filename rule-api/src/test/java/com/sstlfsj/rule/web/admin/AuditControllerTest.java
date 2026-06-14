package com.sstlfsj.rule.web.admin;

import com.sstlfsj.rule.audit.api.service.AuditService;
import com.sstlfsj.rule.config.api.service.SceneService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Set;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AuditControllerTest {

    private MockMvc mockMvc;
    private AuditService auditService;
    private SceneService sceneService;

    @BeforeEach
    void setUp() {
        auditService = mock(AuditService.class);
        sceneService = mock(SceneService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new AuditController(auditService, sceneService)).build();
    }

    @Test
    void querySessions_returns200() throws Exception {
        AuditService.PageResult<AuditService.EvalSessionEntry> empty =
                new AuditService.PageResult<>(List.of(), 0, 0, 20);
        when(auditService.queryEvalSessions("t1", null, 0, 20)).thenReturn(empty);

        mockMvc.perform(get("/admin/v1/evaluation-sessions").param("tenantId", "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.page").value(1));

        // 默认 page=1 → service 收到 0-based 的 0
        verify(auditService).queryEvalSessions("t1", null, 0, 20);
    }

    @Test
    void queryAuditLogs_returns200() throws Exception {
        AuditService.PageResult<AuditService.AuditLogEntry> empty =
                new AuditService.PageResult<>(List.of(), 0, 0, 20);
        when(auditService.queryAuditLogs("t1", null, null, null, null, null, null, 0, 20)).thenReturn(empty);

        mockMvc.perform(get("/admin/v1/audit-logs").param("tenantId", "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(auditService).queryAuditLogs("t1", null, null, null, null, null, null, 0, 20);
    }

    @Test
    void queryTrace_returns200_withNodes() throws Exception {
        AuditService.TraceNodeEntry node = new AuditService.TraceNodeEntry(
                "0", "AND", null, null, null, true, null, null, null, null);
        when(auditService.queryTrace("t1", 42L)).thenReturn(List.of(node));

        mockMvc.perform(get("/admin/v1/evaluation-sessions/42/trace").param("tenantId", "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].nodePath").value("0"))
                .andExpect(jsonPath("$.data[0].result").value(true));

        verify(auditService).queryTrace("t1", 42L);
    }

    @Test
    void queryTrace_returns200_whenEmpty() throws Exception {
        when(auditService.queryTrace("t1", 99L)).thenReturn(List.of());

        mockMvc.perform(get("/admin/v1/evaluation-sessions/99/trace").param("tenantId", "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());

        verify(auditService).queryTrace("t1", 99L);
    }

    @Test
    void getTraceTree_返回嵌套结构() throws Exception {
        AuditService.TraceTreeNode child = new AuditService.TraceTreeNode(
                "ConditionNode", "GT", "user.age", "25", true, null, "FETCHED", null, null, List.of());
        AuditService.TraceTreeNode root = new AuditService.TraceTreeNode(
                "AndNode", null, null, null, true, null, null, null, null, List.of(child));
        when(auditService.queryTraceTree("100", 1L)).thenReturn(List.of(root));

        mockMvc.perform(get("/admin/v1/evaluation-sessions/1/trace/tree").param("tenantId", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$.data[0].nodeType").value("AndNode"))
                .andExpect(jsonPath("$.data[0].children", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$.data[0].children[0].metricCode").value("user.age"));
    }

    @Test
    void getTraceTree_空结果返回空数组() throws Exception {
        when(auditService.queryTraceTree("100", 99L)).thenReturn(List.of());

        mockMvc.perform(get("/admin/v1/evaluation-sessions/99/trace/tree").param("tenantId", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void queryTrace_masksSensitiveLeafValues() throws Exception {
        when(auditService.getSessionSceneCode("100", 1L)).thenReturn("risk.transfer");
        when(sceneService.getSensitiveRefs("100", "risk.transfer"))
                .thenReturn(new SceneService.SensitiveRefs(Set.of("phone"), Set.of()));
        when(auditService.queryTrace("100", 1L)).thenReturn(List.of(
                new AuditService.TraceNodeEntry(
                        "0.0", "ConditionNode", "EQ", "phone", "13800001111",
                        true, null, "PAYLOAD", "ruleA", 1L)));

        mockMvc.perform(get("/admin/v1/evaluation-sessions/1/trace").param("tenantId", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].actualValue").value("***"));
    }

    @Test
    void queryTrace_configUnavailable_failClosedMasksAll() throws Exception {
        when(auditService.getSessionSceneCode("100", 1L)).thenReturn("risk.transfer");
        when(sceneService.getSensitiveRefs("100", "risk.transfer"))
                .thenThrow(new RuntimeException("config down"));
        when(auditService.queryTrace("100", 1L)).thenReturn(List.of(
                new AuditService.TraceNodeEntry(
                        "0.0", "ConditionNode", "EQ", "amount", "100",
                        true, null, "PAYLOAD", "ruleA", 1L)));

        mockMvc.perform(get("/admin/v1/evaluation-sessions/1/trace").param("tenantId", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].actualValue").value("***"));
    }

    @Test
    void querySessionsByRule_returns200_withDefaultParams() throws Exception {
        AuditService.PageResult<AuditService.RuleSessionEntry> empty =
                new AuditService.PageResult<>(List.of(), 0L, 0, 20);
        when(auditService.querySessionsByRuleDefinition(42L, null, 20, 0)).thenReturn(empty);

        mockMvc.perform(get("/admin/v1/rules/42/sessions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(auditService).querySessionsByRuleDefinition(42L, null, 20, 0);
    }

    @Test
    void querySessionsByRule_withStatusFilter_passesStatusToService() throws Exception {
        AuditService.PageResult<AuditService.RuleSessionEntry> empty =
                new AuditService.PageResult<>(List.of(), 0L, 0, 20);
        when(auditService.querySessionsByRuleDefinition(7L, "HIT", 20, 0)).thenReturn(empty);

        mockMvc.perform(get("/admin/v1/rules/7/sessions").param("status", "HIT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(auditService).querySessionsByRuleDefinition(7L, "HIT", 20, 0);
    }

    @Test
    void querySessionsByRule_withPageSize_passesCorrectly() throws Exception {
        AuditService.PageResult<AuditService.RuleSessionEntry> empty =
                new AuditService.PageResult<>(List.of(), 0L, 2, 10);
        when(auditService.querySessionsByRuleDefinition(1L, null, 10, 20)).thenReturn(empty);

        // page=3,size=10 → offset=(3-1)*10=20
        mockMvc.perform(get("/admin/v1/rules/1/sessions")
                        .param("page", "3")
                        .param("size", "10"))
                .andExpect(status().isOk());

        verify(auditService).querySessionsByRuleDefinition(1L, null, 10, 20);
    }
}
