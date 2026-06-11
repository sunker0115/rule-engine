package com.sstlfsj.rule.observability.internal.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sstlfsj.rule.observability.internal.domain.DryRunNodeTraceEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** DryRunNodeTraceMapper 接口定义验证（注解 + 继承关系 + insertBatch 方法）。 */
class DryRunNodeTraceMapperTest {

    @Test
    void mapperAnnotationPresent() {
        assertNotNull(DryRunNodeTraceMapper.class.getAnnotation(Mapper.class));
    }

    @Test
    void extendsBaseMapper() {
        boolean found = false;
        for (Class<?> iface : DryRunNodeTraceMapper.class.getInterfaces()) {
            if (iface.equals(BaseMapper.class)) {
                found = true;
                break;
            }
        }
        assertTrue(found, "DryRunNodeTraceMapper 须继承 BaseMapper");
    }

    @Test
    void genericTypeIsDryRunNodeTraceEntity() {
        java.lang.reflect.Type[] types = DryRunNodeTraceMapper.class.getGenericInterfaces();
        assertEquals(1, types.length);
        java.lang.reflect.ParameterizedType pt = (java.lang.reflect.ParameterizedType) types[0];
        assertEquals(DryRunNodeTraceEntity.class, pt.getActualTypeArguments()[0]);
    }

    @Test
    void insertBatch_methodExists_withInsertAnnotation() throws Exception {
        Method method = DryRunNodeTraceMapper.class.getDeclaredMethod("insertBatch", List.class);
        assertNotNull(method);
        assertNotNull(method.getAnnotation(Insert.class));
    }

    @Test
    void insertBatch_sql_containsParamsColumn() throws Exception {
        Method method = DryRunNodeTraceMapper.class.getDeclaredMethod("insertBatch", List.class);
        String sql = method.getAnnotation(Insert.class).value()[0];
        // 验证列定义和绑定参数都包含 params，确保字段未被遗漏
        assertTrue(sql.contains("params"), "INSERT 列列表须包含 params 列");
        assertTrue(sql.contains("#{e.params}"), "VALUES 绑定须包含 #{e.params}");
    }

    @Test
    void insertBatch_sql_containsDisplayLabelColumn() throws Exception {
        Method method = DryRunNodeTraceMapper.class.getDeclaredMethod("insertBatch", List.class);
        String sql = method.getAnnotation(Insert.class).value()[0];
        // display_label 列与绑定均须存在，否则可读标签快照被静默丢弃
        assertTrue(sql.contains("display_label"), "INSERT 列列表须包含 display_label 列");
        assertTrue(sql.contains("#{e.displayLabel}"), "VALUES 绑定须包含 #{e.displayLabel}");
    }

    @Test
    void purgeOlderThan_methodExists_returningIntForCutoffAndBatch() throws Exception {
        // 数据保留清理入口：default 方法 purgeOlderThan(LocalDateTime, int) -> int
        Method method = DryRunNodeTraceMapper.class.getMethod("purgeOlderThan", LocalDateTime.class, int.class);
        assertTrue(method.isDefault(), "purgeOlderThan 须为 default 方法（封装 BaseMapper.delete，不在 service 散拼 wrapper）");
        assertEquals(int.class, method.getReturnType(), "purgeOlderThan 须返回删除行数（int）");
    }
}
