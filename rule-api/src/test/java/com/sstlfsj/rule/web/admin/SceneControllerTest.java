package com.sstlfsj.rule.web.admin;

import com.sstlfsj.rule.config.api.dto.SceneListItem;
import com.sstlfsj.rule.config.api.dto.UpdateSceneCommand;
import com.sstlfsj.rule.config.api.service.SceneService;
import com.sstlfsj.rule.web.common.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.List;

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
        mockMvc = MockMvcBuilders
                .standaloneSetup(new SceneController(sceneService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    void listScenes_returns200_withList() throws Exception {
        when(sceneService.listScenes(1L, null)).thenReturn(List.of(
                new SceneListItem(5L, 1L, "PAYMENT", "支付场景", "PUSH", "USER", "ACTIVE", null, null)));

        mockMvc.perform(get("/admin/v1/scenes").param("tenantId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].sceneCode").value("PAYMENT"))
                .andExpect(jsonPath("$.data[0].status").value("ACTIVE"));

        verify(sceneService).listScenes(1L, null);
    }

    @Test
    void createScene_returns200_withId() throws Exception {
        when(sceneService.createScene(any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any())).thenReturn(42L);

        mockMvc.perform(post("/admin/v1/scenes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Actor-Id", "user1")
                        .content("""
                            {"tenantId":1,"sceneCode":"fraud","name":"欺诈检测"}
                            """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(42));

        verify(sceneService).createScene(
                eq(1L), eq("fraud"), eq("欺诈检测"),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), eq("user1"));
    }

    @Test
    void createScene_withPayloadSchema_传入typed对象() throws Exception {
        when(sceneService.createScene(any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any())).thenReturn(99L);

        mockMvc.perform(post("/admin/v1/scenes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Actor-Id", "user1")
                        .content("""
                            {
                              "tenantId":1,
                              "sceneCode":"payment",
                              "name":"支付场景",
                              "eventTypes":["payment.initiated"],
                              "payloadSchema":[{"name":"amount","type":"NUMBER","required":true}]
                            }
                            """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(99));

        verify(sceneService).createScene(
                eq(1L), eq("payment"), eq("支付场景"),
                isNull(), isNull(), isNull(),
                eq(List.of("payment.initiated")),
                argThat(ps -> ps != null && !ps.isEmpty() && "amount".equals(ps.get(0).name())),
                isNull(), eq("user1"));
    }

    @Test
    void createScene_missingTenantId_returns400() throws Exception {
        mockMvc.perform(post("/admin/v1/scenes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Actor-Id", "user1")
                        .content("""
                            {"sceneCode":"fraud","name":"欺诈检测"}
                            """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_ARGUMENT"));
    }

    @Test
    void patchScene_returns200() throws Exception {
        doNothing().when(sceneService).updateScene(any(UpdateSceneCommand.class));

        mockMvc.perform(patch("/admin/v1/scenes/payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Actor-Id", "user1")
                        .content("""
                            {
                              "tenantId":1,
                              "payloadSchema":[{"name":"amount","type":"NUMBER","required":true}]
                            }
                            """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(sceneService).updateScene(argThat(cmd ->
                java.util.Objects.equals(1L, cmd.tenantId()) && "payment".equals(cmd.sceneCode())
                && cmd.name() == null && cmd.description() == null
                && cmd.payloadSchema() != null && !cmd.payloadSchema().isEmpty()
                && "amount".equals(cmd.payloadSchema().getFirst().name())
                && cmd.defaultParams() == null && "user1".equals(cmd.actorId())));
    }

    @Test
    void patchScene_missingTenantId_returns400() throws Exception {
        mockMvc.perform(patch("/admin/v1/scenes/payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Actor-Id", "user1")
                        .content("{\"name\":\"新名称\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getScene_returns200_withDetail() throws Exception {
        com.sstlfsj.rule.config.api.dto.SceneDetailDto dto =
                new com.sstlfsj.rule.config.api.dto.SceneDetailDto(
                        5L, 1L, "payment", "支付场景",
                        null, "PUSH", "USER",
                        java.util.List.of("payment.initiated"),
                        java.util.List.of(),
                        java.util.Map.of("timezone", "Asia/Shanghai"),
                        "ACTIVE");
        when(sceneService.getScene(1L, "payment")).thenReturn(dto);

        mockMvc.perform(get("/admin/v1/scenes/payment")
                        .param("tenantId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.sceneCode").value("payment"))
                .andExpect(jsonPath("$.data.eventTypes[0]").value("payment.initiated"));
    }

    @Test
    void getScene_notFound_returns400() throws Exception {
        when(sceneService.getScene(1L, "notexist"))
                .thenThrow(new IllegalArgumentException("Scene 不存在: notexist"));

        mockMvc.perform(get("/admin/v1/scenes/notexist")
                        .param("tenantId", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_ARGUMENT"));
    }
}
