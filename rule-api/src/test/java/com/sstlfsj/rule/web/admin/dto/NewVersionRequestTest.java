package com.sstlfsj.rule.web.admin.dto;

import com.sstlfsj.rule.kernel.api.model.ScriptBody;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class NewVersionRequestTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();

    @Test
    void bindsScriptBody() {
        // EXPRESSION_SCRIPT 出新版本：body 为 ScriptBody
        String json = """
            {"tenantId":"1","kind":"EXPRESSION_SCRIPT",
             "body":{"type":"ScriptBody","script":{"source":"payload.amount > 10000 ? 'REVIEW' : 'PASS'","lang":"CEL"}}}
            """;
        NewVersionRequest req = mapper.readValue(json, NewVersionRequest.class);

        assertThat(req.body()).isInstanceOf(ScriptBody.class);
        assertThat(req.fromVersionId()).isNull();
        ScriptBody sb = (ScriptBody) req.body();
        assertThat(sb.script().source()).isEqualTo("payload.amount > 10000 ? 'REVIEW' : 'PASS'");
        assertThat(sb.script().lang()).isEqualTo("CEL");
    }
}
