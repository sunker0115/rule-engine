package com.sstlfsj.rule.web.common;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** springdoc OpenAPI 文档元信息配置：提供标题、版本、描述。 */
@Configuration
public class OpenApiConfig {

    /**
     * 构建规则引擎 REST API 的 OpenAPI 文档元信息。
     *
     * @return 含标题/版本/描述的 OpenAPI 定义
     */
    @Bean
    public OpenAPI ruleEngineOpenAPI() {
        return new OpenAPI().info(new Info()
                .title("规则引擎 API")
                .version("v1")
                .description("通用规则引擎的配置、评估、审计与 SDK 下发 REST 接口"));
    }
}
