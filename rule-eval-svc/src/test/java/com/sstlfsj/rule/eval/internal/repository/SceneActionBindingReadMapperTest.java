package com.sstlfsj.rule.eval.internal.repository;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/** SceneActionBindingReadMapper 接口定义验证：注解、查询方法。 */
class SceneActionBindingReadMapperTest {

    @Test
    void mapperAnnotationPresent() {
        assertNotNull(SceneActionBindingReadMapper.class.getAnnotation(Mapper.class));
    }

    @Test
    void findBySceneCode_methodExists_withSelectAnnotation() throws Exception {
        Method method = SceneActionBindingReadMapper.class.getDeclaredMethod(
                "findBySceneCode", Long.class, String.class);
        assertNotNull(method);
        assertNotNull(method.getAnnotation(Select.class));
    }
}
