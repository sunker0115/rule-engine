# 评估链路补全实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 补全 v1 评估链路的两个遗漏：dry-run NodeTrace 隔离写入 `dry_run_node_trace` 表；规则命中后 ActionHandler 调用并落库 `action_execution`。

**Architecture:** 新增 `DryRunTraceWriter` SPI（与 `TraceWriter` 并列），`EvalServiceImpl` 按 `isDryRun` 路由到不同 writer；规则命中后同步调用 `ActionDispatchService`，按 `scene_action_binding` 查找 handler 执行并 INSERT `action_execution`。

**Tech Stack:** Java 25 / Spring Boot 4 / MyBatis-Plus / rule-kernel SPI / rule-observability / rule-eval-svc

**Maven 环境（每次运行测试前设置）：**
```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-25.jdk/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH
MVN=/Users/sunke/.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9/bin/mvn
```

---

## 文件变更清单

| 操作 | 文件 |
|------|------|
| 新建 | `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/spi/trace/DryRunTraceWriter.java` |
| 新建 | `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/spi/trace/NoopDryRunTraceWriter.java` |
| 新建（测试）| `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/api/spi/trace/DryRunTraceWriterTest.java` |
| 新建 | `rule-observability/src/main/java/com/sstlfsj/rule/observability/internal/domain/DryRunNodeTraceEntity.java` |
| 新建 | `rule-observability/src/main/java/com/sstlfsj/rule/observability/internal/repository/DryRunNodeTraceMapper.java` |
| 新建（测试）| `rule-observability/src/test/java/com/sstlfsj/rule/observability/internal/repository/DryRunNodeTraceMapperTest.java` |
| 新建 | `rule-observability/src/main/java/com/sstlfsj/rule/observability/internal/trace/DryRunTraceWriterDbImpl.java` |
| 新建（测试）| `rule-observability/src/test/java/com/sstlfsj/rule/observability/internal/trace/DryRunTraceWriterDbImplTest.java` |
| 修改 | `rule-observability/src/main/java/com/sstlfsj/rule/observability/ObservabilityAutoConfiguration.java` |
| 修改（测试）| `rule-observability/src/test/java/com/sstlfsj/rule/observability/ObservabilityAutoConfigurationTest.java` |
| 修改 | `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/service/EvalServiceImpl.java` |
| 修改（测试）| `rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/service/EvalServiceImplTest.java` |
| 新建 | `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/domain/ActionExecutionEntity.java` |
| 新建 | `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/domain/SceneActionBindingRow.java` |
| 新建 | `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/repository/ActionExecutionMapper.java` |
| 新建 | `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/repository/SceneActionBindingReadMapper.java` |
| 新建（测试）| `rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/repository/ActionExecutionMapperTest.java` |
| 新建（测试）| `rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/repository/SceneActionBindingReadMapperTest.java` |
| 新建 | `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/action/ActionDispatchService.java` |
| 新建（测试）| `rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/action/ActionDispatchServiceTest.java` |
| 新建 | `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/action/BlockTransactionHandler.java` |
| 新建 | `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/action/SendAlertHandler.java` |
| 修改 | `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/EvalAutoConfiguration.java` |

---

## Task 1: DryRunTraceWriter SPI + NoopDryRunTraceWriter（rule-kernel）

**Files:**
- Create: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/spi/trace/DryRunTraceWriter.java`
- Create: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/spi/trace/NoopDryRunTraceWriter.java`
- Create test: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/api/spi/trace/DryRunTraceWriterTest.java`

- [ ] **Step 1: 写失败测试**

```java
// rule-kernel/src/test/java/com/sstlfsj/rule/kernel/api/spi/trace/DryRunTraceWriterTest.java
package com.sstlfsj.rule.kernel.api.spi.trace;

