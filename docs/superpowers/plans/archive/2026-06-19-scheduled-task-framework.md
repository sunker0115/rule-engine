# Scheduled Task Framework (track #1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把评估耦合的 `job_definition` 重写为通用 `ScheduledTask` + `TaskExecutor` SPI 框架,TRIGGER 成为第一个 executor;调度仍走既有 `Scheduler` SPI(XXL/ThreadPool)。

**Architecture:** 单表 `scheduled_task`(域真相源,含 typed `TaskConfig` JSON)+ XXL 触发(只存 cron+jobCode 镜像)。调度器按 cron 触发 → `runById(taskId)` 回 DB 重载 → dispatcher 按 `task_type` 查 `TaskExecutor` → 执行 → 写统一 `scheduled_task_execution`。`@RuleJob` 注解 + 启动扫描照旧,只是 seed 进 `scheduled_task`(TRIGGER 型)。

**Tech Stack:** Java 25 / Spring Boot 4 / MyBatis-Plus(Jackson3TypeHandler typed-JSON,RuleVersion 模板)/ XXL-JOB / Flyway。设计见 `docs/superpowers/specs/2026-06-19-distributed-ready-scheduling-and-propagation-design.md` §3–§5。

**环境:** 跑 mvn 前用 `mvn-env` skill 设 `$MVN`。跨模块带 `-am`,最终 `clean test` 兜底。模块:`rule-job-svc`(主)、`rule-kernel`(SPI:TaskConfig/TaskExecutor 若需跨模块)、`rule-api`(controller)、`rule-config-svc`(migration 集中目录)。

**范围:** 本计划 = track #1 **后端**。前端(`job-list`/`job-detail` → 调度任务管理)为同 track 后续计划,本计划不含。OUTCOME_INGESTION executor 是 track #2,本计划只把框架立好(`TaskConfig` sealed 暂只 permits `TriggerConfig`,#2 扩 permits)。

---

### Task 1: 迁移脚本 — scheduled_task + scheduled_task_execution,drop 旧 job 表

**Files:**
- Create: `rule-config-svc/src/main/resources/db/migration/V1_37__scheduled_task.sql`

- [ ] **Step 1: 写迁移**

```sql
-- 通用调度任务框架:替换 job_definition / job_execution(评估耦合聚合)。greenfield 无生产数据需保留。
CREATE TABLE IF NOT EXISTS scheduled_task (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id   BIGINT       NOT NULL,
  code        VARCHAR(64)  NOT NULL COMMENT '租户内唯一',
  name        VARCHAR(128) NOT NULL,
  task_type   VARCHAR(32)  NOT NULL COMMENT 'TaskType: TRIGGER / OUTCOME_INGESTION',
  cron        VARCHAR(128) NOT NULL COMMENT 'Spring 6 段 cron;seed 初值,XXL admin 运行时权威',
  config      JSON         NOT NULL COMMENT 'typed TaskConfig(多态 kind 判别)',
  status      VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / DISABLED',
  created_by  VARCHAR(64),
  created_at  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_by  VARCHAR(64),
  updated_at  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_tenant_code (tenant_id, code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='通用调度任务定义';

CREATE TABLE IF NOT EXISTS scheduled_task_execution (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  scheduled_task_id BIGINT      NOT NULL,
  tenant_id        BIGINT       NOT NULL,
  trigger_at       TIMESTAMP(3) NOT NULL,
  status           VARCHAR(16)  NOT NULL DEFAULT 'RUNNING' COMMENT 'RUNNING/SUCCESS/PARTIAL_FAIL/FAILED',
  processed_count  INT          NOT NULL DEFAULT 0 COMMENT 'TRIGGER:主体数 / INGESTION:标签行数',
  success_count    INT          NOT NULL DEFAULT 0,
  error_count      INT          NOT NULL DEFAULT 0,
  error_summary    TEXT,
  finished_at      TIMESTAMP(3) NULL,
  KEY idx_task_trigger (scheduled_task_id, trigger_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='调度任务执行记录';

DROP TABLE IF EXISTS job_execution;
DROP TABLE IF EXISTS job_definition;
```

> COLLATE 显式锁 utf8mb4_unicode_ci 与既有表对齐(见 memory:新建表 collation 约束;e2e join 才暴露)。

- [ ] **Step 2: 提交**

```bash
git add rule-config-svc/src/main/resources/db/migration/V1_37__scheduled_task.sql
git commit -m "feat(db): scheduled_task/scheduled_task_execution 表, drop job_definition/job_execution(V1_37)"
```

---

### Task 2: TaskType 枚举 + sealed TaskConfig + TriggerConfig

**Files:**
- Create: `rule-job-svc/src/main/java/com/sstlfsj/rule/job/api/TaskType.java`
- Create: `rule-job-svc/src/main/java/com/sstlfsj/rule/job/api/TaskConfig.java`
- Create: `rule-job-svc/src/main/java/com/sstlfsj/rule/job/api/TriggerConfig.java`
- Test: `rule-job-svc/src/test/java/com/sstlfsj/rule/job/api/TaskConfigTest.java`

- [ ] **Step 1: 写 TaskType**

```java
package com.sstlfsj.rule.job.api;

/** 调度任务类型(封闭集,仅动态/租户配置型)。 */
public enum TaskType { TRIGGER, OUTCOME_INGESTION }
```

- [ ] **Step 2: 写 sealed TaskConfig（多态判别 kind）**

```java
package com.sstlfsj.rule.job.api;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * 调度任务的类型化配置载体。多态由 kind 判别自描述(JSON↔子类型)。
 * track #2 接 OUTCOME_INGESTION 时在 permits + @JsonSubTypes 加 OutcomeIngestionConfig。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes({ @JsonSubTypes.Type(value = TriggerConfig.class, name = "TRIGGER") })
public sealed interface TaskConfig permits TriggerConfig {
    /** 对应的任务类型,供 dispatcher 校验 config 与 task_type 一致。 */
    TaskType type();
}
```

> 注意:注解在 `com.fasterxml.jackson.annotation`(Jackson3 注解仍在此包,databind 在 tools.jackson;见 memory)。

- [ ] **Step 3: 写 TriggerConfig**

```java
package com.sstlfsj.rule.job.api;

/**
 * TRIGGER 任务配置:取主体 → 合成 RuleEvent → 评估。
 *
 * @param sceneCode    绑定场景(仅 PUSH/HYBRID)
 * @param eventType    合成 RuleEvent 的 eventType
 * @param subjectQuery 主体查询(如 BeanMethodQuery)
 */
public record TriggerConfig(String sceneCode, String eventType, SubjectQuery subjectQuery) implements TaskConfig {
    @Override public TaskType type() { return TaskType.TRIGGER; }
}
```

> `SubjectQuery`(已存在,`com.sstlfsj.rule.job.api.SubjectQuery` 接口 + `BeanMethodQuery` 实现)沿用,不改。

- [ ] **Step 4: 写测试(多态 JSON round-trip)**

```java
package com.sstlfsj.rule.job.api;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class TaskConfigTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();

    @Test
    void triggerConfig_polymorphicRoundTrip() {
        TaskConfig cfg = new TriggerConfig("risk.transfer", "login", new BeanMethodQuery("b#m"));
        String json = mapper.writeValueAsString(cfg);
        assertThat(json).contains("\"kind\":\"TRIGGER\"");

        TaskConfig back = mapper.readValue(json, TaskConfig.class);
        assertThat(back).isInstanceOf(TriggerConfig.class);
        assertThat(back.type()).isEqualTo(TaskType.TRIGGER);
        assertThat(((TriggerConfig) back).sceneCode()).isEqualTo("risk.transfer");
        assertThat(((TriggerConfig) back).subjectQuery()).isInstanceOf(BeanMethodQuery.class);
    }
}
```

