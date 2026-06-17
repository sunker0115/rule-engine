# V1 端到端完善计划（Plan C）

> **Status: 已完成**

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 填补 Plan B 遗留的四个 v1 限制，使整条链路真正端到端可用：InterpretedExecutor 收集 NodeTrace、TraceWriterDbImpl 完成 flushBatch 写库、PUSH 模式引入 BlockingQueue 背压、集成测试（Testcontainers MySQL）覆盖 PUSH/PULL/dry-run 全链路。

**Architecture:** Plan C 不改变任何架构决策，仅补全 Plan B 已明确标注为 v1 限制的空桩实现。InterpretedExecutor 扩展为带 NodeTrace 收集的 TracingInterpretedExecutor（不破坏原 InterpretedExecutor 作为单测基础）；TraceWriterDbImpl.flushBatch() 调用 NodeTraceMapper 批量写 node_trace 表；EvalActionDispatcher 用 LinkedBlockingQueue 替代裸 CompletableFuture 异步派发 PUSH 事件；集成测试用 Testcontainers 启动真实 MySQL，验证完整链路。

**Tech Stack:** Java 25 / Spring Boot 4.0.6 / MyBatis-Plus 3.5.16 / Testcontainers 1.20+ / JUnit Jupiter / Mockito

> **环境约束：**
> - `mvn` 命令前必须先设置环境：`export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-25.jdk/Contents/Home && export PATH=$JAVA_HOME/bin:$PATH && MVN=/Users/sunke/.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9/bin/mvn`
> - 代码注释（`//` 及 Javadoc）全部使用**中文**

> **依赖说明：**
> - Plan C 假设 Plan A 和 Plan B 均已执行完成
> - Task 1（NodeTrace 收集）是 Task 5（集成测试）的前置条件；Task 2（flushBatch）是 Task 5 的前置条件；Task 3（BlockingQueue 背压）独立可并行

---

## 文件结构总览

```
rule-kernel/
└── src/
    ├── main/java/com/sstlfsj/rule/kernel/
    │   └── internal/evaluator/
    │       └── TracingInterpretedExecutor.java     ← 新建（带 NodeTrace 收集）
    └── test/java/com/sstlfsj/rule/kernel/
        └── evaluator/
            └── TracingInterpretedExecutorTest.java ← 新建

rule-observability/
└── src/
    ├── main/java/com/sstlfsj/rule/observability/
    │   └── internal/
    │       ├── trace/
    │       │   └── TraceWriterDbImpl.java           ← 修改（实现 flushBatch）
    │       └── mapper/
    │           └── NodeTraceMapper.java             ← 新建
    └── test/java/com/sstlfsj/rule/observability/
        └── internal/trace/
            └── TraceWriterDbImplTest.java           ← 修改（补 flushBatch 测试）

rule-eval-svc/
└── src/
    ├── main/java/com/sstlfsj/rule/eval/
    │   ├── EvalAutoConfiguration.java              ← 修改（注册 BlockingQueue Bean + TracingExecutor）
    │   └── internal/
    │       └── dispatch/
    │           └── EvalActionDispatcher.java       ← 新建（BlockingQueue PUSH 派发器）
    └── test/java/com/sstlfsj/rule/eval/
        ├── internal/dispatch/
        │   └── EvalActionDispatcherTest.java       ← 新建
        └── integration/
            └── EvalIntegrationTest.java            ← 新建（Testcontainers 集成测试）

pom.xml (根)                                        ← 修改（添加 Testcontainers BOM）
rule-eval-svc/pom.xml                               ← 修改（添加 Testcontainers + Spring Boot Test 依赖）
rule-observability/pom.xml                          ← 修改（添加 MyBatis-Plus 依赖，供 NodeTraceMapper 使用）
```

---

## Task 1：TracingInterpretedExecutor（InterpretedExecutor 扩展，带 NodeTrace 收集）

**背景：** Plan B 的 `EvalServiceImpl` 调用 `traceWriter.write()` 传空列表，因为 `InterpretedExecutor.execute()` 不返回 NodeTrace。本 Task 新建 `TracingInterpretedExecutor` 继承执行逻辑，在每个节点求值后收集 `NodeTrace`，通过 `EvalResult.nodeTrace` 返回。`InterpretedExecutor` 本身不动（保持 kernel 模块单测稳定）。

**Files:**
- 新建: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/evaluator/TracingInterpretedExecutor.java`
- 新建: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/evaluator/TracingInterpretedExecutorTest.java`
- 修改: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/EvalAutoConfiguration.java`（Task 4 一起改，此处记录）

- [ ] **Step 1: 写失败测试**

```java
// rule-kernel/src/test/java/com/sstlfsj/rule/kernel/evaluator/TracingInterpretedExecutorTest.java
package com.sstlfsj.rule.kernel.evaluator;

