package com.sstlfsj.rule.web.admin;

import com.sstlfsj.rule.config.api.dto.DraftCreatedResult;
import com.sstlfsj.rule.config.api.dto.SlotBinding;
import com.sstlfsj.rule.config.api.dto.TemplateSlot;
import com.sstlfsj.rule.config.api.service.RuleTemplateService;
import com.sstlfsj.rule.config.internal.domain.RuleTemplate;
import com.sstlfsj.rule.kernel.api.model.AstBody;
import com.sstlfsj.rule.kernel.api.model.RuleBody;
import com.sstlfsj.rule.web.common.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** RuleTemplateController 单元测试：新 API（bodySkeleton + bindings）+ feature flag 条件装配。 */
class RuleTemplateControllerTest {

    private MockMvc mockMvc;
    private RuleTemplateService templateService;

    @BeforeEach
    void setUp() {
        templateService = mock(RuleTemplateService.class);
        JsonMapper mapper = JsonMapper.builder()
                .addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class)
                .build();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new RuleTemplateController(templateService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new JacksonJsonHttpMessageConverter(mapper))
                .build();
    }

    @Test
    void create_returns201_andPassesBodySkeletonAndBindings() throws Exception {
        when(templateService.create(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(100L);

        mockMvc.perform(post("/admin/v1/rule-templates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Actor-Id", "user1")
                        .content("""
                            {
                              "tenantId": 1,
                              "code": "tpl.a",
                              "name": "模板A",
                              "kind": "AST_BOOLEAN",
                              "description": "desc",
                              "bodySkeleton": {"type":"AstBody","conditionAst":{"type":"AndNode","children":[]}},
                              "slots": [{"key":"threshold","label":"阈值","dataType":"LONG","required":true}],
                              "bindings": [{"slotKey":"threshold","target":{"type":"JsonPointerTarget","jsonPointer":"/conditionAst"}}]
                            }
                            """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value(100));

        ArgumentCaptor<RuleBody> bodyCaptor = ArgumentCaptor.forClass(RuleBody.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TemplateSlot>> slotsCaptor = ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SlotBinding>> bindingsCaptor = ArgumentCaptor.forClass(List.class);
        verify(templateService).create(eq(1L), eq("tpl.a"), eq("模板A"), eq("AST_BOOLEAN"),
                eq("desc"), bodyCaptor.capture(), slotsCaptor.capture(), bindingsCaptor.capture(), eq("user1"));
        assertThat(bodyCaptor.getValue()).isInstanceOf(AstBody.class);
        assertThat(slotsCaptor.getValue()).hasSize(1);
        assertThat(slotsCaptor.getValue().get(0).key()).isEqualTo("threshold");
        assertThat(bindingsCaptor.getValue()).hasSize(1);
        assertThat(bindingsCaptor.getValue().get(0).slotKey()).isEqualTo("threshold");
    }

    @Test
    void create_returns400_whenTenantIdMissing() throws Exception {
        mockMvc.perform(post("/admin/v1/rule-templates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Actor-Id", "user1")
                        .content("""
                            {"code":"tpl.a","name":"模板A","kind":"AST_BOOLEAN"}
                            """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void update_returns200_andPassesBodySkeletonAndBindings() throws Exception {
        doNothing().when(templateService).update(any(), any(), any(), any(), any(), any(), any(), any(), any());

        mockMvc.perform(put("/admin/v1/rule-templates/tpl.a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Actor-Id", "user1")
                        .content("""
                            {
                              "tenantId": 1,
                              "name": "模板A改",
                              "kind": "AST_BOOLEAN",
                              "description": "d2",
                              "bodySkeleton": {"type":"AstBody","conditionAst":{"type":"AndNode","children":[]}},
                              "slots": [],
                              "bindings": []
                            }
                            """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        ArgumentCaptor<RuleBody> bodyCaptor = ArgumentCaptor.forClass(RuleBody.class);
        verify(templateService).update(eq(1L), eq("tpl.a"), eq("模板A改"), eq("AST_BOOLEAN"),
                eq("d2"), bodyCaptor.capture(), eq(List.of()), eq(List.of()), eq("user1"));
        assertThat(bodyCaptor.getValue()).isInstanceOf(AstBody.class);
    }

    @Test
    void publish_returns200_andReadsTenantFromHeader() throws Exception {
        doNothing().when(templateService).publish(any(), any(), any());

        mockMvc.perform(post("/admin/v1/rule-templates/tpl.a/publish")
                        .header("X-Tenant-Id", "1")
                        .header("X-Actor-Id", "user1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(templateService).publish(1L, "tpl.a", "user1");
    }

    @Test
    void disable_returns200_andReadsTenantFromHeader() throws Exception {
        doNothing().when(templateService).disable(any(), any(), any());

        mockMvc.perform(post("/admin/v1/rule-templates/tpl.a/disable")
                        .header("X-Tenant-Id", "1")
                        .header("X-Actor-Id", "user1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(templateService).disable(1L, "tpl.a", "user1");
    }

    @Test
    void list_returns200_withTemplates() throws Exception {
        RuleTemplate t = new RuleTemplate();
        t.setId(1L);
        t.setCode("tpl.a");
        when(templateService.list(1L, "PUBLISHED")).thenReturn(List.of(t));

        mockMvc.perform(get("/admin/v1/rule-templates")
                        .header("X-Tenant-Id", "1")
                        .param("status", "PUBLISHED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].code").value("tpl.a"));

        verify(templateService).list(1L, "PUBLISHED");
    }

    @Test
    void get_returns200_withTemplate() throws Exception {
        RuleTemplate t = new RuleTemplate();
        t.setId(1L);
        t.setCode("tpl.a");
        when(templateService.get(1L, "tpl.a")).thenReturn(t);

        mockMvc.perform(get("/admin/v1/rule-templates/tpl.a")
                        .header("X-Tenant-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.code").value("tpl.a"));

        verify(templateService).get(1L, "tpl.a");
    }

    @Test
    void instantiate_returns201_andPassesSlotValues() throws Exception {
        when(templateService.instantiate(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new DraftCreatedResult(10L, 20L, 1L, "DRAFT"));

        mockMvc.perform(post("/admin/v1/rule-templates/tpl.a/instantiate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Actor-Id", "user1")
                        .content("""
                            {
                              "tenantId": 1,
                              "ruleCode": "rule.x",
                              "ruleName": "规则X",
                              "sceneCode": "risk.transfer",
                              "triggerEventTypes": ["TXN"],
                              "slotValues": {"threshold": 100}
                            }
                            """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.ruleDefinitionId").value(10))
                .andExpect(jsonPath("$.data.status").value("DRAFT"));

        verify(templateService).instantiate(eq(1L), eq("tpl.a"), eq("rule.x"), eq("规则X"),
                eq("risk.transfer"), eq(List.of("TXN")), any(), eq("user1"));
    }

    // ---- feature flag：@ConditionalOnProperty(rule.template.enabled=true) ----

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(RuleTemplateService.class, () -> mock(RuleTemplateService.class))
            .withUserConfiguration(RuleTemplateController.class);

    @Test
    void controllerBeanAbsent_whenFlagUnset() {
        contextRunner.run(context ->
                assertThat(context).doesNotHaveBean(RuleTemplateController.class));
    }

    @Test
    void controllerBeanAbsent_whenFlagFalse() {
        contextRunner.withPropertyValues("rule.template.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(RuleTemplateController.class));
    }

    @Test
    void controllerBeanPresent_whenFlagTrue() {
        contextRunner.withPropertyValues("rule.template.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(RuleTemplateController.class));
    }
}
