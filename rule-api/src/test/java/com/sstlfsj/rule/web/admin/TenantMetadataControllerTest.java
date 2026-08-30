package com.sstlfsj.rule.web.admin;

import com.sstlfsj.rule.config.api.service.MetadataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class TenantMetadataControllerTest {

    private MockMvc mockMvc;
    private MetadataService metadataService;

    @BeforeEach
    void setUp() {
        metadataService = mock(MetadataService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new TenantMetadataController(metadataService)).build();
    }

    @Test
    void getTenantMetadata_returns200_withConditionTypesAndMetrics() throws Exception {
        MetadataService.MetadataResponse resp = new MetadataService.MetadataResponse(
                List.of(), List.of(), List.of(), List.of("CEL", "AVIATOR"));
        when(metadataService.getTenantMetadata(1L)).thenReturn(resp);

        mockMvc.perform(get("/admin/v1/metadata").param("tenantId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.expressionLangs[0]").value("CEL"));

        verify(metadataService).getTenantMetadata(1L);
    }

    @Test
    void getTenantMetadata_noScene_doesNotCallSceneMetadata() throws Exception {
        when(metadataService.getTenantMetadata(9001L))
                .thenReturn(new MetadataService.MetadataResponse(List.of(), List.of(), List.of(), List.of()));

        mockMvc.perform(get("/admin/v1/metadata").param("tenantId", "9001"))
                .andExpect(status().isOk());

        // 确认没有调 scene 相关方法——这是租户级端点的核心保证
        verify(metadataService, never()).getSceneMetadata(any(), any());
        verify(metadataService).getTenantMetadata(9001L);
    }
}
