package com.sstlfsj.rule.web.audit;

import com.sstlfsj.rule.audit.api.service.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AuditControllerTest {

    private MockMvc mockMvc;
    private AuditService auditService;

    @BeforeEach
    void setUp() {
        auditService = mock(AuditService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new AuditController(auditService)).build();
    }

    @Test
    void querySessions_returns200() throws Exception {
        AuditService.PageResult<AuditService.EvalSessionEntry> empty =
                new AuditService.PageResult<>(List.of(), 0, 0, 20);
        when(auditService.queryEvalSessions("t1", null, 0, 20)).thenReturn(empty);

        mockMvc.perform(get("/api/v1/evaluation-sessions").param("tenantId", "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(auditService).queryEvalSessions("t1", null, 0, 20);
    }

    @Test
    void queryAuditLogs_returns200() throws Exception {
        AuditService.PageResult<AuditService.AuditLogEntry> empty =
                new AuditService.PageResult<>(List.of(), 0, 0, 20);
        when(auditService.queryAuditLogs("t1", null, null, 0, 20)).thenReturn(empty);

        mockMvc.perform(get("/api/v1/audit-logs").param("tenantId", "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(auditService).queryAuditLogs("t1", null, null, 0, 20);
    }
}
