package com.sstlfsj.rule.web.admin;

import tools.jackson.databind.json.JsonMapper;
import com.sstlfsj.rule.config.api.dto.DraftCreatedResult;
import com.sstlfsj.rule.config.api.dto.RuleContent;
import com.sstlfsj.rule.config.api.service.ConfigService;
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

/** RuleController EXPRESSION_SCRIPT 写入口：script 透传、conditionAst 为 null。 */
class RuleControllerScriptTest {

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
    void createDraft_expressionScript_passesScriptThrough_conditionAstNull() throws Exception {
        when(configService.createDraft(any(), any(), any(), any(), any()))
                .thenReturn(new DraftCreatedResult(10L, 20L, 1L, "DRAFT"));

        mockMvc.perform(post("/admin/v1/rules")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Actor-Id", "user1")
                        .content("""
                            {
                              "tenantId": 1,
                              "sceneCode": "risk.transfer",
                              "code": "rule.script",
                              "name": "脚本规则",
                              "kind": "EXPRESSION_SCRIPT",
                              "body": {"type":"ScriptBody","script":{"source":"payload.amount > 0 ? 'REVIEW' : 'PASS'","lang":"CEL"}}
                            }
                            """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.ruleDefinitionId").value(10));

        // 捕获透传给 service 的 RuleContent，校验 script 与 conditionAst
        ArgumentCaptor<RuleContent> contentCap = ArgumentCaptor.forClass(RuleContent.class);
        verify(configService).createDraft(eq(1L), eq("risk.transfer"), eq("rule.script"),
                contentCap.capture(), eq("user1"));
        RuleContent content = contentCap.getValue();
        assertThat(content.name()).isEqualTo("脚本规则");
        assertThat(content.kind()).isEqualTo("EXPRESSION_SCRIPT");
        // body 为 ScriptBody，原样透传
        assertThat(content.body()).isInstanceOf(com.sstlfsj.rule.kernel.api.model.ScriptBody.class);
        var sb = (com.sstlfsj.rule.kernel.api.model.ScriptBody) content.body();
        assertThat(sb.script().source()).isEqualTo("payload.amount > 0 ? 'REVIEW' : 'PASS'");
        assertThat(sb.script().lang()).isEqualTo("CEL");
    }
}
