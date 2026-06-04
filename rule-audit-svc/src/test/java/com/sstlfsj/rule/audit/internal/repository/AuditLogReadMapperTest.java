package com.sstlfsj.rule.audit.internal.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sstlfsj.rule.audit.internal.domain.AuditLogRow;
import org.junit.jupiter.api.Test;

import java.lang.reflect.ParameterizedType;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证 AuditLogReadMapper 接口定义正确：继承 BaseMapper 且泛型参数为 AuditLogRow。 */
class AuditLogReadMapperTest {

    @Test
    void 接口继承BaseMapper且泛型参数正确() {
        assertThat(BaseMapper.class).isAssignableFrom(AuditLogReadMapper.class);

        ParameterizedType superInterface = (ParameterizedType)
                AuditLogReadMapper.class.getGenericInterfaces()[0];
        assertThat(superInterface.getActualTypeArguments()[0]).isEqualTo(AuditLogRow.class);
    }
}
