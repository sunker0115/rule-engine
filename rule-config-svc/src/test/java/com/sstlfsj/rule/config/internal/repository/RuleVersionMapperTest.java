package com.sstlfsj.rule.config.internal.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sstlfsj.rule.config.internal.domain.RuleVersion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;

import static org.junit.jupiter.api.Assertions.*;

/** 验证 RuleVersionMapper 接口结构及 maxVersion 自定义方法。 */
class RuleVersionMapperTest {

    @Test
    void extendsBaseMapper_withCorrectGeneric() {
        var generic = (ParameterizedType) RuleVersionMapper.class.getGenericInterfaces()[0];
        assertEquals(BaseMapper.class, generic.getRawType());
        assertEquals(RuleVersion.class, generic.getActualTypeArguments()[0]);
    }

    @Test
    void hasMapperAnnotation() {
        assertNotNull(RuleVersionMapper.class.getAnnotation(Mapper.class));
    }

    @Test
    void maxVersion_methodExists_withSelectAnnotation() throws NoSuchMethodException {
        Method m = RuleVersionMapper.class.getDeclaredMethod("maxVersion", Long.class);
        assertEquals(Long.class, m.getReturnType());
        Select select = m.getAnnotation(Select.class);
        assertNotNull(select);
        assertTrue(select.value()[0].contains("MAX(version)"));
    }
}
