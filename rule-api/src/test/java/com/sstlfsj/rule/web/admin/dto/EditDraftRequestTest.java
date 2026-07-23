package com.sstlfsj.rule.web.admin.dto;

import com.sstlfsj.rule.kernel.api.model.ScriptBody;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class EditDraftRequestTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();

    @Test
    void bindsScriptBody() {
        // EXPRESSION_SCRIPT 草稿编辑：body 为 ScriptBody
        String json = """
            {"tenantId":"1","kind":"EXPRESSION_SCRIPT",
             "body":{"type":"ScriptBody","script":{"source":"metrics.txn_cnt_1d > 50 ? 'REVIEW' : 'PASS'","lang":"CEL"}}}
            """;
        EditDraftRequest req = mapper.readValue(json, EditDraftRequest.class);

        assertThat(req.body()).isInstanceOf(ScriptBody.class);
        ScriptBody sb = (ScriptBody) req.body();
        assertThat(sb.script().source()).isEqualTo("metrics.txn_cnt_1d > 50 ? 'REVIEW' : 'PASS'");
        assertThat(sb.script().lang()).isEqualTo("CEL");
    }
}
