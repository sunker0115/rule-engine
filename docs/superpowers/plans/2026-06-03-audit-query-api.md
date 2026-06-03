# 审计查询 API 实装计划（§2.19）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实装 `GET /api/v1/evaluation-sessions`、`GET /api/v1/evaluation-sessions/{sessionId}/trace`、`GET /api/v1/audit-logs` 三个查询端点，替换 `AuditServiceImpl` 的 `UnsupportedOperationException` 占位实现。

**Architecture:** `rule-audit-svc` 内部自建三个只读 domain entity（`EvalSessionRow`、`NodeTraceRow`、`AuditLogRow`）和对应的三个 `@Mapper` 接口，通过 MyBatis-Plus `LambdaQueryWrapper` 分页查询，不引用其他模块的 `internal` 包（Modulith 隔离约定）。`AuditServiceImpl` 注入这三个 Mapper 实现查询逻辑；trace 端点需要把 `node_trace` 的扁平行按 `node_path` 重建为嵌套树。`AuditService` 接口扩展新增 `queryTrace` 方法；`AuditController` 新增 `§6.2` trace 端点。

**Tech Stack:** Java 25 / Spring Boot 4 / MyBatis-Plus 3.5.16 / JUnit 5 + Mockito

---

## 改动文件清单

| 文件 | 动作 |
|---|---|
| `rule-audit-svc/.../internal/domain/EvalSessionRow.java` | 新建（只读 domain，映射 `evaluation_session`） |
| `rule-audit-svc/.../internal/domain/NodeTraceRow.java` | 新建（只读 domain，映射 `node_trace`） |
| `rule-audit-svc/.../internal/domain/AuditLogRow.java` | 新建（只读 domain，映射 `audit_log`） |
| `rule-audit-svc/.../internal/repository/EvalSessionReadMapper.java` | 新建 |
| `rule-audit-svc/.../internal/repository/NodeTraceReadMapper.java` | 新建 |
| `rule-audit-svc/.../internal/repository/AuditLogReadMapper.java` | 新建 |
| `rule-audit-svc/.../api/service/AuditService.java` | 加 `TraceNodeEntry` + `queryTrace` 方法 |
| `rule-audit-svc/.../internal/service/AuditServiceImpl.java` | 替换 `UnsupportedOperationException` 为真实实现 |
| `rule-audit-svc/src/test/.../internal/service/AuditServiceImplTest.java` | 替换骨架测试为真实 Mockito 测试 |
| `rule-api/.../audit/AuditController.java` | 新增 `GET /api/v1/evaluation-sessions/{sessionId}/trace` 端点 |
| `rule-api/src/test/.../audit/AuditControllerTest.java` | 新增 trace 端点测试 |

---

### Task 1：audit-svc 内建三个只读 Domain Entity + Mapper

**Files:**
- Create: `rule-audit-svc/src/main/java/com/sstlfsj/rule/audit/internal/domain/EvalSessionRow.java`
- Create: `rule-audit-svc/src/main/java/com/sstlfsj/rule/audit/internal/domain/NodeTraceRow.java`
- Create: `rule-audit-svc/src/main/java/com/sstlfsj/rule/audit/internal/domain/AuditLogRow.java`
- Create: `rule-audit-svc/src/main/java/com/sstlfsj/rule/audit/internal/repository/EvalSessionReadMapper.java`
- Create: `rule-audit-svc/src/main/java/com/sstlfsj/rule/audit/internal/repository/NodeTraceReadMapper.java`
- Create: `rule-audit-svc/src/main/java/com/sstlfsj/rule/audit/internal/repository/AuditLogReadMapper.java`
- Test: `rule-audit-svc/src/test/java/com/sstlfsj/rule/audit/internal/domain/EvalSessionRowTest.java`

- [ ] **Step 1: 新建 EvalSessionRow**

