package com.sstlfsj.rule.audit;

import com.sstlfsj.rule.audit.api.service.AuditService;
import com.sstlfsj.rule.audit.api.service.AuditService.AuditLogEntry;
import com.sstlfsj.rule.audit.api.service.AuditService.EvalSessionEntry;
import com.sstlfsj.rule.audit.api.service.AuditService.PageResult;
import com.sstlfsj.rule.audit.api.service.AuditService.RuleSessionEntry;
import com.sstlfsj.rule.audit.api.service.AuditService.TraceNodeEntry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证 AuditService 接口的 record 语义及结构完整性。 */
class AuditServiceTest {

    @Test
    void auditLogEntry_字段赋值与读取正确() {
        Instant now = Instant.now();
        AuditLogEntry entry = new AuditLogEntry(
                1L, "t1", "SCENE", 42L,
                "CREATE", "u1", "USER",
                null, "{\"name\":\"s1\"}", now
        );

        assertThat(entry.id()).isEqualTo(1L);
        assertThat(entry.tenantId()).isEqualTo("t1");
        assertThat(entry.resourceType()).isEqualTo("SCENE");
        assertThat(entry.resourceId()).isEqualTo(42L);
        assertThat(entry.action()).isEqualTo("CREATE");
        assertThat(entry.actorId()).isEqualTo("u1");
        assertThat(entry.actorType()).isEqualTo("USER");
        assertThat(entry.beforeSnapshot()).isNull();
        assertThat(entry.afterSnapshot()).isEqualTo("{\"name\":\"s1\"}");
        assertThat(entry.occurredAt()).isEqualTo(now);
    }

    @Test
    void evalSessionEntry_字段赋值与读取正确() {
        Instant now = Instant.now();
        EvalSessionEntry entry = new EvalSessionEntry(
                "sess-001", "t1", "scene-A", "evt-001", "COMPLETED", "HIT", 42, now, now,
                "ORDER", "subj-1", "API", "STANDARD", null, null, 5, 3, 85.5, "HIGH", now, "{\"ast\":{}}"
        );

        assertThat(entry.sessionId()).isEqualTo("sess-001");
        assertThat(entry.tenantId()).isEqualTo("t1");
        assertThat(entry.sceneCode()).isEqualTo("scene-A");
        assertThat(entry.eventId()).isEqualTo("evt-001");
        assertThat(entry.status()).isEqualTo("COMPLETED");
        assertThat(entry.finalDecision()).isEqualTo("HIT");
        assertThat(entry.evalDurationMs()).isEqualTo(42);
        assertThat(entry.startedAt()).isEqualTo(now);
        assertThat(entry.finishedAt()).isEqualTo(now);
        assertThat(entry.eventType()).isEqualTo("ORDER");
        assertThat(entry.subjectId()).isEqualTo("subj-1");
        assertThat(entry.source()).isEqualTo("API");
        assertThat(entry.mode()).isEqualTo("STANDARD");
        assertThat(entry.blockedBy()).isNull();
        assertThat(entry.errorCode()).isNull();
        assertThat(entry.candidateRuleCount()).isEqualTo(5);
        assertThat(entry.hitRuleCount()).isEqualTo(3);
        assertThat(entry.score()).isEqualTo(85.5);
        assertThat(entry.category()).isEqualTo("HIGH");
        assertThat(entry.occurredAt()).isEqualTo(now);
        assertThat(entry.contextSnapshot()).isEqualTo("{\"ast\":{}}");
    }

    @Test
    void pageResult_字段赋值与读取正确() {
        List<String> items = List.of("a", "b");
        PageResult<String> result = new PageResult<>(items, 100L, 0, 10);

        assertThat(result.items()).containsExactly("a", "b");
        assertThat(result.total()).isEqualTo(100L);
        assertThat(result.page()).isEqualTo(0);
        assertThat(result.size()).isEqualTo(10);
    }

    @Test
    void auditLogEntry_resourceId允许为null() {
        AuditLogEntry entry = new AuditLogEntry(
                2L, "t1", "RULE_DEFINITION", null,
                "DELETE", "u2", "SYSTEM",
                "{}", null, Instant.now()
        );
        assertThat(entry.resourceId()).isNull();
    }

    @Test
    void traceNodeEntry_字段赋值与读取正确() {
        TraceNodeEntry entry = new TraceNodeEntry(
                "0.1", "CONDITION", "GT", "amount",
                "500.00", true, null, "EVENT", "RC-1", 3L
        );

        assertThat(entry.nodePath()).isEqualTo("0.1");
        assertThat(entry.nodeType()).isEqualTo("CONDITION");
        assertThat(entry.conditionType()).isEqualTo("GT");
        assertThat(entry.metricCode()).isEqualTo("amount");
        assertThat(entry.actualValue()).isEqualTo("500.00");
        assertThat(entry.result()).isTrue();
        assertThat(entry.errorCode()).isNull();
        assertThat(entry.valueSource()).isEqualTo("EVENT");
        // 规则身份冗余键随 trace 行读出
        assertThat(entry.ruleCode()).isEqualTo("RC-1");
        assertThat(entry.ruleVersion()).isEqualTo(3L);
    }

    @Test
    void traceNodeEntry_result和errorCode允许为null() {
        TraceNodeEntry entry = new TraceNodeEntry(
                "0", "AND", null, null,
                null, null, "EVAL_ERROR", null, null, null
        );

        assertThat(entry.result()).isNull();
        assertThat(entry.errorCode()).isEqualTo("EVAL_ERROR");
        assertThat(entry.conditionType()).isNull();
    }

    @Test
    void traceNodeEntry_nodePath字典序语义_多位节点编号() {
        // "0.1.10" 字典序排在 "0.1.2" 之前，验证 record 存储格式与排序说明一致
        TraceNodeEntry a = new TraceNodeEntry("0.1.10", "CONDITION", null, null, null, null, null, null, null, null);
        TraceNodeEntry b = new TraceNodeEntry("0.1.2", "CONDITION", null, null, null, null, null, null, null, null);

        assertThat(a.nodePath().compareTo(b.nodePath())).isLessThan(0);
    }

    @Test
    void ruleSessionEntry_字段赋值与读取正确() {
        Instant now = Instant.now();
        RuleSessionEntry entry = new RuleSessionEntry(
                "sess-100", "evt-abc", "u1",
                "HIT", "REJECT", 35, now, 7L
        );

        assertThat(entry.sessionId()).isEqualTo("sess-100");
        assertThat(entry.eventId()).isEqualTo("evt-abc");
        assertThat(entry.subjectId()).isEqualTo("u1");
        assertThat(entry.status()).isEqualTo("HIT");
        assertThat(entry.finalDecision()).isEqualTo("REJECT");
        assertThat(entry.evalDurationMs()).isEqualTo(35);
        assertThat(entry.startedAt()).isEqualTo(now);
        assertThat(entry.ruleVersionId()).isEqualTo(7L);
    }

    @Test
    void ruleSessionEntry_可选字段允许为null() {
        RuleSessionEntry entry = new RuleSessionEntry(
                "sess-200", "evt-xyz", null,
                "MISS", null, null, null, null
        );

        assertThat(entry.subjectId()).isNull();
        assertThat(entry.finalDecision()).isNull();
        assertThat(entry.evalDurationMs()).isNull();
        assertThat(entry.startedAt()).isNull();
        assertThat(entry.ruleVersionId()).isNull();
    }
}