- [ ] **Step 5: 跑 + 提交**

Run: `$MVN -pl rule-job-svc -am test -Dtest=TaskConfigTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS

```bash
git add rule-job-svc/src/main/java/com/sstlfsj/rule/job/api/TaskType.java rule-job-svc/src/main/java/com/sstlfsj/rule/job/api/TaskConfig.java rule-job-svc/src/main/java/com/sstlfsj/rule/job/api/TriggerConfig.java rule-job-svc/src/test/java/com/sstlfsj/rule/job/api/TaskConfigTest.java
git commit -m "feat(job): TaskType + sealed TaskConfig + TriggerConfig(多态 typed-JSON)"
```

---

### Task 3: ScheduledTask / ScheduledTaskExecution 实体 + Mapper + TaskConfig TypeHandler

**Files:**
- Create: `rule-job-svc/.../internal/domain/ScheduledTask.java`
- Create: `rule-job-svc/.../internal/domain/ScheduledTaskExecution.java`
- Create: `rule-job-svc/.../internal/domain/TaskStatus.java`
- Create: `rule-job-svc/.../internal/domain/TaskExecutionStatus.java`
- Create: `rule-job-svc/.../internal/repository/ScheduledTaskMapper.java`
- Create: `rule-job-svc/.../internal/repository/ScheduledTaskExecutionMapper.java`
- Test: `rule-job-svc/.../internal/domain/ScheduledTaskTest.java`

- [ ] **Step 1: 写枚举 TaskStatus / TaskExecutionStatus**

```java
package com.sstlfsj.rule.job.internal.domain;

/** 调度任务生命周期状态。 */
public enum TaskStatus { ACTIVE, DISABLED }
```

```java
package com.sstlfsj.rule.job.internal.domain;

/** 单次执行结果状态。 */
public enum TaskExecutionStatus { RUNNING, SUCCESS, PARTIAL_FAIL, FAILED }
```

- [ ] **Step 2: 写 ScheduledTask 实体(typed config via Jackson3TypeHandler,RuleVersion 模板)**

```java
package com.sstlfsj.rule.job.internal.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.Jackson3TypeHandler;
import com.sstlfsj.rule.job.api.TaskConfig;
import com.sstlfsj.rule.job.api.TaskType;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** scheduled_task 表实体(typed config 列,JSON↔对象由 TypeHandler 持久层完成)。 */
@Getter
@Setter
@TableName(value = "scheduled_task", autoResultMap = true)
public class ScheduledTask {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String code;
    private String name;
    private TaskType taskType;
    private String cron;
    @TableField(typeHandler = Jackson3TypeHandler.class)
    private TaskConfig config;
    private TaskStatus status;
    private String createdBy;
    private LocalDateTime createdAt;
    private String updatedBy;
    private LocalDateTime updatedAt;
}
```

- [ ] **Step 3: 写 ScheduledTaskExecution 实体**

```java
package com.sstlfsj.rule.job.internal.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** scheduled_task_execution 表实体。 */
@Getter
@Setter
@TableName("scheduled_task_execution")
public class ScheduledTaskExecution {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long scheduledTaskId;
    private Long tenantId;
    private LocalDateTime triggerAt;
    private TaskExecutionStatus status;
    private Integer processedCount;
    private Integer successCount;
    private Integer errorCount;
    private String errorSummary;
    private LocalDateTime finishedAt;
}
```

- [ ] **Step 4: 写 Mapper(单表查询用 default 方法封装,§5 纪律)**

```java
package com.sstlfsj.rule.job.internal.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sstlfsj.rule.job.internal.domain.ScheduledTask;
import com.sstlfsj.rule.job.internal.domain.TaskStatus;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/** scheduled_task Mapper。 */
@Mapper
public interface ScheduledTaskMapper extends BaseMapper<ScheduledTask> {

    /** 查租户某 code 的任务,不存在返回 null。 */
    default ScheduledTask findByTenantCode(Long tenantId, String code) {
        return selectOne(new LambdaQueryWrapper<ScheduledTask>()
                .eq(ScheduledTask::getTenantId, tenantId)
                .eq(ScheduledTask::getCode, code));
    }

    /** 查租户全部任务。 */
    default List<ScheduledTask> findByTenant(Long tenantId) {
        return selectList(new LambdaQueryWrapper<ScheduledTask>()
                .eq(ScheduledTask::getTenantId, tenantId)
                .orderByAsc(ScheduledTask::getId));
    }

    /** 查全部 ACTIVE 任务(启动注册用)。 */
    default List<ScheduledTask> findAllActive() {
        return selectList(new LambdaQueryWrapper<ScheduledTask>()
                .eq(ScheduledTask::getStatus, TaskStatus.ACTIVE));
    }
}
```

```java
package com.sstlfsj.rule.job.internal.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sstlfsj.rule.job.internal.domain.ScheduledTaskExecution;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/** scheduled_task_execution Mapper。 */
@Mapper
public interface ScheduledTaskExecutionMapper extends BaseMapper<ScheduledTaskExecution> {

    /** 某任务最近 limit 条执行记录,按触发时间倒序。 */
    default List<ScheduledTaskExecution> recentByTask(Long scheduledTaskId, int limit) {
        return selectList(new Page<ScheduledTaskExecution>(1, limit),
                new LambdaQueryWrapper<ScheduledTaskExecution>()
                        .eq(ScheduledTaskExecution::getScheduledTaskId, scheduledTaskId)
                        .orderByDesc(ScheduledTaskExecution::getTriggerAt));
    }
}
```

- [ ] **Step 5: 写实体 round-trip 测试**

```java
package com.sstlfsj.rule.job.internal.domain;

import com.sstlfsj.rule.job.api.TaskType;
import com.sstlfsj.rule.job.api.TriggerConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScheduledTaskTest {
    @Test
    void settersAndGetters_roundTrip() {
        ScheduledTask t = new ScheduledTask();
        t.setTenantId(1L);
        t.setCode("c");
        t.setTaskType(TaskType.TRIGGER);
        t.setCron("0 0 * * * *");
        t.setConfig(new TriggerConfig("s", "e", null));
        t.setStatus(TaskStatus.ACTIVE);
        assertEquals(TaskType.TRIGGER, t.getTaskType());
        assertEquals(TaskStatus.ACTIVE, t.getStatus());
        assertEquals("s", ((TriggerConfig) t.getConfig()).sceneCode());
    }
}
```

- [ ] **Step 6: 跑 + 提交**

Run: `$MVN -pl rule-job-svc -am test -Dtest='ScheduledTaskTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS

```bash
git add rule-job-svc/src/main/java/com/sstlfsj/rule/job/internal/domain/ScheduledTask.java rule-job-svc/src/main/java/com/sstlfsj/rule/job/internal/domain/ScheduledTaskExecution.java rule-job-svc/src/main/java/com/sstlfsj/rule/job/internal/domain/TaskStatus.java rule-job-svc/src/main/java/com/sstlfsj/rule/job/internal/domain/TaskExecutionStatus.java rule-job-svc/src/main/java/com/sstlfsj/rule/job/internal/repository/ScheduledTaskMapper.java rule-job-svc/src/main/java/com/sstlfsj/rule/job/internal/repository/ScheduledTaskExecutionMapper.java rule-job-svc/src/test/java/com/sstlfsj/rule/job/internal/domain/ScheduledTaskTest.java
git commit -m "feat(job): ScheduledTask/Execution 实体 + Mapper + typed config TypeHandler"
```

