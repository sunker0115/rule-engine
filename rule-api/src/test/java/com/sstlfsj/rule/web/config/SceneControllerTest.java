package com.sstlfsj.rule.web.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sstlfsj.rule.config.api.service.SceneService;
import com.sstlfsj.rule.web.common.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** SceneController 单元测试。 */
class SceneControllerTest {

    private MockMvc mockMvc;
    private SceneService sceneService;

    @BeforeEach
    void setUp() throws Exception {
        sceneService = mock(SceneService.class);
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        // ObjectMapper 注入点同生产：测试用默认实例，行为与 Spring Boot 自动配置 bean 一致
        mockMvc = MockMvcBuilders
                .standaloneSetup(new SceneController(sceneService, new ObjectMapper()))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    void createScene_returns200_withId() throws Exception {
        when(sceneService.createScene(any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any())).thenReturn(42L);

        mockMvc.perform(post("/api/v1/scenes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Actor-Id", "user1")
                        .content("""
                            {"tenantId":"t1","sceneCode":"fraud","name":"欺诈检测"}
                            """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(42));

        verify(sceneService).createScene(
                eq("t1"), eq("fraud"), eq("欺诈检测"),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), eq("user1"));
    }

    @Test
    void createScene_withPayloadSchema_传入序列化后的字符串() throws Exception {
        when(sceneService.createScene(any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any())).thenReturn(99L);

        mockMvc.perform(post("/api/v1/scenes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Actor-Id", "user1")
                        .content("""
                            {
                              "tenantId":"t1",
                              "sceneCode":"payment",
                              "name":"支付场景",
                              "eventTypes":["payment.initiated"],
                              "payloadSchema":[{"name":"amount","type":"NUMBER","required":true}]
                            }
                            """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(99));

        verify(sceneService).createScene(
                eq("t1"), eq("payment"), eq("支付场景"),
                isNull(), isNull(), isNull(),
                eq("[\"payment.initiated\"]"),
                argThat(s -> s != null && s.contains("amount")),
                isNull(), eq("user1"));
    }

    @Test
    void createScene_missingTenantId_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/scenes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Actor-Id", "user1")
                        .content("""
                            {"sceneCode":"fraud","name":"欺诈检测"}
                            """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("INVALID_ARGUMENT"));
    }
}
