package com.sstlfsj.rule.observability.internal.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sstlfsj.rule.observability.internal.domain.NodeTraceEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** NodeTraceMapper 接口定义验证（注解 + 继承关系 + insertBatch 方法）。 */
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

    @Test
    void insertBatch_methodExists_withInsertAnnotation() throws Exception {
        Method method = NodeTraceMapper.class.getDeclaredMethod("insertBatch", List.class);
        assertNotNull(method, "insertBatch(List) 方法须存在");
        assertNotNull(method.getAnnotation(Insert.class), "insertBatch 须有 @Insert 注解");
    }

    @Test
    void insertBatch_sqlPersistsDisplayLabelAndParams() throws Exception {
        Method method = NodeTraceMapper.class.getDeclaredMethod("insertBatch", List.class);
        String sql = method.getAnnotation(Insert.class).value()[0];
        // 列清单与占位符均须含 display_label / params，否则两字段被静默丢弃
        assertTrue(sql.contains("display_label"), "INSERT 须包含 display_label 列");
        assertTrue(sql.contains("params"), "INSERT 须包含 params 列");
        assertTrue(sql.contains("#{e.displayLabel}"), "INSERT 须绑定 displayLabel");
        assertTrue(sql.contains("#{e.params}"), "INSERT 须绑定 params");
    }

    @Test
    void purgeOlderThan_methodExists_returningIntForCutoffAndBatch() throws Exception {
        // 数据保留清理入口：default 方法 purgeOlderThan(LocalDateTime, int) -> int
        Method method = NodeTraceMapper.class.getMethod("purgeOlderThan", LocalDateTime.class, int.class);
        assertTrue(method.isDefault(), "purgeOlderThan 须为 default 方法（封装 BaseMapper.delete，不在 service 散拼 wrapper）");
        assertEquals(int.class, method.getReturnType(), "purgeOlderThan 须返回删除行数（int）");
    }
}
