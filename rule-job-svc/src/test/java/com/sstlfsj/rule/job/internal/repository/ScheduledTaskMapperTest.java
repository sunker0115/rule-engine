package com.sstlfsj.rule.job.internal.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sstlfsj.rule.job.internal.domain.ScheduledTask;
import org.apache.ibatis.annotations.Mapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 验证 ScheduledTaskMapper 接口结构及语义查询方法签名。 */
class ScheduledTaskMapperTest {

    @Test
    void extendsBaseMapper_withCorrectGeneric() {
        var generic = (ParameterizedType) ScheduledTaskMapper.class.getGenericInterfaces()[0];
        assertEquals(BaseMapper.class, generic.getRawType());
        assertEquals(ScheduledTask.class, generic.getActualTypeArguments()[0]);
    }

    @Test
    void hasMapperAnnotation() {
        assertNotNull(ScheduledTaskMapper.class.getAnnotation(Mapper.class));
    }

    @Test
    void findByTenantCode_defaultMethodExists_withCorrectSignature() throws NoSuchMethodException {
        Method m = ScheduledTaskMapper.class.getDeclaredMethod("findByTenantCode", Long.class, String.class);
        assertTrue(m.isDefault(), "findByTenantCode 应为 default 方法");
        assertEquals(ScheduledTask.class, m.getReturnType());
    }

    @Test
    void findByTenant_defaultMethodExists_withCorrectSignature() throws NoSuchMethodException {
        Method m = ScheduledTaskMapper.class.getDeclaredMethod("findByTenant", Long.class);
        assertTrue(m.isDefault(), "findByTenant 应为 default 方法");
        assertEquals(List.class, m.getReturnType());
    }

    @Test
    void findAllActive_defaultMethodExists_withCorrectSignature() throws NoSuchMethodException {
        Method m = ScheduledTaskMapper.class.getDeclaredMethod("findAllActive");
        assertTrue(m.isDefault(), "findAllActive 应为 default 方法");
        assertEquals(List.class, m.getReturnType());
    }
}
