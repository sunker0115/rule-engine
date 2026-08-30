package com.sstlfsj.rule.web.admin;

import com.sstlfsj.rule.config.api.dto.DraftCreatedResult;
import com.sstlfsj.rule.config.api.dto.SlotBinding;
import com.sstlfsj.rule.config.api.dto.TemplateDetail;
import com.sstlfsj.rule.config.api.dto.TemplateSlot;
import com.sstlfsj.rule.config.api.dto.SlotKind;
import com.sstlfsj.rule.config.api.dto.ValueDataType;
import com.sstlfsj.rule.config.api.service.RuleTemplateService;
import com.sstlfsj.rule.config.internal.domain.RuleTemplate;
import com.sstlfsj.rule.config.internal.domain.RuleTemplateVersion;
import com.sstlfsj.rule.config.internal.domain.TemplateStatus;
import com.sstlfsj.rule.kernel.api.model.AstBody;
import com.sstlfsj.rule.kernel.api.model.RuleBody;
import com.sstlfsj.rule.kernel.api.model.RuleKind;
import com.sstlfsj.rule.web.common.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

/** RuleTemplateController 单元测试：V2 适配（slots 带 kind，返回 TemplateDetail，版本历史，enum→String VO 边界）。 */
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
                              "slots": [{"key":"threshold","label":"阈值","kind":"VALUE","dataType":"LONG","required":true}],
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
        assertThat(slotsCaptor.getValue().get(0).kind()).isEqualTo(SlotKind.VALUE);
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
    void create_slotWithKind_deserializesCorrectly() throws Exception {
        when(templateService.create(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(200L);

        mockMvc.perform(post("/admin/v1/rule-templates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Actor-Id", "user1")
                        .content("""
                            {
                              "tenantId": 1,
                              "code": "tpl.ref",
                              "name": "REF模板",
                              "kind": "AST_BOOLEAN",
                              "bodySkeleton": {"type":"AstBody","conditionAst":{"type":"AndNode","children":[]}},
                              "slots": [
                                {"key":"threshold","label":"阈值","kind":"VALUE","dataType":"LONG","required":true},
                                {"key":"metric","label":"指标","kind":"METRIC_REF","dataType":null,"required":false}
                              ],
                              "bindings": [
                                {"slotKey":"threshold","target":{"type":"JsonPointerTarget","jsonPointer":"/conditionAst"}}
                              ]
                            }
                            """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data").value(200));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TemplateSlot>> slotsCaptor = ArgumentCaptor.forClass(List.class);
        verify(templateService).create(eq(1L), eq("tpl.ref"), any(), eq("AST_BOOLEAN"),
                any(), any(), slotsCaptor.capture(), any(), eq("user1"));
        assertThat(slotsCaptor.getValue()).hasSize(2);
        // VALUE slot
        assertThat(slotsCaptor.getValue().get(0).key()).isEqualTo("threshold");
        assertThat(slotsCaptor.getValue().get(0).kind()).isEqualTo(SlotKind.VALUE);
        assertThat(slotsCaptor.getValue().get(0).dataType()).isEqualTo(ValueDataType.LONG);
        // REF slot — dataType 为空
        assertThat(slotsCaptor.getValue().get(1).key()).isEqualTo("metric");
        assertThat(slotsCaptor.getValue().get(1).kind()).isEqualTo(SlotKind.METRIC_REF);
        assertThat(slotsCaptor.getValue().get(1).dataType()).isNull();
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
    void enable_returns200_andReadsTenantFromHeader() throws Exception {
        doNothing().when(templateService).enable(any(), any(), any());

        mockMvc.perform(post("/admin/v1/rule-templates/tpl.a/enable")
                        .header("X-Tenant-Id", "1")
                        .header("X-Actor-Id", "user1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(templateService).enable(1L, "tpl.a", "user1");
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
    void get_returns200_withTemplateDetail() throws Exception {
        RuleTemplate t = new RuleTemplate();
        t.setId(1L);
        t.setCode("tpl.a");
        t.setTenantId(1L);
        t.setName("模板A");
        t.setKind(RuleKind.AST_BOOLEAN);
        t.setStatus(TemplateStatus.PUBLISHED);

        RuleTemplateVersion v = new RuleTemplateVersion();
        v.setId(10L);
        v.setTemplateId(1L);
        v.setVersion(1);
        v.setStatus(TemplateStatus.PUBLISHED);

        TemplateDetail detail = new TemplateDetail(t, v);
        when(templateService.getVersion(1L, "tpl.a")).thenReturn(detail);

        mockMvc.perform(get("/admin/v1/rule-templates/tpl.a")
                        .header("X-Tenant-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.template.code").value("tpl.a"))
                .andExpect(jsonPath("$.data.template.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.data.version.version").value(1))
                .andExpect(jsonPath("$.data.version.status").value("PUBLISHED"));

        verify(templateService).getVersion(1L, "tpl.a");
    }

    @Test
    void listVersions_returns200_withVersionList() throws Exception {
        RuleTemplateVersion v1 = new RuleTemplateVersion();
        v1.setId(10L);
        v1.setTemplateId(1L);
        v1.setVersion(2);
        v1.setStatus(TemplateStatus.PUBLISHED);

        RuleTemplateVersion v2 = new RuleTemplateVersion();
        v2.setId(11L);
        v2.setTemplateId(1L);
        v2.setVersion(1);
        v2.setStatus(TemplateStatus.PUBLISHED);

        when(templateService.listVersions(1L, "tpl.a")).thenReturn(List.of(v1, v2));

        mockMvc.perform(get("/admin/v1/rule-templates/tpl.a/versions")
                        .header("X-Tenant-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].version").value(2))
                .andExpect(jsonPath("$.data[0].status").value("PUBLISHED"))
                .andExpect(jsonPath("$.data[1].version").value(1));

        verify(templateService).listVersions(1L, "tpl.a");
    }

    @Test
    void getVersion_returns200_withSpecificVersion() throws Exception {
        RuleTemplate t = new RuleTemplate();
        t.setId(1L);
        t.setCode("tpl.a");

        RuleTemplateVersion v = new RuleTemplateVersion();
        v.setId(10L);
        v.setTemplateId(1L);
        v.setVersion(2);
        v.setStatus(TemplateStatus.DRAFT);

        TemplateDetail detail = new TemplateDetail(t, v);
        when(templateService.getVersion(1L, "tpl.a", 2)).thenReturn(detail);

        mockMvc.perform(get("/admin/v1/rule-templates/tpl.a/versions/2")
                        .header("X-Tenant-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version.version").value(2))
                .andExpect(jsonPath("$.data.version.status").value("DRAFT"));

        verify(templateService).getVersion(1L, "tpl.a", 2);
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

}
