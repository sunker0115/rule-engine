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

class MetadataControllerTest {

    private MockMvc mockMvc;
    private MetadataService metadataService;

    @BeforeEach
    void setUp() {
        metadataService = mock(MetadataService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new MetadataController(metadataService)).build();
    }

    @Test
    void getMetadata_returns200_withResponse() throws Exception {
        MetadataService.MetadataResponse resp = new MetadataService.MetadataResponse(
                List.of(), List.of(), List.of());
        when(metadataService.getSceneMetadata("t1", "PAYMENT")).thenReturn(resp);

        mockMvc.perform(get("/admin/v1/scenes/PAYMENT/metadata")
                        .param("tenantId", "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(metadataService).getSceneMetadata("t1", "PAYMENT");
    }

    @Test
    void getProvidedMetrics_返回allowProvided为true的指标() throws Exception {
        MetadataService.MetricMeta allowedMetric = new MetadataService.MetricMeta(
                "user.kyc.level", "KYC等级", "LONG", "ATTRIBUTE", true);
        MetadataService.ProvidedMetricsResponse resp =
                new MetadataService.ProvidedMetricsResponse(List.of(allowedMetric));
        when(metadataService.getProvidedMetrics("100", "risk.transfer")).thenReturn(resp);

        mockMvc.perform(get("/admin/v1/scenes/risk.transfer/provided-metrics")
                        .param("tenantId", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.metrics", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$.data.metrics[0].metricCode").value("user.kyc.level"))
                .andExpect(jsonPath("$.data.metrics[0].allowProvided").value(true));
    }

    @Test
    void getProvidedMetrics_无allowProvided指标时返回空列表() throws Exception {
        when(metadataService.getProvidedMetrics("100", "risk.transfer"))
                .thenReturn(new MetadataService.ProvidedMetricsResponse(List.of()));

        mockMvc.perform(get("/admin/v1/scenes/risk.transfer/provided-metrics")
                        .param("tenantId", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.metrics", org.hamcrest.Matchers.hasSize(0)));
    }
}
