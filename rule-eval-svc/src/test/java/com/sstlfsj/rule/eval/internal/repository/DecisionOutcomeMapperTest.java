package com.sstlfsj.rule.eval.internal.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sstlfsj.rule.eval.api.service.OutcomeService.OutcomeRecord;
import com.sstlfsj.rule.eval.internal.domain.DecisionOutcome;
import org.apache.ibatis.annotations.Insert;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证 DecisionOutcomeMapper：结构（继承 BaseMapper + SQL 列/覆盖语义）+ default 方法 upsertOutcomes 的转换/短路逻辑。
 * SQL 校验纯反射，default 方法走 thenCallRealMethod 跑真实转换、捕获 upsertBatch 入参；真实落库在 e2e 验证。
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

    @Test
    void upsertOutcomes_emptyOrNull_shortCircuits_noUpsertBatch() {
        DecisionOutcomeMapper mapper = mock(DecisionOutcomeMapper.class);
        when(mapper.upsertOutcomes(anyLong(), org.mockito.ArgumentMatchers.any())).thenCallRealMethod();

        assertEquals(0, mapper.upsertOutcomes(1L, List.of()));
        assertEquals(0, mapper.upsertOutcomes(1L, null));
        verify(mapper, org.mockito.Mockito.never()).upsertBatch(anyList());
    }

    @Test
    void upsertOutcomes_mapsOutcomeRecordToRow() {
        DecisionOutcomeMapper mapper = mock(DecisionOutcomeMapper.class);
        when(mapper.upsertOutcomes(anyLong(), anyList())).thenCallRealMethod();

        Instant t = Instant.parse("2026-06-18T10:00:00Z");
        int n = mapper.upsertOutcomes(7L, List.of(
                new OutcomeRecord("evt-1", "FRAUD", new BigDecimal("1280.50"), t, "ops", "chargeback")));
        assertEquals(1, n);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DecisionOutcome>> cap = ArgumentCaptor.forClass(List.class);
        verify(mapper).upsertBatch(cap.capture());
        DecisionOutcome row = cap.getValue().get(0);
        assertEquals(7L, row.getTenantId());
        assertEquals("evt-1", row.getEventId());
        assertEquals("FRAUD", row.getOutcomeLabel());
        assertEquals(new BigDecimal("1280.50"), row.getOutcomeValue());
        assertEquals("chargeback", row.getOutcomeNote());
        assertEquals("ops", row.getSource());
        assertNotNull(row.getLabeledAt());   // Instant → LocalDateTime 已转换
    }

    @Test
    void distinctLabels_methodExists_returnsListString() throws Exception {
        // distinctLabels(Long) 存在且返回 List<String>（LambdaQueryWrapper 查询，真实 DB 行为在 e2e 验）
        var method = DecisionOutcomeMapper.class.getMethod("distinctLabels", Long.class);
        assertTrue(method.isDefault(), "distinctLabels 须为 default 方法（封装 LambdaQueryWrapper，不在 service 散拼）");
        assertEquals(List.class, method.getReturnType(), "返回类型须为 List");
    }

    private String upsertSql() throws Exception {
        Insert insert = DecisionOutcomeMapper.class
                .getMethod("upsertBatch", List.class)
                .getAnnotation(Insert.class);
        return String.join("", insert.value());
    }
}
