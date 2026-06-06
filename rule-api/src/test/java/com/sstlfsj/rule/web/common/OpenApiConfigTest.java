package com.sstlfsj.rule.web.common;

import io.swagger.v3.oas.models.OpenAPI;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiConfigTest {

    @Test
    void ruleEngineOpenAPI_提供标题版本描述() {
        OpenAPI openAPI = new OpenApiConfig().ruleEngineOpenAPI();

        assertThat(openAPI.getInfo()).isNotNull();
        assertThat(openAPI.getInfo().getTitle()).isEqualTo("规则引擎 API");
        assertThat(openAPI.getInfo().getVersion()).isEqualTo("v1");
        assertThat(openAPI.getInfo().getDescription()).contains("规则引擎");
    }
}
