package com.sstlfsj.rule.web.admin;

import com.sstlfsj.rule.config.api.service.SceneActionBindingService;
import com.sstlfsj.rule.config.api.service.SceneActionBindingService.SceneActionBindingItem;
import com.sstlfsj.rule.web.common.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** SceneActionBindingController 单元测试（Object↔JSON 透传 + 整组覆盖）。 */
class SceneActionBindingControllerTest {

    private MockMvc mockMvc;
    private SceneActionBindingService bindingService;

    @BeforeEach
    void setUp() {
        bindingService = mock(SceneActionBindingService.class);
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new SceneActionBindingController(bindingService, JsonMapper.builder().build()))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    void list_returns200_withParsedObjects() throws Exception {
        when(bindingService.list("1", "PAY")).thenReturn(List.of(
                new SceneActionBindingItem("BLOCK_TX", "{\"reason\":\"risk\"}", null)));

        mockMvc.perform(get("/admin/v1/scenes/PAY/action-bindings").param("tenantId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].actionType").value("BLOCK_TX"))
                .andExpect(jsonPath("$.data[0].defaultParams.reason").value("risk"))
                .andExpect(jsonPath("$.data[0].rateLimitOverride").doesNotExist());

        verify(bindingService).list("1", "PAY");
    }

    @Test
    void replace_returns200_serializesObjectsToJson() throws Exception {
        mockMvc.perform(put("/admin/v1/scenes/PAY/action-bindings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Actor-Id", "alice")
                        .content("""
                            {
                              "tenantId":"1",
                              "bindings":[
                                {"actionType":"BLOCK_TX","defaultParams":{"reason":"risk"}},
                                {"actionType":"SEND_ALERT"}
                              ]
                            }
                            """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SceneActionBindingItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(bindingService).replace(eq("1"), eq("PAY"), captor.capture(), eq("alice"));
        List<SceneActionBindingItem> items = captor.getValue();
        assertThat(items).hasSize(2);
        assertThat(items.get(0).actionType()).isEqualTo("BLOCK_TX");
        assertThat(items.get(0).defaultParamsJson()).contains("risk");
        assertThat(items.get(1).actionType()).isEqualTo("SEND_ALERT");
        assertThat(items.get(1).defaultParamsJson()).isNull();
    }

    @Test
    void replace_emptyBindings_clearsAll() throws Exception {
        mockMvc.perform(put("/admin/v1/scenes/PAY/action-bindings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Actor-Id", "alice")
                        .content("{\"tenantId\":\"1\",\"bindings\":[]}"))
                .andExpect(status().isOk());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SceneActionBindingItem>> captor = ArgumentCaptor.forClass(List.class);
        verify(bindingService).replace(eq("1"), eq("PAY"), captor.capture(), eq("alice"));
        assertThat(captor.getValue()).isEmpty();
    }

    @Test
    void replace_missingTenantId_returns400() throws Exception {
        mockMvc.perform(put("/admin/v1/scenes/PAY/action-bindings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Actor-Id", "alice")
                        .content("{\"bindings\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        verify(bindingService, never()).replace(any(), any(), any(), any());
    }

    @Test
    void replace_sceneNotFound_returns400() throws Exception {
        doThrow(new IllegalArgumentException("Scene 不存在: PAY"))
                .when(bindingService).replace(eq("1"), eq("PAY"), any(), eq("alice"));

        mockMvc.perform(put("/admin/v1/scenes/PAY/action-bindings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Actor-Id", "alice")
                        .content("{\"tenantId\":\"1\",\"bindings\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }
}
