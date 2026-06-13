package com.sstlfsj.rule.web.admin.dto;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class EditDraftRequestTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();

    @Test
    void bindsScriptSource_conditionAstNull() {
        // EXPRESSION_SCRIPT 草稿编辑：script 经 {source,lang} 绑定、conditionAst 缺省
        String json = """
            {"tenantId":"1","kind":"EXPRESSION_SCRIPT",
             "script":{"source":"metrics.txn_cnt_1d > 50 ? 'REVIEW' : 'PASS'","lang":"CEL"}}
            """;
        EditDraftRequest req = mapper.readValue(json, EditDraftRequest.class);

        assertThat(req.conditionAst()).isNull();
        assertThat(req.script()).isNotNull();
        assertThat(req.script().source()).isEqualTo("metrics.txn_cnt_1d > 50 ? 'REVIEW' : 'PASS'");
        assertThat(req.script().lang()).isEqualTo("CEL");
    }
}
