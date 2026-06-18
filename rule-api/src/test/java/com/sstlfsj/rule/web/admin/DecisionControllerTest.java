package com.sstlfsj.rule.web.admin;

import com.sstlfsj.rule.config.api.service.DecisionService;
import com.sstlfsj.rule.config.internal.domain.DecisionDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** DecisionController 委托校验：路由方法把参数透传给 DecisionService。 */
class DecisionControllerTest {

    private final DecisionService service = mock(DecisionService.class);
    private final DecisionController controller = new DecisionController(service);

    @Test
    void create_delegatesToService() {
        when(service.create(9001L, "REJECT", "拒绝", 10, "desc", "actor")).thenReturn(7L);

        var resp = controller.create(9001L, "actor",
                new DecisionController.DecisionRequest("REJECT", "拒绝", 10, "desc"));

        assertThat(resp.data()).isEqualTo(7L);
        verify(service).create(9001L, "REJECT", "拒绝", 10, "desc", "actor");
    }

    @Test
    void disable_delegatesToService() {
        controller.disable("REJECT", 9001L, "actor");
        verify(service).disable(9001L, "REJECT", "actor");
    }

    @Test
    void list_delegatesToService() {
        when(service.list(9001L)).thenReturn(List.of(new DecisionDefinition()));
        var resp = controller.list(9001L);
        assertThat(resp.data()).hasSize(1);
    }

    @Test
    void sources_delegatesToService() {
        var refs = List.of(new com.sstlfsj.rule.config.api.service.DecisionService.RuleRef(
                101L, "risk.transfer", "转账", "risk.transfer", "PUBLISHED"));
        when(service.findRulesProducingDecision(9001L, "REJECT")).thenReturn(refs);
        var resp = controller.sources("REJECT", 9001L);
        assertThat(resp.data().decisionCode()).isEqualTo("REJECT");
        assertThat(resp.data().sourceCount()).isEqualTo(1);
        verify(service).findRulesProducingDecision(9001L, "REJECT");
    }

    @Test
    void get_delegatesToService() {
        var d = new DecisionDefinition(); d.setCode("REJECT");
        when(service.get(9001L, "REJECT")).thenReturn(d);
        assertThat(controller.get("REJECT", 9001L).data().getCode()).isEqualTo("REJECT");
    }

    @Test
    void usageCounts_delegatesToService() {
        when(service.countRuleUsages(9001L)).thenReturn(
                List.of(new com.sstlfsj.rule.config.api.service.UsageCount("REJECT", 3)));
        var resp = controller.usageCounts(9001L);
        assertThat(resp.data()).hasSize(1);
        assertThat(resp.data().get(0).count()).isEqualTo(3);
    }

    @Test
    void usageCountsPath_notCapturedByGetByCode() throws Exception {
        // 路由优先级：/usage-counts 字面段应命中 usageCounts() 而非 get("usage-counts")
        org.springframework.test.web.servlet.MockMvc mvc =
            org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();
        when(service.countRuleUsages(1L)).thenReturn(java.util.List.of());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/admin/v1/decisions/usage-counts").param("tenantId", "1"))
           .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        verify(service).countRuleUsages(1L);
        verify(service, never()).get(eq(1L), eq("usage-counts"));
    }
}