---

### Task 4: TaskExecutor SPI + TaskRunResult + TaskExecutorRegistry(分发)

**Files:**
- Create: `rule-job-svc/.../api/TaskRunResult.java`
- Create: `rule-job-svc/.../api/TaskExecutor.java`
- Create: `rule-job-svc/.../internal/runner/TaskExecutorRegistry.java`
- Test: `rule-job-svc/.../internal/runner/TaskExecutorRegistryTest.java`

- [ ] **Step 1: 写 TaskRunResult(executor 返回,dispatcher 落库)**

```java
package com.sstlfsj.rule.job.api;

import com.sstlfsj.rule.job.internal.domain.TaskExecutionStatus;

/**
 * 一次任务执行的结果(executor 返回,由 dispatcher 写 scheduled_task_execution)。
 *
 * @param status        终态
 * @param processedCount 处理总数(TRIGGER:主体 / INGESTION:标签行)
 * @param successCount  成功数
 * @param errorCount    失败数
 * @param errorSummary  错误摘要(可空)
 */
public record TaskRunResult(TaskExecutionStatus status, int processedCount, int successCount,
                            int errorCount, String errorSummary) {}
```

> `TaskRunResult` 在 api 包但引用 internal 的 `TaskExecutionStatus`——把 `TaskExecutionStatus` 也提到 api 包更干净。**决策:把 `TaskExecutionStatus` 与 `TaskStatus` 放 api 包**(executor SPI 返回值需引用),Task 3 的 import 路径相应改为 `api`。执行 Task 3 时即按 api 包放这两个枚举。

- [ ] **Step 2: 写 TaskExecutor SPI**

```java
package com.sstlfsj.rule.job.api;

import com.sstlfsj.rule.job.internal.domain.ScheduledTask;

/**
 * 调度任务执行器 SPI。每型一个实现,Spring 自动收集,按 type() 路由。
 *
 * @param <C> 该型的 TaskConfig 子类型
 */
public interface TaskExecutor<C extends TaskConfig> {
    /** 该 executor 负责的任务类型。 */
    TaskType type();
    /** config 子类型,供 dispatcher 反序列化校验。 */
    Class<C> configType();
    /**
     * 执行一次任务。
     * @param task   触发的任务(含 id/tenant,供关联执行记录)
     * @param config 已 typed 的配置
     * @return 执行结果
     */
    TaskRunResult execute(ScheduledTask task, C config);
}
```

> `TaskExecutor` 引用 internal 的 `ScheduledTask`。为不让 api 依赖 internal,**把 `ScheduledTask`/`ScheduledTaskExecution` 实体也视作 api 可见的领域类**——但实体带 MyBatis 注解,惯例在 internal。折中:executor 的 `execute` 入参改为只传**必要的 typed 值**而非整个实体:`execute(long taskId, long tenantId, C config)`。采用此签名,避免 api→internal 反向依赖。

修正后的 SPI:

```java
package com.sstlfsj.rule.job.api;

public interface TaskExecutor<C extends TaskConfig> {
    TaskType type();
    Class<C> configType();
    /**
     * @param taskId   任务 id(供日志/关联)
     * @param tenantId 租户 id
     * @param config   typed 配置
     */
    TaskRunResult execute(long taskId, long tenantId, C config);
}
```

- [ ] **Step 3: 写 TaskExecutorRegistry(收集 + 路由 + config 反序列化校验)**

```java
package com.sstlfsj.rule.job.internal.runner;

import com.sstlfsj.rule.job.api.TaskConfig;
import com.sstlfsj.rule.job.api.TaskExecutor;
import com.sstlfsj.rule.job.api.TaskRunResult;
import com.sstlfsj.rule.job.api.TaskType;
import com.sstlfsj.rule.job.internal.domain.ScheduledTask;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** 按 TaskType 收集 TaskExecutor 并路由。Spring 注入 List<TaskExecutor> 自动收集。 */
@Slf4j
@Component
public class TaskExecutorRegistry {

    private final Map<TaskType, TaskExecutor<?>> byType = new EnumMap<>(TaskType.class);

    public TaskExecutorRegistry(List<TaskExecutor<?>> executors) {
        for (TaskExecutor<?> e : executors) {
            if (byType.putIfAbsent(e.type(), e) != null) {
                throw new IllegalStateException("多个 TaskExecutor 声明同一 type=" + e.type());
            }
        }
    }

    /**
     * 路由执行:校验 config 子类型与 executor.configType() 一致,转型后执行。
     *
     * @param task 触发的任务
     * @return 执行结果
     */
    @SuppressWarnings("unchecked")
    public <C extends TaskConfig> TaskRunResult dispatch(ScheduledTask task) {
        TaskExecutor<C> executor = (TaskExecutor<C>) byType.get(task.getTaskType());
        if (executor == null) {
            throw new IllegalStateException("无 TaskExecutor 处理 type=" + task.getTaskType());
        }
        TaskConfig config = task.getConfig();
        if (!executor.configType().isInstance(config)) {
            throw new IllegalStateException("task " + task.getId() + " config 类型 "
                    + (config == null ? "null" : config.getClass().getSimpleName())
                    + " 与 executor.configType=" + executor.configType().getSimpleName() + " 不符");
        }
        return executor.execute(task.getId(), task.getTenantId(), (C) executor.configType().cast(config));
    }
}
```

- [ ] **Step 4: 写测试(stub executor + 路由 + 类型不符抛错)**

```java
package com.sstlfsj.rule.job.internal.runner;

import com.sstlfsj.rule.job.api.*;
import com.sstlfsj.rule.job.internal.domain.ScheduledTask;
import com.sstlfsj.rule.job.internal.domain.TaskExecutionStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaskExecutorRegistryTest {

    private final TaskExecutor<TriggerConfig> triggerExec = new TaskExecutor<>() {
        public TaskType type() { return TaskType.TRIGGER; }
        public Class<TriggerConfig> configType() { return TriggerConfig.class; }
        public TaskRunResult execute(long taskId, long tenantId, TriggerConfig config) {
            return new TaskRunResult(TaskExecutionStatus.SUCCESS, 1, 1, 0, null);
        }
    };

    @Test
    void dispatch_routesByType() {
        TaskExecutorRegistry reg = new TaskExecutorRegistry(List.of(triggerExec));
        ScheduledTask task = new ScheduledTask();
        task.setId(1L); task.setTenantId(7L);
        task.setTaskType(TaskType.TRIGGER);
        task.setConfig(new TriggerConfig("s", "e", null));
        assertThat(reg.dispatch(task).status()).isEqualTo(TaskExecutionStatus.SUCCESS);
    }

    @Test
    void duplicateType_throwsAtConstruction() {
        assertThatThrownBy(() -> new TaskExecutorRegistry(List.of(triggerExec, triggerExec)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void noExecutorForType_throws() {
        TaskExecutorRegistry reg = new TaskExecutorRegistry(List.of());
        ScheduledTask task = new ScheduledTask();
        task.setTaskType(TaskType.TRIGGER);
        assertThatThrownBy(() -> reg.dispatch(task)).isInstanceOf(IllegalStateException.class);
    }
}
```

- [ ] **Step 5: 跑 + 提交**