import com.sstlfsj.rule.kernel.api.model.NodeTrace;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DryRunTraceWriterTest {

    @Test
    void write_receivesCorrectArguments() {
        List<String> capturedTenant = new ArrayList<>();
        List<String> capturedSession = new ArrayList<>();

        DryRunTraceWriter writer = (tenantId, sessionId, traces) -> {
            capturedTenant.add(tenantId);
            capturedSession.add(sessionId);
        };

        NodeTrace trace = new NodeTrace("CONDITION", "AMOUNT_GT", null,
                true, 100, "PROVIDED", null, null, null);
        writer.write("t1", "sess-001", List.of(trace));

        assertEquals("t1", capturedTenant.get(0));
        assertEquals("sess-001", capturedSession.get(0));
    }

    @Test
    void write_isFunctionalInterface() {
        DryRunTraceWriter writer = (tenantId, sessionId, traces) -> {};
        assertDoesNotThrow(() -> writer.write("t1", "s1", List.of()));
    }

    @Test
    void noopDryRunTraceWriter_doesNotThrow() {
        NoopDryRunTraceWriter writer = new NoopDryRunTraceWriter();
        assertInstanceOf(DryRunTraceWriter.class, writer);
        NodeTrace trace = new NodeTrace("LEAF", "AMOUNT_GT", "revenue", true, 100, "DB", null, null, null);
        assertDoesNotThrow(() -> writer.write("t1", "s1", List.of(trace)));
        assertDoesNotThrow(() -> writer.write(null, null, List.of()));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-25.jdk/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH
MVN=/Users/sunke/.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9/bin/mvn
$MVN -pl rule-kernel -am test -Dtest='DryRunTraceWriterTest' -Dsurefire.failIfNoSpecifiedTests=false
```

期望：FAIL — `DryRunTraceWriter` 类不存在

- [ ] **Step 3: 实现 SPI 接口**

```java
// rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/spi/trace/DryRunTraceWriter.java
package com.sstlfsj.rule.kernel.api.spi.trace;

import com.sstlfsj.rule.kernel.api.model.NodeTrace;
import java.util.List;

/** dry-run 评估链路追踪数据持久化 SPI，写 dry_run_node_trace 表，与主服务 TraceWriter 隔离。 */
public interface DryRunTraceWriter {
    /**
     * 异步持久化 dry-run 节点追踪列表。
     *
     * @param tenantId  租户 ID
     * @param sessionId dry-run 会话 ID（字符串形式）
     * @param traces    本次评估收集的 NodeTrace 树根节点列表
     */
    void write(String tenantId, String sessionId, List<NodeTrace> traces);
}
```

```java
// rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/spi/trace/NoopDryRunTraceWriter.java
package com.sstlfsj.rule.kernel.api.spi.trace;

import com.sstlfsj.rule.kernel.api.model.NodeTrace;
import java.util.List;

/** DryRunTraceWriter 空实现，用于测试和禁用场景。 */
public class NoopDryRunTraceWriter implements DryRunTraceWriter {
    @Override
    public void write(String tenantId, String sessionId, List<NodeTrace> traces) {}
}
```

- [ ] **Step 4: 运行测试确认通过**

```bash
$MVN -pl rule-kernel -am test -Dtest='DryRunTraceWriterTest' -Dsurefire.failIfNoSpecifiedTests=false
```

期望：BUILD SUCCESS，3 tests passed

- [ ] **Step 5: 运行 rule-kernel 全量测试**

```bash
$MVN -pl rule-kernel -am test
```

期望：BUILD SUCCESS，全部通过

- [ ] **Step 6: 提交**

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/spi/trace/DryRunTraceWriter.java \
        rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/spi/trace/NoopDryRunTraceWriter.java \
        rule-kernel/src/test/java/com/sstlfsj/rule/kernel/api/spi/trace/DryRunTraceWriterTest.java
git commit -m "feat(kernel): 新增 DryRunTraceWriter SPI 及 Noop 实现"
```

---

## Task 2: DryRunNodeTraceEntity + DryRunNodeTraceMapper（rule-observability）

**Files:**
- Create: `rule-observability/src/main/java/com/sstlfsj/rule/observability/internal/domain/DryRunNodeTraceEntity.java`
- Create: `rule-observability/src/main/java/com/sstlfsj/rule/observability/internal/repository/DryRunNodeTraceMapper.java`
- Create test: `rule-observability/src/test/java/com/sstlfsj/rule/observability/internal/repository/DryRunNodeTraceMapperTest.java`

- [ ] **Step 1: 写失败测试**

```java
// rule-observability/src/test/java/com/sstlfsj/rule/observability/internal/repository/DryRunNodeTraceMapperTest.java
package com.sstlfsj.rule.observability.internal.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sstlfsj.rule.observability.internal.domain.DryRunNodeTraceEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DryRunNodeTraceMapperTest {

    @Test
    void mapperAnnotationPresent() {
        assertNotNull(DryRunNodeTraceMapper.class.getAnnotation(Mapper.class));
    }

    @Test
    void extendsBaseMapper() {
        boolean found = false;
        for (Class<?> iface : DryRunNodeTraceMapper.class.getInterfaces()) {
            if (iface.equals(BaseMapper.class)) {
                found = true;
                break;
            }
        }
        assertTrue(found, "DryRunNodeTraceMapper 须继承 BaseMapper");
    }

    @Test
    void genericTypeIsDryRunNodeTraceEntity() {
        java.lang.reflect.Type[] types = DryRunNodeTraceMapper.class.getGenericInterfaces();
        assertEquals(1, types.length);
        java.lang.reflect.ParameterizedType pt = (java.lang.reflect.ParameterizedType) types[0];
        assertEquals(DryRunNodeTraceEntity.class, pt.getActualTypeArguments()[0]);
    }

    @Test
    void insertBatch_methodExists_withInsertAnnotation() throws Exception {
        Method method = DryRunNodeTraceMapper.class.getDeclaredMethod("insertBatch", List.class);
        assertNotNull(method);
        assertNotNull(method.getAnnotation(Insert.class));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
$MVN -pl rule-observability -am test -Dtest='DryRunNodeTraceMapperTest' -Dsurefire.failIfNoSpecifiedTests=false
```

期望：FAIL — 类不存在

- [ ] **Step 3: 实现 DryRunNodeTraceEntity**

字段按 `dry_run_node_trace` DDL 映射，`dry_run_session_id` 对应主服务的 `evaluation_session_id`。

```java
// rule-observability/src/main/java/com/sstlfsj/rule/observability/internal/domain/DryRunNodeTraceEntity.java
package com.sstlfsj.rule.observability.internal.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** dry_run_node_trace 表实体（dry-run 评估链路隔离写库）。 */
@TableName("dry_run_node_trace")
public class DryRunNodeTraceEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long dryRunSessionId;
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
    private LocalDateTime evaluatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getDryRunSessionId() { return dryRunSessionId; }
    public void setDryRunSessionId(Long dryRunSessionId) { this.dryRunSessionId = dryRunSessionId; }

    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }

    public Long getRuleVersionId() { return ruleVersionId; }
    public void setRuleVersionId(Long ruleVersionId) { this.ruleVersionId = ruleVersionId; }

    public String getNodePath() { return nodePath; }
    public void setNodePath(String nodePath) { this.nodePath = nodePath; }

    public String getNodeType() { return nodeType; }
    public void setNodeType(String nodeType) { this.nodeType = nodeType; }

    public String getConditionType() { return conditionType; }
    public void setConditionType(String conditionType) { this.conditionType = conditionType; }

    public String getMetricCode() { return metricCode; }
    public void setMetricCode(String metricCode) { this.metricCode = metricCode; }

    public String getParams() { return params; }
    public void setParams(String params) { this.params = params; }

    public String getActualValue() { return actualValue; }
    public void setActualValue(String actualValue) { this.actualValue = actualValue; }

    public Boolean getResult() { return result; }
    public void setResult(Boolean result) { this.result = result; }

    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }

    public String getValueSource() { return valueSource; }
    public void setValueSource(String valueSource) { this.valueSource = valueSource; }

    public LocalDateTime getEvaluatedAt() { return evaluatedAt; }
    public void setEvaluatedAt(LocalDateTime evaluatedAt) { this.evaluatedAt = evaluatedAt; }
}
```

- [ ] **Step 4: 实现 DryRunNodeTraceMapper**

SQL 结构与 `NodeTraceMapper.insertBatch` 一致，目标表改为 `dry_run_node_trace`，`evaluation_session_id` 改为 `dry_run_session_id`。

```java
// rule-observability/src/main/java/com/sstlfsj/rule/observability/internal/repository/DryRunNodeTraceMapper.java
package com.sstlfsj.rule.observability.internal.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sstlfsj.rule.observability.internal.domain.DryRunNodeTraceEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/** dry_run_node_trace 表 MyBatis-Plus Mapper（批量写，异步通道）。 */
@Mapper
public interface DryRunNodeTraceMapper extends BaseMapper<DryRunNodeTraceEntity> {

    /**
     * 批量插入 dry_run_node_trace 行，生成单条多值 INSERT 语句。
     * 列表为空时不执行（由调用方保证）。
     */
    @Insert("""
            <script>
            INSERT INTO dry_run_node_trace
              (dry_run_session_id, tenant_id, rule_version_id, node_path, node_type,
               condition_type, metric_code, actual_value, result,
               error_code, value_source, evaluated_at)
            VALUES
            <foreach collection="list" item="e" separator=",">
              (#{e.dryRunSessionId}, #{e.tenantId}, #{e.ruleVersionId}, #{e.nodePath}, #{e.nodeType},
               #{e.conditionType}, #{e.metricCode}, #{e.actualValue}, #{e.result},
               #{e.errorCode}, #{e.valueSource}, #{e.evaluatedAt})
            </foreach>
            </script>
            """)
    void insertBatch(List<DryRunNodeTraceEntity> list);
}
```

- [ ] **Step 5: 运行测试确认通过**

```bash
$MVN -pl rule-observability -am test -Dtest='DryRunNodeTraceMapperTest' -Dsurefire.failIfNoSpecifiedTests=false
```

期望：BUILD SUCCESS，4 tests passed

- [ ] **Step 6: 提交**

```bash
git add rule-observability/src/main/java/com/sstlfsj/rule/observability/internal/domain/DryRunNodeTraceEntity.java \
        rule-observability/src/main/java/com/sstlfsj/rule/observability/internal/repository/DryRunNodeTraceMapper.java \
        rule-observability/src/test/java/com/sstlfsj/rule/observability/internal/repository/DryRunNodeTraceMapperTest.java
git commit -m "feat(observability): DryRunNodeTraceEntity + DryRunNodeTraceMapper"
```

---

## Task 3: DryRunTraceWriterDbImpl（rule-observability）

**Files:**
- Create: `rule-observability/src/main/java/com/sstlfsj/rule/observability/internal/trace/DryRunTraceWriterDbImpl.java`
- Create test: `rule-observability/src/test/java/com/sstlfsj/rule/observability/internal/trace/DryRunTraceWriterDbImplTest.java`

- [ ] **Step 1: 写失败测试**

```java
// rule-observability/src/test/java/com/sstlfsj/rule/observability/internal/trace/DryRunTraceWriterDbImplTest.java
package com.sstlfsj.rule.observability.internal.trace;

import com.sstlfsj.rule.kernel.api.model.NodeTrace;
import com.sstlfsj.rule.kernel.api.spi.trace.DryRunTraceWriter;
import com.sstlfsj.rule.observability.internal.domain.DryRunNodeTraceEntity;
import com.sstlfsj.rule.observability.internal.repository.DryRunNodeTraceMapper;
import com.sstlfsj.rule.observability.internal.repository.NodeTraceMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DryRunTraceWriterDbImplTest {

    @Test
    void implementsDryRunTraceWriter() {
        DryRunTraceWriterDbImpl writer = new DryRunTraceWriterDbImpl(100, 10, 50,
                mock(DryRunNodeTraceMapper.class));
        assertInstanceOf(DryRunTraceWriter.class, writer);
    }

    @Test
    void write_throwsNpe_beforeInit() {
        DryRunTraceWriterDbImpl writer = new DryRunTraceWriterDbImpl(100, 10, 50,
                mock(DryRunNodeTraceMapper.class));
        NodeTrace trace = new NodeTrace("LEAF", "AMOUNT_GT", "revenue", true, 100, "DB", null, null, null);
        assertThrows(NullPointerException.class, () -> writer.write("t1", "s1", List.of(trace)));
    }

    @Test
    void afterPropertiesSet_startsConsumerThread() throws Exception {
        DryRunTraceWriterDbImpl writer = new DryRunTraceWriterDbImpl(100, 10, 50,
                mock(DryRunNodeTraceMapper.class));
        writer.afterPropertiesSet();
        try {
            NodeTrace trace = new NodeTrace("LEAF", "AMOUNT_GT", "revenue", true, 100, "DB", null, null, null);
            assertDoesNotThrow(() -> writer.write("t1", "s1", List.of(trace)));
        } finally {
            writer.destroy();
        }
    }

    @Test
    void write_dropsEntriesWhenQueueFull() throws Exception {
        DryRunTraceWriterDbImpl writer = new DryRunTraceWriterDbImpl(1, 10, 60_000,
                mock(DryRunNodeTraceMapper.class));
        writer.afterPropertiesSet();
        try {
            NodeTrace trace = new NodeTrace("LEAF", "AMOUNT_GT", "revenue", true, 100, "DB", null, null, null);
            assertDoesNotThrow(() -> {
                writer.write("t1", "s1", List.of(trace));
                writer.write("t1", "s2", List.of(trace));
            });
        } finally {
            writer.destroy();
        }
    }

    @Test
    void flushBatch_callsInsertBatch_notInsert() throws Exception {
        DryRunNodeTraceMapper mapper = mock(DryRunNodeTraceMapper.class);
        DryRunTraceWriterDbImpl w = new DryRunTraceWriterDbImpl(100, 10, 60_000, mapper);
        w.afterPropertiesSet();

        NodeTrace child = new NodeTrace("LEAF", "EQ", "score", false, 50, "DB", null, null, null);
        NodeTrace root  = new NodeTrace("CONDITION", "GT", "revenue", true, 100, "DB", null, List.of(child), 7L);
        w.write("1", "42", List.of(root));
        w.destroy();

        verify(mapper, atLeastOnce()).insertBatch(argThat(list -> list.size() == 2));
        verify(mapper, never()).insert(any(DryRunNodeTraceEntity.class));
    }

    @Test
    void flushBatch_setsDryRunSessionId_notEvaluationSessionId() throws Exception {
        DryRunNodeTraceMapper mapper = mock(DryRunNodeTraceMapper.class);
        DryRunTraceWriterDbImpl w = new DryRunTraceWriterDbImpl(100, 10, 60_000, mapper);
        w.afterPropertiesSet();

        NodeTrace root = new NodeTrace("CONDITION", "GT", "revenue", true, 100, "DB", null, null, 42L);
        w.write("1", "99", List.of(root));
        w.destroy();

        verify(mapper, atLeastOnce()).insertBatch(argThat(list ->
                list.size() == 1
                && Long.valueOf(99L).equals(list.get(0).getDryRunSessionId())
                && Long.valueOf(42L).equals(list.get(0).getRuleVersionId())));
    }

    @Test
    void flushBatch_nodePath_rootUsesIndex_childAppendsDot() throws Exception {
        DryRunNodeTraceMapper mapper = mock(DryRunNodeTraceMapper.class);
        DryRunTraceWriterDbImpl w = new DryRunTraceWriterDbImpl(100, 10, 60_000, mapper);
        w.afterPropertiesSet();

        NodeTrace child = new NodeTrace("LEAF", "EQ", "score", false, 50, "DB", null, null, null);
        NodeTrace root  = new NodeTrace("CONDITION", "GT", "revenue", true, 100, "DB", null, List.of(child), 7L);
        w.write("1", "42", List.of(root));
        w.destroy();

        verify(mapper, atLeastOnce()).insertBatch(argThat(list -> {
            if (list.size() != 2) return false;
            return "0".equals(list.get(0).getNodePath())
                    && "0.0".equals(list.get(1).getNodePath());
        }));
    }

    @Test
    void write_doesNotCallNodeTraceMapper() throws Exception {
        // DryRunTraceWriterDbImpl 不得写 node_trace 表，只写 dry_run_node_trace
        NodeTraceMapper nodeTraceMapper = mock(NodeTraceMapper.class);
        DryRunNodeTraceMapper dryMapper = mock(DryRunNodeTraceMapper.class);
        DryRunTraceWriterDbImpl w = new DryRunTraceWriterDbImpl(100, 10, 60_000, dryMapper);
        w.afterPropertiesSet();

        NodeTrace trace = new NodeTrace("LEAF", "EQ", "score", false, 50, "DB", null, null, null);
        w.write("1", "1", List.of(trace));
        w.destroy();

        verifyNoInteractions(nodeTraceMapper);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
$MVN -pl rule-observability -am test -Dtest='DryRunTraceWriterDbImplTest' -Dsurefire.failIfNoSpecifiedTests=false
```

期望：FAIL — `DryRunTraceWriterDbImpl` 不存在

- [ ] **Step 3: 实现 DryRunTraceWriterDbImpl**

内部结构与 `TraceWriterDbImpl` 完全相同，区别：使用 `DryRunNodeTraceMapper`；`flattenToList` 构建的实体用 `setDryRunSessionId`。

```java
// rule-observability/src/main/java/com/sstlfsj/rule/observability/internal/trace/DryRunTraceWriterDbImpl.java
package com.sstlfsj.rule.observability.internal.trace;

import com.sstlfsj.rule.kernel.api.model.NodeTrace;
import com.sstlfsj.rule.kernel.api.spi.trace.DryRunTraceWriter;
import com.sstlfsj.rule.observability.internal.domain.DryRunNodeTraceEntity;
import com.sstlfsj.rule.observability.internal.repository.DryRunNodeTraceMapper;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * DryRunTraceWriter 异步 DB 实现：BlockingQueue + 虚拟线程消费 + 批量写 dry_run_node_trace。
 * 队列满时静默丢弃，不阻塞评估热路径。
 */
public class DryRunTraceWriterDbImpl implements DryRunTraceWriter, InitializingBean, DisposableBean {

    private final int queueCapacity;
    private final int batchSize;
    private final long flushIntervalMs;
    private final DryRunNodeTraceMapper dryRunNodeTraceMapper;

    private record TraceEntry(String tenantId, String sessionId, List<NodeTrace> traces) {}
    private LinkedBlockingQueue<TraceEntry> queue;

    private volatile boolean running = false;
    private Thread consumerThread;

    public DryRunTraceWriterDbImpl(int queueCapacity, int batchSize, long flushIntervalMs,
                                   DryRunNodeTraceMapper dryRunNodeTraceMapper) {
        this.queueCapacity = queueCapacity;
        this.batchSize = batchSize;
        this.flushIntervalMs = flushIntervalMs;
        this.dryRunNodeTraceMapper = dryRunNodeTraceMapper;
    }

    @Override
    public void afterPropertiesSet() {
        queue = new LinkedBlockingQueue<>(queueCapacity);
        running = true;
        consumerThread = Thread.ofVirtual().name("dry-run-trace-writer").start(this::consumeLoop);
    }

    @Override
    public void write(String tenantId, String sessionId, List<NodeTrace> traces) {
        queue.offer(new TraceEntry(tenantId, sessionId, traces));
    }

    private void consumeLoop() {
        while (running || !queue.isEmpty()) {
            try {
                Thread.sleep(flushIntervalMs);
                flushBatch();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void flushBatch() {
        List<TraceEntry> batch = new ArrayList<>(batchSize);
        queue.drainTo(batch, batchSize);
        for (TraceEntry entry : batch) {
            Long sessionId = parseLong(entry.sessionId());
            Long tenantId  = parseLong(entry.tenantId());
            List<DryRunNodeTraceEntity> entities = new ArrayList<>();
            flattenToList(entry.traces(), sessionId, tenantId, "", entities);
            if (!entities.isEmpty()) {
                dryRunNodeTraceMapper.insertBatch(entities);
            }
        }
    }

    /**
     * 递归展开树形 NodeTrace 到实体列表，nodePath 按深度优先编号（"0", "0.0", "0.1"...）。
     *
     * @param traces      当前层节点列表
     * @param sessionId   dry-run 会话 ID
     * @param tenantId    租户 ID
     * @param pathPrefix  父节点路径前缀（根节点传空串）
     * @param out         结果收集列表
     */
    private void flattenToList(List<NodeTrace> traces, Long sessionId, Long tenantId,
                                String pathPrefix, List<DryRunNodeTraceEntity> out) {
        for (int i = 0; i < traces.size(); i++) {
            NodeTrace trace = traces.get(i);
            String nodePath = pathPrefix.isEmpty()
                    ? String.valueOf(i)
                    : pathPrefix + "." + i;

            DryRunNodeTraceEntity entity = new DryRunNodeTraceEntity();
            entity.setDryRunSessionId(sessionId);
            entity.setTenantId(tenantId);
            entity.setNodePath(nodePath);
            entity.setNodeType(trace.nodeType());
            entity.setConditionType(trace.conditionType());
            entity.setMetricCode(trace.metricCode());
            entity.setActualValue(trace.actualValue() == null ? null : trace.actualValue().toString());
            entity.setResult(trace.result());
            entity.setErrorCode(trace.errorCode());
            entity.setValueSource(trace.valueSource());
            entity.setRuleVersionId(trace.ruleVersionId());
            entity.setEvaluatedAt(LocalDateTime.now());
            out.add(entity);

            if (trace.children() != null && !trace.children().isEmpty()) {
                flattenToList(trace.children(), sessionId, tenantId, nodePath, out);
            }
        }
    }

    private static Long parseLong(String value) {
        if (value == null) return null;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public void destroy() {
        running = false;
        flushBatch();
        if (consumerThread != null) {
            consumerThread.interrupt();
        }
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

```bash
$MVN -pl rule-observability -am test -Dtest='DryRunTraceWriterDbImplTest' -Dsurefire.failIfNoSpecifiedTests=false
```

期望：BUILD SUCCESS，7 tests passed

- [ ] **Step 5: 运行 rule-observability 全量测试**

```bash
$MVN -pl rule-observability -am test
```

期望：BUILD SUCCESS，全部通过

- [ ] **Step 6: 提交**

```bash
git add rule-observability/src/main/java/com/sstlfsj/rule/observability/internal/trace/DryRunTraceWriterDbImpl.java \
        rule-observability/src/test/java/com/sstlfsj/rule/observability/internal/trace/DryRunTraceWriterDbImplTest.java
git commit -m "feat(observability): DryRunTraceWriterDbImpl 异步批写 dry_run_node_trace"
```

---

## Task 4: ObservabilityAutoConfiguration 注册 + EvalServiceImpl trace 路由修复

**Files:**
- Modify: `rule-observability/src/main/java/com/sstlfsj/rule/observability/ObservabilityAutoConfiguration.java`
- Modify test: `rule-observability/src/test/java/com/sstlfsj/rule/observability/ObservabilityAutoConfigurationTest.java`
- Modify: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/service/EvalServiceImpl.java`
- Modify test: `rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/service/EvalServiceImplTest.java`

- [ ] **Step 1: 更新 ObservabilityAutoConfigurationTest（先写测试）**

在现有测试类中追加两个测试方法，并增加 `DryRunNodeTraceMapper` mock 配置：

```java
// ObservabilityAutoConfigurationTest.java 完整替换（保留现有测试，追加新测试）
package com.sstlfsj.rule.observability;

import com.sstlfsj.rule.kernel.api.spi.trace.DryRunTraceWriter;
import com.sstlfsj.rule.kernel.api.spi.trace.TraceWriter;
import com.sstlfsj.rule.observability.internal.repository.DryRunNodeTraceMapper;
import com.sstlfsj.rule.observability.internal.repository.NodeTraceMapper;
import com.sstlfsj.rule.observability.internal.trace.DryRunTraceWriterDbImpl;
import com.sstlfsj.rule.observability.internal.trace.NoopTraceWriter;
import com.sstlfsj.rule.observability.internal.trace.TraceWriterDbImpl;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ObservabilityAutoConfigurationTest {

    @Configuration
    static class MapperMockConfig {
        @Bean
        NodeTraceMapper nodeTraceMapper() {
            return mock(NodeTraceMapper.class);
        }

        @Bean
        DryRunNodeTraceMapper dryRunNodeTraceMapper() {
            return mock(DryRunNodeTraceMapper.class);
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ObservabilityAutoConfiguration.class));

    private final ApplicationContextRunner runnerWithMapper = runner
            .withUserConfiguration(MapperMockConfig.class);

    @Test
    void traceWriterDb_registeredByDefault() {
        runnerWithMapper.run(ctx -> {
            assertThat(ctx).hasSingleBean(TraceWriter.class);
            assertThat(ctx.getBean(TraceWriter.class)).isInstanceOf(TraceWriterDbImpl.class);
        });
    }

    @Test
    void traceWriterDb_registeredWhenEnabled() {
        runnerWithMapper.withPropertyValues("engine.rule.trace.enabled=true")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(TraceWriter.class);
                    assertThat(ctx.getBean(TraceWriter.class)).isInstanceOf(TraceWriterDbImpl.class);
                });
    }

    @Test
    void noopTraceWriter_registeredWhenDisabled() {
        runner.withPropertyValues("engine.rule.trace.enabled=false")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(TraceWriter.class);
                    assertThat(ctx.getBean(TraceWriter.class)).isInstanceOf(NoopTraceWriter.class);
                });
    }

    @Test
    void dryRunTraceWriterDb_registeredByDefault() {
        runnerWithMapper.run(ctx -> {
            assertThat(ctx).hasSingleBean(DryRunTraceWriter.class);
            assertThat(ctx.getBean(DryRunTraceWriter.class)).isInstanceOf(DryRunTraceWriterDbImpl.class);
        });
    }

    @Test
    void dryRunTraceWriterDb_registeredWhenEnabled() {
        runnerWithMapper.withPropertyValues("engine.rule.trace.enabled=true")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(DryRunTraceWriter.class);
                    assertThat(ctx.getBean(DryRunTraceWriter.class)).isInstanceOf(DryRunTraceWriterDbImpl.class);
                });
    }
}
```

- [ ] **Step 2: 运行测试确认新测试失败**

```bash
$MVN -pl rule-observability -am test -Dtest='ObservabilityAutoConfigurationTest' -Dsurefire.failIfNoSpecifiedTests=false
```

期望：FAIL — `dryRunTraceWriterDb_registeredByDefault` 和 `dryRunTraceWriterDb_registeredWhenEnabled` 失败

- [ ] **Step 3: 更新 ObservabilityAutoConfiguration**

```java
// rule-observability/src/main/java/com/sstlfsj/rule/observability/ObservabilityAutoConfiguration.java
package com.sstlfsj.rule.observability;

import com.sstlfsj.rule.kernel.api.spi.trace.DryRunTraceWriter;
import com.sstlfsj.rule.kernel.api.spi.trace.TraceWriter;
import com.sstlfsj.rule.observability.internal.repository.DryRunNodeTraceMapper;
import com.sstlfsj.rule.observability.internal.repository.NodeTraceMapper;
import com.sstlfsj.rule.observability.internal.trace.DryRunTraceWriterDbImpl;
import com.sstlfsj.rule.observability.internal.trace.NoopTraceWriter;
import com.sstlfsj.rule.observability.internal.trace.TraceWriterDbImpl;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/** 自动装配规则可观测性模块（指标 + TraceWriter + DryRunTraceWriter）。 */
@AutoConfiguration
public class ObservabilityAutoConfiguration {

    /**
     * 默认启用异步 DB 批写 TraceWriter（主服务）。
     * 可通过 engine.rule.trace.enabled=false 切换为 Noop 实现。
     */
    @Bean
    @ConditionalOnProperty(name = "engine.rule.trace.enabled", havingValue = "true", matchIfMissing = true)
    public TraceWriter traceWriterDb(NodeTraceMapper nodeTraceMapper) {
        return new TraceWriterDbImpl(10000, 500, 200, nodeTraceMapper);
    }

    /** 当 engine.rule.trace.enabled=false 时注册空实现，用于测试或 SDK 嵌入模式。 */
    @Bean
    @ConditionalOnProperty(name = "engine.rule.trace.enabled", havingValue = "false")
    public TraceWriter noopTraceWriter() {
        return new NoopTraceWriter();
    }

    /**
     * 默认启用异步 DB 批写 DryRunTraceWriter（dry-run 隔离写 dry_run_node_trace）。
     * 与 TraceWriter 共享 engine.rule.trace.enabled 开关。
     */
    @Bean
    @ConditionalOnProperty(name = "engine.rule.trace.enabled", havingValue = "true", matchIfMissing = true)
    public DryRunTraceWriter dryRunTraceWriterDb(DryRunNodeTraceMapper dryRunNodeTraceMapper) {
        return new DryRunTraceWriterDbImpl(10000, 500, 200, dryRunNodeTraceMapper);
    }
}
```

- [ ] **Step 4: 运行 ObservabilityAutoConfigurationTest 确认通过**

```bash
$MVN -pl rule-observability -am test -Dtest='ObservabilityAutoConfigurationTest' -Dsurefire.failIfNoSpecifiedTests=false
```

期望：BUILD SUCCESS，5 tests passed

- [ ] **Step 5: 更新 EvalServiceImplTest（先加 mock 字段，再改调用验证）**

在现有测试类中：
1. 增加 `@Mock DryRunTraceWriter dryRunTraceWriter;` 字段
2. 更新 `dryRun_writesToDryRunSessionNotProd` 测试验证 `dryRunTraceWriter.write()` 被调用
3. 增加 `evaluate_ruleHit_callsProdTraceWriter_notDryRun` 测试

```java
// rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/service/EvalServiceImplTest.java
package com.sstlfsj.rule.eval.internal.service;

import com.sstlfsj.rule.eval.internal.context.EvalContextAssembler;
import com.sstlfsj.rule.eval.internal.index.SceneRuleIndex;
import com.sstlfsj.rule.eval.internal.session.EvalSessionWriter;
import com.sstlfsj.rule.eval.internal.snapshot.SceneSnapshotLoader;
import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.spi.executor.RuleVersionExecutor;
import com.sstlfsj.rule.kernel.api.spi.pregate.PreGate;
import com.sstlfsj.rule.kernel.api.spi.trace.DryRunTraceWriter;
import com.sstlfsj.rule.kernel.api.spi.trace.TraceWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EvalServiceImplTest {

    @Mock SceneRuleIndex index;
    @Mock SceneSnapshotLoader snapshotLoader;
    @Mock EvalContextAssembler contextAssembler;
    @Mock RuleVersionExecutor executor;
    @Mock EvalSessionWriter sessionWriter;
    @Mock TraceWriter traceWriter;
    @Mock DryRunTraceWriter dryRunTraceWriter;

    // EvalServiceImpl 构造器接受 List<PreGate>，Mockito @InjectMocks 会注入空列表
    @InjectMocks EvalServiceImpl impl;

    private RuleEvent event() {
        return new RuleEvent("1", "fraud_check", "RISK_EVENT", "u1",
                "evt-001", Instant.now(), Map.of(), Map.of());
    }

    private RuleVersionSnapshot snapshot(Long id, String decisionCode) {
        return new RuleVersionSnapshot(
                id, "fraud_check", "1",
                new ConditionNode("EQ", null, null, Map.of()),
                List.of(),
                List.of(new RuleVersionSnapshot.DecisionBinding(decisionCode, 10)),
                null);
    }

    @Test
    void evaluate_noMatchingRules_returnsMiss() {
        when(index.match("1", "fraud_check", "RISK_EVENT")).thenReturn(List.of());

        EvalResult result = impl.evaluate(event());

        assertFalse(result.ruleHit());
        verifyNoInteractions(sessionWriter);
    }

    @Test
    void evaluate_ruleHit_returnsHitWithDecision() {
        RuleVersionSnapshot snap = snapshot(1L, "REJECT");
        when(index.match("1", "fraud_check", "RISK_EVENT")).thenReturn(List.of(snap));
        when(contextAssembler.assemble(any(), any()))
                .thenReturn(new EvalContext("1", event(), new Subject("u1", SubjectType.USER, Map.of()), Map.of()));
        when(executor.execute(any(), any())).thenReturn(EvalResult.hit());
        when(sessionWriter.insertPending(any(), anyInt(), anyString())).thenReturn(1L);

        EvalResult result = impl.evaluate(event());

        assertTrue(result.ruleHit());
        assertFalse(result.hitDecisions().isEmpty());
        assertEquals("REJECT", result.hitDecisions().get(0).code());
        verify(sessionWriter).updateFinal(anyLong(), any());
        verify(traceWriter).write(anyString(), anyString(), anyList());
    }

    @Test
    void evaluate_ruleHit_callsProdTraceWriter_notDryRunWriter() {
        RuleVersionSnapshot snap = snapshot(1L, "REJECT");
        when(index.match("1", "fraud_check", "RISK_EVENT")).thenReturn(List.of(snap));
        when(contextAssembler.assemble(any(), any()))
                .thenReturn(new EvalContext("1", event(), new Subject("u1", SubjectType.USER, Map.of()), Map.of()));
        when(executor.execute(any(), any())).thenReturn(EvalResult.hit());
        when(sessionWriter.insertPending(any(), anyInt(), anyString())).thenReturn(1L);

        impl.evaluate(event());

        verify(traceWriter).write(anyString(), anyString(), anyList());
        verifyNoInteractions(dryRunTraceWriter);
    }

    @Test
    void evaluate_ruleMiss_returnsMiss() {
        RuleVersionSnapshot snap = snapshot(1L, "REJECT");
        when(index.match("1", "fraud_check", "RISK_EVENT")).thenReturn(List.of(snap));
        when(contextAssembler.assemble(any(), any()))
                .thenReturn(new EvalContext("1", event(), new Subject("u1", SubjectType.USER, Map.of()), Map.of()));
        when(executor.execute(any(), any())).thenReturn(EvalResult.miss());
        when(sessionWriter.insertPending(any(), anyInt(), anyString())).thenReturn(1L);

        EvalResult result = impl.evaluate(event());

        assertFalse(result.ruleHit());
        assertTrue(result.hitDecisions().isEmpty());
        assertNull(result.finalDecision());
    }

    @Test
    void evaluate_multipleHits_highestPriorityWins() {
        RuleVersionSnapshot snapLow  = new RuleVersionSnapshot(1L, "fraud_check", "1",
                new ConditionNode("EQ", null, null, Map.of()), List.of(),
                List.of(new RuleVersionSnapshot.DecisionBinding("LOW_RISK", 5)), null);
        RuleVersionSnapshot snapHigh = new RuleVersionSnapshot(2L, "fraud_check", "1",
                new ConditionNode("EQ", null, null, Map.of()), List.of(),
                List.of(new RuleVersionSnapshot.DecisionBinding("REJECT", 20)), null);
        when(index.match("1", "fraud_check", "RISK_EVENT")).thenReturn(List.of(snapLow, snapHigh));
        when(contextAssembler.assemble(any(), any()))
                .thenReturn(new EvalContext("1", event(), new Subject("u1", SubjectType.USER, Map.of()), Map.of()));
        when(executor.execute(any(), any())).thenReturn(EvalResult.hit());
        when(sessionWriter.insertPending(any(), anyInt(), anyString())).thenReturn(1L);

        EvalResult result = impl.evaluate(event());

        assertTrue(result.ruleHit());
        assertEquals("REJECT", result.finalDecision().code());
    }

    @Test
    void acceptEvent_returnsTrueAndDoesNotBlock() throws Exception {
        impl.afterPropertiesSet();
        try {
            boolean accepted = impl.acceptEvent(event());
            assertTrue(accepted);
        } finally {
            impl.destroy();
        }
    }

    @Test
    void dryRun_writesDryRunTraceWriter_notProdWriter() {
        RuleVersionSnapshot snap = snapshot(42L, "PASS");
        when(snapshotLoader.loadById(42L)).thenReturn(snap);
        when(contextAssembler.assemble(any(), any()))
                .thenReturn(new EvalContext("1", event(), new Subject("u1", SubjectType.USER, Map.of()), Map.of()));
        when(executor.execute(any(), any())).thenReturn(EvalResult.miss());
        when(sessionWriter.insertDryRunPending(any(), anyLong())).thenReturn(1L);

        impl.dryRun(event(), 42L);

        verify(dryRunTraceWriter).write(anyString(), anyString(), anyList());
        verifyNoInteractions(traceWriter);
    }

    @Test
    void dryRun_writesToDryRunSessionNotProd() {
        RuleVersionSnapshot snap = snapshot(42L, "PASS");
        when(snapshotLoader.loadById(42L)).thenReturn(snap);
        when(contextAssembler.assemble(any(), any()))
                .thenReturn(new EvalContext("1", event(), new Subject("u1", SubjectType.USER, Map.of()), Map.of()));
        when(executor.execute(any(), any())).thenReturn(EvalResult.miss());
        when(sessionWriter.insertDryRunPending(any(), anyLong())).thenReturn(1L);

        EvalResult result = impl.dryRun(event(), 42L);

        assertFalse(result.ruleHit());
        verify(sessionWriter).insertDryRunPending(any(), eq(42L));
        verify(sessionWriter, never()).insertPending(any(), anyInt(), anyString());
    }
}
```

- [ ] **Step 6: 运行测试确认失败（新增测试因代码还未改）**

```bash
$MVN -pl rule-eval-svc -am test -Dtest='EvalServiceImplTest' -Dsurefire.failIfNoSpecifiedTests=false
```

期望：FAIL — `dryRun_writesDryRunTraceWriter_notProdWriter` 失败（当前 trace 路由错误）

- [ ] **Step 7: 修改 EvalServiceImpl 注入 DryRunTraceWriter 并修复 trace 路由**

在现有 `EvalServiceImpl.java` 中：
1. 添加 import: `import com.sstlfsj.rule.kernel.api.spi.trace.DryRunTraceWriter;`
2. 添加字段: `private final DryRunTraceWriter dryRunTraceWriter;`
3. 构造器末尾加 `DryRunTraceWriter dryRunTraceWriter` 参数并赋值
4. 替换 `doEvaluate()` 末尾的 trace 写入逻辑

构造器改动（在 `TraceWriter traceWriter` 参数后追加）：
```java
EvalServiceImpl(SceneRuleIndex index,
                SceneSnapshotLoader snapshotLoader,
                List<PreGate> preGates,
                EvalContextAssembler contextAssembler,
                RuleVersionExecutor executor,
                EvalSessionWriter sessionWriter,
                TraceWriter traceWriter,
                DryRunTraceWriter dryRunTraceWriter) {
    this.index = index;
    this.snapshotLoader = snapshotLoader;
    this.preGateMap = preGates == null ? Map.of()
            : preGates.stream().collect(Collectors.toMap(PreGate::gateType, g -> g));
    this.contextAssembler = contextAssembler;
    this.executor = executor;
    this.sessionWriter = sessionWriter;
    this.traceWriter = traceWriter;
    this.dryRunTraceWriter = dryRunTraceWriter;
    this.dispatcher = new EvalActionDispatcher(10000, this::evaluate);
}
```

`doEvaluate()` 末尾 trace 写入部分替换为：
```java
// ⑦ 更新 session 终态 + 提交 traces 到隔离写库
if (isDryRun) {
    sessionWriter.updateDryRunFinal(sessionId, result);
    dryRunTraceWriter.write(event.tenantId(), sessionId.toString(), allTraces);
} else {
    sessionWriter.updateFinal(sessionId, result);
    traceWriter.write(event.tenantId(), sessionId.toString(), allTraces);
}

return result;
```

- [ ] **Step 8: 运行 EvalServiceImplTest 确认通过**

```bash
$MVN -pl rule-eval-svc -am test -Dtest='EvalServiceImplTest' -Dsurefire.failIfNoSpecifiedTests=false
```

期望：BUILD SUCCESS，9 tests passed

- [ ] **Step 9: 运行全量测试**

```bash
$MVN -pl rule-observability,rule-eval-svc -am test
```

期望：BUILD SUCCESS，全部通过

- [ ] **Step 10: 提交**

```bash
git add rule-observability/src/main/java/com/sstlfsj/rule/observability/ObservabilityAutoConfiguration.java \
        rule-observability/src/test/java/com/sstlfsj/rule/observability/ObservabilityAutoConfigurationTest.java \
        rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/service/EvalServiceImpl.java \
        rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/service/EvalServiceImplTest.java
git commit -m "feat(eval): DryRunTraceWriter 注入 + dry-run trace 路由修复"
```

---

## Task 5: ActionExecutionEntity + SceneActionBindingRow + Mapper（rule-eval-svc）

**Files:**
- Create: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/domain/ActionExecutionEntity.java`
- Create: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/domain/SceneActionBindingRow.java`
- Create: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/repository/ActionExecutionMapper.java`
- Create: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/repository/SceneActionBindingReadMapper.java`
- Create test: `rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/repository/ActionExecutionMapperTest.java`
- Create test: `rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/repository/SceneActionBindingReadMapperTest.java`

- [ ] **Step 1: 写失败测试**

```java
// rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/repository/ActionExecutionMapperTest.java
package com.sstlfsj.rule.eval.internal.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sstlfsj.rule.eval.internal.domain.ActionExecutionEntity;
import org.apache.ibatis.annotations.Mapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ActionExecutionMapperTest {

    @Test
    void mapperAnnotationPresent() {
        assertNotNull(ActionExecutionMapper.class.getAnnotation(Mapper.class));
    }

    @Test
    void extendsBaseMapper() {
        boolean found = false;
        for (Class<?> iface : ActionExecutionMapper.class.getInterfaces()) {
            if (iface.equals(BaseMapper.class)) {
                found = true;
                break;
            }
        }
        assertTrue(found, "ActionExecutionMapper 须继承 BaseMapper");
    }

    @Test
    void genericTypeIsActionExecutionEntity() {
        java.lang.reflect.Type[] types = ActionExecutionMapper.class.getGenericInterfaces();
        assertEquals(1, types.length);
        java.lang.reflect.ParameterizedType pt = (java.lang.reflect.ParameterizedType) types[0];
        assertEquals(ActionExecutionEntity.class, pt.getActualTypeArguments()[0]);
    }
}
```

```java
// rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/repository/SceneActionBindingReadMapperTest.java
package com.sstlfsj.rule.eval.internal.repository;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

class SceneActionBindingReadMapperTest {

    @Test
    void mapperAnnotationPresent() {
        assertNotNull(SceneActionBindingReadMapper.class.getAnnotation(Mapper.class));
    }

    @Test
    void findBySceneCode_methodExists_withSelectAnnotation() throws Exception {
        Method method = SceneActionBindingReadMapper.class.getDeclaredMethod(
                "findBySceneCode", Long.class, String.class);
        assertNotNull(method);
        assertNotNull(method.getAnnotation(Select.class));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
$MVN -pl rule-eval-svc -am test \
  -Dtest='ActionExecutionMapperTest,SceneActionBindingReadMapperTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

期望：FAIL — 类不存在

- [ ] **Step 3: 实现 ActionExecutionEntity**

```java
// rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/domain/ActionExecutionEntity.java
package com.sstlfsj.rule.eval.internal.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** action_execution 表实体：记录每次 ActionHandler 执行结果。 */
@TableName("action_execution")
public class ActionExecutionEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long evaluationSessionId;
    private Long tenantId;
    private String eventId;
    private String actionId;
    private String actionType;
    private String decisionCode;
    private String status;
    private String errorCode;
    private Boolean retryable;
    private Integer retryCount;
    private LocalDateTime executedAt;
    private Boolean compensated;
    private LocalDateTime compensatedAt;
    private String compensatedBy;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getEvaluationSessionId() { return evaluationSessionId; }
    public void setEvaluationSessionId(Long evaluationSessionId) { this.evaluationSessionId = evaluationSessionId; }

    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }

    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }

    public String getActionId() { return actionId; }
    public void setActionId(String actionId) { this.actionId = actionId; }

    public String getActionType() { return actionType; }
    public void setActionType(String actionType) { this.actionType = actionType; }

    public String getDecisionCode() { return decisionCode; }
    public void setDecisionCode(String decisionCode) { this.decisionCode = decisionCode; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }

    public Boolean getRetryable() { return retryable; }
    public void setRetryable(Boolean retryable) { this.retryable = retryable; }

    public Integer getRetryCount() { return retryCount; }
    public void setRetryCount(Integer retryCount) { this.retryCount = retryCount; }

    public LocalDateTime getExecutedAt() { return executedAt; }
    public void setExecutedAt(LocalDateTime executedAt) { this.executedAt = executedAt; }

    public Boolean getCompensated() { return compensated; }
    public void setCompensated(Boolean compensated) { this.compensated = compensated; }

    public LocalDateTime getCompensatedAt() { return compensatedAt; }
    public void setCompensatedAt(LocalDateTime compensatedAt) { this.compensatedAt = compensatedAt; }

    public String getCompensatedBy() { return compensatedBy; }
    public void setCompensatedBy(String compensatedBy) { this.compensatedBy = compensatedBy; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
```

- [ ] **Step 4: 实现 SceneActionBindingRow**

```java
// rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/domain/SceneActionBindingRow.java
package com.sstlfsj.rule.eval.internal.domain;

/** scene_action_binding 表查询结果 DTO。 */
public record SceneActionBindingRow(String actionType, String defaultParamsJson) {}
```

- [ ] **Step 5: 实现 ActionExecutionMapper**

```java
// rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/repository/ActionExecutionMapper.java
package com.sstlfsj.rule.eval.internal.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sstlfsj.rule.eval.internal.domain.ActionExecutionEntity;
import org.apache.ibatis.annotations.Mapper;

/** action_execution 表 Mapper，单条 insert 即可（无批量需求）。 */
@Mapper
public interface ActionExecutionMapper extends BaseMapper<ActionExecutionEntity> {}
```

- [ ] **Step 6: 实现 SceneActionBindingReadMapper**

JOIN scene 表通过 `tenant_id + scene.code` 查询绑定列表。

```java
// rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/repository/SceneActionBindingReadMapper.java
package com.sstlfsj.rule.eval.internal.repository;

import com.sstlfsj.rule.eval.internal.domain.SceneActionBindingRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** scene_action_binding 只读 Mapper，按 sceneCode 查询该场景下所有 ActionHandler 绑定。 */
@Mapper
public interface SceneActionBindingReadMapper {

    /**
     * 查询指定租户和场景下的所有 Action 绑定。
     *
     * @param tenantId  租户 ID
     * @param sceneCode 场景编码
     * @return Action 绑定列表，场景无绑定时返回空列表
     */
    @Select("""
            SELECT sab.action_type AS actionType, sab.default_params AS defaultParamsJson
            FROM scene_action_binding sab
            JOIN scene s ON sab.scene_id = s.id
            WHERE s.tenant_id = #{tenantId} AND s.code = #{sceneCode}
            """)
    List<SceneActionBindingRow> findBySceneCode(@Param("tenantId") Long tenantId,
                                                @Param("sceneCode") String sceneCode);
}
```

- [ ] **Step 7: 运行测试确认通过**

```bash
$MVN -pl rule-eval-svc -am test \
  -Dtest='ActionExecutionMapperTest,SceneActionBindingReadMapperTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

期望：BUILD SUCCESS，5 tests passed

- [ ] **Step 8: 提交**

```bash
git add rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/domain/ActionExecutionEntity.java \
        rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/domain/SceneActionBindingRow.java \
        rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/repository/ActionExecutionMapper.java \
        rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/repository/SceneActionBindingReadMapper.java \
        rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/repository/ActionExecutionMapperTest.java \
        rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/repository/SceneActionBindingReadMapperTest.java
git commit -m "feat(eval): ActionExecutionEntity + SceneActionBindingRow + Mapper 定义"
```

---

## Task 6: ActionDispatchService（rule-eval-svc）

**Files:**
- Create: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/action/ActionDispatchService.java`
- Create test: `rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/action/ActionDispatchServiceTest.java`

- [ ] **Step 1: 写失败测试**

```java
// rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/action/ActionDispatchServiceTest.java
package com.sstlfsj.rule.eval.internal.action;

import com.sstlfsj.rule.eval.internal.domain.ActionExecutionEntity;
import com.sstlfsj.rule.eval.internal.domain.SceneActionBindingRow;
import com.sstlfsj.rule.eval.internal.repository.ActionExecutionMapper;
import com.sstlfsj.rule.eval.internal.repository.SceneActionBindingReadMapper;
import com.sstlfsj.rule.kernel.api.model.ActionContext;
import com.sstlfsj.rule.kernel.api.model.ActionResult;
import com.sstlfsj.rule.kernel.api.spi.action.ActionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ActionDispatchServiceTest {

    private SceneActionBindingReadMapper bindingMapper;
    private ActionExecutionMapper executionMapper;
    private ActionHandler stubHandler;
    private ActionDispatchService service;

    @BeforeEach
    void setUp() {
        bindingMapper = mock(SceneActionBindingReadMapper.class);
        executionMapper = mock(ActionExecutionMapper.class);
        stubHandler = mock(ActionHandler.class);
        when(stubHandler.execute(any())).thenReturn(ActionResult.success("aid", "BLOCK_TRANSACTION"));

        service = new ActionDispatchService(
                Map.of("BLOCK_TRANSACTION", stubHandler),
                bindingMapper,
                executionMapper);
    }

    @Test
    void dispatch_withBinding_callsHandlerAndInsertsExecution() {
        when(bindingMapper.findBySceneCode(1L, "fraud_check"))
                .thenReturn(List.of(new SceneActionBindingRow("BLOCK_TRANSACTION", null)));

        service.dispatch(42L, 1L, "evt-001", "fraud_check",
                List.of(new com.sstlfsj.rule.kernel.api.model.Decision("REJECT", "", 10, 1L)));

        verify(stubHandler).execute(any(ActionContext.class));
        verify(executionMapper).insert(argThat(entity ->
                "BLOCK_TRANSACTION".equals(entity.getActionType())
                && "SUCCESS".equals(entity.getStatus())
                && Long.valueOf(42L).equals(entity.getEvaluationSessionId())
                && "evt-001".equals(entity.getEventId())));
    }

    @Test
    void dispatch_emptyBindings_doesNothing() {
        when(bindingMapper.findBySceneCode(1L, "fraud_check")).thenReturn(List.of());

        service.dispatch(42L, 1L, "evt-001", "fraud_check",
                List.of(new com.sstlfsj.rule.kernel.api.model.Decision("REJECT", "", 10, 1L)));

        verifyNoInteractions(stubHandler);
        verifyNoInteractions(executionMapper);
    }

    @Test
    void dispatch_handlerNotRegistered_insertsSkippedRecord() {
        when(bindingMapper.findBySceneCode(1L, "fraud_check"))
                .thenReturn(List.of(new SceneActionBindingRow("UNKNOWN_ACTION", null)));

        service.dispatch(42L, 1L, "evt-001", "fraud_check",
                List.of(new com.sstlfsj.rule.kernel.api.model.Decision("REJECT", "", 10, 1L)));

        verifyNoInteractions(stubHandler);
        verify(executionMapper).insert(argThat(entity ->
                "UNKNOWN_ACTION".equals(entity.getActionType())
                && "SKIPPED".equals(entity.getStatus())
                && "NO_HANDLER".equals(entity.getErrorCode())));
    }

    @Test
    void dispatch_insertException_doesNotPropagate() {
        when(bindingMapper.findBySceneCode(1L, "fraud_check"))
                .thenReturn(List.of(new SceneActionBindingRow("BLOCK_TRANSACTION", null)));
        doThrow(new RuntimeException("DB 写入失败")).when(executionMapper).insert(any());

        // 插入异常不应向上传播，不影响 EvalResult
        service.dispatch(42L, 1L, "evt-001", "fraud_check",
                List.of(new com.sstlfsj.rule.kernel.api.model.Decision("REJECT", "", 10, 1L)));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
$MVN -pl rule-eval-svc -am test -Dtest='ActionDispatchServiceTest' -Dsurefire.failIfNoSpecifiedTests=false
```

期望：FAIL — `ActionDispatchService` 不存在

- [ ] **Step 3: 实现 ActionDispatchService**

```java
// rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/action/ActionDispatchService.java
package com.sstlfsj.rule.eval.internal.action;

import com.sstlfsj.rule.eval.internal.domain.ActionExecutionEntity;
import com.sstlfsj.rule.eval.internal.domain.SceneActionBindingRow;
import com.sstlfsj.rule.eval.internal.repository.ActionExecutionMapper;
import com.sstlfsj.rule.eval.internal.repository.SceneActionBindingReadMapper;
import com.sstlfsj.rule.kernel.api.model.ActionContext;
import com.sstlfsj.rule.kernel.api.model.ActionResult;
import com.sstlfsj.rule.kernel.api.model.Decision;
import com.sstlfsj.rule.kernel.api.spi.action.ActionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 规则命中后同步派发 ActionHandler，结果写 action_execution。
 * v1 handler 均为 stub，同步调用无性能影响；v1.5 接真实 handler 时在此提取异步层。
 */
public class ActionDispatchService {

    private static final Logger log = LoggerFactory.getLogger(ActionDispatchService.class);

    private final Map<String, ActionHandler> handlers;
    private final SceneActionBindingReadMapper bindingMapper;
    private final ActionExecutionMapper executionMapper;

    public ActionDispatchService(Map<String, ActionHandler> handlers,
                                 SceneActionBindingReadMapper bindingMapper,
                                 ActionExecutionMapper executionMapper) {
        this.handlers = handlers;
        this.bindingMapper = bindingMapper;
        this.executionMapper = executionMapper;
    }

    /**
     * 派发本次命中的所有 Decision 对应的 Action，逐条插入 action_execution。
     * insert 异常静默记录 warn 日志，不向上传播（审计失败不影响 EvalResult）。
     *
     * @param sessionId   评估会话 ID
     * @param tenantId    租户 ID
     * @param eventId     业务事件 ID（用于幂等唯一键）
     * @param sceneCode   场景编码
     * @param hitDecisions 本次命中的 Decision 列表
     */
    public void dispatch(Long sessionId, Long tenantId, String eventId,
                         String sceneCode, List<Decision> hitDecisions) {
        List<SceneActionBindingRow> bindings = bindingMapper.findBySceneCode(tenantId, sceneCode);
        if (bindings.isEmpty()) {
            return;
        }

        for (Decision decision : hitDecisions) {
            for (SceneActionBindingRow binding : bindings) {
                String actionId = UUID.randomUUID().toString();
                ActionResult result = executeHandler(actionId, binding, decision);
                insertExecution(sessionId, tenantId, eventId, actionId,
                        binding.actionType(), decision.code(), result);
            }
        }
    }

    private ActionResult executeHandler(String actionId, SceneActionBindingRow binding,
                                        Decision decision) {
        ActionHandler handler = handlers.get(binding.actionType());
        if (handler == null) {
            return ActionResult.skipped(actionId, binding.actionType(), "NO_HANDLER");
        }
        ActionContext ctx = new ActionContext(actionId, binding.actionType(),
                Map.of(), null, null, decision.code());
        return handler.execute(ctx);
    }

    private void insertExecution(Long sessionId, Long tenantId, String eventId,
                                 String actionId, String actionType, String decisionCode,
                                 ActionResult result) {
        ActionExecutionEntity entity = new ActionExecutionEntity();
        entity.setEvaluationSessionId(sessionId);
        entity.setTenantId(tenantId);
        entity.setEventId(eventId);
        entity.setActionId(actionId);
        entity.setActionType(actionType);
        entity.setDecisionCode(decisionCode);
        entity.setStatus(result.status().name());
        entity.setErrorCode(result.errorCode());
        entity.setRetryable(result.retryable());
        entity.setRetryCount(0);
        entity.setCompensated(false);
        entity.setExecutedAt(LocalDateTime.now());
        entity.setCreatedAt(LocalDateTime.now());
        try {
            executionMapper.insert(entity);
        } catch (Exception e) {
            log.warn("action_execution 写库失败，actionId={}, actionType={}: {}",
                    actionId, actionType, e.getMessage());
        }
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

```bash
$MVN -pl rule-eval-svc -am test -Dtest='ActionDispatchServiceTest' -Dsurefire.failIfNoSpecifiedTests=false
```

期望：BUILD SUCCESS，4 tests passed

- [ ] **Step 5: 提交**

```bash
git add rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/action/ActionDispatchService.java \
        rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/action/ActionDispatchServiceTest.java
git commit -m "feat(eval): ActionDispatchService 同步派发 ActionHandler + action_execution 落库"
```

---

## Task 7: Stub ActionHandler 实现（rule-eval-svc）

**Files:**
- Create: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/action/BlockTransactionHandler.java`
- Create: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/action/SendAlertHandler.java`

这两个 handler 由 `ActionDispatchServiceTest` 间接覆盖（v1 stub 逻辑极简，无需独立单测）。

- [ ] **Step 1: 实现 BlockTransactionHandler**

```java
// rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/action/BlockTransactionHandler.java
package com.sstlfsj.rule.eval.internal.action;

import com.sstlfsj.rule.kernel.api.annotation.ActionType;
import com.sstlfsj.rule.kernel.api.model.ActionContext;
import com.sstlfsj.rule.kernel.api.model.ActionResult;
import com.sstlfsj.rule.kernel.api.spi.action.ActionHandler;
import org.springframework.stereotype.Component;

/** 阻断交易 ActionHandler，v1 stub 实现，直接返回 success。 */
@Component
@ActionType("BLOCK_TRANSACTION")
public class BlockTransactionHandler implements ActionHandler {

    @Override
    public ActionResult execute(ActionContext ctx) {
        return ActionResult.success(ctx.actionId(), ctx.actionType());
    }
}
```

- [ ] **Step 2: 实现 SendAlertHandler**

```java
// rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/action/SendAlertHandler.java
package com.sstlfsj.rule.eval.internal.action;

import com.sstlfsj.rule.kernel.api.annotation.ActionType;
import com.sstlfsj.rule.kernel.api.model.ActionContext;
import com.sstlfsj.rule.kernel.api.model.ActionResult;
import com.sstlfsj.rule.kernel.api.spi.action.ActionHandler;
import org.springframework.stereotype.Component;

/** 发送告警 ActionHandler，v1 stub 实现，直接返回 success。 */
@Component
@ActionType("SEND_ALERT")
public class SendAlertHandler implements ActionHandler {

    @Override
    public ActionResult execute(ActionContext ctx) {
        return ActionResult.success(ctx.actionId(), ctx.actionType());
    }
}
```

- [ ] **Step 3: 运行 rule-eval-svc 当前测试确认不破坏已有测试**

```bash
$MVN -pl rule-eval-svc -am test
```

期望：BUILD SUCCESS，全部通过

- [ ] **Step 4: 提交**

```bash
git add rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/action/BlockTransactionHandler.java \
        rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/action/SendAlertHandler.java
git commit -m "feat(eval): BlockTransactionHandler + SendAlertHandler v1 stub 实现"
```

---

## Task 8: EvalAutoConfiguration + EvalServiceImpl action dispatch 整合

**Files:**
- Modify: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/EvalAutoConfiguration.java`
- Modify: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/service/EvalServiceImpl.java`
- Modify test: `rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/service/EvalServiceImplTest.java`

- [ ] **Step 1: 更新 EvalServiceImplTest（先加 mock 字段 + 新增测试）**

在现有 `EvalServiceImplTest` 中追加：
1. 字段：`@Mock ActionDispatchService actionDispatchService;`
2. 新增测试方法：

```java
@Mock ActionDispatchService actionDispatchService;
```

追加到字段列表（放在 `@Mock DryRunTraceWriter dryRunTraceWriter;` 之后）。

追加测试方法：

```java
@Test
void evaluate_ruleHit_dispatchesAction() {
    RuleVersionSnapshot snap = snapshot(1L, "REJECT");
    when(index.match("1", "fraud_check", "RISK_EVENT")).thenReturn(List.of(snap));
    when(contextAssembler.assemble(any(), any()))
            .thenReturn(new EvalContext("1", event(), new Subject("u1", SubjectType.USER, Map.of()), Map.of()));
    when(executor.execute(any(), any())).thenReturn(EvalResult.hit());
    when(sessionWriter.insertPending(any(), anyInt(), anyString())).thenReturn(1L);

    impl.evaluate(event());

    verify(actionDispatchService).dispatch(anyLong(), anyLong(), anyString(), anyString(), anyList());
}

@Test
void evaluate_ruleMiss_doesNotDispatchAction() {
    RuleVersionSnapshot snap = snapshot(1L, "REJECT");
    when(index.match("1", "fraud_check", "RISK_EVENT")).thenReturn(List.of(snap));
    when(contextAssembler.assemble(any(), any()))
            .thenReturn(new EvalContext("1", event(), new Subject("u1", SubjectType.USER, Map.of()), Map.of()));
    when(executor.execute(any(), any())).thenReturn(EvalResult.miss());
    when(sessionWriter.insertPending(any(), anyInt(), anyString())).thenReturn(1L);

    impl.evaluate(event());

    verifyNoInteractions(actionDispatchService);
}

@Test
void dryRun_doesNotDispatchAction() {
    RuleVersionSnapshot snap = snapshot(42L, "PASS");
    when(snapshotLoader.loadById(42L)).thenReturn(snap);
    when(contextAssembler.assemble(any(), any()))
            .thenReturn(new EvalContext("1", event(), new Subject("u1", SubjectType.USER, Map.of()), Map.of()));
    when(executor.execute(any(), any())).thenReturn(EvalResult.hit());
    when(sessionWriter.insertDryRunPending(any(), anyLong())).thenReturn(1L);

    impl.dryRun(event(), 42L);

    verifyNoInteractions(actionDispatchService);
}
```

还需在 import 列表加：
```java
import com.sstlfsj.rule.eval.internal.action.ActionDispatchService;
```

- [ ] **Step 2: 运行测试确认新增测试失败**

```bash
$MVN -pl rule-eval-svc -am test -Dtest='EvalServiceImplTest' -Dsurefire.failIfNoSpecifiedTests=false
```

期望：FAIL — `evaluate_ruleHit_dispatchesAction` 等新测试失败

- [ ] **Step 3: 修改 EvalServiceImpl 注入 ActionDispatchService + dispatch 调用**

在 `EvalServiceImpl.java` 中：
1. 追加 import: `import com.sstlfsj.rule.eval.internal.action.ActionDispatchService;`
2. 追加字段: `private final ActionDispatchService actionDispatchService;`
3. 构造器末尾追加 `ActionDispatchService actionDispatchService` 参数并赋值

构造器参数列表（完整版）：
```java
EvalServiceImpl(SceneRuleIndex index,
                SceneSnapshotLoader snapshotLoader,
                List<PreGate> preGates,
                EvalContextAssembler contextAssembler,
                RuleVersionExecutor executor,
                EvalSessionWriter sessionWriter,
                TraceWriter traceWriter,
                DryRunTraceWriter dryRunTraceWriter,
                ActionDispatchService actionDispatchService) {
    this.index = index;
    this.snapshotLoader = snapshotLoader;
    this.preGateMap = preGates == null ? Map.of()
            : preGates.stream().collect(Collectors.toMap(PreGate::gateType, g -> g));
    this.contextAssembler = contextAssembler;
    this.executor = executor;
    this.sessionWriter = sessionWriter;
    this.traceWriter = traceWriter;
    this.dryRunTraceWriter = dryRunTraceWriter;
    this.actionDispatchService = actionDispatchService;
    this.dispatcher = new EvalActionDispatcher(10000, this::evaluate);
}
```

在 `doEvaluate()` 末尾（trace 写入之后，return 之前）追加：
```java
// ⑧ 非 dry-run 且有命中 Decision 时派发 Action（dry-run 不派发，见 D7）
if (!isDryRun && !hitDecisions.isEmpty()) {
    actionDispatchService.dispatch(sessionId, parseTenantId(event.tenantId()),
            event.eventId(), event.sceneCode(), hitDecisions);
}

return result;
```

同时在 `EvalServiceImpl` 类中添加私有工具方法：
```java
private static Long parseTenantId(String tenantId) {
    try {
        return Long.parseLong(tenantId);
    } catch (NumberFormatException e) {
        return null;
    }
}
```

- [ ] **Step 4: 运行 EvalServiceImplTest 确认通过**

```bash
$MVN -pl rule-eval-svc -am test -Dtest='EvalServiceImplTest' -Dsurefire.failIfNoSpecifiedTests=false
```

期望：BUILD SUCCESS，12 tests passed

- [ ] **Step 5: 更新 EvalAutoConfiguration 注册 ActionDispatchService bean**

```java
// rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/EvalAutoConfiguration.java
package com.sstlfsj.rule.eval;

import com.sstlfsj.rule.eval.internal.action.ActionDispatchService;
import com.sstlfsj.rule.eval.internal.repository.ActionExecutionMapper;
import com.sstlfsj.rule.eval.internal.repository.SceneActionBindingReadMapper;
import com.sstlfsj.rule.kernel.api.annotation.ActionType;
import com.sstlfsj.rule.kernel.api.spi.action.ActionHandler;
import com.sstlfsj.rule.kernel.api.spi.executor.RuleVersionExecutor;
import com.sstlfsj.rule.kernel.internal.evaluator.TracingInterpretedExecutor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 自动装配规则评估模块。 */
@AutoConfiguration
@ComponentScan("com.sstlfsj.rule.eval.internal")
public class EvalAutoConfiguration {

    /**
     * 默认使用 TracingInterpretedExecutor（AST 树形解释执行，附带 NodeTrace 收集）。
     *
     * @param conditionEvaluators 所有注册的 ConditionEvaluator，按 conditionType 索引
     * @return TracingInterpretedExecutor 实例
     */
    @Bean
    public RuleVersionExecutor ruleVersionExecutor(
            @Autowired(required = false)
            Map<String, com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator> conditionEvaluators) {
        return new TracingInterpretedExecutor(
                conditionEvaluators == null ? Map.of() : conditionEvaluators);
    }

    /**
     * ActionDispatchService：按 @ActionType.value() 构建 handler 映射，注入 Mapper 依赖。
     *
     * @param actionHandlers      Spring 容器中所有 ActionHandler bean
     * @param bindingMapper       scene_action_binding 只读 Mapper
     * @param executionMapper     action_execution 写 Mapper
     * @return ActionDispatchService 实例
     */
    @Bean
    public ActionDispatchService actionDispatchService(
            @Autowired(required = false) List<ActionHandler> actionHandlers,
            SceneActionBindingReadMapper bindingMapper,
            ActionExecutionMapper executionMapper) {
        Map<String, ActionHandler> handlerMap = new HashMap<>();
        if (actionHandlers != null) {
            for (ActionHandler handler : actionHandlers) {
                ActionType ann = handler.getClass().getAnnotation(ActionType.class);
                if (ann != null) {
                    handlerMap.put(ann.value(), handler);
                }
            }
        }
        return new ActionDispatchService(handlerMap, bindingMapper, executionMapper);
    }
}
```

- [ ] **Step 6: 运行 rule-eval-svc 全量测试**

```bash
$MVN -pl rule-eval-svc -am test
```

期望：BUILD SUCCESS，全部通过

- [ ] **Step 7: 提交**

```bash
git add rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/EvalAutoConfiguration.java \
        rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/service/EvalServiceImpl.java \
        rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/service/EvalServiceImplTest.java
git commit -m "feat(eval): EvalAutoConfiguration 注册 ActionDispatchService + EvalServiceImpl action dispatch 整合"
```

---

## Task 9: 全量测试验证

- [ ] **Step 1: 运行所有受影响模块的全量测试**

```bash
$MVN -pl rule-kernel,rule-observability,rule-eval-svc -am test
```

期望：BUILD SUCCESS，全部通过（无跳过、无失败）

- [ ] **Step 2: 确认 Testcontainers 集成测试通过（如有）**

```bash
$MVN -pl rule-eval-svc -am test -Dgroups=integration
```

如无集成测试标记，此步骤跳过。

- [ ] **Step 3: 最终提交（如有未提交的改动）**

```bash
git status
# 确认无遗漏的改动未提交
```
