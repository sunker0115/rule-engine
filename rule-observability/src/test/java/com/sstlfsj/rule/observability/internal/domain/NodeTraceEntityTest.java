package com.sstlfsj.rule.observability.internal.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.sstlfsj.rule.kernel.api.model.ValueSource;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/** NodeTraceEntity 字段赋值与 MyBatis-Plus 注解验证。 */
class NodeTraceEntityTest {

    @Test
    void tableNameAnnotation_isNodeTrace() {
        TableName annotation = NodeTraceEntity.class.getAnnotation(TableName.class);
        assertNotNull(annotation);
        assertEquals("node_trace", annotation.value());
    }

    @Test
    void setterGetter_roundTrip() {
        NodeTraceEntity entity = new NodeTraceEntity();
        LocalDateTime now = LocalDateTime.of(2026, 1, 1, 12, 0);

        entity.setId(1L);
        entity.setEvaluationSessionId(100L);
        entity.setTenantId(200L);
        entity.setRuleVersionId(300L);
        entity.setNodePath("0.1");
        entity.setNodeType("LEAF");
        entity.setConditionType("AMOUNT_GT");
        entity.setMetricCode("revenue");
        entity.setDisplayLabel("revenue>1000");
        entity.setParams("{\"threshold\":1000}");
        entity.setActualValue("1500");
        entity.setResult(true);
        entity.setErrorCode(null);
        entity.setValueSource(ValueSource.FETCHED);
        entity.setEvaluatedAt(now);

        assertEquals(1L, entity.getId());
        assertEquals(100L, entity.getEvaluationSessionId());
        assertEquals(200L, entity.getTenantId());
        assertEquals(300L, entity.getRuleVersionId());
        assertEquals("0.1", entity.getNodePath());
        assertEquals("LEAF", entity.getNodeType());
        assertEquals("AMOUNT_GT", entity.getConditionType());
        assertEquals("revenue", entity.getMetricCode());
        assertEquals("revenue>1000", entity.getDisplayLabel());
        assertEquals("{\"threshold\":1000}", entity.getParams());
        assertEquals("1500", entity.getActualValue());
        assertTrue(entity.getResult());
        assertNull(entity.getErrorCode());
        assertEquals(ValueSource.FETCHED, entity.getValueSource());
        assertEquals(now, entity.getEvaluatedAt());
    }

    @Test
    void defaultConstruction_allFieldsNull() {
        NodeTraceEntity entity = new NodeTraceEntity();
        assertNull(entity.getId());
        assertNull(entity.getEvaluationSessionId());
        assertNull(entity.getResult());
        assertNull(entity.getEvaluatedAt());
    }
}