Run: `$MVN -pl rule-job-svc -am test -Dtest='TaskExecutorRegistryTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS

```bash
git add rule-job-svc/src/main/java/com/sstlfsj/rule/job/api/TaskRunResult.java rule-job-svc/src/main/java/com/sstlfsj/rule/job/api/TaskExecutor.java rule-job-svc/src/main/java/com/sstlfsj/rule/job/internal/runner/TaskExecutorRegistry.java rule-job-svc/src/test/java/com/sstlfsj/rule/job/internal/runner/TaskExecutorRegistryTest.java
git commit -m "feat(job): TaskExecutor SPI + TaskRunResult + TaskExecutorRegistry(类型路由)"
```

---

### Task 5: TriggerExecutor — 迁移 JobRunner 逻辑为 TRIGGER executor

**Files:**
- Create: `rule-job-svc/.../internal/runner/TriggerExecutor.java`
- Test: `rule-job-svc/.../internal/runner/TriggerExecutorTest.java`
- Reference: 现有 `JobRunner.java`(整段逻辑搬来,改为读 `TriggerConfig` + 返回 `TaskRunResult`,不再自己写执行记录)

- [ ] **Step 1: 写 TriggerExecutor(搬 JobRunner 的 subject→event→inject 逻辑)**

```java
package com.sstlfsj.rule.job.internal.runner;

import com.sstlfsj.rule.eval.api.service.EvalService;
import com.sstlfsj.rule.job.api.*;
import com.sstlfsj.rule.job.internal.domain.TaskExecutionStatus;
import com.sstlfsj.rule.job.internal.subject.SubjectQueryRunner;
import com.sstlfsj.rule.kernel.api.model.EventSource;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** TRIGGER executor:取主体 → 合成 RuleEvent → EvalService.acceptEvent 注入(D11)。 */
@Slf4j
@Component
public class TriggerExecutor implements TaskExecutor<TriggerConfig> {

    private static final int ERROR_SUMMARY_MAX = 2000;
    private static final int INJECT_MAX_RETRY = 20;
    private static final long INJECT_BACKOFF_MS = 50;

    private final SubjectQueryRunner subjectQueryRunner;
    private final EvalService evalService;

    public TriggerExecutor(SubjectQueryRunner subjectQueryRunner, EvalService evalService) {
        this.subjectQueryRunner = subjectQueryRunner;
        this.evalService = evalService;
    }

    @Override public TaskType type() { return TaskType.TRIGGER; }
    @Override public Class<TriggerConfig> configType() { return TriggerConfig.class; }

    @Override
    public TaskRunResult execute(long taskId, long tenantId, TriggerConfig config) {
        int[] counters = {0, 0, 0}; // processed, success, error
        List<String> errors = new ArrayList<>();
        String tenant = String.valueOf(tenantId);
        TaskExecutionStatus status;
        try {
            subjectQueryRunner.forEachTarget(config.subjectQuery(), target -> {
                counters[0]++;
                String subjectId = target.subjectId();
                try {
                    String eventId = EventIdHasher.hash(taskId, subjectId);
                    RuleEvent event = RuleEvent.builder()
                            .tenantId(tenant)
                            .sceneCode(config.sceneCode())
                            .eventType(config.eventType())
                            .subjectId(subjectId)
                            .eventId(eventId)
                            .payload(target.payload())
                            .providedMetrics(target.providedMetrics())
                            .source(EventSource.JOB)
                            .build();
                    if (injectWithBackpressure(event)) {
                        counters[1]++;
                    } else {
                        counters[2]++;
                        errors.add("subjectId=" + subjectId + " 注入失败(队列持续满,重试耗尽)");
                    }
                } catch (RuntimeException e) {
                    counters[2]++;
                    errors.add("subjectId=" + subjectId + " 异常: " + e.getMessage());
                    log.warn("TRIGGER 主体注入失败 taskId={} subjectId={}", taskId, subjectId, e);
                }
            });
            status = counters[2] == 0 ? TaskExecutionStatus.SUCCESS
                    : (counters[1] > 0 ? TaskExecutionStatus.PARTIAL_FAIL : TaskExecutionStatus.FAILED);
        } catch (RuntimeException e) {
            status = TaskExecutionStatus.FAILED;
            errors.add("主体查询失败: " + e.getMessage());
            log.warn("TRIGGER 主体查询失败 taskId={}", taskId, e);
        }
        String summary = errors.isEmpty() ? null : truncate(String.join("; ", errors));
        return new TaskRunResult(status, counters[0], counters[1], counters[2], summary);
    }

    /** 背压重试注入:队列满时退避重试,超 INJECT_MAX_RETRY 仍失败返回 false。 */
    private boolean injectWithBackpressure(RuleEvent event) {
        for (int i = 0; i < INJECT_MAX_RETRY; i++) {
            if (evalService.acceptEvent(event)) return true;
            try { Thread.sleep(INJECT_BACKOFF_MS); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); return false; }
        }
        return false;
    }

    private static String truncate(String s) {
        return s.length() <= ERROR_SUMMARY_MAX ? s : s.substring(0, ERROR_SUMMARY_MAX);
    }
}
```

> `EventIdHasher`、`SubjectQueryRunner`、`target.subjectId()/payload()/providedMetrics()` 均沿用现有(不改)。`injectWithBackpressure` 逻辑核对现有 `JobRunner` 同名方法(本步已等价重写)。

- [ ] **Step 2: 写测试(mock SubjectQueryRunner + EvalService,验计数与终态)**

```java
package com.sstlfsj.rule.job.internal.runner;

import com.sstlfsj.rule.eval.api.service.EvalService;
import com.sstlfsj.rule.job.api.TriggerConfig;
import com.sstlfsj.rule.job.api.TaskRunResult;
import com.sstlfsj.rule.job.internal.domain.TaskExecutionStatus;
import com.sstlfsj.rule.job.internal.subject.SubjectQueryRunner;
import com.sstlfsj.rule.job.internal.subject.SubjectTarget;
import org.junit.jupiter.api.Test;

import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TriggerExecutorTest {

    private final SubjectQueryRunner subjectRunner = mock(SubjectQueryRunner.class);
    private final EvalService evalService = mock(EvalService.class);
    private final TriggerExecutor executor = new TriggerExecutor(subjectRunner, evalService);

    @Test
    void allSubjectsInjected_success() {
        doAnswer(inv -> {
            Consumer<SubjectTarget> sink = inv.getArgument(1);
            sink.accept(SubjectTarget.of("u1"));
            sink.accept(SubjectTarget.of("u2"));
            return null;
        }).when(subjectRunner).forEachTarget(any(), any());
        when(evalService.acceptEvent(any())).thenReturn(true);

        TaskRunResult r = executor.execute(1L, 7L, new TriggerConfig("s", "e", null));
        assertThat(r.status()).isEqualTo(TaskExecutionStatus.SUCCESS);
        assertThat(r.processedCount()).isEqualTo(2);
        assertThat(r.successCount()).isEqualTo(2);
        assertThat(r.errorCount()).isZero();
    }

    @Test
    void partialInjectFailure_partialFail() {
        doAnswer(inv -> {
            Consumer<SubjectTarget> sink = inv.getArgument(1);
            sink.accept(SubjectTarget.of("u1"));
            sink.accept(SubjectTarget.of("u2"));
            return null;
        }).when(subjectRunner).forEachTarget(any(), any());
        when(evalService.acceptEvent(any())).thenReturn(true).thenReturn(false);

        TaskRunResult r = executor.execute(1L, 7L, new TriggerConfig("s", "e", null));
        assertThat(r.status()).isEqualTo(TaskExecutionStatus.PARTIAL_FAIL);
        assertThat(r.successCount()).isEqualTo(1);
        assertThat(r.errorCount()).isEqualTo(1);
        assertThat(r.errorSummary()).contains("注入失败");
    }
}
```

> 执行前核对 `SubjectTarget` 的实际构造法(现有类型,可能是 `SubjectTarget.of(id)` 或 builder);按真实 API 调整 stub。`acceptEvent` 第二次 false 会触发 20 次重试退避——测试为快,把 `INJECT_MAX_RETRY`/`INJECT_BACKOFF_MS` 设为可注入或在测试用 `when(...).thenReturn(false)` 仅 1 主体避免长等。**执行时:把退避参数改为构造器可注入(默认值不变),测试传 maxRetry=1, backoff=0。**

- [ ] **Step 3: 跑 + 提交**

Run: `$MVN -pl rule-job-svc -am test -Dtest='TriggerExecutorTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS

