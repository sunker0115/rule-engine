package com.sstlfsj.rule.config.internal;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ContextConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

@ContextConfiguration(classes = JacksonConfig.class)
@SpringBootTest
class JacksonConfigTest {

    @Autowired
    ApplicationContext ctx;

    @Test
    void objectMapperBean_注册成功() {
        assertThat(ctx.getBean(ObjectMapper.class)).isNotNull();
    }

    @Test
    void objectMapperBean_conditionalOnMissingBean_已有时不重复注册() {
        // 同一上下文中只应存在一个 ObjectMapper bean
        String[] names = ctx.getBeanNamesForType(ObjectMapper.class);
        assertThat(names).hasSize(1);
    }
}
