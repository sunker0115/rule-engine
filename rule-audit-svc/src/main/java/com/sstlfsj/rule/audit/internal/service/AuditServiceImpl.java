package com.sstlfsj.rule.audit.internal.service;

import com.sstlfsj.rule.audit.api.service.AuditService;
import org.springframework.stereotype.Service;

@Service
class AuditServiceImpl implements AuditService {

    @Override
    public PageResult<AuditLogEntry> queryAuditLogs(String tenantId, String resourceType,
                                                     Long resourceId, int page, int size) {
        throw new UnsupportedOperationException("queryAuditLogs not yet implemented");
    }

    @Override
    public PageResult<EvalSessionEntry> queryEvalSessions(String tenantId, String eventId,
                                                           int page, int size) {
        throw new UnsupportedOperationException("queryEvalSessions not yet implemented");
    }
}