```bash
git add rule-job-svc/src/main/java/com/sstlfsj/rule/job/internal/runner/TriggerExecutor.java rule-job-svc/src/test/java/com/sstlfsj/rule/job/internal/runner/TriggerExecutorTest.java
git commit -m "feat(job): TriggerExecutor —— JobRunner 逻辑迁为 TRIGGER executor"
```

---

### Task 6: ScheduledTaskScheduleManager — 注册 + runById 分发 + 执行记录生命周期

**Files:**
- Create: `rule-job-svc/.../internal/service/ScheduledTaskScheduleManager.java`
- Test: `rule-job-svc/.../internal/service/ScheduledTaskScheduleManagerTest.java`

- [ ] **Step 1: 写 ScheduleManager(register/unregister + runById:重载→dispatch→落执行记录)**

```java
package com.sstlfsj.rule.job.internal.service;

import com.sstlfsj.rule.job.api.TaskRunResult;
import com.sstlfsj.rule.job.internal.domain.ScheduledTask;
import com.sstlfsj.rule.job.internal.domain.ScheduledTaskExecution;
import com.sstlfsj.rule.job.internal.domain.TaskExecutionStatus;
import com.sstlfsj.rule.job.internal.domain.TaskStatus;
import com.sstlfsj.rule.job.internal.repository.ScheduledTaskExecutionMapper;
import com.sstlfsj.rule.job.internal.repository.ScheduledTaskMapper;
import com.sstlfsj.rule.job.internal.runner.TaskExecutorRegistry;
import com.sstlfsj.rule.kernel.api.spi.scheduler.Scheduler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/** 任务↔调度器中介 + 执行编排:cron 触发 runById → 重载 → dispatch → 写 scheduled_task_execution。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduledTaskScheduleManager {

    private final Scheduler scheduler;
    private final ScheduledTaskMapper taskMapper;
    private final ScheduledTaskExecutionMapper executionMapper;
    private final TaskExecutorRegistry executorRegistry;

    /** 注册到调度器(cron 触发回 runById,config 触发时重载)。 */
    public void register(ScheduledTask task) {
        Long id = task.getId();
        scheduler.schedule(key(id), task.getCron(), () -> runById(id));
    }

    /** 从调度器撤销。 */
    public void unregister(Long taskId) {
        scheduler.unschedule(key(taskId));
    }

    /** 手动触发一次(管理能力,不经调度器);返回执行记录。 */
    public ScheduledTaskExecution runOnce(Long taskId) {
        return doRun(taskMapper.selectById(taskId));
    }

    private void runById(Long taskId) {
        ScheduledTask latest = taskMapper.selectById(taskId);
        if (latest != null && latest.getStatus() == TaskStatus.ACTIVE) {
            doRun(latest);
        }
    }

    /** 执行编排:建 RUNNING 记录 → dispatch executor → 用 TaskRunResult 终结记录。 */
    private ScheduledTaskExecution doRun(ScheduledTask task) {
        ScheduledTaskExecution exec = new ScheduledTaskExecution();
        exec.setScheduledTaskId(task.getId());
        exec.setTenantId(task.getTenantId());
        exec.setTriggerAt(LocalDateTime.now());
        exec.setStatus(TaskExecutionStatus.RUNNING);
        exec.setProcessedCount(0);
        exec.setSuccessCount(0);
        exec.setErrorCount(0);
        executionMapper.insert(exec);
        try {
            TaskRunResult r = executorRegistry.dispatch(task);
            exec.setStatus(r.status());
            exec.setProcessedCount(r.processedCount());
            exec.setSuccessCount(r.successCount());
            exec.setErrorCount(r.errorCount());
            exec.setErrorSummary(r.errorSummary());
        } catch (RuntimeException e) {
            exec.setStatus(TaskExecutionStatus.FAILED);
            exec.setErrorSummary("执行异常: " + e.getMessage());
            log.warn("调度任务执行异常 taskId={}", task.getId(), e);
        }
        exec.setFinishedAt(LocalDateTime.now());
        executionMapper.updateById(exec);
        return exec;
    }

    private String key(Long taskId) {
        return "scheduled-task:" + taskId;
    }
}
```

- [ ] **Step 2: 写测试(mock 各依赖,验记录生命周期 RUNNING→终态)**

```java
package com.sstlfsj.rule.job.internal.service;

import com.sstlfsj.rule.job.api.TaskRunResult;
import com.sstlfsj.rule.job.internal.domain.*;
import com.sstlfsj.rule.job.internal.repository.ScheduledTaskExecutionMapper;
import com.sstlfsj.rule.job.internal.repository.ScheduledTaskMapper;
import com.sstlfsj.rule.job.internal.runner.TaskExecutorRegistry;
import com.sstlfsj.rule.kernel.api.spi.scheduler.Scheduler;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ScheduledTaskScheduleManagerTest {

    private final Scheduler scheduler = mock(Scheduler.class);
    private final ScheduledTaskMapper taskMapper = mock(ScheduledTaskMapper.class);
    private final ScheduledTaskExecutionMapper execMapper = mock(ScheduledTaskExecutionMapper.class);
    private final TaskExecutorRegistry registry = mock(TaskExecutorRegistry.class);
    private final ScheduledTaskScheduleManager mgr =
            new ScheduledTaskScheduleManager(scheduler, taskMapper, execMapper, registry);

    @Test
    void runOnce_writesRunningThenFinalizes() {
        ScheduledTask task = new ScheduledTask();
        task.setId(5L); task.setTenantId(7L); task.setTaskType(TaskType.TRIGGER);
        when(taskMapper.selectById(5L)).thenReturn(task);
        when(registry.dispatch(task))
                .thenReturn(new TaskRunResult(TaskExecutionStatus.SUCCESS, 3, 3, 0, null));

        ScheduledTaskExecution exec = mgr.runOnce(5L);

        verify(execMapper).insert(any(ScheduledTaskExecution.class)); // RUNNING 先落
        verify(execMapper).updateById(any(ScheduledTaskExecution.class)); // 终态回写
        assertThat(exec.getStatus()).isEqualTo(TaskExecutionStatus.SUCCESS);
        assertThat(exec.getProcessedCount()).isEqualTo(3);
        assertThat(exec.getFinishedAt()).isNotNull();
    }

    @Test
    void dispatchThrows_recordedFailed() {
        ScheduledTask task = new ScheduledTask();
        task.setId(5L); task.setTenantId(7L); task.setTaskType(TaskType.TRIGGER);
        when(taskMapper.selectById(5L)).thenReturn(task);
        when(registry.dispatch(task)).thenThrow(new IllegalStateException("boom"));

        ScheduledTaskExecution exec = mgr.runOnce(5L);
        assertThat(exec.getStatus()).isEqualTo(TaskExecutionStatus.FAILED);
        assertThat(exec.getErrorSummary()).contains("boom");
    }
}
```

- [ ] **Step 3: 跑 + 提交**

