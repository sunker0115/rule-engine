package com.sstlfsj.rule.eval.internal.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sstlfsj.rule.eval.internal.domain.DryRunSession;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 DryRunSessionMapper 接口结构正确：继承 BaseMapper 且泛型绑定到 DryRunSession。
 * 无需数据库连接，纯编译期 / 反射校验。
 */
class DryRunSessionMapperTest {

    @Test
    void mapper_extendsBaseMapper_withCorrectGeneric() {
        assertTrue(BaseMapper.class.isAssignableFrom(DryRunSessionMapper.class));
    }

    @Test
    void mapper_genericType_isDryRunSession() throws Exception {
        var genericInterface = DryRunSessionMapper.class.getGenericInterfaces()[0];
        assertTrue(genericInterface.getTypeName().contains(DryRunSession.class.getName()));
    }

    @Test
    void purgeOlderThan_methodExists_returningIntForCutoffAndBatch() throws Exception {
        // 数据保留清理入口：default 方法 purgeOlderThan(LocalDateTime, int) -> int
        Method method = DryRunSessionMapper.class.getMethod("purgeOlderThan", LocalDateTime.class, int.class);
        assertTrue(method.isDefault(), "purgeOlderThan 须为 default 方法（封装 BaseMapper.delete，不在 service 散拼 wrapper）");
        assertEquals(int.class, method.getReturnType(), "purgeOlderThan 须返回删除行数（int）");
    }
}
