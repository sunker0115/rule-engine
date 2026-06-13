package com.sstlfsj.rule.web.admin.dto;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class NewVersionRequestTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();

    @Test
    void bindsScriptSource_conditionAstNull() {
        // EXPRESSION_SCRIPT 出新版本：script 经 {source,lang} 绑定、conditionAst 缺省
        String json = """
            {"tenantId":"1","kind":"EXPRESSION_SCRIPT",
             "script":{"source":"payload.amount > 10000 ? 'REVIEW' : 'PASS'","lang":"CEL"}}
            """;
        NewVersionRequest req = mapper.readValue(json, NewVersionRequest.class);

        assertThat(req.conditionAst()).isNull();
        assertThat(req.fromVersionId()).isNull();
        assertThat(req.script()).isNotNull();
        assertThat(req.script().source()).isEqualTo("payload.amount > 10000 ? 'REVIEW' : 'PASS'");
        assertThat(req.script().lang()).isEqualTo("CEL");
    }
}