Run: `$MVN -pl rule-job-svc -am test -Dtest='ScheduledTaskScheduleManagerTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS

```bash
git add rule-job-svc/src/main/java/com/sstlfsj/rule/job/internal/service/ScheduledTaskScheduleManager.java rule-job-svc/src/test/java/com/sstlfsj/rule/job/internal/service/ScheduledTaskScheduleManagerTest.java
git commit -m "feat(job): ScheduledTaskScheduleManager —— 注册+runById 分发+执行记录编排"
```

---

### Task 7: ScheduledTaskScanner(@RuleJob seed) + StartupRegistrar

**Files:**
- Create: `rule-job-svc/.../internal/service/ScheduledTaskScanner.java`(替换 `RuleJobScanner`)
- Create: `rule-job-svc/.../internal/service/ScheduledTaskStartupRegistrar.java`(替换 `JobStartupRegistrar`)
- Reference: 现有 `RuleJobScanner`/`JobStartupRegistrar`(逻辑等价,改落 scheduled_task TRIGGER 型)

- [ ] **Step 1: 写 ScheduledTaskScanner(@RuleJob → upsert scheduled_task TRIGGER)**

```java
package com.sstlfsj.rule.job.internal.service;

import com.sstlfsj.rule.job.api.BeanMethodQuery;
import com.sstlfsj.rule.job.api.TaskType;
import com.sstlfsj.rule.job.api.TriggerConfig;
import com.sstlfsj.rule.job.api.annotation.RuleJob;
import com.sstlfsj.rule.job.internal.domain.ScheduledTask;
import com.sstlfsj.rule.job.internal.domain.TaskStatus;
import com.sstlfsj.rule.job.internal.repository.ScheduledTaskMapper;
import com.sstlfsj.rule.job.internal.subject.BeanMethodRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Method;

/** 扫 @RuleJob 注解 → 注册 BeanMethodRegistry + upsert scheduled_task(TRIGGER 型)。 */
@Slf4j
@Component
@RequiredArgsConstructor
class ScheduledTaskScanner implements SmartInitializingSingleton {

    private final ApplicationContext applicationContext;
    private final ScheduledTaskMapper taskMapper;
    private final BeanMethodRegistry registry;

    @Override
    public void afterSingletonsInstantiated() {
        int count = 0;
        for (String beanName : applicationContext.getBeanDefinitionNames()) {
            Class<?> type;
            try { type = applicationContext.getType(beanName); }
            catch (RuntimeException e) { continue; }
            if (type == null) continue;
            for (Method method : ClassUtils.getUserClass(type).getMethods()) {
                RuleJob ann = AnnotationUtils.findAnnotation(method, RuleJob.class);
                if (ann != null) {
                    registerRuleJob(applicationContext.getBean(beanName), method, ann);
                    count++;
                }
            }
        }
        if (count > 0) log.info("[scheduled-task] @RuleJob 扫描完成,注册 {} 个 TRIGGER 任务", count);
    }

    private void registerRuleJob(Object bean, Method method, RuleJob ann) {
        String ref = method.getDeclaringClass().getName() + "#" + method.getName();
        registry.register(ref, bean, method);
        Long tenantId = Long.valueOf(ann.tenant());
        String name = ann.name().isBlank() ? ann.code() : ann.name();
        // config typed:TriggerConfig(scene, eventType, BeanMethodQuery(ref))
        TriggerConfig config = new TriggerConfig(ann.scene(), ann.eventType(), new BeanMethodQuery(ref));
        ScheduledTask existing = taskMapper.findByTenantCode(tenantId, ann.code());
        if (existing == null) {
            ScheduledTask t = new ScheduledTask();
            t.setTenantId(tenantId);
            t.setCode(ann.code());
            t.setName(name);
            t.setTaskType(TaskType.TRIGGER);
            t.setCron(ann.cron());
            t.setConfig(config);
            t.setStatus(TaskStatus.ACTIVE);
            t.setCreatedBy("@RuleJob");
            taskMapper.insert(t);
        } else {
            // 已存在:更新 config/cron/name(代码定义为准),保留 status(运维启停优先)
            existing.setName(name);
            existing.setCron(ann.cron());
            existing.setConfig(config);
            existing.setUpdatedBy("@RuleJob");
            taskMapper.updateById(existing);
        }
        log.info("[scheduled-task] TRIGGER: code={} cron={} scene={} ref={}", ann.code(), ann.cron(), ann.scene(), ref);
    }
}
```

> 核对现有 `RuleJobScanner.upsert` 的"已存在"分支语义(本步按"代码定义更新 config/cron/name、保留 status"重写,与现有等价或更明确)。`RuleJob` 注解字段(`tenant/scene/code/name/cron/eventType`)沿用。

- [ ] **Step 2: 写 StartupRegistrar**

```java
package com.sstlfsj.rule.job.internal.service;

import com.sstlfsj.rule.job.internal.domain.ScheduledTask;
import com.sstlfsj.rule.job.internal.repository.ScheduledTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/** 启动期把全部 ACTIVE 任务注册到调度器(多实例由 XXL admin 单实例派发)。 */
@Slf4j
@Component
@RequiredArgsConstructor
class ScheduledTaskStartupRegistrar implements ApplicationRunner {

    private final ScheduledTaskMapper taskMapper;
    private final ScheduledTaskScheduleManager scheduleManager;

    @Override
    public void run(ApplicationArguments args) {
        List<ScheduledTask> active = taskMapper.findAllActive();
        active.forEach(scheduleManager::register);
        log.info("调度任务启动注册完成,注册 {} 个 ACTIVE 任务", active.size());
    }
}
```

- [ ] **Step 3: 跑模块测试(无新单测,验编译+既有绿)+ 提交**

Run: `$MVN -pl rule-job-svc -am test -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 编译通过(此时旧 RuleJobScanner/JobStartupRegistrar 仍在 → Task 8 删;若 bean 重复冲突,Task 8 一并解决,本步可能需先标记旧的 @Component 移除——见 Task 8 顺序说明)

> **顺序说明**:Task 7 新增的 Scanner/Registrar 与旧的 `RuleJobScanner`/`JobStartupRegistrar` 会 bean 冲突(都扫 @RuleJob / 都注册)。**为避免半截状态,Task 7 与 Task 8 连续做、一次提交**:先写新类(本 Task)→ 立即做 Task 8 删旧 → 再统一编译/测试/提交。故本步不单独提交,跳到 Task 8。

---

### Task 8: 删旧 job 后端 + ScheduledTaskService/Controller(API)

**Files:**
- Delete: `internal/runner/JobRunner.java`、`internal/service/JobScheduleManager.java`、`internal/service/RuleJobScanner.java`、`internal/service/JobStartupRegistrar.java`、`internal/service/JobServiceImpl.java`、`internal/domain/JobDefinition.java`、`internal/domain/JobExecution.java`、`internal/domain/JobStatus.java`、`internal/domain/JobExecutionStatus.java`、`internal/repository/JobDefinitionMapper.java`、`internal/repository/JobExecutionMapper.java`、`api/service/JobService.java`、`api/dto/JobDefinitionDto.java`、`api/dto/JobExecutionVO.java`、`api/JobPage.java`(核对实际清单 `git rm`)
- Delete: `rule-api/.../web/admin/JobController.java`
- Create: `rule-job-svc/.../api/service/ScheduledTaskService.java`
- Create: `rule-job-svc/.../internal/service/ScheduledTaskServiceImpl.java`
- Create: `rule-job-svc/.../api/dto/ScheduledTaskVO.java`、`api/dto/ScheduledTaskExecutionVO.java`
- Create: `rule-api/.../web/admin/ScheduledTaskController.java`
- Reference: 现有 `JobService`/`JobServiceImpl`/`JobController`(形状沿用:enable/disable/list/get/triggerOnce/recentExecutions)

