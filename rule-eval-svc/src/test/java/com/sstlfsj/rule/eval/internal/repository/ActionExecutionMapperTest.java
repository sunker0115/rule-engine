package com.sstlfsj.rule.eval.internal.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sstlfsj.rule.eval.internal.domain.ActionExecutionEntity;
import org.apache.ibatis.annotations.Mapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** ActionExecutionMapper 接口定义验证：注解、泛型、继承关系。 */
class ActionExecutionMapperTest {

    @Test
    void mapperAnnotationPresent() {
        assertNotNull(ActionExecutionMapper.class.getAnnotation(Mapper.class));
    }

    @Test
    void extendsBaseMapper() {
        boolean found = false;
        for (Class<?> iface : ActionExecutionMapper.class.getInterfaces()) {
            if (iface.equals(BaseMapper.class)) {
                found = true;
                break;
            }
        }
        assertTrue(found, "ActionExecutionMapper 须继承 BaseMapper");
    }

    @Test
    void genericTypeIsActionExecutionEntity() {
        java.lang.reflect.Type[] types = ActionExecutionMapper.class.getGenericInterfaces();
        assertEquals(1, types.length);
        java.lang.reflect.ParameterizedType pt = (java.lang.reflect.ParameterizedType) types[0];
        assertEquals(ActionExecutionEntity.class, pt.getActualTypeArguments()[0]);
    }

    @Test
    void purgeOlderThanDefaultMethodPresent() throws NoSuchMethodException {
        java.lang.reflect.Method m = ActionExecutionMapper.class.getMethod(
                "purgeOlderThan", java.time.LocalDateTime.class, int.class);
        assertTrue(m.isDefault(), "purgeOlderThan 须为接口 default 方法");
        assertEquals(int.class, m.getReturnType());
    }
}
