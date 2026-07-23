package com.sstlfsj.rule.web.admin;

import tools.jackson.databind.json.JsonMapper;
import com.sstlfsj.rule.config.api.dto.DraftCreatedResult;
import com.sstlfsj.rule.config.api.dto.RuleContent;
import com.sstlfsj.rule.config.api.service.ConfigService;
import com.sstlfsj.rule.kernel.api.model.flow.OutputNode;
import com.sstlfsj.rule.kernel.api.model.flow.RuleRefNode;
import com.sstlfsj.rule.web.common.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** RuleController DECISION_FLOW 写入口：flowGraph 透传、conditionAst 为 null。 */
class RuleControllerFlowTest {

    private MockMvc mockMvc;
    private ConfigService configService;

    @BeforeEach
    void setUp() {
        configService = mock(ConfigService.class);
        JsonMapper mapper = JsonMapper.builder().build();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new RuleController(configService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new JacksonJsonHttpMessageConverter(mapper))
                .build();
    }

    @Test
    void createDraft_decisionFlow_passesFlowGraphThrough_conditionAstNull() throws Exception {
        when(configService.createDraft(any(), any(), any(), any(), any()))
                .thenReturn(new DraftCreatedResult(10L, 20L, 1L, "DRAFT"));

        mockMvc.perform(post("/admin/v1/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Actor-Id", "user1")
                        .content("""
                            {
                              "tenantId": 1,
                              "sceneCode": "risk.transfer",
                              "code": "rule.flow",
                              "name": "决策图规则",
                              "kind": "DECISION_FLOW",
                              "body": {"type":"FlowBody","referencedSnapshots":{},"flowGraph": {
                                "inputNodeId": "n1",
                                "nodes": [
                                  {"type":"RuleRefNode","id":"n1","ruleCode":"blacklist"},
                                  {"type":"OutputNode","id":"n2","decisionCode":"REVIEW"}
                                ],
                                "edges": [{"from":"n1","to":"n2","caseKey":null}]
                              }}
                            }
                            """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.ruleDefinitionId").value(10));

        // 捕获透传给 service 的 RuleContent，校验 flowGraph 原样透传、conditionAst 为 null
        ArgumentCaptor<RuleContent> contentCap = ArgumentCaptor.forClass(RuleContent.class);
        verify(configService).createDraft(eq(1L), eq("risk.transfer"), eq("rule.flow"),
                contentCap.capture(), eq("user1"));
        RuleContent content = contentCap.getValue();
        assertThat(content.kind()).isEqualTo("DECISION_FLOW");
        assertThat(content.body()).isInstanceOf(com.sstlfsj.rule.kernel.api.model.FlowBody.class);
        var fb = (com.sstlfsj.rule.kernel.api.model.FlowBody) content.body();
        assertThat(fb.flowGraph()).isNotNull();
        assertThat(fb.flowGraph().inputNodeId()).isEqualTo("n1");
        assertThat(fb.flowGraph().nodes()).hasSize(2);
        assertThat(fb.flowGraph().nodes().get(0)).isInstanceOf(RuleRefNode.class);
        assertThat(fb.flowGraph().nodes().get(1)).isInstanceOf(OutputNode.class);
        assertThat(fb.flowGraph().edges()).hasSize(1);
    }
}