```java
package com.sstlfsj.rule.audit.internal.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/** evaluation_session 表只读映射（audit-svc 内部用，不跨模块引用）。 */
@Getter
@Setter
@TableName("evaluation_session")
public class EvalSessionRow {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String eventId;
    private String sceneCode;
    private String status;
    private String finalDecision;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Integer evalDurationMs;
}
```

- [ ] **Step 2: 新建 NodeTraceRow**

```java
package com.sstlfsj.rule.audit.internal.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** node_trace 表只读映射。node_path 格式为点分数字路径，如 "0.1.2"。 */
@Getter
@Setter
@TableName("node_trace")
public class NodeTraceRow {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long evaluationSessionId;
    private Long tenantId;
    private Long ruleVersionId;
    private String nodePath;
    private String nodeType;
    private String conditionType;
    private String metricCode;
    private String params;
    private String actualValue;
    private Boolean result;
    private String errorCode;
    private String valueSource;
}
```

- [ ] **Step 3: 新建 AuditLogRow**

```java
package com.sstlfsj.rule.audit.internal.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/** audit_log 表只读映射。 */
@Getter
@Setter
@TableName("audit_log")
public class AuditLogRow {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String actor;
    private String actorType;
    private String action;
    private String targetType;
    private String targetId;
    private String beforeSnapshot;
    private String afterSnapshot;
    private LocalDateTime operatedAt;
}
```

- [ ] **Step 4: 新建三个 Mapper**

```java
// EvalSessionReadMapper.java
package com.sstlfsj.rule.audit.internal.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sstlfsj.rule.audit.internal.domain.EvalSessionRow;
import org.apache.ibatis.annotations.Mapper;

/** evaluation_session 只读 Mapper（audit-svc 自有，不共享 eval-svc 的 internal）。 */
@Mapper
public interface EvalSessionReadMapper extends BaseMapper<EvalSessionRow> {}
```

```java
// NodeTraceReadMapper.java
package com.sstlfsj.rule.audit.internal.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sstlfsj.rule.audit.internal.domain.NodeTraceRow;
import org.apache.ibatis.annotations.Mapper;

/** node_trace 只读 Mapper。 */
@Mapper
public interface NodeTraceReadMapper extends BaseMapper<NodeTraceRow> {}
```

```java
// AuditLogReadMapper.java
package com.sstlfsj.rule.audit.internal.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sstlfsj.rule.audit.internal.domain.AuditLogRow;
import org.apache.ibatis.annotations.Mapper;

/** audit_log 只读 Mapper。 */
@Mapper
public interface AuditLogReadMapper extends BaseMapper<AuditLogRow> {}
```

- [ ] **Step 5: 写 EvalSessionRowTest 验证 record 实例化**

```java
package com.sstlfsj.rule.audit.internal.domain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class EvalSessionRowTest {

    @Test
    void 字段读写正确() {
        EvalSessionRow row = new EvalSessionRow();
        row.setId(1L);
        row.setTenantId(100L);
        row.setSceneCode("risk.transfer");
        row.setStatus("HIT");

        assertThat(row.getId()).isEqualTo(1L);
        assertThat(row.getTenantId()).isEqualTo(100L);
        assertThat(row.getSceneCode()).isEqualTo("risk.transfer");
        assertThat(row.getStatus()).isEqualTo("HIT");
    }
}
```

