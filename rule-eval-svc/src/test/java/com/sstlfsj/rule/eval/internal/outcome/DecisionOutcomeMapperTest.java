package com.sstlfsj.rule.eval.internal.outcome;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 DecisionOutcomeMapper 结构正确：继承 BaseMapper，upsert SQL 含全部列 + ON DUPLICATE KEY UPDATE 覆盖语义。
 * 无需数据库连接，纯反射 / SQL 字符串校验；真实 upsert 落库在 e2e 验证。
 */
class DecisionOutcomeMapperTest {

    @Test
    void mapper_extendsBaseMapper_withCorrectGeneric() {
        assertTrue(BaseMapper.class.isAssignableFrom(DecisionOutcomeMapper.class));
        var genericInterface = DecisionOutcomeMapper.class.getGenericInterfaces()[0];
        assertTrue(genericInterface.getTypeName().contains(DecisionOutcome.class.getName()));
    }

    @Test
    void upsertBatch_sql_insertsAllColumns() throws Exception {
        String sql = upsertSql();
        for (String col : List.of("tenant_id", "event_id", "outcome_label", "outcome_value",
                "outcome_note", "labeled_at", "source")) {
            assertTrue(sql.contains(col), "INSERT 列清单应含 " + col);
        }
    }

    @Test
    void upsertBatch_sql_overwritesOnDuplicateKey() throws Exception {
        String sql = upsertSql();
        assertTrue(sql.contains("ON DUPLICATE KEY UPDATE"), "应为 (tenant_id,event_id) 幂等 upsert");
        // 覆盖语义：撞 uk 时用新值覆盖标签字段（标签可修正）
        assertTrue(sql.contains("outcome_label = VALUES(outcome_label)"), "撞 uk 应覆盖 outcome_label");
        assertTrue(sql.contains("labeled_at    = VALUES(labeled_at)")
                || sql.contains("labeled_at = VALUES(labeled_at)"), "撞 uk 应覆盖 labeled_at");
    }

    private String upsertSql() throws Exception {
        Insert insert = DecisionOutcomeMapper.class
                .getMethod("upsertBatch", List.class)
                .getAnnotation(Insert.class);
        return String.join("", insert.value());
    }
}
