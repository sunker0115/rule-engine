package com.sstlfsj.rule.observability.internal.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sstlfsj.rule.observability.internal.domain.NodeTraceEntity;
import org.apache.ibatis.annotations.Mapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** NodeTraceMapper 接口定义验证（注解 + 继承关系）。 */
class NodeTraceMapperTest {

    @Test
    void mapperAnnotationPresent() {
        assertNotNull(NodeTraceMapper.class.getAnnotation(Mapper.class));
    }

    @Test
    void extendsBaseMapper() {
        boolean extendsBaseMapper = false;
        for (Class<?> iface : NodeTraceMapper.class.getInterfaces()) {
            if (iface.equals(BaseMapper.class)) {
                extendsBaseMapper = true;
                break;
            }
        }
        assertTrue(extendsBaseMapper, "NodeTraceMapper 须继承 BaseMapper");
    }

    @Test
    void genericTypeIsNodeTraceEntity() throws Exception {
        java.lang.reflect.Type[] types = NodeTraceMapper.class.getGenericInterfaces();
        assertEquals(1, types.length);
        java.lang.reflect.ParameterizedType pt = (java.lang.reflect.ParameterizedType) types[0];
        assertEquals(NodeTraceEntity.class, pt.getActualTypeArguments()[0]);
    }
}
