package com.sstlfsj.rule.audit.internal.service;

import com.sstlfsj.rule.audit.api.service.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证 AuditServiceImpl 骨架方法抛出 UnsupportedOperationException。 */
class AuditServiceImplTest {

    private AuditService service;

    @BeforeEach
    void setUp() {
        service = new AuditServiceImpl();
    }

    @Test
    void queryAuditLogs_骨架方法抛出UnsupportedOperationException() {
        assertThatThrownBy(() -> service.queryAuditLogs("t1", "SCENE", null, 0, 10))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("queryAuditLogs");
    }

    @Test
    void queryEvalSessions_骨架方法抛出UnsupportedOperationException() {
        assertThatThrownBy(() -> service.queryEvalSessions("t1", null, 0, 10))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("queryEvalSessions");
    }
}