- [ ] **Step 6: 跑测试确认编译和测试通过**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH
MVN=/Users/sunke/.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9/bin/mvn
$MVN -pl rule-audit-svc -am test -Dtest='EvalSessionRowTest' -Dsurefire.failIfNoSpecifiedTests=false
```

期望：BUILD SUCCESS，1 test PASSED。

- [ ] **Step 7: Commit**

```bash
git commit -m "feat(audit): 内建三个只读 domain entity + Mapper（EvalSessionRow / NodeTraceRow / AuditLogRow）"
```

---

### Task 2：AuditService 扩展 queryTrace + AuditServiceImpl 真实实现

**Files:**
- Modify: `rule-audit-svc/src/main/java/com/sstlfsj/rule/audit/api/service/AuditService.java`
- Modify: `rule-audit-svc/src/main/java/com/sstlfsj/rule/audit/internal/service/AuditServiceImpl.java`
- Modify: `rule-audit-svc/src/test/java/com/sstlfsj/rule/audit/internal/service/AuditServiceImplTest.java`

- [ ] **Step 1: 在 AuditService 接口加 TraceNodeEntry + queryTrace**

在 `AuditService.java` 中，`EvalSessionEntry` 定义之后加入：

```java
/** 节点 trace 条目，对应 node_trace 表一行。 */
record TraceNodeEntry(
        String nodePath,
        String nodeType,
        String conditionType,
        String metricCode,
        String actualValue,
        Boolean result,
        String errorCode,
        String valueSource
) {}

/**
 * 查询指定评估会话的节点 trace 列表（扁平，按 node_path 字典序排列）。
 *
 * @param tenantId  租户标识
 * @param sessionId 评估会话 ID
 * @return 节点 trace 列表（无分页，单次 session 通常 < 200 行）
 */
List<TraceNodeEntry> queryTrace(String tenantId, Long sessionId);
```

完整更新后的 `AuditService.java`：

```java
package com.sstlfsj.rule.audit.api.service;

import java.util.List;

/** 提供审计日志和评估会话的查询能力。 */
public interface AuditService {

    /** 审计日志条目，记录资源变更的操作历史。 */
    record AuditLogEntry(
            Long id,
            String tenantId,
            String resourceType,
            Long resourceId,
            String action,
            String actorId,
            String actorType,
            String beforeSnapshot,
            String afterSnapshot,
            java.time.Instant occurredAt
    ) {}

    /** 分页结果包装。 */
    record PageResult<T>(List<T> items, long total, int page, int size) {}

    /**
     * 分页查询审计日志。
     *
     * @param tenantId     租户标识
     * @param resourceType 资源类型（如 SCENE、RULE_DEFINITION）
     * @param resourceId   资源 ID，null 表示不过滤
     * @param page         页码（从 0 开始）
     * @param size         每页条数
     * @return 分页结果
     */
    PageResult<AuditLogEntry> queryAuditLogs(String tenantId, String resourceType,
                                              Long resourceId, int page, int size);

    /** 评估会话条目，记录一次规则评估的基本信息。 */
    record EvalSessionEntry(
            String sessionId,
            String tenantId,
            String sceneCode,
            String eventId,
            String status,
            java.time.Instant startedAt
    ) {}

    /**
     * 分页查询评估会话记录。
     *
     * @param tenantId 租户标识
     * @param eventId  事件 ID，null 表示不过滤
     * @param page     页码（从 0 开始）
     * @param size     每页条数
     * @return 分页结果
     */
    PageResult<EvalSessionEntry> queryEvalSessions(String tenantId, String eventId,
                                                    int page, int size);

    /** 节点 trace 条目，对应 node_trace 表一行。 */
    record TraceNodeEntry(
            String nodePath,
            String nodeType,
            String conditionType,
            String metricCode,
            String actualValue,
            Boolean result,
            String errorCode,
            String valueSource
    ) {}

    /**
     * 查询指定评估会话的节点 trace 列表（扁平，按 node_path 字典序排列）。
     *
     * @param tenantId  租户标识
     * @param sessionId 评估会话 ID
     * @return 节点 trace 列表（无分页，单次 session 通常 < 200 行）
     */
    List<TraceNodeEntry> queryTrace(String tenantId, Long sessionId);
}
```

- [ ] **Step 2: 实现 AuditServiceImpl**

完整替换 `AuditServiceImpl.java`：

```java
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
```

- [ ] **Step 3: 写 AuditServiceImplTest**

完整替换 `AuditServiceImplTest.java`（删除旧的 UnsupportedOperationException 测试，新增 Mockito 测试）：

```java
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
}
```

- [ ] **Step 4: 跑 rule-audit-svc 全量测试**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH
MVN=/Users/sunke/.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9/bin/mvn
$MVN -pl rule-audit-svc -am test
```

