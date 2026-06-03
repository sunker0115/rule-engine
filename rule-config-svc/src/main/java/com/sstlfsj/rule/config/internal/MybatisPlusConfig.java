package com.sstlfsj.rule.config.internal;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 拦截器占位配置。
 *
 * <p>3.5.16 已将分页支持内置到 spring-boot-autoconfigure，不再需要手动注册 PaginationInnerInterceptor。
 * 本 bean 仅用于 @ConditionalOnMissingBean 防止自动配置重复注册。
 */
@Configuration
class MybatisPlusConfig {

    @Bean
    @ConditionalOnMissingBean(MybatisPlusInterceptor.class)
    MybatisPlusInterceptor mybatisPlusInterceptor() {
        return new MybatisPlusInterceptor();
    }
}
