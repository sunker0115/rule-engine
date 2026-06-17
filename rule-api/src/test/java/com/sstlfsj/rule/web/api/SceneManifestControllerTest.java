package com.sstlfsj.rule.web.api;

import com.sstlfsj.rule.config.api.service.MetadataService;
import com.sstlfsj.rule.config.api.service.MetadataService.InputFieldSpec;
import com.sstlfsj.rule.config.api.service.MetadataService.InputManifestResponse;
import com.sstlfsj.rule.config.api.service.TenantQueryService;
import com.sstlfsj.rule.web.common.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SceneManifestControllerTest {

    private MockMvc mockMvc;
    private MetadataService metadataService;
    private TenantQueryService tenantQueryService;

    @BeforeEach
    void setUp() {
        metadataService = mock(MetadataService.class);
        tenantQueryService = mock(TenantQueryService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new SceneManifestController(metadataService, tenantQueryService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void inputManifest_returns200_withUnionFields() throws Exception {
        when(tenantQueryService.resolveIdByCode("acme")).thenReturn(9001L);
        when(metadataService.getInputManifest(9001L, "demo.login", null))
                .thenReturn(new InputManifestResponse(List.of(
                        new InputFieldSpec("amount", "DECIMAL", true),
                        new InputFieldSpec("country", "STRING", true))));

        mockMvc.perform(get("/api/v1/rule/scenes/demo.login/input-manifest")
                        .param("tenantCode", "acme"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.fields[0].name").value("amount"))
                .andExpect(jsonPath("$.data.fields[1].name").value("country"));
    }

    @Test
    void inputManifest_passesEventTypeThrough() throws Exception {
        when(tenantQueryService.resolveIdByCode("acme")).thenReturn(9001L);
        when(metadataService.getInputManifest(9001L, "demo.login", "login"))
                .thenReturn(new InputManifestResponse(List.of()));

        mockMvc.perform(get("/api/v1/rule/scenes/demo.login/input-manifest")
                        .param("tenantCode", "acme")
                        .param("eventType", "login"))
                .andExpect(status().isOk());

        verify(metadataService).getInputManifest(9001L, "demo.login", "login");
    }

    @Test
    void inputManifest_returns400_whenTenantCodeUnknown() throws Exception {
        // 未知租户 code → 解析返回 null → 400，不进 service
        when(tenantQueryService.resolveIdByCode("ghost")).thenReturn(null);

        mockMvc.perform(get("/api/v1/rule/scenes/demo.login/input-manifest")
                        .param("tenantCode", "ghost"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(metadataService);
    }
}
