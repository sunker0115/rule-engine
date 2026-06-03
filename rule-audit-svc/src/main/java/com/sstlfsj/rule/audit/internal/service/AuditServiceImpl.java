package com.sstlfsj.rule.audit.internal.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sstlfsj.rule.audit.api.service.AuditService;
import com.sstlfsj.rule.audit.internal.domain.AuditLogRow;
import com.sstlfsj.rule.audit.internal.domain.EvalSessionRow;
import com.sstlfsj.rule.audit.internal.domain.NodeTraceRow;
import com.sstlfsj.rule.audit.internal.repository.AuditLogReadMapper;
import com.sstlfsj.rule.audit.internal.repository.EvalSessionReadMapper;
import com.sstlfsj.rule.audit.internal.repository.NodeTraceReadMapper;
import org.springframework.stereotype.Service;

import java.time.ZoneOffset;
import java.util.List;

@Service
class AuditServiceImpl implements AuditService {

    private final EvalSessionReadMapper evalSessionMapper;
    private final NodeTraceReadMapper nodeTraceMapper;
    private final AuditLogReadMapper auditLogMapper;

    AuditServiceImpl(EvalSessionReadMapper evalSessionMapper,
                     NodeTraceReadMapper nodeTraceMapper,
                     AuditLogReadMapper auditLogMapper) {
        this.evalSessionMapper = evalSessionMapper;
        this.nodeTraceMapper = nodeTraceMapper;
        this.auditLogMapper = auditLogMapper;
    }

    @Override
    public PageResult<AuditLogEntry> queryAuditLogs(String tenantId, String resourceType,
                                                     Long resourceId, int page, int size) {
        LambdaQueryWrapper<AuditLogRow> wrapper = new LambdaQueryWrapper<AuditLogRow>()
                .eq(AuditLogRow::getTenantId, Long.valueOf(tenantId))
                .eq(resourceType != null, AuditLogRow::getTargetType, resourceType)
                .eq(resourceId != null, AuditLogRow::getTargetId, String.valueOf(resourceId))
                .orderByDesc(AuditLogRow::getOperatedAt);

        // MyBatis-Plus 分页从 1 开始，对外 API 从 0 开始
        Page<AuditLogRow> mp = auditLogMapper.selectPage(new Page<>(page + 1, size), wrapper);
        List<AuditLogEntry> items = mp.getRecords().stream()
                .map(r -> new AuditLogEntry(
                        r.getId(),
                        tenantId,
                        r.getTargetType(),
                        r.getTargetId() != null ? Long.valueOf(r.getTargetId()) : null,
                        r.getAction(),
                        r.getActor(),
                        r.getActorType(),
                        r.getBeforeSnapshot(),
                        r.getAfterSnapshot(),
                        r.getOperatedAt() != null
                                ? r.getOperatedAt().toInstant(ZoneOffset.UTC) : null
                ))
                .toList();
        return new PageResult<>(items, mp.getTotal(), page, size);
    }

    @Override
    public PageResult<EvalSessionEntry> queryEvalSessions(String tenantId, String eventId,
                                                           int page, int size) {
        LambdaQueryWrapper<EvalSessionRow> wrapper = new LambdaQueryWrapper<EvalSessionRow>()
                .eq(EvalSessionRow::getTenantId, Long.valueOf(tenantId))
                .eq(eventId != null, EvalSessionRow::getEventId, eventId)
                .orderByDesc(EvalSessionRow::getStartedAt);

        // MyBatis-Plus 分页从 1 开始，对外 API 从 0 开始
        Page<EvalSessionRow> mp = evalSessionMapper.selectPage(new Page<>(page + 1, size), wrapper);
        List<EvalSessionEntry> items = mp.getRecords().stream()
                .map(r -> new EvalSessionEntry(
                        String.valueOf(r.getId()),
                        tenantId,
                        r.getSceneCode(),
                        r.getEventId(),
                        r.getStatus(),
                        r.getStartedAt() != null
                                ? r.getStartedAt().toInstant(ZoneOffset.UTC) : null
                ))
                .toList();
        return new PageResult<>(items, mp.getTotal(), page, size);
    }

    @Override
    public List<TraceNodeEntry> queryTrace(String tenantId, Long sessionId) {
        // 按 node_path 字典序返回，单次 session 通常 < 200 行，无需分页
        List<NodeTraceRow> rows = nodeTraceMapper.selectList(
                new LambdaQueryWrapper<NodeTraceRow>()
                        .eq(NodeTraceRow::getEvaluationSessionId, sessionId)
                        .eq(NodeTraceRow::getTenantId, Long.valueOf(tenantId))
                        .orderByAsc(NodeTraceRow::getNodePath)
        );
        return rows.stream()
                .map(r -> new TraceNodeEntry(
                        r.getNodePath(),
                        r.getNodeType(),
                        r.getConditionType(),
                        r.getMetricCode(),
                        r.getActualValue(),
                        r.getResult(),
                        r.getErrorCode(),
                        r.getValueSource()
                ))
                .toList();
    }
}