- [ ] **Step 1: 写 ScheduledTaskService api(沿用 JobService 形状,术语换 task)**

```java
package com.sstlfsj.rule.job.api.service;

import com.sstlfsj.rule.job.api.dto.ScheduledTaskExecutionVO;
import com.sstlfsj.rule.job.api.dto.ScheduledTaskVO;

import java.util.List;

/** 调度任务管理:启用/禁用/查询/手动触发/执行记录(通用,跨 task_type)。 */
public interface ScheduledTaskService {
    void enable(Long tenantId, Long taskId);
    void disable(Long tenantId, Long taskId);
    List<ScheduledTaskVO> list(Long tenantId);
    ScheduledTaskVO get(Long tenantId, Long taskId);
    ScheduledTaskExecutionVO triggerOnce(Long tenantId, Long taskId);
    List<ScheduledTaskExecutionVO> recentExecutions(Long tenantId, Long taskId, int limit);
}
```

- [ ] **Step 2: 写 VO（typed,task_type + config 暴露给前端;config 出契约转 typed 即可,JSON 序列化）**

```java
package com.sstlfsj.rule.job.api.dto;

import com.sstlfsj.rule.job.api.TaskConfig;
import com.sstlfsj.rule.job.api.TaskType;

import java.time.Instant;

/** 调度任务视图。 */
public record ScheduledTaskVO(Long id, Long tenantId, String code, String name, TaskType taskType,
                              String cron, TaskConfig config, String status, Instant createdAt, Instant updatedAt) {}
```

```java
package com.sstlfsj.rule.job.api.dto;

import java.time.Instant;

/** 执行记录视图。 */
public record ScheduledTaskExecutionVO(Long id, Long scheduledTaskId, String status, Integer processedCount,
                                       Integer successCount, Integer errorCount, String errorSummary,
                                       Instant triggerAt, Instant finishedAt) {}
```

- [ ] **Step 3: 写 ScheduledTaskServiceImpl(沿用 JobServiceImpl 的 enable/disable/查询逻辑,落 scheduled_task)**

```java
package com.sstlfsj.rule.job.internal.service;

import com.sstlfsj.rule.job.api.dto.ScheduledTaskExecutionVO;
import com.sstlfsj.rule.job.api.dto.ScheduledTaskVO;
import com.sstlfsj.rule.job.api.service.ScheduledTaskService;
import com.sstlfsj.rule.job.internal.domain.ScheduledTask;
import com.sstlfsj.rule.job.internal.domain.ScheduledTaskExecution;
import com.sstlfsj.rule.job.internal.domain.TaskStatus;
import com.sstlfsj.rule.job.internal.repository.ScheduledTaskExecutionMapper;
import com.sstlfsj.rule.job.internal.repository.ScheduledTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.time.LocalDateTime;
import java.util.List;

/** ScheduledTaskService 实现。 */
@Service
@RequiredArgsConstructor
public class ScheduledTaskServiceImpl implements ScheduledTaskService {

    private final ScheduledTaskMapper taskMapper;
    private final ScheduledTaskExecutionMapper executionMapper;
    private final ScheduledTaskScheduleManager scheduleManager;

    @Override
    @Transactional
    public void enable(Long tenantId, Long taskId) {
        ScheduledTask task = require(tenantId, taskId);
        task.setStatus(TaskStatus.ACTIVE);
        taskMapper.updateById(task);
        scheduleManager.register(task);
    }

    @Override
    @Transactional
    public void disable(Long tenantId, Long taskId) {
        ScheduledTask task = require(tenantId, taskId);
        task.setStatus(TaskStatus.DISABLED);
        taskMapper.updateById(task);
        scheduleManager.unregister(taskId);
    }

    @Override
    public List<ScheduledTaskVO> list(Long tenantId) {
        return taskMapper.findByTenant(tenantId).stream().map(this::toVO).toList();
    }

    @Override
    public ScheduledTaskVO get(Long tenantId, Long taskId) {
        return toVO(require(tenantId, taskId));
    }

    @Override
    public ScheduledTaskExecutionVO triggerOnce(Long tenantId, Long taskId) {
        require(tenantId, taskId);
        return toExecVO(scheduleManager.runOnce(taskId));
    }

    @Override
    public List<ScheduledTaskExecutionVO> recentExecutions(Long tenantId, Long taskId, int limit) {
        require(tenantId, taskId);
        return executionMapper.recentByTask(taskId, limit).stream().map(this::toExecVO).toList();
    }

    private ScheduledTask require(Long tenantId, Long taskId) {
        ScheduledTask t = taskMapper.selectById(taskId);
        if (t == null || !tenantId.equals(t.getTenantId())) {
            throw new IllegalArgumentException("调度任务不存在: id=" + taskId + ", tenantId=" + tenantId);
        }
        return t;
    }

    private ScheduledTaskVO toVO(ScheduledTask t) {
        return new ScheduledTaskVO(t.getId(), t.getTenantId(), t.getCode(), t.getName(), t.getTaskType(),
                t.getCron(), t.getConfig(), t.getStatus().name(), toInstant(t.getCreatedAt()), toInstant(t.getUpdatedAt()));
    }

    private ScheduledTaskExecutionVO toExecVO(ScheduledTaskExecution e) {
        return new ScheduledTaskExecutionVO(e.getId(), e.getScheduledTaskId(), e.getStatus().name(),
                e.getProcessedCount(), e.getSuccessCount(), e.getErrorCount(), e.getErrorSummary(),
                toInstant(e.getTriggerAt()), toInstant(e.getFinishedAt()));
    }

    private static java.time.Instant toInstant(LocalDateTime t) {
        return t == null ? null : t.atZone(ZoneId.systemDefault()).toInstant();
    }
}
```

> 现有 `JobServiceImpl.enableJob` 有"绑定 Scene 为 PULL 时拒绝启用"校验。**核对该校验**:若 TRIGGER 仍需此约束,在 `enable` 内对 `task.getConfig()` 为 `TriggerConfig` 时查 scene mode 校验(沿用现有逻辑);非 TRIGGER 跳过。执行时按现有 `JobServiceImpl` 补回该校验。

- [ ] **Step 4: 写 ScheduledTaskController(沿用 JobController 形状,路径 /admin/v1/scheduled-tasks)**

```java
package com.sstlfsj.rule.web.admin;

import com.sstlfsj.rule.job.api.dto.ScheduledTaskExecutionVO;
import com.sstlfsj.rule.job.api.dto.ScheduledTaskVO;
import com.sstlfsj.rule.job.api.service.ScheduledTaskService;
import com.sstlfsj.rule.web.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 调度任务管理入口。 */
@RestController
@RequestMapping("/admin/v1/scheduled-tasks")
@RequiredArgsConstructor
public class ScheduledTaskController {

    private final ScheduledTaskService service;

    @GetMapping
    public ApiResponse<List<ScheduledTaskVO>> list(@RequestParam Long tenantId) {
        return ApiResponse.ok(service.list(tenantId));
    }

    @GetMapping("/{taskId}")
    public ApiResponse<ScheduledTaskVO> get(@PathVariable Long taskId, @RequestParam Long tenantId) {
        return ApiResponse.ok(service.get(tenantId, taskId));
    }

    @PostMapping("/{taskId}/enable")
    public ApiResponse<Void> enable(@PathVariable Long taskId, @RequestParam Long tenantId) {
        service.enable(tenantId, taskId);
        return ApiResponse.ok(null);
    }

    @PostMapping("/{taskId}/disable")
    public ApiResponse<Void> disable(@PathVariable Long taskId, @RequestParam Long tenantId) {
        service.disable(tenantId, taskId);
        return ApiResponse.ok(null);
    }

    @PostMapping("/{taskId}/trigger")
    public ApiResponse<ScheduledTaskExecutionVO> trigger(@PathVariable Long taskId, @RequestParam Long tenantId) {
        return ApiResponse.ok(service.triggerOnce(tenantId, taskId));
    }

    @GetMapping("/{taskId}/executions")
    public ApiResponse<List<ScheduledTaskExecutionVO>> executions(
            @PathVariable Long taskId, @RequestParam Long tenantId,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(service.recentExecutions(tenantId, taskId, limit));
    }
}
```

