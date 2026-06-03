package com.sstlfsj.rule.observability.internal.mapper;

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
        // 确认泛型父接口为 BaseMapper<NodeTraceEntity>
        boolean extendsBaseMapper = false;
        for (Class<?> iface : NodeTraceMapper.class.getInterfaces()) {
            if (iface.equals(BaseMapper.class)) {
                extendsBaseMapper = true;
                break;
            }
        }
        // getInterfaces() 返回原始类型，泛型擦除后就是 BaseMapper
        assertTrue(extendsBaseMapper, "NodeTraceMapper 须继承 BaseMapper");
    }

    @Test
    void genericTypeIsNodeTraceEntity() throws Exception {
        // 通过 getGenericInterfaces() 验证泛型参数为 NodeTraceEntity
        java.lang.reflect.Type[] types = NodeTraceMapper.class.getGenericInterfaces();
        assertEquals(1, types.length);
        java.lang.reflect.ParameterizedType pt = (java.lang.reflect.ParameterizedType) types[0];
        assertEquals(NodeTraceEntity.class, pt.getActualTypeArguments()[0]);
    }
}
