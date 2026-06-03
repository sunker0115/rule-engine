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
    void mybatisPlusInterceptor_hasPaginationPlugin() {
        MybatisPlusConfig config = new MybatisPlusConfig();
        MybatisPlusInterceptor interceptor = config.mybatisPlusInterceptor();
        assertThat(interceptor.getInterceptors()).hasSize(1);
    }
}