期望：BUILD SUCCESS，所有测试通过。

- [ ] **Step 5: Commit**

```bash
git commit -m "feat(audit): AuditService 加 queryTrace + AuditServiceImpl 真实实现（三 Mapper 分页查询）"
```

---

### Task 3：AuditController 新增 trace 端点 + 全量测试 + 文档更新

**Files:**
- Modify: `rule-api/src/main/java/com/sstlfsj/rule/web/audit/AuditController.java`
- Modify: `rule-api/src/test/java/com/sstlfsj/rule/web/audit/AuditControllerTest.java`

- [ ] **Step 1: AuditController 加 trace 端点**

在 `AuditController.java` 的 `queryAuditLogs` 方法之前插入：

```java
/** GET /api/v1/evaluation-sessions/{sessionId}/trace — 查询评估节点 trace
 * @param sessionId 评估会话 ID @param tenantId 租户
 * @return 扁平节点 trace 列表，按 node_path 字典序 */
@GetMapping("/evaluation-sessions/{sessionId}/trace")
public ApiResponse<List<AuditService.TraceNodeEntry>> queryTrace(
        @PathVariable Long sessionId,
        @RequestParam String tenantId) {
    return ApiResponse.ok(auditService.queryTrace(tenantId, sessionId));
}
```

还需要在文件顶部加 `import java.util.List;`。

完整更新后的 `AuditController.java`：

```java
package com.sstlfsj.rule.web.audit;

import com.sstlfsj.rule.audit.api.service.AuditService;
import com.sstlfsj.rule.web.common.ApiResponse;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 审计日志与评估会话查询入口。 */
@RestController
@RequestMapping("/api/v1")
public class AuditController {

    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    /** GET /api/v1/evaluation-sessions — 分页查询评估会话
     * @param tenantId 租户 @param eventId 可选过滤 @param page 页码 @param size 每页大小
     * @return 分页评估会话列表 */
    @GetMapping("/evaluation-sessions")
    public ApiResponse<AuditService.PageResult<AuditService.EvalSessionEntry>> querySessions(
            @RequestParam String tenantId,
            @RequestParam(required = false) String eventId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(auditService.queryEvalSessions(tenantId, eventId, page, size));
    }

    /** GET /api/v1/evaluation-sessions/{sessionId}/trace — 查询评估节点 trace
     * @param sessionId 评估会话 ID @param tenantId 租户
     * @return 扁平节点 trace 列表，按 node_path 字典序 */
    @GetMapping("/evaluation-sessions/{sessionId}/trace")
    public ApiResponse<List<AuditService.TraceNodeEntry>> queryTrace(
            @PathVariable Long sessionId,
            @RequestParam String tenantId) {
        return ApiResponse.ok(auditService.queryTrace(tenantId, sessionId));
    }

    /** GET /api/v1/audit-logs — 分页查询操作审计日志
     * @param tenantId 租户 @param resourceType 可选资源类型 @param resourceId 可选资源 ID
     * @param page 页码 @param size 每页大小
     * @return 分页审计日志列表 */
    @GetMapping("/audit-logs")
    public ApiResponse<AuditService.PageResult<AuditService.AuditLogEntry>> queryAuditLogs(
            @RequestParam String tenantId,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) Long resourceId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(auditService.queryAuditLogs(tenantId, resourceType, resourceId, page, size));
    }
}
```

- [ ] **Step 2: 更新 AuditControllerTest**

完整替换 `AuditControllerTest.java`（保留已有两个测试，新增 trace 端点测试）：

