package com.sstlfsj.rule.audit.internal.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sstlfsj.rule.audit.api.service.AuditService;
import com.sstlfsj.rule.audit.internal.domain.AuditLogRow;
import com.sstlfsj.rule.audit.internal.domain.EvalSessionRow;
import com.sstlfsj.rule.audit.internal.domain.NodeTraceRow;
import com.sstlfsj.rule.audit.internal.repository.AuditLogReadMapper;
import com.sstlfsj.rule.audit.internal.repository.EvalSessionReadMapper;
import com.sstlfsj.rule.audit.internal.repository.NodeTraceReadMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditServiceImplTest {

    @Mock EvalSessionReadMapper evalSessionMapper;
    @Mock NodeTraceReadMapper nodeTraceMapper;
    @Mock AuditLogReadMapper auditLogMapper;

    private AuditServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AuditServiceImpl(evalSessionMapper, nodeTraceMapper, auditLogMapper);
    }

    @Test
    void queryEvalSessions_返回分页结果() {
        EvalSessionRow row = new EvalSessionRow();
        row.setId(1L);
        row.setTenantId(100L);
        row.setSceneCode("risk.transfer");
        row.setEventId("evt-001");
        row.setStatus("HIT");
        row.setStartedAt(LocalDateTime.of(2026, 6, 1, 10, 0));

        Page<EvalSessionRow> mp = new Page<>(1, 20);
        mp.setRecords(List.of(row));
        mp.setTotal(1L);
        when(evalSessionMapper.selectPage(any(), any())).thenReturn(mp);

        AuditService.PageResult<AuditService.EvalSessionEntry> result =
                service.queryEvalSessions("100", null, 0, 20);

        assertThat(result.total()).isEqualTo(1L);
        assertThat(result.items()).hasSize(1);
        AuditService.EvalSessionEntry entry = result.items().get(0);
        assertThat(entry.sessionId()).isEqualTo("1");
        assertThat(entry.sceneCode()).isEqualTo("risk.transfer");
        assertThat(entry.status()).isEqualTo("HIT");
    }

    @Test
    void queryEvalSessions_emptyResult_返回空列表() {
        Page<EvalSessionRow> mp = new Page<>(1, 20);
        mp.setRecords(List.of());
        mp.setTotal(0L);
        when(evalSessionMapper.selectPage(any(), any())).thenReturn(mp);

        AuditService.PageResult<AuditService.EvalSessionEntry> result =
                service.queryEvalSessions("100", "evt-xyz", 0, 20);

        assertThat(result.total()).isEqualTo(0L);
        assertThat(result.items()).isEmpty();
    }

    @Test
    void queryAuditLogs_返回分页结果() {
        AuditLogRow row = new AuditLogRow();
        row.setId(10L);
        row.setTenantId(100L);
        row.setTargetType("rule_definition");
        row.setTargetId("42");
        row.setAction("CREATE");
        row.setActor("user-1");
        row.setActorType("USER");
        row.setAfterSnapshot("{\"code\":\"rule-a\"}");
        row.setOperatedAt(LocalDateTime.of(2026, 6, 1, 9, 0));

        Page<AuditLogRow> mp = new Page<>(1, 20);
        mp.setRecords(List.of(row));
        mp.setTotal(1L);
        when(auditLogMapper.selectPage(any(), any())).thenReturn(mp);

        AuditService.PageResult<AuditService.AuditLogEntry> result =
                service.queryAuditLogs("100", "rule_definition", null, 0, 20);

        assertThat(result.total()).isEqualTo(1L);
        AuditService.AuditLogEntry entry = result.items().get(0);
        assertThat(entry.id()).isEqualTo(10L);
        assertThat(entry.resourceType()).isEqualTo("rule_definition");
        assertThat(entry.resourceId()).isEqualTo(42L);
        assertThat(entry.action()).isEqualTo("CREATE");
        assertThat(entry.actorId()).isEqualTo("user-1");
        assertThat(entry.occurredAt()).isEqualTo(java.time.Instant.parse("2026-06-01T09:00:00Z"));
    }

    @Test
    void queryTrace_返回节点列表() {
        NodeTraceRow row = new NodeTraceRow();
        row.setEvaluationSessionId(1L);
        row.setTenantId(100L);
        row.setNodePath("0");
        row.setNodeType("AND");
        row.setResult(true);

        when(nodeTraceMapper.selectList(any())).thenReturn(List.of(row));

        List<AuditService.TraceNodeEntry> result = service.queryTrace("100", 1L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).nodePath()).isEqualTo("0");
        assertThat(result.get(0).nodeType()).isEqualTo("AND");
        assertThat(result.get(0).result()).isTrue();
    }

    @Test
    void queryTrace_noRows_返回空列表() {
        when(nodeTraceMapper.selectList(any())).thenReturn(List.of());

        List<AuditService.TraceNodeEntry> result = service.queryTrace("100", 999L);

        assertThat(result).isEmpty();
    }

    @Test
    void queryTraceTree_单层根节点() {
        NodeTraceRow root = new NodeTraceRow();
        root.setEvaluationSessionId(1L);
        root.setTenantId(100L);
        root.setNodePath("0");
        root.setNodeType("AndNode");
        root.setResult(true);

        when(nodeTraceMapper.selectList(any())).thenReturn(List.of(root));

        List<AuditService.TraceTreeNode> tree = service.queryTraceTree("100", 1L);

        assertThat(tree).hasSize(1);
        assertThat(tree.get(0).nodeType()).isEqualTo("AndNode");
        assertThat(tree.get(0).children()).isEmpty();
    }

    @Test
    void queryTraceTree_父子关系正确重建() {
        NodeTraceRow rootRow = new NodeTraceRow();
        rootRow.setEvaluationSessionId(1L);
        rootRow.setTenantId(100L);
        rootRow.setNodePath("0");
        rootRow.setNodeType("AndNode");
        rootRow.setResult(true);

        NodeTraceRow childRow = new NodeTraceRow();
        childRow.setEvaluationSessionId(1L);
        childRow.setTenantId(100L);
        childRow.setNodePath("0.0");
        childRow.setNodeType("ConditionNode");
        childRow.setConditionType("GT");
        childRow.setMetricCode("user.age");
        childRow.setResult(true);
        childRow.setActualValue("25");

        when(nodeTraceMapper.selectList(any())).thenReturn(List.of(rootRow, childRow));

        List<AuditService.TraceTreeNode> tree = service.queryTraceTree("100", 1L);

        assertThat(tree).hasSize(1);
        AuditService.TraceTreeNode root = tree.get(0);
        assertThat(root.children()).hasSize(1);
        AuditService.TraceTreeNode child = root.children().get(0);
        assertThat(child.nodeType()).isEqualTo("ConditionNode");
        assertThat(child.metricCode()).isEqualTo("user.age");
        assertThat(child.actualValue()).isEqualTo("25");
    }

    @Test
    void queryTraceTree_空rows返回空列表() {
        when(nodeTraceMapper.selectList(any())).thenReturn(List.of());

        assertThat(service.queryTraceTree("100", 999L)).isEmpty();
    }
}
