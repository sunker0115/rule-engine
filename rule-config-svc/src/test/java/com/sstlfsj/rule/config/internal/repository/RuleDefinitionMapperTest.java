package com.sstlfsj.rule.config.internal.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sstlfsj.rule.config.internal.domain.RuleDefinition;
import org.apache.ibatis.annotations.Mapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 验证 RuleDefinitionMapper 接口结构及 B7 新增语义查询方法签名。 */
class RuleDefinitionMapperTest {

    @Test
    void extendsBaseMapper_withCorrectGeneric() {
        var generic = (ParameterizedType) RuleDefinitionMapper.class.getGenericInterfaces()[0];
        assertEquals(BaseMapper.class, generic.getRawType());
        assertEquals(RuleDefinition.class, generic.getActualTypeArguments()[0]);
    }

    @Test
    void hasMapperAnnotation() {
        assertNotNull(RuleDefinitionMapper.class.getAnnotation(Mapper.class));
    }

    @Test
    void selectForExport_defaultMethodExists_withCorrectSignature() throws NoSuchMethodException {
        Method m = RuleDefinitionMapper.class.getDeclaredMethod("selectForExport", Long.class, List.class, String.class);
        assertTrue(m.isDefault(), "selectForExport 应为 default 方法");
        assertEquals(List.class, m.getReturnType());
    }

    @Test
    void findByTenantAndCode_defaultMethodExists_withCorrectSignature() throws NoSuchMethodException {
        Method m = RuleDefinitionMapper.class.getDeclaredMethod("findByTenantAndCode", Long.class, String.class);
        assertTrue(m.isDefault(), "findByTenantAndCode 应为 default 方法");
        assertEquals(RuleDefinition.class, m.getReturnType());
    }
}
