# 数据保留/清理 Job Implementation Plan

> **For agentic workers:** 按 task 逐个实现,TDD + 频繁提交。

**Goal:** 实现"规划中:数据保留/清理 job"——定时按 `engine.rule.retention.*` 配置删除超期的运行/历史表行,防止无限膨胀。

**Architecture (已对齐):** 各模块**各自调各自的**(方案 B,守 Modulith)。`rule-observability` 调度清理它自己两张 trace 表(`node_trace`/`dry_run_node_trace`),`rule-eval-svc` 调度清理它自己两张 session 表(`evaluation_session`/`dry_run_session`)。各模块一个 `@Scheduled` 清理 bean + 一份 `RetentionProperties`(同 `engine.rule.retention` 前缀、各取子集,沿用 `TraceProperties`/`TraceWriterProperties` 模式)。`@EnableScheduling` 放 rule-app。删除走 `DELETE ... LIMIT n` 循环分批(短事务、幂等、可恢复)。无跨模块事件(现有 `DomainEventPublisher` 是 eval-svc 内部、A 类 `@ApplicationModuleListener` 要事务性生产者,均不适配 `@Scheduled` 心跳)。

**Tech Stack:** Java 25, Spring Boot 4, MyBatis-Plus(`BaseMapper` + `LambdaQueryWrapper` default 方法,无 JdbcTemplate), Flyway, `@Scheduled`。

**测试环境:** `mvn-env` skill 设 JAVA_HOME(JDK 25)+ `$MVN`;跨模块带 `-am`;收尾全量 `$MVN clean test`。

**4 表 age 列(无 FK,删除顺序逻辑安全):**
| 表 | 保留键 | age 列 | 属主模块 |
|---|---|---|---|
| `node_trace` | `node-trace-days`(30) | `evaluated_at` | observability |
| `dry_run_node_trace` | `dry-run-session-days`(7) | `evaluated_at` | observability |
| `evaluation_session` | `evaluation-session-days`(90) | `started_at` | eval-svc |
| `dry_run_session` | `dry-run-session-days`(7) | `started_at` | eval-svc |

**配置(最终 application.yml `engine.rule.retention`):**
```yaml
retention:
  enabled: true
  cron: "0 30 3 * * *"      # 每日 03:30
  batch-size: 1000
  evaluation-session-days: 90
  node-trace-days: 30
  dry-run-session-days: 7   # 同管 dry_run 两张表
```

**实现决策(锁定):** 机制=@Scheduled per-module;cron/batch-size/enabled 走配置;dry_run 两表共用 7d;`dry_run_node_trace.evaluated_at` 加索引(V1_25);`@EnableScheduling` 在 rule-app。

---

## Task R1: V1_25 迁移 — dry_run_node_trace.evaluated_at 索引

**Files:** Create `rule-config-svc/src/main/resources/db/migration/V1_25__dry_run_node_trace_evaluated_idx.sql`

按 evaluated_at 范围 DELETE 需索引,否则全表扫。先读 V1_0 里 `dry_run_node_trace` 的 DDL 确认现有索引命名风格(`idx_*`)与列名。

```sql
-- 数据保留清理:dry_run_node_trace 按 evaluated_at 范围删,补索引避免全表扫
ALTER TABLE dry_run_node_trace ADD KEY idx_evaluated_at (evaluated_at);
```

验证:跑一个会 boot Flyway 的 config 集成测试(如 `MetricVersioningIntegrationTest`)确认 V1_25 应用无误。提交。

---

## Task R2: rule-observability 清理(node_trace + dry_run_node_trace)

**Files:**
- Create `rule-observability/src/main/java/com/sstlfsj/rule/observability/internal/retention/RetentionProperties.java`
- Create `rule-observability/src/main/java/com/sstlfsj/rule/observability/internal/retention/TraceRetentionCleaner.java`
- Modify `NodeTraceMapper.java`、`DryRunNodeTraceMapper.java`(加 purge default 方法)
- Modify `ObservabilityAutoConfiguration.java`(`@EnableConfigurationProperties(RetentionProperties.class)`)
- Tests: `NodeTraceMapperTest`/`DryRunNodeTraceMapperTest`(追加 purge 用例)、`TraceRetentionCleanerTest`(新建)

**RetentionProperties**(读子集;模板 `TraceWriterProperties`):
```java
@Getter @Setter
@ConfigurationProperties(prefix = "engine.rule.retention")
public class RetentionProperties {
    private boolean enabled = true;
    private int nodeTraceDays = 30;
    private int dryRunSessionDays = 7;
    private int batchSize = 1000;
}
```

**Mapper purge default 方法**(`BaseMapper`+`LambdaQueryWrapper`,分批 `LIMIT`;模板 `DryRunSessionMapper.markFinal`):
```java
default int purgeOlderThan(LocalDateTime cutoff, int batchSize) {
    return delete(new LambdaQueryWrapper<NodeTraceEntity>()
            .lt(NodeTraceEntity::getEvaluatedAt, cutoff)
            .last("LIMIT " + batchSize));   // batchSize 是常量 int,无注入风险
}
```
(DryRunNodeTraceMapper 同理,字段 `DryRunNodeTraceEntity::getEvaluatedAt`。)