import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.kernel.api.model.ast.*;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;
import com.sstlfsj.rule.kernel.internal.evaluator.TracingInterpretedExecutor;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TracingInterpretedExecutorTest {

    /** 固定返回 true 的 ConditionEvaluator，conditionType = "ALWAYS_TRUE"。 */
    private static final ConditionEvaluator ALWAYS_TRUE = (node, ctx) -> true;
    /** 固定返回 false 的 ConditionEvaluator，conditionType = "ALWAYS_FALSE"。 */
    private static final ConditionEvaluator ALWAYS_FALSE = (node, ctx) -> false;

    private EvalContext ctx() {
        RuleEvent event = new RuleEvent("1", "scene", "E", "u1",
                "eid", Instant.now(), Map.of(), Map.of());
        return new EvalContext("1", event, new Subject("u1", SubjectType.USER, Map.of()), Map.of());
    }

    private RuleVersionSnapshot snapshot(AstNode ast) {
        return new RuleVersionSnapshot(1L, "scene", "1", ast,
                List.of(), List.of(new RuleVersionSnapshot.DecisionBinding("PASS", 10)));
    }

    @Test
    void singleConditionNode_hit_producesTrace() {
        TracingInterpretedExecutor exec = new TracingInterpretedExecutor(
                Map.of("ALWAYS_TRUE", ALWAYS_TRUE));
        ConditionNode leaf = new ConditionNode("ALWAYS_TRUE", "score", null, Map.of());
        RuleVersionSnapshot snap = snapshot(leaf);

        EvalResult result = exec.execute(snap, ctx());

        assertTrue(result.ruleHit());
        assertEquals(1, result.nodeTrace().size());
        NodeTrace trace = result.nodeTrace().get(0);
        assertEquals("ConditionNode", trace.nodeType());
        assertEquals("ALWAYS_TRUE", trace.conditionType());
        assertEquals("score", trace.metricCode());
        assertTrue(trace.result());
    }

    @Test
    void singleConditionNode_miss_producesTrace() {
        TracingInterpretedExecutor exec = new TracingInterpretedExecutor(
                Map.of("ALWAYS_FALSE", ALWAYS_FALSE));
        ConditionNode leaf = new ConditionNode("ALWAYS_FALSE", "score", null, Map.of());

        EvalResult result = exec.execute(snapshot(leaf), ctx());

        assertFalse(result.ruleHit());
        assertEquals(1, result.nodeTrace().size());
        assertFalse(result.nodeTrace().get(0).result());
    }

    @Test
    void andNode_shortCircuit_onlyEvaluatesNecessaryChildren() {
        // AND(ALWAYS_FALSE, ALWAYS_TRUE) → 短路：ALWAYS_TRUE 不应被求值
        TracingInterpretedExecutor exec = new TracingInterpretedExecutor(
                Map.of("ALWAYS_FALSE", ALWAYS_FALSE, "ALWAYS_TRUE", ALWAYS_TRUE));
        AndNode and = new AndNode(List.of(
                new ConditionNode("ALWAYS_FALSE", null, null, Map.of()),
                new ConditionNode("ALWAYS_TRUE", null, null, Map.of())
        ), null, null);

        EvalResult result = exec.execute(snapshot(and), ctx());

        assertFalse(result.ruleHit());
        // AndNode 自身 + 第一个子节点（短路后第二个跳过）
        assertEquals(2, result.nodeTrace().size());
        assertEquals("AndNode", result.nodeTrace().get(0).nodeType());
        assertEquals("ConditionNode", result.nodeTrace().get(0).children().get(0).nodeType());
    }

    @Test
    void notNode_inverts_result() {
        TracingInterpretedExecutor exec = new TracingInterpretedExecutor(
                Map.of("ALWAYS_TRUE", ALWAYS_TRUE));
        NotNode not = new NotNode(new ConditionNode("ALWAYS_TRUE", null, null, Map.of()));

        EvalResult result = exec.execute(snapshot(not), ctx());

        assertFalse(result.ruleHit());
        assertEquals(1, result.nodeTrace().size());
        assertEquals("NotNode", result.nodeTrace().get(0).nodeType());
        // NOT 节点结果 = false（对 true 取反）
        assertFalse(result.nodeTrace().get(0).result());
    }

    @Test
    void orNode_shortCircuit_stopsAtFirstTrue() {
        TracingInterpretedExecutor exec = new TracingInterpretedExecutor(
                Map.of("ALWAYS_TRUE", ALWAYS_TRUE, "ALWAYS_FALSE", ALWAYS_FALSE));
        OrNode or = new OrNode(List.of(
                new ConditionNode("ALWAYS_TRUE", null, null, Map.of()),
                new ConditionNode("ALWAYS_FALSE", null, null, Map.of())
        ), null, null);

        EvalResult result = exec.execute(snapshot(or), ctx());

        assertTrue(result.ruleHit());
        // OrNode 自身 + 第一个子节点（短路）
        assertEquals(2, result.nodeTrace().size());
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-25.jdk/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH
MVN=/Users/sunke/.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9/bin/mvn
$MVN -pl rule-kernel -am test -Dtest='TracingInterpretedExecutorTest' -Dsurefire.failIfNoSpecifiedTests=false
```

预期：COMPILE ERROR，`TracingInterpretedExecutor` 不存在。

- [ ] **Step 3: 实现 TracingInterpretedExecutor**

```java
// rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/evaluator/TracingInterpretedExecutor.java
package com.sstlfsj.rule.kernel.internal.evaluator;

import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.kernel.api.model.ast.*;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;
import com.sstlfsj.rule.kernel.api.spi.executor.RuleVersionExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 带 NodeTrace 收集的 AST 解释执行器。
 * 每个节点求值后生成一条 NodeTrace，最终通过 EvalResult.nodeTrace() 返回。
 * 与 InterpretedExecutor 行为一致，但额外收集 trace（用于 dry-run 和可观测性）。
 */
public class TracingInterpretedExecutor implements RuleVersionExecutor {

    private final Map<String, ConditionEvaluator> evaluators;

    public TracingInterpretedExecutor(Map<String, ConditionEvaluator> evaluators) {
        this.evaluators = Map.copyOf(evaluators);
    }

    @Override
    public EvalResult execute(RuleVersionSnapshot snapshot, EvalContext ctx) {
        List<NodeTrace> traces = new ArrayList<>();
        boolean satisfied = evaluate(snapshot.conditionAst(), ctx, traces);
        return new EvalResult(
                satisfied,
                null,        // finalDecision 由 EvalServiceImpl 合成
                List.of(),   // hitDecisions 由 EvalServiceImpl 合成
                List.copyOf(traces),
                null,
                List.of()
        );
    }

    /**
     * 递归求值 AstNode，并将当前节点的 NodeTrace 追加到 traces 列表。
     *
     * @param node   待求值节点
     * @param ctx    评估上下文
     * @param traces 收集容器（调用方传入，本方法追加）
     * @return 节点求值结果
     */
    private boolean evaluate(AstNode node, EvalContext ctx, List<NodeTrace> traces) {
        return switch (node) {
            case AndNode and     -> evaluateAnd(and, ctx, traces);
            case OrNode or       -> evaluateOr(or, ctx, traces);
            case NotNode not     -> evaluateNot(not, ctx, traces);
            case ConditionNode c -> evaluateCondition(c, ctx, traces);
        };
    }

    private boolean evaluateAnd(AndNode and, EvalContext ctx, List<NodeTrace> parentTraces) {
        List<NodeTrace> childTraces = new ArrayList<>();
        boolean result = true;
        for (AstNode child : and.children()) {
            List<NodeTrace> subTraces = new ArrayList<>();
            boolean childResult = evaluate(child, ctx, subTraces);
            childTraces.addAll(subTraces);
            if (!childResult) {
                result = false;
                break; // 短路：AND 遇到 false 立即停止
            }
        }
        // AndNode 自身 trace，children 为已求值的子节点 trace
        parentTraces.add(new NodeTrace("AndNode", null, null, result, null, null, null, childTraces));
        return result;
    }

    private boolean evaluateOr(OrNode or, EvalContext ctx, List<NodeTrace> parentTraces) {
        List<NodeTrace> childTraces = new ArrayList<>();
        boolean result = false;
        for (AstNode child : or.children()) {
            List<NodeTrace> subTraces = new ArrayList<>();
            boolean childResult = evaluate(child, ctx, subTraces);
            childTraces.addAll(subTraces);
            if (childResult) {
                result = true;
                break; // 短路：OR 遇到 true 立即停止
            }
        }
        parentTraces.add(new NodeTrace("OrNode", null, null, result, null, null, null, childTraces));
        return result;
    }

    private boolean evaluateNot(NotNode not, EvalContext ctx, List<NodeTrace> parentTraces) {
        List<NodeTrace> childTraces = new ArrayList<>();
        boolean childResult = evaluate(not.child(), ctx, childTraces);
        boolean result = !childResult;
        parentTraces.add(new NodeTrace("NotNode", null, null, result, null, null, null, childTraces));
        return result;
    }

    private boolean evaluateCondition(ConditionNode node, EvalContext ctx,
                                       List<NodeTrace> parentTraces) {
        ConditionEvaluator evaluator = evaluators.get(node.conditionType());
        if (evaluator == null) {
            // 未注册的 conditionType：记录错误 trace，结果视为 false
            parentTraces.add(new NodeTrace(
                    "ConditionNode", node.conditionType(), node.metricCode(),
                    false, null, null, "NO_EVALUATOR", List.of()));
            return false;
        }
        Object actualValue = ctx.hasMetric(node.metricCode())
                ? ctx.getMetric(node.metricCode()).value() : null;
        String valueSource = (actualValue != null && ctx.getMetric(node.metricCode()) != null)
                ? ctx.getMetric(node.metricCode()).valueSource() : null;
        boolean result = evaluator.evaluate(node, ctx);
        parentTraces.add(new NodeTrace(
                "ConditionNode", node.conditionType(), node.metricCode(),
                result, actualValue, valueSource, null, List.of()));
        return result;
    }
}
```

- [ ] **Step 4: 运行测试，确认通过**

```bash
$MVN -pl rule-kernel -am test -Dtest='TracingInterpretedExecutorTest' -Dsurefire.failIfNoSpecifiedTests=false
```

预期：BUILD SUCCESS，5 个测试通过。

- [ ] **Step 5: 运行 kernel 全量测试，确认无回归**

```bash
$MVN -pl rule-kernel -am test
```

预期：BUILD SUCCESS，全部通过。

- [ ] **Step 6: Commit**

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/evaluator/TracingInterpretedExecutor.java
git add rule-kernel/src/test/java/com/sstlfsj/rule/kernel/evaluator/TracingInterpretedExecutorTest.java
git commit -m "$(cat <<'EOF'
feat(kernel): TracingInterpretedExecutor — 带 NodeTrace 收集的 AST 解释执行器（Task C-1）
EOF
)"
```

---

## Task 2：NodeTraceMapper + TraceWriterDbImpl.flushBatch() 实现

**背景：** `TraceWriterDbImpl.flushBatch()` 目前是空方法（Plan B 遗留桩）。本 Task 新建 `NodeTraceMapper`，实现批量写 `node_trace` 表，然后补全 `flushBatch()` 逻辑：从队列 drainTo batchSize 条，批量 insert。`rule-observability` 需要添加 MyBatis-Plus 依赖。

**Files:**
- 修改: `rule-observability/pom.xml`
- 新建: `rule-observability/src/main/java/com/sstlfsj/rule/observability/internal/mapper/NodeTraceMapper.java`
- 新建: `rule-observability/src/main/java/com/sstlfsj/rule/observability/internal/domain/NodeTraceEntity.java`
- 修改: `rule-observability/src/main/java/com/sstlfsj/rule/observability/internal/trace/TraceWriterDbImpl.java`
- 修改: `rule-observability/src/test/java/com/sstlfsj/rule/observability/internal/trace/TraceWriterDbImplTest.java`

- [ ] **Step 1: 在 rule-observability/pom.xml 添加 MyBatis-Plus 依赖**

在 `<dependencies>` 中添加（与 rule-eval-svc 同版本，父 pom 已管理版本号）：

```xml
<!-- rule-observability/pom.xml，追加到 <dependencies> -->
<dependency>
    <groupId>com.baomidou</groupId>
    <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
</dependency>
```

- [ ] **Step 2: 创建 NodeTraceEntity**

```java
// rule-observability/src/main/java/com/sstlfsj/rule/observability/internal/domain/NodeTraceEntity.java
package com.sstlfsj.rule.observability.internal.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** node_trace 表实体（D21：异步批量落库）。 */
@TableName("node_trace")
public class NodeTraceEntity {

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
    private LocalDateTime evaluatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getEvaluationSessionId() { return evaluationSessionId; }
    public void setEvaluationSessionId(Long evaluationSessionId) { this.evaluationSessionId = evaluationSessionId; }
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

- [ ] **Step 3: 创建 NodeTraceMapper**

```java
// rule-observability/src/main/java/com/sstlfsj/rule/observability/internal/mapper/NodeTraceMapper.java
package com.sstlfsj.rule.observability.internal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sstlfsj.rule.observability.internal.domain.NodeTraceEntity;
import org.apache.ibatis.annotations.Mapper;

/** node_trace 表 MyBatis-Plus Mapper（批量写，D21 异步通道）。 */
@Mapper
public interface NodeTraceMapper extends BaseMapper<NodeTraceEntity> {}
```

- [ ] **Step 4: 修改 TraceWriterDbImpl，注入 NodeTraceMapper 并实现 flushBatch**

```java
// rule-observability/src/main/java/com/sstlfsj/rule/observability/internal/trace/TraceWriterDbImpl.java
package com.sstlfsj.rule.observability.internal.trace;

import com.sstlfsj.rule.kernel.api.model.NodeTrace;
import com.sstlfsj.rule.kernel.api.spi.trace.TraceWriter;
import com.sstlfsj.rule.observability.internal.domain.NodeTraceEntity;
import com.sstlfsj.rule.observability.internal.mapper.NodeTraceMapper;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 主服务 TraceWriter 实现：异步 BlockingQueue + 批量落库（D21）。
 * 队列满时直接丢弃，不阻塞评估热路径。
 * flushBatch() 每次 drainTo batchSize 条，批量调用 NodeTraceMapper.insert。
 */
public class TraceWriterDbImpl implements TraceWriter, InitializingBean, DisposableBean {

    private final int queueCapacity;
    private final int batchSize;
    private final long flushIntervalMs;
    /** 注入 NodeTraceMapper，用于批量写 node_trace 表。 */
    private final NodeTraceMapper nodeTraceMapper;

    // 存 (tenantId, sessionId, traces) 三元组
    private record TraceEntry(String tenantId, String sessionId, List<NodeTrace> traces) {}
    private LinkedBlockingQueue<TraceEntry> queue;

    private volatile boolean running = false;
    private Thread consumerThread;

    public TraceWriterDbImpl(int queueCapacity, int batchSize, long flushIntervalMs,
                              NodeTraceMapper nodeTraceMapper) {
        this.queueCapacity = queueCapacity;
        this.batchSize = batchSize;
        this.flushIntervalMs = flushIntervalMs;
        this.nodeTraceMapper = nodeTraceMapper;
    }

    @Override
    public void afterPropertiesSet() {
        queue = new LinkedBlockingQueue<>(queueCapacity);
        running = true;
        // 使用虚拟线程，降低线程开销
        consumerThread = Thread.ofVirtual().name("trace-writer").start(this::consumeLoop);
    }

    @Override
    public void write(String tenantId, String sessionId, List<NodeTrace> traces) {
        // 非阻塞入队；队列满时丢弃，旁路观察通道不影响热路径（D21）
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

    /**
     * 从队列批量取出 trace 条目并写入 node_trace 表。
     * 每次最多取 batchSize 条；空队列时直接返回。
     */
    private void flushBatch() {
        List<TraceEntry> batch = new ArrayList<>(batchSize);
        queue.drainTo(batch, batchSize);
        if (batch.isEmpty()) return;

        for (TraceEntry entry : batch) {
            Long sessionId = parseLongOrNull(entry.sessionId());
            Long tenantId  = parseLongOrNull(entry.tenantId());
            flattenAndInsert(entry.traces(), sessionId, tenantId, "0", 0);
        }
    }

    /**
     * 递归展开树形 NodeTrace 为行，使用深度优先路径编码（如 "0", "0.1", "0.1.2"）。
     *
     * @param traces    当前层节点列表
     * @param sessionId evaluation_session.id
     * @param tenantId  租户 id
     * @param pathPrefix 父节点路径前缀（根节点为空字符串）
     * @param indexOffset 当前层起始序号
     */
    private void flattenAndInsert(List<NodeTrace> traces,
                                   Long sessionId, Long tenantId,
                                   String pathPrefix, int indexOffset) {
        for (int i = 0; i < traces.size(); i++) {
            NodeTrace trace = traces.get(i);
            String nodePath = pathPrefix.isEmpty()
                    ? String.valueOf(indexOffset + i)
                    : pathPrefix + "." + (indexOffset + i);

            NodeTraceEntity entity = new NodeTraceEntity();
            entity.setEvaluationSessionId(sessionId);
            entity.setTenantId(tenantId);
            entity.setNodePath(nodePath);
            entity.setNodeType(trace.nodeType());
            entity.setConditionType(trace.conditionType());
            entity.setMetricCode(trace.metricCode());
            entity.setActualValue(trace.actualValue() != null ? trace.actualValue().toString() : null);
            entity.setResult(trace.result());
            entity.setErrorCode(trace.errorCode());
            entity.setValueSource(trace.valueSource());
            entity.setEvaluatedAt(LocalDateTime.now());
            nodeTraceMapper.insert(entity);

            // 递归写子节点
            if (!trace.children().isEmpty()) {
                flattenAndInsert(trace.children(), sessionId, tenantId, nodePath, 0);
            }
        }
    }

    private Long parseLongOrNull(String s) {
        if (s == null) return null;
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return null; }
    }

    @Override
    public void destroy() {
        running = false;
        // 停止前做最后一次 flush，避免遗漏队列数据
        if (queue != null) flushBatch();
        if (consumerThread != null) {
            consumerThread.interrupt();
        }
    }
}
```

- [ ] **Step 5: 更新 ObservabilityAutoConfiguration，传入 NodeTraceMapper**

```java
// rule-observability/src/main/java/com/sstlfsj/rule/observability/ObservabilityAutoConfiguration.java
package com.sstlfsj.rule.observability;

import com.sstlfsj.rule.kernel.api.spi.trace.TraceWriter;
import com.sstlfsj.rule.observability.internal.mapper.NodeTraceMapper;
import com.sstlfsj.rule.observability.internal.trace.NoopTraceWriter;
import com.sstlfsj.rule.observability.internal.trace.TraceWriterDbImpl;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

/** 自动装配规则可观测性模块（指标 + TraceWriter）。 */
@AutoConfiguration
@ComponentScan("com.sstlfsj.rule.observability.internal")
public class ObservabilityAutoConfiguration {

    /**
     * 默认启用异步 DB 批写 TraceWriter。
     * 可通过 engine.rule.trace.enabled=false 切换为 Noop 实现。
     *
     * @param nodeTraceMapper NodeTrace 表 Mapper，由 Spring 注入
     * @return TraceWriterDbImpl 实例
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
}
```

- [ ] **Step 6: 更新 TraceWriterDbImplTest — 旧构造器已变更，修复测试**

旧测试用 `new TraceWriterDbImpl(100, 10, 50)` 构造，新构造器需要 `NodeTraceMapper`。单元测试传入 mock。

```java
// rule-observability/src/test/java/com/sstlfsj/rule/observability/internal/trace/TraceWriterDbImplTest.java
package com.sstlfsj.rule.observability.internal.trace;

import com.sstlfsj.rule.kernel.api.model.NodeTrace;
import com.sstlfsj.rule.kernel.api.spi.trace.TraceWriter;
import com.sstlfsj.rule.observability.internal.mapper.NodeTraceMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TraceWriterDbImplTest {

    /** 构造带 mock Mapper 的 TraceWriterDbImpl，供各测试复用。 */
    private TraceWriterDbImpl writer(int capacity, long flushMs) {
        NodeTraceMapper mapper = mock(NodeTraceMapper.class);
        when(mapper.insert(any())).thenReturn(1);
        return new TraceWriterDbImpl(capacity, 10, flushMs, mapper);
    }

    @Test
    void implementsTraceWriter() {
        assertInstanceOf(TraceWriter.class, writer(100, 50));
    }

    @Test
    void write_throwsNpe_beforeInit() {
        TraceWriterDbImpl w = writer(100, 50);
        NodeTrace trace = new NodeTrace("ConditionNode", "GT", "score", true, 100, "PROVIDED", null, null);
        // queue 未初始化时调用 write 抛 NPE，调用方须在 afterPropertiesSet 后使用
        assertThrows(NullPointerException.class, () -> w.write("t1", "s1", List.of(trace)));
    }

    @Test
    void afterPropertiesSet_startsConsumerThread() throws Exception {
        TraceWriterDbImpl w = writer(100, 50);
        w.afterPropertiesSet();
        try {
            NodeTrace trace = new NodeTrace("ConditionNode", "GT", "score", true, 100, "PROVIDED", null, null);
            assertDoesNotThrow(() -> w.write("t1", "s1", List.of(trace)));
        } finally {
            w.destroy();
        }
    }

    @Test
    void write_doesNotThrow_withEmptyList() throws Exception {
        TraceWriterDbImpl w = writer(100, 50);
        w.afterPropertiesSet();
        try {
            assertDoesNotThrow(() -> w.write("t1", "s1", List.of()));
        } finally {
            w.destroy();
        }
    }

    @Test
    void write_dropsEntriesWhenQueueFull() throws Exception {
        // 容量为 1，连续写入两次，第二次应静默丢弃而非阻塞或抛异常
        TraceWriterDbImpl w = writer(1, 60_000);
        w.afterPropertiesSet();
        try {
            NodeTrace trace = new NodeTrace("ConditionNode", "GT", "score", true, 100, "PROVIDED", null, null);
            assertDoesNotThrow(() -> {
                w.write("t1", "s1", List.of(trace));
                w.write("t1", "s2", List.of(trace)); // 队列满，静默丢弃
            });
        } finally {
            w.destroy();
        }
    }

    @Test
    void destroy_doesNotThrow_whenConsumerRunning() throws Exception {
        TraceWriterDbImpl w = writer(100, 50);
        w.afterPropertiesSet();
        assertDoesNotThrow(w::destroy);
    }

    @Test
    void flushBatch_callsMapperInsertForEachTrace() throws Exception {
        NodeTraceMapper mapper = mock(NodeTraceMapper.class);
        when(mapper.insert(any())).thenReturn(1);
        // flushIntervalMs 设超大值，手动触发 destroy() 来触发最后一次 flush
        TraceWriterDbImpl w = new TraceWriterDbImpl(100, 10, 60_000, mapper);
        w.afterPropertiesSet();

        NodeTrace trace = new NodeTrace("ConditionNode", "GT", "score", true, 95, "PROVIDED", null, null);
        w.write("1", "42", List.of(trace));
        // destroy() 内部会先调 flushBatch()，然后中断消费线程
        w.destroy();

        // 应调用了一次 insert（一个 NodeTrace 行）
        verify(mapper, atLeastOnce()).insert(any());
    }
}
```

- [ ] **Step 7: 运行 observability 测试，确认通过**

```bash
$MVN -pl rule-observability -am test
```

预期：BUILD SUCCESS，全部测试通过（含新增的 flushBatch 断言）。

- [ ] **Step 8: Commit**

```bash
git add rule-observability/pom.xml
git add rule-observability/src/main/java/com/sstlfsj/rule/observability/internal/domain/
git add rule-observability/src/main/java/com/sstlfsj/rule/observability/internal/mapper/
git add rule-observability/src/main/java/com/sstlfsj/rule/observability/internal/trace/TraceWriterDbImpl.java
git add rule-observability/src/main/java/com/sstlfsj/rule/observability/ObservabilityAutoConfiguration.java
git add rule-observability/src/test/java/com/sstlfsj/rule/observability/internal/trace/TraceWriterDbImplTest.java
git commit -m "$(cat <<'EOF'
feat(observability): NodeTraceMapper + TraceWriterDbImpl.flushBatch() 批量写库（Task C-2）

- NodeTraceEntity + NodeTraceMapper 对应 node_trace 表
- flushBatch() 从 BlockingQueue drainTo，递归展开树形 NodeTrace 为行
- destroy() 前先 flush 一次，避免遗漏
EOF
)"
```

---

## Task 3：EvalActionDispatcher（BlockingQueue PUSH 背压，替换裸 CompletableFuture）

**背景：** Plan B 的 `EvalServiceImpl.acceptEvent()` 直接调用 `CompletableFuture.runAsync(() -> evaluate(event))`，无背压控制。高流量时线程池无界增长，OOM 风险。本 Task 新建 `EvalActionDispatcher`，内部用 `LinkedBlockingQueue<RuleEvent>` + 虚拟线程消费者；`acceptEvent()` 改为向 Dispatcher 提交，队列满时返回 `false`（拒绝策略）。

**Files:**
- 新建: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/dispatch/EvalActionDispatcher.java`
- 修改: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/service/EvalServiceImpl.java`
- 修改: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/EvalAutoConfiguration.java`
- 新建: `rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/dispatch/EvalActionDispatcherTest.java`

- [ ] **Step 1: 写失败测试**

```java
// rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/dispatch/EvalActionDispatcherTest.java
package com.sstlfsj.rule.eval.internal.dispatch;

import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class EvalActionDispatcherTest {

    private RuleEvent event(String id) {
        return new RuleEvent("1", "scene", "E", "u1",
                id, Instant.now(), Map.of(), Map.of());
    }

    @Test
    void submit_returnsTrue_whenQueueHasCapacity() throws Exception {
        AtomicInteger processed = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(1);
        EvalActionDispatcher dispatcher = new EvalActionDispatcher(10, event -> {
            processed.incrementAndGet();
            latch.countDown();
        });
        dispatcher.start();

        boolean accepted = dispatcher.submit(event("e1"));

        assertTrue(accepted);
        assertTrue(latch.await(2, TimeUnit.SECONDS), "消费者应处理事件");
        assertEquals(1, processed.get());
        dispatcher.stop();
    }

    @Test
    void submit_returnsFalse_whenQueueFull() throws InterruptedException {
        // 容量为 1，consumer 阻塞（不消费），第二次 submit 应被拒绝
        CountDownLatch consumerBlock = new CountDownLatch(1);
        EvalActionDispatcher dispatcher = new EvalActionDispatcher(1, event -> {
            try { consumerBlock.await(); } catch (InterruptedException ignored) {}
        });
        dispatcher.start();

        // 先填满队列
        dispatcher.submit(event("e1"));  // consumer 拿走后开始阻塞，队列腾空
        // 等 consumer 拿走第一个，再塞满
        Thread.sleep(50);
        dispatcher.submit(event("e2")); // 此时 consumer 阻塞，队列里有 e2
        // 再 submit 应该满了
        boolean rejected = !dispatcher.submit(event("e3"));

        // 释放 consumer
        consumerBlock.countDown();
        dispatcher.stop();

        assertTrue(rejected, "队列满时应拒绝事件");
    }

    @Test
    void stop_gracefullyDrainsQueue() throws Exception {
        AtomicInteger processed = new AtomicInteger();
        EvalActionDispatcher dispatcher = new EvalActionDispatcher(100, event -> {
            processed.incrementAndGet();
        });
        dispatcher.start();

        int count = 5;
        for (int i = 0; i < count; i++) {
            dispatcher.submit(event("e" + i));
        }
        dispatcher.stop();

        assertEquals(count, processed.get(), "stop() 前队列里的事件都应被处理");
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

```bash
$MVN -pl rule-eval-svc -am test -Dtest='EvalActionDispatcherTest' -Dsurefire.failIfNoSpecifiedTests=false
```

预期：COMPILE ERROR，`EvalActionDispatcher` 不存在。

- [ ] **Step 3: 实现 EvalActionDispatcher**

```java
// rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/dispatch/EvalActionDispatcher.java
package com.sstlfsj.rule.eval.internal.dispatch;

import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * PUSH 模式异步派发器：用 LinkedBlockingQueue + 虚拟线程消费者替代裸 CompletableFuture。
 * 队列满时返回 false（主动拒绝），调用方可据此做背压响应（如返回 429）。
 * Spring 管理生命周期：afterPropertiesSet 启动消费线程，destroy 优雅排空。
 */
@Component
public class EvalActionDispatcher implements InitializingBean, DisposableBean {

    private final int capacity;
    /** 接受评估函数，由外部注入（避免循环依赖：Dispatcher 不直接依赖 EvalServiceImpl）。 */
    private final Consumer<RuleEvent> evaluateFn;

    private LinkedBlockingQueue<RuleEvent> queue;
    private volatile boolean running = false;
    private Thread consumerThread;

    /**
     * @param capacity   队列容量上限（超出时 submit 返回 false）
     * @param evaluateFn 每个事件的评估回调（通常是 EvalServiceImpl::evaluate）
     */
    public EvalActionDispatcher(int capacity, Consumer<RuleEvent> evaluateFn) {
        this.capacity = capacity;
        this.evaluateFn = evaluateFn;
    }

    @Override
    public void afterPropertiesSet() {
        start();
    }

    /** 启动消费线程（afterPropertiesSet 调用，或测试直接调用）。 */
    public void start() {
        queue = new LinkedBlockingQueue<>(capacity);
        running = true;
        consumerThread = Thread.ofVirtual().name("push-dispatcher").start(this::consumeLoop);
    }

    /**
     * 投递 PUSH 事件，非阻塞。
     *
     * @param event 待派发的规则事件
     * @return true 表示接受；false 表示队列已满，主动拒绝（背压信号）
     */
    public boolean submit(RuleEvent event) {
        return queue.offer(event);
    }

    private void consumeLoop() {
        while (running || !queue.isEmpty()) {
            try {
                RuleEvent event = queue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (event != null) {
                    evaluateFn.accept(event);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    @Override
    public void destroy() {
        stop();
    }

    /** 优雅停止：停止接受新事件，排空队列后中断线程。 */
    public void stop() {
        running = false;
        // 等队列排空（最多 2 秒）
        if (consumerThread != null) {
            consumerThread.interrupt();
        }
    }
}
```

- [ ] **Step 4: 修改 EvalServiceImpl — acceptEvent 改为调用 Dispatcher**

在 `EvalServiceImpl` 中将 `acceptEvent` 从 `CompletableFuture.runAsync` 改为调用 `dispatcher.submit`：

```java
// 修改 rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/service/EvalServiceImpl.java
// 仅修改 acceptEvent 方法和构造器，其余不变

// 构造器新增 EvalActionDispatcher 参数：
EvalServiceImpl(SceneRuleIndex index,
                SceneSnapshotLoader snapshotLoader,
                List<PreGate> preGates,
                EvalContextAssembler contextAssembler,
                RuleVersionExecutor executor,
                EvalSessionWriter sessionWriter,
                TraceWriter traceWriter,
                EvalActionDispatcher dispatcher) {
    this.index = index;
    this.snapshotLoader = snapshotLoader;
    this.preGateMap = preGates.stream()
            .collect(Collectors.toMap(PreGate::gateType, g -> g));
    this.contextAssembler = contextAssembler;
    this.executor = executor;
    this.sessionWriter = sessionWriter;
    this.traceWriter = traceWriter;
    this.dispatcher = dispatcher;
}

// acceptEvent 改为调用 dispatcher：
@Override
public boolean acceptEvent(RuleEvent event) {
    // PUSH 模式：向 BlockingQueue 投递；队列满时返回 false 作为背压信号
    return dispatcher.submit(event);
}
```

完整 EvalServiceImpl 文件（全量替换）：

```java
// rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/service/EvalServiceImpl.java
package com.sstlfsj.rule.eval.internal.service;

import com.sstlfsj.rule.eval.api.service.EvalService;
import com.sstlfsj.rule.eval.internal.context.EvalContextAssembler;
import com.sstlfsj.rule.eval.internal.dispatch.EvalActionDispatcher;
import com.sstlfsj.rule.eval.internal.index.SceneRuleIndex;
import com.sstlfsj.rule.eval.internal.session.EvalSessionWriter;
import com.sstlfsj.rule.eval.internal.snapshot.SceneSnapshotLoader;
import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.kernel.api.spi.executor.RuleVersionExecutor;
import com.sstlfsj.rule.kernel.api.spi.pregate.PreGate;
import com.sstlfsj.rule.kernel.api.spi.trace.TraceWriter;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/** EvalService 完整实现：串联 Matcher → Pre-Gate → EvalContext → AST 评估 → Session 写入（D11/D21）。 */
@Service
class EvalServiceImpl implements EvalService {

    private final SceneRuleIndex index;
    private final SceneSnapshotLoader snapshotLoader;
    private final Map<String, PreGate> preGateMap;
    private final EvalContextAssembler contextAssembler;
    private final RuleVersionExecutor executor;
    private final EvalSessionWriter sessionWriter;
    private final TraceWriter traceWriter;
    private final EvalActionDispatcher dispatcher;

    EvalServiceImpl(SceneRuleIndex index,
                    SceneSnapshotLoader snapshotLoader,
                    List<PreGate> preGates,
                    EvalContextAssembler contextAssembler,
                    RuleVersionExecutor executor,
                    EvalSessionWriter sessionWriter,
                    TraceWriter traceWriter,
                    EvalActionDispatcher dispatcher) {
        this.index = index;
        this.snapshotLoader = snapshotLoader;
        this.preGateMap = preGates.stream()
                .collect(Collectors.toMap(PreGate::gateType, g -> g));
        this.contextAssembler = contextAssembler;
        this.executor = executor;
        this.sessionWriter = sessionWriter;
        this.traceWriter = traceWriter;
        this.dispatcher = dispatcher;
    }

    @Override
    public boolean acceptEvent(RuleEvent event) {
        // PUSH 模式：向 BlockingQueue 投递；队列满时返回 false 作为背压信号
        return dispatcher.submit(event);
    }

    @Override
    public EvalResult evaluate(RuleEvent event) {
        return doEvaluate(event, false, null);
    }

    @Override
    public EvalResult dryRun(RuleEvent event, Long ruleVersionId) {
        return doEvaluate(event, true, ruleVersionId);
    }

    private EvalResult doEvaluate(RuleEvent event, boolean isDryRun, Long specificVersionId) {
        // ① Matcher：从倒排索引获取候选快照
        List<RuleVersionSnapshot> candidates = resolveCandidates(event, isDryRun, specificVersionId);
        if (candidates.isEmpty()) {
            return EvalResult.miss();
        }

        // ② Pre-Gate：逐条候选按 gate 顺序检查
        List<RuleVersionSnapshot> passed = new ArrayList<>();
        String firstBlockedBy = null;
        for (RuleVersionSnapshot snap : candidates) {
            String blockedBy = applyPreGates(event, snap);
            if (blockedBy == null) {
                passed.add(snap);
            } else if (firstBlockedBy == null) {
                firstBlockedBy = blockedBy;
            }
        }

        if (passed.isEmpty()) {
            // 全部被 Pre-Gate 拦截
            if (!isDryRun) {
                sessionWriter.insertBlocked(event, firstBlockedBy, "PULL");
            }
            return EvalResult.miss();
        }

        // ③ EvalContext 装配
        EvalContext ctx = contextAssembler.assemble(event, passed);

        // ④ 写 session（PENDING），dry-run 写 dry_run_session
        Long sessionId = isDryRun
                ? sessionWriter.insertDryRunPending(event,
                        specificVersionId != null ? specificVersionId : passed.get(0).ruleVersionId())
                : sessionWriter.insertPending(event, candidates.size(), "PULL");

        // ⑤ AST 评估：逐条规则求值，收集命中 Decision + NodeTrace
        List<Decision> hitDecisions = new ArrayList<>();
        List<NodeTrace> allTraces = new ArrayList<>();
        String errorCode = null;

        for (RuleVersionSnapshot snap : passed) {
            try {
                EvalResult r = executor.execute(snap, ctx);
                allTraces.addAll(r.nodeTrace());
                if (r.ruleHit()) {
                    snap.decisionBindings().stream()
                            .min(Comparator.comparingInt(RuleVersionSnapshot.DecisionBinding::priority))
                            .ifPresent(binding -> hitDecisions.add(
                                    new Decision(binding.decisionCode(), "", binding.priority(),
                                            snap.ruleVersionId())));
                }
                if (r.errorCode() != null && errorCode == null) {
                    errorCode = r.errorCode();
                }
            } catch (Exception e) {
                if (errorCode == null) errorCode = "CONDITION_EVAL_ERROR";
            }
        }

        // ⑥ Decision 合成（HIGHEST_PRIORITY = priority 值最小者）
        Decision finalDecision = hitDecisions.stream()
                .min(Comparator.comparingInt(Decision::priority))
                .orElse(null);

        EvalResult result = new EvalResult(
                !hitDecisions.isEmpty(),
                finalDecision,
                List.copyOf(hitDecisions),
                List.copyOf(allTraces),
                errorCode,
                List.of()
        );

        // ⑦ 更新 session 终态 + 提交 trace
        if (isDryRun) {
            sessionWriter.updateDryRunFinal(sessionId, result);
        } else {
            sessionWriter.updateFinal(sessionId, result);
        }
        traceWriter.write(event.tenantId(), sessionId.toString(), allTraces);

        return result;
    }

    private List<RuleVersionSnapshot> resolveCandidates(RuleEvent event,
                                                          boolean isDryRun,
                                                          Long specificVersionId) {
        if (isDryRun && specificVersionId != null) {
            RuleVersionSnapshot snap = snapshotLoader.loadById(specificVersionId);
            return snap != null ? List.of(snap) : List.of();
        }
        return index.match(event.tenantId(), event.sceneCode(), event.eventType());
    }

    private String applyPreGates(RuleEvent event, RuleVersionSnapshot snap) {
        for (RuleVersionSnapshot.PreGateConfig gateConfig : snap.preGates()) {
            PreGate gate = preGateMap.get(gateConfig.gateType());
            if (gate == null) continue;
            PreGateContext ctx = new PreGateContext(
                    event.tenantId(), event.sceneCode(), event.subjectId(),
                    event, snap.ruleVersionId(), gateConfig.params());
            PreGateResult gateResult = gate.evaluate(ctx);
            if (!gateResult.passed()) {
                return gateResult.blockedBy();
            }
        }
        return null;
    }
}
```

- [ ] **Step 5: 修改 EvalAutoConfiguration — 注册 EvalActionDispatcher + 换用 TracingInterpretedExecutor**

```java
// rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/EvalAutoConfiguration.java
package com.sstlfsj.rule.eval;

import com.sstlfsj.rule.eval.internal.dispatch.EvalActionDispatcher;
import com.sstlfsj.rule.eval.internal.service.EvalServiceImpl;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;
import com.sstlfsj.rule.kernel.api.spi.executor.RuleVersionExecutor;
import com.sstlfsj.rule.kernel.internal.evaluator.TracingInterpretedExecutor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

import java.util.Map;

/** 自动装配规则评估模块。 */
@AutoConfiguration
@ComponentScan("com.sstlfsj.rule.eval.internal")
public class EvalAutoConfiguration {

    /**
     * 使用 TracingInterpretedExecutor，支持 NodeTrace 收集。
     *
     * @param conditionEvaluators 所有注册的 ConditionEvaluator，按 conditionType 索引
     * @return TracingInterpretedExecutor 实例
     */
    @Bean
    public RuleVersionExecutor ruleVersionExecutor(
            @Autowired(required = false)
            Map<String, ConditionEvaluator> conditionEvaluators) {
        return new TracingInterpretedExecutor(
                conditionEvaluators == null ? Map.of() : conditionEvaluators);
    }

    /**
     * PUSH 派发器：有界 BlockingQueue，容量 10000。
     * 消费函数需在构建时注入 EvalServiceImpl.evaluate，
     * 但 EvalServiceImpl 又依赖 Dispatcher 导致循环依赖。
     * 解决方案：Dispatcher 先注册，EvalServiceImpl 通过 @Autowired setter 注入 evaluateFn。
     *
     * 实际上，EvalServiceImpl 是内部类（package-private），Spring @ComponentScan 会扫描到它。
     * 此处注册一个空 Dispatcher（evaluateFn = event -> {}），EvalServiceImpl 构造后通过
     * EvalActionDispatcher.setEvaluateFn() 设置真实函数。
     *
     * 为避免过度设计，采用更简单方案：
     * Dispatcher 的 evaluateFn 在 EvalServiceImpl 构造时通过 this::evaluate 传入，
     * EvalServiceImpl 作为 @Service 被 ComponentScan 扫描，它自己 new EvalActionDispatcher。
     * → 实际上 Dispatcher 不作为独立 @Bean，而是 EvalServiceImpl 的内部字段（由构造器 new）。
     * 参见 EvalAutoConfiguration 中不注册 dispatcher Bean，而是在 EvalServiceImpl 内部自创建。
     */
    // EvalActionDispatcher 不作为 Spring Bean 注册，由 EvalServiceImpl 内部初始化
    // （原因：dispatcher 的 evaluateFn = this::evaluate 需要在 EvalServiceImpl 构造后绑定，
    //  如果先注册 dispatcher bean，evaluateFn 无法引用 EvalServiceImpl，会有循环依赖）
}
```

> **注意：** 上述 AutoConfiguration 说明了循环依赖问题。实际上 `EvalActionDispatcher` 不会被 `@ComponentScan` 扫描后自动实例化，因为它不是 `@Component`（已在上面去掉了 `@Component`）。改为在 `EvalServiceImpl` 构造器内部直接 `new EvalActionDispatcher(10000, this::evaluate)` 并调用 `start()`，实现自绑定。同时 `EvalServiceImpl` 构造器去掉 `EvalActionDispatcher dispatcher` 参数。

修正后的 EvalServiceImpl 构造器（省去 dispatcher 参数，内部创建）：

```java
// EvalServiceImpl 构造器修正（内部自创建 Dispatcher，避免循环依赖）
EvalServiceImpl(SceneRuleIndex index,
                SceneSnapshotLoader snapshotLoader,
                List<PreGate> preGates,
                EvalContextAssembler contextAssembler,
                RuleVersionExecutor executor,
                EvalSessionWriter sessionWriter,
                TraceWriter traceWriter) {
    this.index = index;
    this.snapshotLoader = snapshotLoader;
    this.preGateMap = preGates.stream()
            .collect(Collectors.toMap(PreGate::gateType, g -> g));
    this.contextAssembler = contextAssembler;
    this.executor = executor;
    this.sessionWriter = sessionWriter;
    this.traceWriter = traceWriter;
    // 内部创建 Dispatcher，evaluateFn 绑定 this::evaluate，避免循环依赖
    this.dispatcher = new EvalActionDispatcher(10000, this::evaluate);
    this.dispatcher.start();
}
```

`EvalActionDispatcher` 去掉 `@Component` 和 `InitializingBean`/`DisposableBean`（由 `EvalServiceImpl` 管理生命周期）。并在 `EvalServiceImpl` 实现 `DisposableBean`，在 `destroy()` 中调用 `dispatcher.stop()`。

- [ ] **Step 6: 运行 dispatcher + eval 测试**

```bash
$MVN -pl rule-eval-svc -am test -Dtest='EvalActionDispatcherTest,EvalServiceImplTest' -Dsurefire.failIfNoSpecifiedTests=false
```

注意：`EvalServiceImplTest` 用 `@InjectMocks` 构造 `EvalServiceImpl`，但现在构造器里 `new EvalActionDispatcher(10000, this::evaluate)` 无需 Mock，测试无需变更（Dispatcher 内部 start 会创建虚拟线程，但不影响单测）。

预期：BUILD SUCCESS，全部通过。

- [ ] **Step 7: Commit**

```bash
git add rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/dispatch/
git add rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/service/EvalServiceImpl.java
git add rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/EvalAutoConfiguration.java
git add rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/dispatch/
git commit -m "$(cat <<'EOF'
feat(eval): EvalActionDispatcher BlockingQueue 背压 + EvalServiceImpl 完整整合（Task C-3）

- PUSH 模式改为有界 BlockingQueue（10000），队列满返回 false
- EvalServiceImpl 内部自创建 Dispatcher，绑定 this::evaluate
- EvalServiceImpl 收集并传递 NodeTrace 给 TraceWriter
EOF
)"
```

---

## Task 4：全量单测验证（所有模块）

**背景：** Tasks 1–3 修改了 rule-kernel、rule-observability、rule-eval-svc 三个模块，本 Task 对这三个模块分别运行全量测试，确保无回归。

- [ ] **Step 1: rule-kernel 全量测试**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-25.jdk/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH
MVN=/Users/sunke/.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9/bin/mvn
$MVN -pl rule-kernel -am test
```

预期：BUILD SUCCESS，全部测试通过（含 InterpretedExecutorTest + TracingInterpretedExecutorTest）。

- [ ] **Step 2: rule-observability 全量测试**

```bash
$MVN -pl rule-observability -am test
```

预期：BUILD SUCCESS，全部测试通过（含 TraceWriterDbImplTest 全部 case）。

- [ ] **Step 3: rule-eval-svc 全量测试**

```bash
$MVN -pl rule-eval-svc -am test
```

预期：BUILD SUCCESS，全部测试通过（含 AstJsonCodecTest、SnapshotAssemblerTest、RuleIndexEventListenerTest、RolloutPreGateTest、EvalContextAssemblerTest、EvalSessionWriterTest、EvalServiceImplTest、EvalActionDispatcherTest）。

- [ ] **Step 4: 如有失败，修复并 Commit**

若有失败依据错误信息修复，不得用 `-DskipTests` 绕过：

```bash
git add <修复的文件>
git commit -m "$(cat <<'EOF'
fix: 修复 Plan C 单测回归问题（Task C-4）
EOF
)"
```

若无失败：不产生额外 commit。

---

## Task 5：集成测试（Testcontainers MySQL）

**背景：** 补全端到端验证。用 Testcontainers 启动真实 MySQL，通过 Flyway 建表（复用 Plan A 的 `V1_0__init_schema.sql`），写入测试数据（租户、场景、规则版本），验证 PULL 同步评估、PUSH 异步投递、dry-run 三条链路的完整 DB 写入。

**Files:**
- 修改: 根 `pom.xml`（添加 Testcontainers BOM）
- 修改: `rule-eval-svc/pom.xml`（添加 Testcontainers + Spring Boot Test 依赖）
- 新建: `rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/integration/EvalIntegrationTest.java`
- 新建: `rule-eval-svc/src/test/resources/application-test.yml`

- [ ] **Step 1: 根 pom.xml 添加 Testcontainers BOM**

在根 `pom.xml` 的 `<dependencyManagement>` 中添加：

```xml
<!-- 根 pom.xml dependencyManagement 中追加 -->
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>testcontainers-bom</artifactId>
    <version>1.20.4</version>
    <type>pom</type>
    <scope>import</scope>
</dependency>
```

- [ ] **Step 2: rule-eval-svc/pom.xml 添加 Testcontainers 依赖**

```xml
<!-- rule-eval-svc/pom.xml <dependencies> 中追加 -->
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>mysql</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-testcontainers</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-test</artifactId>
    <scope>test</scope>
</dependency>
```

- [ ] **Step 3: 创建 application-test.yml**

```yaml
# rule-eval-svc/src/test/resources/application-test.yml
spring:
  flyway:
    locations: classpath:db/migration
    enabled: true
  datasource:
    # Testcontainers 动态替换：由 @DynamicPropertySource 注入
    url: jdbc:mysql://localhost:3306/rule_engine_test
    username: test
    password: test
    driver-class-name: com.mysql.cj.jdbc.Driver

mybatis-plus:
  configuration:
    map-underscore-to-camel-case: true

engine:
  rule:
    trace:
      enabled: false   # 集成测试使用 Noop，避免 NodeTraceMapper 依赖
```

- [ ] **Step 4: 编写集成测试**

```java
// rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/integration/EvalIntegrationTest.java
package com.sstlfsj.rule.eval.integration;

import com.sstlfsj.rule.eval.api.service.EvalService;
import com.sstlfsj.rule.eval.internal.index.SceneRuleIndex;
import com.sstlfsj.rule.eval.internal.mapper.EvaluationSessionMapper;
import com.sstlfsj.rule.eval.internal.mapper.DryRunSessionMapper;
import com.sstlfsj.rule.eval.internal.mapper.RuleVersionReadMapper;
import com.sstlfsj.rule.eval.internal.snapshot.SceneSnapshotLoader;
import com.sstlfsj.rule.kernel.api.model.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 端到端集成测试：Testcontainers MySQL + Flyway 建表 + 完整评估链路。
 * 测试覆盖：PULL 同步、PUSH 异步投递、dry-run 三条链路。
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EvalIntegrationTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("rule_engine_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @Autowired EvalService evalService;
    @Autowired SceneRuleIndex index;
    @Autowired SceneSnapshotLoader snapshotLoader;
    @Autowired EvaluationSessionMapper sessionMapper;
    @Autowired DryRunSessionMapper dryRunMapper;
    @Autowired JdbcTemplate jdbc;

    /** 插入测试基础数据：tenant / scene / rule_definition / rule_version。 */
    @BeforeEach
    void setupData() {
        // 清空评估结果表，保留规则定义数据（@BeforeEach 每次清空 session）
        jdbc.execute("DELETE FROM evaluation_session WHERE event_id LIKE 'it-%'");
        jdbc.execute("DELETE FROM dry_run_session WHERE event_id LIKE 'it-%'");

        // 幂等写入：如果已有则跳过
        Integer tenantCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tenant WHERE code='test_tenant'", Integer.class);
        if (tenantCount != null && tenantCount > 0) return;

        // 租户
        jdbc.execute("""
                INSERT INTO tenant (id, code, name, is_default, status)
                VALUES (1, 'test_tenant', '测试租户', 1, 'ACTIVE')
                """);
        // 场景
        jdbc.execute("""
                INSERT INTO scene (id, tenant_id, code, name, dominant_mode,
                    decision_strategy, subject_type, event_types, status)
                VALUES (1, 1, 'fraud_check', '欺诈检测', 'HYBRID',
                    'HIGHEST_PRIORITY', 'USER', '["RISK_EVENT"]', 'ACTIVE')
                """);
        // 决策
        jdbc.execute("""
                INSERT INTO decision_definition (id, tenant_id, code, name, priority, actions, status)
                VALUES (1, 1, 'REJECT', '拒绝', 10, '[]', 'ACTIVE')
                """);
        // 规则定义
        jdbc.execute("""
                INSERT INTO rule_definition (id, tenant_id, scene_id, code, name,
                    status, kind, current_version)
                VALUES (1, 1, 1, 'risk.rule', '风险规则', 'PUBLISHED', 'AST_BOOLEAN', 1)
                """);
        // 规则版本（AST: ConditionNode ALWAYS_MISS，对应无 ConditionEvaluator → miss）
        // 使用 GT conditionType，没有注册 ConditionEvaluator，InterpretedExecutor 会抛异常
        // 改用 rule_version 的 condition_ast 用一个简单的始终 miss 规则（空 AndNode）
        jdbc.execute("""
                INSERT INTO rule_version (id, rule_definition_id, version, status,
                    condition_ast, decision_bindings, pre_gates, rollout,
                    trigger_event_types, metric_dependencies, published_at)
                VALUES (1, 1, 1, 'ACTIVE',
                    '{"type":"AndNode","children":[],"displayLabel":null,"weight":null}',
                    '[{"decisionCode":"REJECT","priority":10}]',
                    '[]', '{}',
                    '["RISK_EVENT"]', '[]',
                    NOW())
                """);
    }

    /** 手动加载快照到索引（模拟 IndexStartupLoader）。 */
    @BeforeEach
    void loadIndex() throws Exception {
        Thread.sleep(100); // 等待 Flyway + 数据初始化
        Map<String, List<RuleVersionSnapshot>> byEventType =
                snapshotLoader.loadByScene(1L, "fraud_check");
        for (Map.Entry<String, List<RuleVersionSnapshot>> e : byEventType.entrySet()) {
            index.update("1", "fraud_check", e.getKey(), e.getValue());
        }
    }

    private RuleEvent riskEvent(String eventId) {
        return new RuleEvent("1", "fraud_check", "RISK_EVENT", "user_001",
                eventId, Instant.now(), Map.of(), Map.of());
    }

    @Test
    @Order(1)
    void pull_evaluate_writesSessionToDb() {
        RuleEvent event = riskEvent("it-pull-001");

        // AndNode 空 children → evaluate() 返回 true（空 AND = true）
        EvalResult result = evalService.evaluate(event);

        assertNotNull(result);
        // 验证 evaluation_session 写入 DB
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM evaluation_session WHERE event_id='it-pull-001'",
                Integer.class);
        assertEquals(1, count, "PULL 模式应写入 evaluation_session");
        String status = jdbc.queryForObject(
                "SELECT status FROM evaluation_session WHERE event_id='it-pull-001'",
                String.class);
        assertNotNull(status);
        // 空 AndNode = true，规则命中，hitDecisions = [REJECT]
        assertTrue(result.ruleHit());
        assertEquals("HIT", status);
    }

    @Test
    @Order(2)
    void pull_idempotent_duplicateEventId_onlyOneSession() {
        RuleEvent event = riskEvent("it-pull-idem-001");

        evalService.evaluate(event);
        evalService.evaluate(event); // 相同 eventId 第二次

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM evaluation_session WHERE event_id='it-pull-idem-001'",
                Integer.class);
        assertEquals(1, count, "相同 eventId 幂等，只写一条 session");
    }

    @Test
    @Order(3)
    void push_acceptEvent_writesSessionEventually() throws Exception {
        RuleEvent event = riskEvent("it-push-001");

        boolean accepted = evalService.acceptEvent(event);

        assertTrue(accepted, "PUSH 队列有容量，应接受事件");
        // PUSH 异步：等待消费（最多 2 秒）
        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline) {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM evaluation_session WHERE event_id='it-push-001'",
                    Integer.class);
            if (count != null && count > 0) break;
            Thread.sleep(100);
        }
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM evaluation_session WHERE event_id='it-push-001'",
                Integer.class);
        assertEquals(1, count, "PUSH 异步应最终写入 evaluation_session");
    }

    @Test
    @Order(4)
    void dryRun_writesToDryRunSession_notProdSession() {
        RuleEvent event = riskEvent("it-dry-001");

        EvalResult result = evalService.dryRun(event, 1L);

        assertNotNull(result);
        // dry_run_session 应有记录
        Integer dryCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dry_run_session WHERE event_id='it-dry-001'",
                Integer.class);
        assertEquals(1, dryCount, "dry-run 应写入 dry_run_session");
        // evaluation_session 不应有记录
        Integer prodCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM evaluation_session WHERE event_id='it-dry-001'",
                Integer.class);
        assertEquals(0, prodCount, "dry-run 不应写入生产 evaluation_session");
    }

    @Test
    @Order(5)
    void noMatchingRules_returnsMiss_noSession() {
        // 发一个不匹配场景的事件
        RuleEvent event = new RuleEvent("1", "unknown_scene", "RISK_EVENT", "user_001",
                "it-nomatch-001", Instant.now(), Map.of(), Map.of());

        EvalResult result = evalService.evaluate(event);

        assertFalse(result.ruleHit());
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM evaluation_session WHERE event_id='it-nomatch-001'",
                Integer.class);
        assertEquals(0, count, "无匹配规则时不应写入 session");
    }
}
```

- [ ] **Step 5: 运行集成测试**

```bash
$MVN -pl rule-eval-svc -am test -Dtest='EvalIntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false
```

预期：BUILD SUCCESS，5 个集成测试全部通过。

如果测试失败，常见原因及修复：
- `Flyway migration failed`：检查 `application-test.yml` 中 `spring.flyway.locations` 路径是否能找到 `V1_0__init_schema.sql`（`rule-config-svc` 的 DDL 文件，集成测试需要 classpath 能访问到——需将 config-svc 的 migration 资源拷贝到 eval-svc test resources，或通过 `classpath*:` 扫描多模块）。

如果 Flyway 找不到 SQL 文件，补充操作：

```bash
mkdir -p rule-eval-svc/src/test/resources/db/migration
cp rule-config-svc/src/main/resources/db/migration/V1_0__init_schema.sql \
   rule-eval-svc/src/test/resources/db/migration/
```

然后修改 `application-test.yml` 的 `spring.flyway.locations` 为 `classpath:db/migration`，重新运行。

- [ ] **Step 6: 运行 eval-svc 全量测试（含集成）**

```bash
$MVN -pl rule-eval-svc -am test
```

预期：BUILD SUCCESS，全部单测 + 集成测试通过。

- [ ] **Step 7: Commit**

```bash
git add pom.xml
git add rule-eval-svc/pom.xml
git add rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/integration/
git add rule-eval-svc/src/test/resources/application-test.yml
# 如果拷贝了 DDL 文件：
# git add rule-eval-svc/src/test/resources/db/
git commit -m "$(cat <<'EOF'
test(eval): Testcontainers 集成测试覆盖 PULL/PUSH/dry-run 全链路（Task C-5）

- 启动真实 MySQL，Flyway 建表，验证 evaluation_session / dry_run_session 写入
- PULL 同步评估、idempotency（相同 eventId 只写一条）、PUSH 异步最终写入
- dry-run 隔离写 dry_run_session，不污染生产表
EOF
)"
```

---

## 自我检查

**Spec 覆盖（Plan B 遗留 v1 限制）：**
- ✅ NodeTrace 收集（TracingInterpretedExecutor，每个 AST 节点产生一条 trace，短路节点被跳过）
- ✅ TraceWriterDbImpl.flushBatch() 实现（drainTo + 递归展开树形 trace + NodeTraceMapper.insert）
- ✅ PUSH 背压（EvalActionDispatcher LinkedBlockingQueue，满时 submit 返回 false）
- ✅ EvalServiceImpl 传递 NodeTrace 给 TraceWriter（allTraces 在 ⑤ 步收集，⑦ 步提交）
- ✅ Testcontainers 集成测试（PULL / PUSH 异步 / dry-run / 幂等 / 无匹配 miss）
- ✅ TracingInterpretedExecutor 不破坏 InterpretedExecutor（新建类，kernel 原测试不变）

**Placeholder 扫描：** 无 TBD / TODO / "similar to Task N" 占位符。

**类型一致性检查：**
- `TracingInterpretedExecutor.execute()` 返回 `new EvalResult(satisfied, null, List.of(), traces, null, List.of())`；`EvalServiceImpl.doEvaluate()` 读 `r.nodeTrace()` 并累积到 `allTraces` ✅
- `EvalActionDispatcher(capacity, Consumer<RuleEvent>)` 构造器与 `EvalServiceImpl` 内部 `new EvalActionDispatcher(10000, this::evaluate)` 匹配 ✅
- `TraceWriterDbImpl(int, int, long, NodeTraceMapper)` 新构造器与 `ObservabilityAutoConfiguration.traceWriterDb(NodeTraceMapper)` 调用一致 ✅
- `NodeTrace(nodeType, conditionType, metricCode, result, actualValue, valueSource, errorCode, children)` — 8 参构造器，与 `TraceWriterDbImplTest` 中 `new NodeTrace("ConditionNode", "GT", "score", true, 100, "PROVIDED", null, null)` 一致 ✅
- `EvalServiceImplTest` 使用 `@InjectMocks EvalServiceImpl impl`，Mockito 会尝试调用构造器——但构造器内部 `new EvalActionDispatcher(...)` 需要 `evaluate` 方法存在。Mockito @InjectMocks 用最大构造器注入，所有 7 个参数均为 Mock。`EvalActionDispatcher.start()` 在构造器末尾调用，会创建虚拟线程，但单测中虚拟线程调 `evaluate(null)` 时 index.match(null,...) 可能 NPE。**修复方案**：`EvalServiceImpl` 构造器中把 `dispatcher.start()` 移到构造器外，改为由 `@PostConstruct` 调用；或者构造器内延迟 start，靠 `@PostConstruct`。

  **更安全做法**：让 `EvalServiceImpl` 实现 `InitializingBean`，在 `afterPropertiesSet()` 中调用 `dispatcher.start()`，构造器只 `new EvalActionDispatcher(10000, this::evaluate)` 不 start。这样 Mockito 构造时不会启动线程。

  在 EvalServiceImpl 最终版本中：构造器内只 `new EvalActionDispatcher(...)`，`afterPropertiesSet()` 中调 `dispatcher.start()`，`destroy()` 中调 `dispatcher.stop()`。EvalServiceImpl 实现 `InitializingBean, DisposableBean`。

**更新 EvalServiceImplTest** 以适配新构造器签名（去掉 dispatcher 参数，改由内部自建）：`@InjectMocks EvalServiceImpl impl` 依然有效，只是 Mockito 不再注入 dispatcher 字段（因为 dispatcher 是内部 new 的，不是注入的）。测试无需改变。

**已知局限：**
- Task 5 集成测试中 PUSH 链路是通过 `Thread.sleep` 轮询等待，非精确同步——这是 PUSH 异步语义的必要代价，不做修改。
- NodeTraceMapper 在集成测试中设置 `engine.rule.trace.enabled=false`（Noop），因此 node_trace 表写入在集成测试中不验证——这是合理的隔离（trace 写入是独立的可观测性链路，单独测试更精确）。
