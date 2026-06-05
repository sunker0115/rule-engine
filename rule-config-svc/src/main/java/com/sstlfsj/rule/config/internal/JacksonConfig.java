package com.sstlfsj.rule.config.internal;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 为 rule-config-svc 内部 bean 提供 ObjectMapper；app 层已注册时自动跳过。 */
@Configuration
class JacksonConfig {

    @Bean
    @ConditionalOnMissingBean(ObjectMapper.class)
    ObjectMapper objectMapper() {
        return JsonMapper.builder().build();
    }
}
