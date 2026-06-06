package com.sstlfsj.rule.audit.internal.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sstlfsj.rule.audit.internal.domain.EvalSessionRow;
import com.sstlfsj.rule.audit.internal.domain.RuleSessionRow;
import org.apache.ibatis.annotations.Param;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.util.List;

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

    @Test
    void selectByRuleDefinitionId_方法签名正确() throws Exception {
        Method method = EvalSessionReadMapper.class.getMethod(
                "selectByRuleDefinitionId", Long.class, String.class, int.class, int.class);
        assertThat(method.getReturnType()).isEqualTo(List.class);
        ParameterizedType returnType = (ParameterizedType) method.getGenericReturnType();
        assertThat(returnType.getActualTypeArguments()[0]).isEqualTo(RuleSessionRow.class);
    }

    @Test
    void countByRuleDefinitionId_方法签名正确() throws Exception {
        Method method = EvalSessionReadMapper.class.getMethod(
                "countByRuleDefinitionId", Long.class, String.class);
        assertThat(method.getReturnType()).isEqualTo(long.class);
    }

    @Test
    void selectByRuleDefinitionId_参数带Param注解() throws Exception {
        Method method = EvalSessionReadMapper.class.getMethod(
                "selectByRuleDefinitionId", Long.class, String.class, int.class, int.class);
        java.lang.annotation.Annotation[][] paramAnnotations = method.getParameterAnnotations();
        assertThat(((Param) paramAnnotations[0][0]).value()).isEqualTo("ruleDefinitionId");
        assertThat(((Param) paramAnnotations[1][0]).value()).isEqualTo("status");
        assertThat(((Param) paramAnnotations[2][0]).value()).isEqualTo("limit");
        assertThat(((Param) paramAnnotations[3][0]).value()).isEqualTo("offset");
    }
}
