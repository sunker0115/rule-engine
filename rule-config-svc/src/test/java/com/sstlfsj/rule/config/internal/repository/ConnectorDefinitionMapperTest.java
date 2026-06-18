package com.sstlfsj.rule.config.internal.repository;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;
import static org.assertj.core.api.Assertions.assertThat;

/** 验证 ConnectorDefinitionMapper 分页查询方法签名存在（行为覆盖见 ConnectorDefinitionMapperIT）。 */
class ConnectorDefinitionMapperTest {

    @Test
    void searchPage_methodExists() throws NoSuchMethodException {
        Method m = ConnectorDefinitionMapper.class.getMethod(
                "searchPage",
                com.baomidou.mybatisplus.extension.plugins.pagination.Page.class,
                Long.class, String.class, String.class);
        assertThat(m.isDefault()).isTrue();
    }
}
