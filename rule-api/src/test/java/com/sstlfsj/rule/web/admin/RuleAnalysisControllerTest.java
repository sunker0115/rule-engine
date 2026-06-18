package com.sstlfsj.rule.web.admin;

import com.sstlfsj.rule.config.api.service.RuleAnalysisService;
import com.sstlfsj.rule.kernel.api.analysis.ConflictFinding;
import com.sstlfsj.rule.kernel.api.analysis.DeadRuleFinding;
import com.sstlfsj.rule.kernel.api.analysis.RuleSetAnalysisReport;
import com.sstlfsj.rule.kernel.api.analysis.Severity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RuleAnalysisControllerTest {

    private MockMvc mockMvc;
    private RuleAnalysisService ruleAnalysisService;

    @BeforeEach
    void setUp() {
        ruleAnalysisService = mock(RuleAnalysisService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new RuleAnalysisController(ruleAnalysisService)).build();
    }

    @Test
    void analyze_returns200_withReportAndSeverityAsName() throws Exception {
        RuleSetAnalysisReport report = new RuleSetAnalysisReport(
                "PAYMENT",
                List.of(),
                List.of(new DeadRuleFinding("R2", "R1", "被 R1 完全覆盖", Severity.WARN)),
                List.of(new ConflictFinding("R3", "R4", "APPROVE", "REJECT", "输入相交产出对立", Severity.ERROR)),
                List.of(),
                List.of(),
                List.of(),
                List.of());
        when(ruleAnalysisService.analyze(1L, "PAYMENT")).thenReturn(report);

        mockMvc.perform(get("/admin/v1/scenes/PAYMENT/analysis")
                        .param("tenantId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.sceneCode").value("PAYMENT"))
                .andExpect(jsonPath("$.data.deadRules[0].deadRuleCode").value("R2"))
                .andExpect(jsonPath("$.data.deadRules[0].coveredByRuleCode").value("R1"))
                // Severity enum 以 name 字符串序列化
                .andExpect(jsonPath("$.data.deadRules[0].severity").value("WARN"))
                .andExpect(jsonPath("$.data.conflicts[0].locA").value("R3"))
                .andExpect(jsonPath("$.data.conflicts[0].severity").value("ERROR"));

        verify(ruleAnalysisService).analyze(1L, "PAYMENT");
    }
}
