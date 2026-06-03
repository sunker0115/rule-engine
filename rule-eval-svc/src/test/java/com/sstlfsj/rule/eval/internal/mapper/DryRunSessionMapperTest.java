package com.sstlfsj.rule.eval.internal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sstlfsj.rule.eval.internal.domain.DryRunSession;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 DryRunSessionMapper 接口结构正确：继承 BaseMapper 且泛型绑定到 DryRunSession。
 * 无需数据库连接，纯编译期 / 反射校验。
 */
class DryRunSessionMapperTest {

    @Test
    void mapper_extendsBaseMapper_withCorrectGeneric() {
        assertTrue(BaseMapper.class.isAssignableFrom(DryRunSessionMapper.class));
    }

    @Test
    void mapper_genericType_isDryRunSession() throws Exception {
        var genericInterface = DryRunSessionMapper.class.getGenericInterfaces()[0];
        assertTrue(genericInterface.getTypeName().contains(DryRunSession.class.getName()));
    }
}
