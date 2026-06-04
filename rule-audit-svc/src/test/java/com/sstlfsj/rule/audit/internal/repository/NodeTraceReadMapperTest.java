package com.sstlfsj.rule.audit.internal.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sstlfsj.rule.audit.internal.domain.NodeTraceRow;
import org.junit.jupiter.api.Test;

import java.lang.reflect.ParameterizedType;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证 NodeTraceReadMapper 接口定义正确：继承 BaseMapper 且泛型参数为 NodeTraceRow。 */
class NodeTraceReadMapperTest {

    @Test
    void 接口继承BaseMapper且泛型参数正确() {
        assertThat(BaseMapper.class).isAssignableFrom(NodeTraceReadMapper.class);

        ParameterizedType superInterface = (ParameterizedType)
                NodeTraceReadMapper.class.getGenericInterfaces()[0];
        assertThat(superInterface.getActualTypeArguments()[0]).isEqualTo(NodeTraceRow.class);
    }
}
