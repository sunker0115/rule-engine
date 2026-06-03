package com.sstlfsj.rule.observability.internal.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/** DryRunNodeTraceEntity 字段赋值与 MyBatis-Plus 注解验证。 */
class DryRunNodeTraceEntityTest {

    @Test
    void tableNameAnnotation_isDryRunNodeTrace() {
        TableName annotation = DryRunNodeTraceEntity.class.getAnnotation(TableName.class);
        assertNotNull(annotation);
        assertEquals("dry_run_node_trace", annotation.value());
    }

    @Test
    void setterGetter_roundTrip() {
        DryRunNodeTraceEntity entity = new DryRunNodeTraceEntity();
        LocalDateTime now = LocalDateTime.of(2026, 1, 1, 12, 0);

        entity.setId(1L);
        entity.setDryRunSessionId(100L);
        entity.setTenantId(200L);
        entity.setRuleVersionId(300L);
        entity.setNodePath("0.1");
        entity.setNodeType("LEAF");
        entity.setConditionType("AMOUNT_GT");
        entity.setMetricCode("revenue");
        entity.setParams("{\"threshold\":1000}");
        entity.setActualValue("1500");
        entity.setResult(true);
        entity.setErrorCode(null);
        entity.setValueSource("FETCHED");
        entity.setEvaluatedAt(now);

        assertEquals(1L, entity.getId());
        assertEquals(100L, entity.getDryRunSessionId());
        assertEquals(200L, entity.getTenantId());
        assertEquals(300L, entity.getRuleVersionId());
        assertEquals("0.1", entity.getNodePath());
        assertEquals("LEAF", entity.getNodeType());
        assertEquals("AMOUNT_GT", entity.getConditionType());
        assertEquals("revenue", entity.getMetricCode());
        assertEquals("{\"threshold\":1000}", entity.getParams());
        assertEquals("1500", entity.getActualValue());
        assertTrue(entity.getResult());
        assertNull(entity.getErrorCode());
        assertEquals("FETCHED", entity.getValueSource());
        assertEquals(now, entity.getEvaluatedAt());
    }

    @Test
    void defaultConstruction_allFieldsNull() {
        DryRunNodeTraceEntity entity = new DryRunNodeTraceEntity();
        assertNull(entity.getId());
        assertNull(entity.getDryRunSessionId());
        assertNull(entity.getResult());
        assertNull(entity.getEvaluatedAt());
    }
}