- [ ] **Step 5: 删旧文件 + 删旧测试**

```bash
cd rule-job-svc/src/main/java/com/sstlfsj/rule/job
git rm internal/runner/JobRunner.java internal/service/JobScheduleManager.java \
       internal/service/RuleJobScanner.java internal/service/JobStartupRegistrar.java \
       internal/service/JobServiceImpl.java internal/domain/JobDefinition.java \
       internal/domain/JobExecution.java internal/domain/JobStatus.java \
       internal/domain/JobExecutionStatus.java internal/repository/JobDefinitionMapper.java \
       internal/repository/JobExecutionMapper.java api/service/JobService.java \
       api/dto/JobDefinitionDto.java api/dto/JobExecutionVO.java api/JobPage.java
cd /Users/sunke/dev/ai-project/rule-engine
git rm rule-api/src/main/java/com/sstlfsj/rule/web/admin/JobController.java
# 删旧 job 测试(核对实际:JobRunnerTest / JobServiceImplTest / JobControllerTest 等)
git rm $(git ls-files 'rule-job-svc/src/test/**/*Job*Test.java' 'rule-api/src/test/**/JobControllerTest.java' 2>/dev/null)
```

> 删除是破坏性操作:执行前 `git grep -l 'JobService\|JobDefinition\|JobExecution\|JobRunner\|JobController'` 找全引用点(含 `JobAutoConfiguration` / DemoFraudJob 引用 / rule-app 装配 / 测试),逐一改指向新类型或删除。`@RuleJob`/`SubjectQuery`/`SubjectQueryRunner`/`BeanMethod*`/`JobTarget`/`EventIdHasher`/`Scheduler` SPI/`XxlJobSchedulerAdapter` **保留不删**。

- [ ] **Step 6: 全量编译 + 测试**

Run: `$MVN -pl rule-job-svc,rule-api -am test -Dsurefire.failIfNoSpecifiedTests=false`
Expected: BUILD SUCCESS(无残留引用旧 Job 类型)

- [ ] **Step 7: 提交**

```bash
git add -A
git commit -m "feat(job): ScheduledTaskService/Controller + 删旧 job 后端, /admin/v1/scheduled-tasks"
```

---

### Task 9: 装配核对 + 全量回归 + 真实服务 e2e

- [ ] **Step 1: 核对装配**

`git grep -n 'job_definition\|job_execution\|JobDefinition\|JobExecution\|RuleJobScanner\|JobStartupRegistrar'` 应只剩文档/迁移注释;`JobAutoConfiguration` 若引用已删类型,改注册新 bean(ScheduledTaskScanner/Registrar/ScheduleManager/Executor 由 @Component 扫描即可,核对 autoconfig 是否需显式声明)。

- [ ] **Step 2: 全量 clean test**

Run: `$MVN clean test`
Expected: BUILD SUCCESS（含 testcontainers 集成测试,Flyway 应用 V1_37）

- [ ] **Step 3: 真实服务 e2e(涉 schema + 调度落库链路)**

1. 打包起 rule-app(连本地 MySQL),确认 Flyway 到 V1_37、`scheduled_task` 建表、`@RuleJob` 示例(DemoFraudJob)seed 成 TRIGGER 行。
2. 查 `scheduled_task`:确认 DemoFraudJob 落 TRIGGER 行 + config 是 typed JSON(`{"kind":"TRIGGER","sceneCode":...}`)。
3. `GET /admin/v1/scheduled-tasks?tenantId=...` 列表;`POST /{id}/trigger` 手动触发一次。
4. 查 `scheduled_task_execution`:确认执行记录真落库(RUNNING→终态、processed/success/error 计数对)。
5. 验 TRIGGER 端到端:触发后查 `evaluation_session` 有对应 JOB source 的评估(链路通)。
6. 清理:删本次测试 `scheduled_task`/`scheduled_task_execution` 行(@RuleJob seed 的可留,基线)。

- [ ] **Step 4: 文档同步**

`docs/05-storage.md` §3.10 Job DDL → scheduled_task;`docs/10-api-contract.md` /admin/v1/jobs → /admin/v1/scheduled-tasks;相关 D11/D48 决策注记"job_definition 重写为通用 scheduled_task 框架"。跨文档先跑 `doc-consistency-review`,派 `rule-engine-reviewer` 审代码↔文档对齐。提交。

---

## Self-Review

**Spec 覆盖**(对 spec §3–§5):
- §3.1 scheduled_task↔XXL(taskId 载荷+runById 重载) → Task 6 `runById` ✅
- §4.1 单表+TaskType+typed config → Task 1/2/3 ✅
- §4.2 sealed TaskConfig 多态 JSON+TypeHandler → Task 2/3 ✅
- §4.3 TaskExecutor SPI 收集+路由 → Task 4 ✅
- §4.4 统一 scheduled_task_execution → Task 1/6 ✅
- §4.5 删/留/迁(job_definition→scheduled_task、JobRunner→TriggerExecutor、@RuleJob seed、保留 Scheduler SPI/XXL/SubjectQuery) → Task 5/7/8 ✅
- §5 RETENTION/ALARM 不进框架 → 本计划未触碰它们(仍 @Scheduled),正确 ✅(ShedLock 是 track #3)
- 前端 job→scheduled-task → **本计划不含**(track #1 后续前端计划),已在范围声明 ✅

**Placeholder 扫描**:无 TBD。Task 4 Step 1/2 含"决策:枚举提 api 包"与"execute 签名改 (taskId,tenantId,config)"是设计澄清,已给最终代码;Task 5/7/8 多处"执行时核对现有 X"是迁移核对提示(非空泛占位,指明核对对象)。

**类型一致**:`TaskConfig`/`TriggerConfig`/`TaskType`/`TaskRunResult`/`TaskExecutor.execute(long,long,C)`/`TaskExecutorRegistry.dispatch(ScheduledTask)`/`ScheduledTaskScheduleManager.runOnce/register`/`ScheduledTask{taskType,config,status}`/`ScheduledTaskExecution{processedCount...}` 跨 Task 一致。`TaskStatus`/`TaskExecutionStatus` 放 **api 包**(Task 4 决策),Task 3 import 按 api 包写。

**执行期风险**(实现时确认):
1. `Jackson3TypeHandler` 对 sealed + @JsonTypeInfo 的多态还原:确认 MyBatis-Plus 该 TypeHandler 用 Spring 全局/默认 ObjectMapper 且认 Jackson3 注解(RuleVersion 已验证同款,大概率 OK;Task 3 集成测试或 e2e 验真实 JSON 列还原子类型)。
2. `SubjectTarget` 构造 API:Task 5 测试 stub 按现有真实类型调整。
3. `JobAutoConfiguration` / rule-app 对旧 Job 类型的引用:Task 8/9 全 grep 清。
4. enable 的 "PULL scene 拒绝" 校验:Task 8 按现有 JobServiceImpl 补回。
