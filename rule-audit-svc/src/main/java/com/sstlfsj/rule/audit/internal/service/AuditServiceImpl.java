package com.sstlfsj.rule.audit.internal.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sstlfsj.rule.audit.api.dto.AuditLogQuery;
import com.sstlfsj.rule.audit.api.dto.EvalSessionQuery;
import com.sstlfsj.rule.audit.api.service.AuditService;
import com.sstlfsj.rule.audit.internal.domain.AuditLogRow;
import com.sstlfsj.rule.audit.internal.domain.EvalSessionRow;
import com.sstlfsj.rule.audit.internal.domain.NodeTraceRow;
import com.sstlfsj.rule.audit.internal.domain.RuleSessionRow;
import com.sstlfsj.rule.audit.internal.repository.AuditLogReadMapper;
import com.sstlfsj.rule.audit.internal.repository.EvalSessionReadMapper;
import com.sstlfsj.rule.audit.internal.repository.NodeTraceReadMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.ZoneOffset;
import java.util.List;

@Service
@RequiredArgsConstructor
class AuditServiceImpl implements AuditService {

    private final EvalSessionReadMapper evalSessionMapper;
    private final NodeTraceReadMapper nodeTraceMapper;
    private final AuditLogReadMapper auditLogMapper;

    @Override
    public PageResult<AuditLogEntry> queryAuditLogs(AuditLogQuery q) {
        Page<AuditLogRow> mp = auditLogMapper.selectAuditLogPage(
                new Page<>(q.page() + 1, q.size()), Long.valueOf(q.tenantId()), q.resourceType(), q.resourceId(),
                q.action(), q.actorId(), q.from(), q.to());
        List<AuditLogEntry> items = mp.getRecords().stream()
                .map(r -> new AuditLogEntry(
                        r.getId(),
                        q.tenantId(),
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
        return new PageResult<>(items, mp.getTotal(), q.page(), q.size());
    }

    @Override
    public PageResult<EvalSessionEntry> queryEvalSessions(EvalSessionQuery q) {
        Page<EvalSessionRow> mp = evalSessionMapper.selectEvalSessionPage(
                new Page<>(q.page() + 1, q.size()), Long.valueOf(q.tenantId()), q.sceneCode(), q.status(), q.eventId());
        List<EvalSessionEntry> items = mp.getRecords().stream()
                .map(r -> new EvalSessionEntry(
                        String.valueOf(r.getId()),
                        q.tenantId(),
                        r.getSceneCode(),
                        r.getEventId(),
                        r.getStatus(),
                        r.getFinalDecision(),
                        r.getEvalDurationMs(),
                        r.getStartedAt() != null
                                ? r.getStartedAt().toInstant(ZoneOffset.UTC) : null,
                        r.getFinishedAt() != null
                                ? r.getFinishedAt().toInstant(ZoneOffset.UTC) : null
                ))
                .toList();
        return new PageResult<>(items, mp.getTotal(), q.page(), q.size());
    }

    @Override
    public List<TraceNodeEntry> queryTrace(String tenantId, Long sessionId) {
        // 按 node_path 字典序返回，单次 session 通常 < 200 行，无需分页
        List<NodeTraceRow> rows = nodeTraceMapper.findBySessionAndTenant(
                sessionId, Long.valueOf(tenantId));
        return rows.stream()
                .map(r -> new TraceNodeEntry(
                        r.getNodePath(),
                        r.getNodeType(),
                        r.getConditionType(),
                        r.getMetricCode(),
                        r.getActualValue(),
                        r.getResult(),
                        r.getErrorCode(),
                        r.getValueSource(),
                        r.getRuleCode(),
                        r.getRuleVersion()
                ))
                .toList();
    }

    @Override
    public List<TraceTreeNode> queryTraceTree(String tenantId, Long sessionId) {
        List<TraceNodeEntry> flat = queryTrace(tenantId, sessionId);
        if (flat.isEmpty()) return List.of();

        // 按 node_path 深度（段数）升序，确保父节点先于子节点处理
        List<TraceNodeEntry> sorted = flat.stream()
                .sorted(java.util.Comparator.comparingInt(
                        e -> e.nodePath().split("\\.", -1).length))
                .toList();

        java.util.Map<String, TraceTreeNodeBuilder> byPath = new java.util.LinkedHashMap<>();
        List<String> roots = new java.util.ArrayList<>();

        for (TraceNodeEntry e : sorted) {
            TraceTreeNodeBuilder builder = new TraceTreeNodeBuilder(e);
            byPath.put(e.nodePath(), builder);
            String parent = parentPath(e.nodePath());
            if (parent == null) {
                roots.add(e.nodePath());
            } else {
                TraceTreeNodeBuilder parentBuilder = byPath.get(parent);
                if (parentBuilder != null) parentBuilder.children.add(builder);
            }
        }
        return roots.stream().map(r -> byPath.get(r).build()).toList();
    }

    @Override
    public PageResult<RuleSessionEntry> querySessionsByRuleDefinition(
            Long ruleDefinitionId, String status, int limit, int offset) {
        List<RuleSessionRow> rows = evalSessionMapper.selectByRuleDefinitionId(
                ruleDefinitionId, status, limit, offset);
        long total = evalSessionMapper.countByRuleDefinitionId(ruleDefinitionId, status);
        List<RuleSessionEntry> items = rows.stream()
                .map(r -> new RuleSessionEntry(
                        String.valueOf(r.getId()),
                        r.getEventId(),
                        r.getSubjectId(),
                        r.getStatus(),
                        r.getFinalDecision(),
                        r.getEvalDurationMs(),
                        r.getStartedAt() != null ? r.getStartedAt().toInstant(ZoneOffset.UTC) : null,
                        r.getRuleVersionId()))
                .toList();
        int page = limit > 0 ? offset / limit : 0;
        return new PageResult<>(items, total, page, limit);
    }

    @Override
    public String getSessionSceneCode(String tenantId, Long sessionId) {
        return evalSessionMapper.findSceneCode(sessionId, Long.valueOf(tenantId));
    }

    @Override
    public EvalSessionEntry getSession(String tenantId, Long sessionId) {
        EvalSessionRow row = evalSessionMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<EvalSessionRow>()
                        .eq(EvalSessionRow::getId, sessionId)
                        .eq(EvalSessionRow::getTenantId, Long.valueOf(tenantId)));
        if (row == null) return null;
        return new EvalSessionEntry(
                String.valueOf(row.getId()),
                tenantId,
                row.getSceneCode(),
                row.getEventId(),
                row.getStatus(),
                row.getFinalDecision(),
                row.getEvalDurationMs(),
                row.getStartedAt() != null
                        ? row.getStartedAt().toInstant(ZoneOffset.UTC) : null,
                row.getFinishedAt() != null
                        ? row.getFinishedAt().toInstant(ZoneOffset.UTC) : null
        );
    }

    /** 返回点分路径的父路径；根节点（不含 "."）返回 null。 */
    private static String parentPath(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? null : path.substring(0, dot);
    }

    /** 可变节点构建器，用于深度优先树重建。 */
    private static final class TraceTreeNodeBuilder {
        final TraceNodeEntry entry;
        final List<TraceTreeNodeBuilder> children = new java.util.ArrayList<>();

        TraceTreeNodeBuilder(TraceNodeEntry e) { this.entry = e; }

        TraceTreeNode build() {
            return new TraceTreeNode(
                    entry.nodeType(), entry.conditionType(), entry.metricCode(),
                    entry.actualValue(), entry.result(), entry.errorCode(), entry.valueSource(),
                    entry.ruleCode(), entry.ruleVersion(),
                    children.stream().map(TraceTreeNodeBuilder::build).toList()
            );
        }
    }
}
