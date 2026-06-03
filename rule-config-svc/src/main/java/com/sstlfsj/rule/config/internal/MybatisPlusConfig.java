package com.sstlfsj.rule.config.internal;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 拦截器配置，供 rule-config-svc 使用；app 层已注册时自动跳过。
 *
 * <p>注：MyBatis-Plus 3.5.16 已内置分页支持，不再需要手动注册 PaginationInnerInterceptor。
 * 分页由框架的 MybatisPlusInnerInterceptorAutoConfiguration 自动处理。
 */
@Configuration
class MybatisPlusConfig {

    @Bean
    @ConditionalOnMissingBean(MybatisPlusInterceptor.class)
    MybatisPlusInterceptor mybatisPlusInterceptor() {
        // 3.5.16 移除了 PaginationInnerInterceptor；分页由框架内置处理
        return new MybatisPlusInterceptor();
    }
}
