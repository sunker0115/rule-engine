package com.sstlfsj.rule.config.internal.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sstlfsj.rule.config.internal.domain.MetricDefinition;
import org.apache.ibatis.annotations.Mapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;

import static org.junit.jupiter.api.Assertions.*;

/** 验证 MetricDefinitionMapper 接口结构及 B7 新增语义查询方法签名。 */
class MetricDefinitionMapperTest {

    @Test
    void extendsBaseMapper_withCorrectGeneric() {
        var generic = (ParameterizedType) MetricDefinitionMapper.class.getGenericInterfaces()[0];
        assertEquals(BaseMapper.class, generic.getRawType());
        assertEquals(MetricDefinition.class, generic.getActualTypeArguments()[0]);
    }

    @Test
    void hasMapperAnnotation() {
        assertNotNull(MetricDefinitionMapper.class.getAnnotation(Mapper.class));
    }

    @Test
    void findByCodeAndVersion_defaultMethodExists_withCorrectSignature() throws NoSuchMethodException {
        Method m = MetricDefinitionMapper.class.getDeclaredMethod(
                "findByCodeAndVersion", Long.class, String.class, Integer.class);
        assertTrue(m.isDefault(), "findByCodeAndVersion 应为 default 方法");
        assertEquals(MetricDefinition.class, m.getReturnType());
    }

    @Test
    void findAnyByCode_defaultMethodExists_withCorrectSignature() throws NoSuchMethodException {
        Method m = MetricDefinitionMapper.class.getDeclaredMethod("findAnyByCode", Long.class, String.class);
        assertTrue(m.isDefault(), "findAnyByCode 应为 default 方法");
        assertEquals(MetricDefinition.class, m.getReturnType());
    }
}