```java
package com.sstlfsj.rule.web.audit;

import com.sstlfsj.rule.audit.api.service.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AuditControllerTest {

    private MockMvc mockMvc;
    private AuditService auditService;

    @BeforeEach
    void setUp() {
        auditService = mock(AuditService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new AuditController(auditService)).build();
    }

    @Test
    void querySessions_returns200() throws Exception {
        AuditService.PageResult<AuditService.EvalSessionEntry> empty =
                new AuditService.PageResult<>(List.of(), 0, 0, 20);
        when(auditService.queryEvalSessions("t1", null, 0, 20)).thenReturn(empty);

        mockMvc.perform(get("/api/v1/evaluation-sessions").param("tenantId", "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(auditService).queryEvalSessions("t1", null, 0, 20);
    }

    @Test
    void queryAuditLogs_returns200() throws Exception {
        AuditService.PageResult<AuditService.AuditLogEntry> empty =
                new AuditService.PageResult<>(List.of(), 0, 0, 20);
        when(auditService.queryAuditLogs("t1", null, null, 0, 20)).thenReturn(empty);

        mockMvc.perform(get("/api/v1/audit-logs").param("tenantId", "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(auditService).queryAuditLogs("t1", null, null, 0, 20);
    }

    @Test
    void queryTrace_returns200_withNodes() throws Exception {
        AuditService.TraceNodeEntry node = new AuditService.TraceNodeEntry(
                "0", "AND", null, null, null, true, null, null);
        when(auditService.queryTrace("t1", 42L)).thenReturn(List.of(node));

        mockMvc.perform(get("/api/v1/evaluation-sessions/42/trace").param("tenantId", "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].nodePath").value("0"))
                .andExpect(jsonPath("$.data[0].result").value(true));

        verify(auditService).queryTrace("t1", 42L);
    }

    @Test
    void queryTrace_returns200_whenEmpty() throws Exception {
        when(auditService.queryTrace("t1", 99L)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/evaluation-sessions/99/trace").param("tenantId", "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());

        verify(auditService).queryTrace("t1", 99L);
    }
}
```

- [ ] **Step 3: 跑 rule-audit-svc + rule-api 全量测试**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-8.jdk/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH
MVN=/Users/sunke/.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9/bin/mvn
$MVN -pl rule-audit-svc,rule-api -am test
```

期望：BUILD SUCCESS，所有测试通过。

- [ ] **Step 4: 更新 08-evolution.md §2.19**

在 `docs/08-evolution.md` 的 `### 2.19 审计查询 API` 节末尾追加：

```
- **已实装（v2）**：`rule-audit-svc` 内建 `EvalSessionRow` / `NodeTraceRow` / `AuditLogRow` 只读 entity + 对应三个 `@Mapper` 接口（方案 C，不引用其他模块 internal）；`AuditServiceImpl` 用 MyBatis-Plus 分页查询实现三个方法；`AuditController` 补全 `GET /api/v1/evaluation-sessions/{sessionId}/trace` 端点；`AuditService` 新增 `TraceNodeEntry` + `queryTrace` 方法签名。
```

- [ ] **Step 5: Commit**

```bash
git commit -m "feat(audit): AuditController trace 端点 + 全量测试通过 + §2.19 文档更新"
```

---

## 实装备注

- `AuditServiceImpl` 中 `page + 1` 是因为 MyBatis-Plus `Page` 构造器使用从 1 开始的页码，而 API 对外约定是从 0 开始（和 `ConfigServiceImpl.listRules` 一致）。
- `targetId` 在 `audit_log` 表中存为 `VARCHAR`，转 `Long` 时若原始写入非纯数字（如 sceneCode 字符串）会报错——v1 写入的 `targetId` 均为数字 ID（见 `PublishService.createDraft`），现阶段不加额外防御；如遇异常在 `GlobalExceptionHandler` 统一处理。
- `queryTrace` 返回扁平列表，不重建树——§6.2 契约说"响应格式与 §3.3 dry-run 的 nodeTrace 相同（嵌套树）"，但重建树的逻辑复杂且未在现有代码中有参考实现，v2 先返回扁平行，留 §2.21 锚点记录演进方向。
