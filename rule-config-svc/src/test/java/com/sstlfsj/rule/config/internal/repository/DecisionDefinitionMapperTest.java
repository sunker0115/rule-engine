package com.sstlfsj.rule.config.internal.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sstlfsj.rule.config.internal.domain.DecisionDefinition;
import org.apache.ibatis.annotations.Mapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 验证 DecisionDefinitionMapper 接口结构及 B7 新增语义查询方法签名。 */
class DecisionDefinitionMapperTest {

    @Test
    void extendsBaseMapper_withCorrectGeneric() {
        var generic = (ParameterizedType) DecisionDefinitionMapper.class.getGenericInterfaces()[0];
        assertEquals(BaseMapper.class, generic.getRawType());
        assertEquals(DecisionDefinition.class, generic.getActualTypeArguments()[0]);
    }

    @Test
    void hasMapperAnnotation() {
        assertNotNull(DecisionDefinitionMapper.class.getAnnotation(Mapper.class));
    }

    @Test
    void findByCode_defaultMethodExists_withCorrectSignature() throws NoSuchMethodException {
        Method m = DecisionDefinitionMapper.class.getDeclaredMethod("findByCode", Long.class, String.class);
        assertTrue(m.isDefault(), "findByCode 应为 default 方法");
        assertEquals(DecisionDefinition.class, m.getReturnType());
    }

    @Test
    void findByCodes_defaultMethodExists_withCorrectSignature() throws NoSuchMethodException {
        Method m = DecisionDefinitionMapper.class.getDeclaredMethod("findByCodes", Long.class, Collection.class);
        assertTrue(m.isDefault(), "findByCodes 应为 default 方法");
        assertEquals(List.class, m.getReturnType());
    }
}
