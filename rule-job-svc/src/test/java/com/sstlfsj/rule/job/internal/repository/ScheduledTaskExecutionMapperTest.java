package com.sstlfsj.rule.job.internal.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sstlfsj.rule.job.internal.domain.ScheduledTaskExecution;
import org.apache.ibatis.annotations.Mapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 验证 ScheduledTaskExecutionMapper 接口结构及语义查询方法签名。 */
class ScheduledTaskExecutionMapperTest {

    @Test
    void extendsBaseMapper_withCorrectGeneric() {
        var generic = (ParameterizedType) ScheduledTaskExecutionMapper.class.getGenericInterfaces()[0];
        assertEquals(BaseMapper.class, generic.getRawType());
        assertEquals(ScheduledTaskExecution.class, generic.getActualTypeArguments()[0]);
    }

    @Test
    void hasMapperAnnotation() {
        assertNotNull(ScheduledTaskExecutionMapper.class.getAnnotation(Mapper.class));
    }

    @Test
    void recentByTask_defaultMethodExists_withCorrectSignature() throws NoSuchMethodException {
        Method m = ScheduledTaskExecutionMapper.class.getDeclaredMethod("recentByTask", Long.class, int.class);
        assertTrue(m.isDefault(), "recentByTask 应为 default 方法");
        assertEquals(List.class, m.getReturnType());
    }
}
