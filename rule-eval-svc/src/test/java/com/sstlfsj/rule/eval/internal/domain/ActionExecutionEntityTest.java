package com.sstlfsj.rule.eval.internal.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.sstlfsj.rule.kernel.api.model.ActionResult;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/** ActionExecutionEntity 定义验证：注解、setter/getter 轮转。 */
class ActionExecutionEntityTest {

    @Test
    void tableNameAnnotation_isActionExecution() {
        TableName ann = ActionExecutionEntity.class.getAnnotation(TableName.class);
        assertNotNull(ann, "须标注 @TableName");
        assertEquals("action_execution", ann.value());
    }

    @Test
    void settersAndGetters_basicFields_roundTrip() {
        ActionExecutionEntity e = new ActionExecutionEntity();
        e.setId(1L);
        e.setEvaluationSessionId(100L);
        e.setTenantId(200L);
        e.setEventId("evt-001");
        e.setActionId("act-001");
        e.setActionType("SEND_ALERT");
        e.setDecisionCode("BLOCK");
        e.setStatus(ActionResult.ActionStatus.SUCCESS);
        e.setErrorCode(null);
        e.setRetryable(false);
        e.setRetryCount(0);

        assertEquals(1L, e.getId());
        assertEquals(100L, e.getEvaluationSessionId());
        assertEquals(200L, e.getTenantId());
        assertEquals("evt-001", e.getEventId());
        assertEquals("act-001", e.getActionId());
        assertEquals("SEND_ALERT", e.getActionType());
        assertEquals("BLOCK", e.getDecisionCode());
        assertEquals(ActionResult.ActionStatus.SUCCESS, e.getStatus());
        assertNull(e.getErrorCode());
        assertFalse(e.getRetryable());
        assertEquals(0, e.getRetryCount());
    }

    @Test
    void settersAndGetters_timeAndCompensation_roundTrip() {
        ActionExecutionEntity e = new ActionExecutionEntity();
        LocalDateTime now = LocalDateTime.now();
        e.setExecutedAt(now);
        e.setCreatedAt(now);
        e.setCompensated(true);
        e.setCompensatedAt(now);
        e.setCompensatedBy("ops-user");

        assertEquals(now, e.getExecutedAt());
        assertEquals(now, e.getCreatedAt());
        assertTrue(e.getCompensated());
        assertEquals(now, e.getCompensatedAt());
        assertEquals("ops-user", e.getCompensatedBy());
    }
}
