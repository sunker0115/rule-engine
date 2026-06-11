package com.sstlfsj.rule.config.internal.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sstlfsj.rule.config.internal.domain.SceneDef;
import org.apache.ibatis.annotations.Mapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 验证 SceneMapper 接口结构及 B7 新增语义查询方法签名。 */
class SceneMapperTest {

    @Test
    void extendsBaseMapper_withCorrectGeneric() {
        var generic = (ParameterizedType) SceneMapper.class.getGenericInterfaces()[0];
        assertEquals(BaseMapper.class, generic.getRawType());
        assertEquals(SceneDef.class, generic.getActualTypeArguments()[0]);
    }

    @Test
    void hasMapperAnnotation() {
        assertNotNull(SceneMapper.class.getAnnotation(Mapper.class));
    }

    @Test
    void findByCode_defaultMethodExists_withCorrectSignature() throws NoSuchMethodException {
        Method m = SceneMapper.class.getDeclaredMethod("findByCode", Long.class, String.class);
        assertTrue(m.isDefault(), "findByCode 应为 default 方法");
        assertEquals(SceneDef.class, m.getReturnType());
    }

    @Test
    void findByIds_defaultMethodExists_withCorrectSignature() throws NoSuchMethodException {
        Method m = SceneMapper.class.getDeclaredMethod("findByIds", Collection.class);
        assertTrue(m.isDefault(), "findByIds 应为 default 方法");
        assertEquals(List.class, m.getReturnType());
    }

    @Test
    void findByTenantId_defaultMethodExists_withCorrectSignature() throws NoSuchMethodException {
        Method m = SceneMapper.class.getDeclaredMethod("findByTenantId", Long.class);
        assertTrue(m.isDefault(), "findByTenantId 应为 default 方法");
        assertEquals(List.class, m.getReturnType());
    }
}
