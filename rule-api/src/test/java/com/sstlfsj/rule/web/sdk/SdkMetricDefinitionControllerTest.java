package com.sstlfsj.rule.web.sdk;

import com.sstlfsj.rule.config.api.service.MetadataService;
import com.sstlfsj.rule.kernel.api.model.MetricDescriptor;
import com.sstlfsj.rule.web.common.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SdkMetricDefinitionControllerTest {

    private MockMvc mockMvc;
    private MetadataService metadataService;

    @BeforeEach
    void setUp() {
        metadataService = mock(MetadataService.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new SdkMetricDefinitionController(metadataService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void getMetricDefinitions_returns200WithData() throws Exception {
        when(metadataService.listMetricDefinitions(eq("t1"), any()))
                .thenReturn(List.of(new MetricDescriptor(
                        "risk.score", "SQL_AGGREGATE", "LONG", false, 60, Map.of("dataType", "LONG"))));

        mockMvc.perform(get("/sdk/v1/metric-definitions")
                        .param("tenantId", "t1")
                        .param("scenes", "fraud"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].metricCode").value("risk.score"))
                .andExpect(jsonPath("$.data[0].sourceType").value("SQL_AGGREGATE"));
    }

    @Test
    void getMetricDefinitions_missingTenantId_returns400() throws Exception {
        mockMvc.perform(get("/sdk/v1/metric-definitions"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getMetricDefinitions_emptyScenes_returnsArray() throws Exception {
        when(metadataService.listMetricDefinitions(eq("t1"), any())).thenReturn(List.of());

        mockMvc.perform(get("/sdk/v1/metric-definitions")
                        .param("tenantId", "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }
}
