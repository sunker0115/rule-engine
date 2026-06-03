package com.sstlfsj.rule.eval.internal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sstlfsj.rule.eval.internal.domain.EvaluationSession;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 EvaluationSessionMapper 接口结构正确：继承 BaseMapper 且泛型绑定到 EvaluationSession。
 * 无需数据库连接，纯编译期 / 反射校验。
 */
class EvaluationSessionMapperTest {

    @Test
    void mapper_extendsBaseMapper_withCorrectGeneric() {
        // 验证接口继承关系在编译期成立（能到这里说明编译通过）
        assertTrue(BaseMapper.class.isAssignableFrom(EvaluationSessionMapper.class));
    }

    @Test
    void mapper_genericType_isEvaluationSession() throws Exception {
        // 验证泛型参数绑定到 EvaluationSession
        var genericInterface = EvaluationSessionMapper.class.getGenericInterfaces()[0];
        assertTrue(genericInterface.getTypeName().contains(EvaluationSession.class.getName()));
    }
}
