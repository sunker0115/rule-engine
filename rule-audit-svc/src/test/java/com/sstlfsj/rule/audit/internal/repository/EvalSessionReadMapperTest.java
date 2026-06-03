package com.sstlfsj.rule.audit.internal.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sstlfsj.rule.audit.internal.domain.EvalSessionRow;
import org.junit.jupiter.api.Test;

import java.lang.reflect.ParameterizedType;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证 EvalSessionReadMapper 接口定义正确：继承 BaseMapper 且泛型参数为 EvalSessionRow。 */
class EvalSessionReadMapperTest {

    @Test
    void 接口继承BaseMapper且泛型参数正确() {
        assertThat(BaseMapper.class).isAssignableFrom(EvalSessionReadMapper.class);

        ParameterizedType superInterface = (ParameterizedType)
                EvalSessionReadMapper.class.getGenericInterfaces()[0];
        assertThat(superInterface.getActualTypeArguments()[0]).isEqualTo(EvalSessionRow.class);
    }
}
