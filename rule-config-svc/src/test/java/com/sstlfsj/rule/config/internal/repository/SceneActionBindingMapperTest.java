package com.sstlfsj.rule.config.internal.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sstlfsj.rule.config.internal.domain.SceneActionBindingDef;
import org.apache.ibatis.annotations.Mapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 验证 SceneActionBindingMapper 接口结构与 default 方法签名。 */
class SceneActionBindingMapperTest {

    @Test
    void extendsBaseMapper_withCorrectGeneric() {
        var generic = (ParameterizedType) SceneActionBindingMapper.class.getGenericInterfaces()[0];
        assertEquals(BaseMapper.class, generic.getRawType());
        assertEquals(SceneActionBindingDef.class, generic.getActualTypeArguments()[0]);
    }

    @Test
    void hasMapperAnnotation() {
        assertNotNull(SceneActionBindingMapper.class.getAnnotation(Mapper.class));
    }

    @Test
    void findBySceneId_defaultMethodExists_withCorrectSignature() throws NoSuchMethodException {
        Method m = SceneActionBindingMapper.class.getDeclaredMethod("findBySceneId", Long.class);
        assertTrue(m.isDefault(), "findBySceneId 应为 default 方法");
        assertEquals(List.class, m.getReturnType());
    }

    @Test
    void deleteBySceneIdAndActionType_defaultMethodExists_withCorrectSignature() throws NoSuchMethodException {
        Method m = SceneActionBindingMapper.class.getDeclaredMethod(
                "deleteBySceneIdAndActionType", Long.class, String.class);
        assertTrue(m.isDefault(), "deleteBySceneIdAndActionType 应为 default 方法");
        assertEquals(int.class, m.getReturnType());
    }
}
