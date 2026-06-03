package com.sstlfsj.rule.eval.internal.repository;

import com.sstlfsj.rule.eval.internal.snapshot.RuleVersionRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 RuleVersionReadMapper 接口结构正确性。
 * SQL 正确性在集成测试（需数据库）中验证，此处仅覆盖接口约定。
 */
class RuleVersionReadMapperTest {

    @Test
    void interfaceAnnotatedWithMapper() {
        assertNotNull(RuleVersionReadMapper.class.getAnnotation(Mapper.class));
    }

    @Test
    void loadAllActive_returnsListOfRuleVersionRow() throws Exception {
        Method method = RuleVersionReadMapper.class.getMethod("loadAllActive");
        assertEquals(List.class, method.getReturnType());
        assertNotNull(method.getAnnotation(Select.class));
        String sql = method.getAnnotation(Select.class).value()[0];
        assertTrue(sql.contains("rv.status = 'ACTIVE'"), "SQL 应过滤 ACTIVE 状态");
    }

    @Test
    void loadActiveByScene_hasCorrectParameters() throws Exception {
        Method method = RuleVersionReadMapper.class.getMethod(
                "loadActiveByScene", Long.class, String.class);
        assertEquals(List.class, method.getReturnType());
        assertNotNull(method.getAnnotation(Select.class));
        String sql = method.getAnnotation(Select.class).value()[0];
        assertTrue(sql.contains("#{tenantId}"), "SQL 应包含 tenantId 参数");
        assertTrue(sql.contains("#{sceneCode}"), "SQL 应包含 sceneCode 参数");
    }

    @Test
    void loadById_returnsRuleVersionRow() throws Exception {
        Method method = RuleVersionReadMapper.class.getMethod("loadById", Long.class);
        assertEquals(RuleVersionRow.class, method.getReturnType());
        assertNotNull(method.getAnnotation(Select.class));
        String sql = method.getAnnotation(Select.class).value()[0];
        assertTrue(sql.contains("#{ruleVersionId}"), "SQL 应包含 ruleVersionId 参数");
    }
}
