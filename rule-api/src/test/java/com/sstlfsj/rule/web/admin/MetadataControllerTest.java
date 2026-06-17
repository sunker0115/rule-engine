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
                List.of(), List.of(), List.of("payment.create", "payment.update"), List.of());
        when(metadataService.getSceneMetadata(1L, "PAYMENT")).thenReturn(resp);

        mockMvc.perform(get("/admin/v1/scenes/PAYMENT/metadata")
                        .param("tenantId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(metadataService).getSceneMetadata(1L, "PAYMENT");
    }
}
