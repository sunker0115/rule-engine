package com.sstlfsj.rule.config.internal;

import com.baomidou.mybatisplus.extension.handlers.Jackson3TypeHandler;
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

    /**
     * 把 Spring 全局 ObjectMapper 注入 Jackson3TypeHandler，使所有
     * {@code @TableField(typeHandler=Jackson3TypeHandler.class)} 字段（如 AstNode、ConnectorDescriptor 等）
     * 用同一个 mapper 序列化/反序列化——含 {@code @JsonTypeInfo} 多态配置、avoid NON_EMPTY 省略等。
     * Jackson3TypeHandler 默认用 {@code new ObjectMapper()}（裸 mapper），不注入会导致
     * AstNode 接口多态字段丢失子类专有字段（如 ScorecardRootNode.bands），e2e 验证暴露此缺陷。
     */
    @Bean
    Jackson3TypeHandlerConfigurer jackson3TypeHandlerConfigurer(ObjectMapper objectMapper) {
        Jackson3TypeHandler.setObjectMapper(objectMapper);
        return new Jackson3TypeHandlerConfigurer();
    }

    /** 占位 bean——触发 jackson3TypeHandlerConfigurer 初始化，本身无业务语义。 */
    record Jackson3TypeHandlerConfigurer() {}
}
