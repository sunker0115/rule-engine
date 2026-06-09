package com.sstlfsj.rule.eval.internal.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sstlfsj.rule.eval.internal.domain.EvaluationSession;
import org.apache.ibatis.annotations.Insert;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 EvaluationSessionMapper 接口结构正确：继承 BaseMapper 且泛型绑定到 EvaluationSession。
 * 无需数据库连接，纯编译期 / 反射校验。
 */
class EvaluationSessionMapperTest {

    @Test
    void mapper_extendsBaseMapper_withCorrectGeneric() {
        assertTrue(BaseMapper.class.isAssignableFrom(EvaluationSessionMapper.class));
    }

    @Test
    void mapper_genericType_isEvaluationSession() throws Exception {
        var genericInterface = EvaluationSessionMapper.class.getGenericInterfaces()[0];
        assertTrue(genericInterface.getTypeName().contains(EvaluationSession.class.getName()));
    }

    // 防回归：批量 INSERT 列清单曾漏 eval_duration_ms，实体已 set 但被 SQL 丢弃；断言 SQL 同时含列与绑定。
    @Test
    void insertBatch_sql_persistsEvalDurationMs() throws Exception {
        Insert insert = EvaluationSessionMapper.class
                .getMethod("insertBatch", List.class)
                .getAnnotation(Insert.class);
        String sql = String.join("", insert.value());
        assertTrue(sql.contains("eval_duration_ms"), "INSERT 列清单应含 eval_duration_ms");
        assertTrue(sql.contains("evalDurationMs"), "VALUES 应绑定 #{s.evalDurationMs}");
    }
}