**TraceRetentionCleaner**(`@Scheduled` bean,`@ConditionalOnProperty(enabled, matchIfMissing=true)`):
```java
@Component
@ConditionalOnProperty(name = "engine.rule.retention.enabled", matchIfMissing = true)
public class TraceRetentionCleaner {
    private static final Logger log = LoggerFactory.getLogger(TraceRetentionCleaner.class);
    private final NodeTraceMapper nodeTraceMapper;
    private final DryRunNodeTraceMapper dryRunNodeTraceMapper;
    private final RetentionProperties props;
    // 构造器注入

    /** 删超期 trace;两表各按自己保留窗,分批循环短事务。 */
    @Scheduled(cron = "${engine.rule.retention.cron:0 30 3 * * *}")
    public void purge() {
        int nt = purgeLoop(c -> nodeTraceMapper.purgeOlderThan(c, props.getBatchSize()),
                LocalDateTime.now().minusDays(props.getNodeTraceDays()));
        int dr = purgeLoop(c -> dryRunNodeTraceMapper.purgeOlderThan(c, props.getBatchSize()),
                LocalDateTime.now().minusDays(props.getDryRunSessionDays()));
        log.info("retention 清理 trace 完成 node_trace={} dry_run_node_trace={}", nt, dr);
    }

    private int purgeLoop(java.util.function.ToIntFunction<LocalDateTime> del, LocalDateTime cutoff) {
        int total = 0, n;
        do { n = del.applyAsInt(cutoff); total += n; } while (n == props.getBatchSize());
        return total;
    }
}
```
> 确认 `NodeTraceEntity`/`DryRunNodeTraceEntity` 的 `getEvaluatedAt()` 类型为 `LocalDateTime`(随 DDL 列);若不同步调 cutoff 类型。`@ConditionalOnProperty` import `org.springframework.boot.autoconfigure.condition.ConditionalOnProperty`。

**Tests**(英文方法名,中文注释):mapper 测试插跨 cutoff 行,断言仅过期行删 + 批循环终止;cleaner 测试 mock 两 mapper,验 cutoff 正确(`minusDays`)+ 两表都调。

跑 `$MVN -pl rule-observability -am test`。提交。

---

## Task R3: rule-eval-svc 清理(evaluation_session + dry_run_session)

**Files:**
- Create `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/retention/RetentionProperties.java`(读子集:`evaluationSessionDays=90`、`dryRunSessionDays=7`、`batchSize`、`enabled`)
- Create `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/retention/SessionRetentionCleaner.java`
- Modify `EvaluationSessionMapper.java`、`DryRunSessionMapper.java`(加 `purgeOlderThan(cutoff, batchSize)`,按 `getStartedAt`)
- Modify `EvalAutoConfiguration.java`(`@EnableConfigurationProperties(RetentionProperties.class)`)
- Tests: 两 mapper purge 用例 + `SessionRetentionCleanerTest`

结构同 R2:`SessionRetentionCleaner` 注入两 session mapper + 本模块 RetentionProperties,`@Scheduled(cron=...)` purge `evaluation_session`(90d)+ `dry_run_session`(7d),分批循环,日志报每表删除数。mapper purge 按 `EvaluationSession::getStartedAt` / `DryRunSession::getStartedAt`(读实体确认 getter)。

> 两模块各有一份 `RetentionProperties`(同前缀、各子集)——这是 `TraceProperties`/`TraceWriterProperties` 既定模式,非重复。

跑 `$MVN -pl rule-eval-svc -am test`。提交。

---

## Task R4: rule-app @EnableScheduling + application.yml

**Files:**
- Modify rule-app 启动类或一个 `@Configuration`(加 `@EnableScheduling`)
- Modify `rule-app/src/main/resources/application.yml`(`engine.rule.retention`:加 `enabled`/`cron`/`batch-size`,删"规划中…未实现"注释)

`@EnableScheduling` 加在 `RuleEngineApplication` 或新建小 `@Configuration`(就近装配)。application.yml 把现有 `retention:` 段补成上面"配置"块,去掉规划注释。

`$MVN -pl rule-app -am test`。提交。

---

## Task R5: 全量回归 + 功能测试 + 收尾

- **全量 clean test**:`$MVN clean test`(无 -pl),BUILD SUCCESS。
- **功能测试**(真服务,本轮含 schema V1_25 + 真删库):打包起 app(确认 Flyway 到 V1_25)。为 4 张表各插 1 行过期(`started_at`/`evaluated_at` backdate 到超保留窗)+ 1 行新鲜;把 cron 临调到近一两分钟内(或临时 `@Scheduled(fixedDelay)`/手动触发 bean)跑清理;查持久层:**过期行删、新鲜行留**,日志报每表删除数。清理测试数据,恢复基线。
- **rule-engine-reviewer**:审本轮代码↔文档对齐(retention 是新能力,application.yml 注释/docs 若提及需一致)。

---

## Self-Review
- Spec 覆盖:V1_25 索引(R1)、observability 两表清理(R2)、eval-svc 两表清理(R3)、调度启用+配置(R4)、回归+功能测试(R5)。
- 类型一致:`purgeOlderThan(LocalDateTime, int) -> int` 四 mapper 统一;cutoff = `LocalDateTime.now().minusDays(...)`;`RetentionProperties` 每模块一份同前缀。
- 待核现状:`*Entity` 的 age getter 类型(LocalDateTime?)、`dry_run_node_trace` 现有索引命名、各 AutoConfiguration 的 `@EnableConfigurationProperties` 现状。
