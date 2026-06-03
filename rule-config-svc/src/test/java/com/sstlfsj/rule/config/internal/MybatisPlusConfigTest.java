package com.sstlfsj.rule.config.internal;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class MybatisPlusConfigTest {

    @Test
    void mybatisPlusInterceptor_isNotNull() {
        MybatisPlusConfig config = new MybatisPlusConfig();
        MybatisPlusInterceptor interceptor = config.mybatisPlusInterceptor();
        assertThat(interceptor).isNotNull();
    }

    @Test
    void mybatisPlusInterceptor_interceptorListIsEmpty() {
        // 3.5.16 移除了 PaginationInnerInterceptor，分页由框架内置处理
        MybatisPlusConfig config = new MybatisPlusConfig();
        MybatisPlusInterceptor interceptor = config.mybatisPlusInterceptor();
        assertThat(interceptor.getInterceptors()).isEmpty();
    }
}
