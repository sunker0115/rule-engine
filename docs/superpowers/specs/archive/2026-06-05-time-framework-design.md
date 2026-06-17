# B20 时间框架 设计文档

> 来源：backlog B20（B19 扩展）。设计日期 2026-06-05。

## 目标

为规则引擎补全完整的时间能力：

1. 给 `EvalContext` 注入统一时钟 `now`（`Instant`），作为所有时间判断的确定性基础。
2. 实装 `time.window`（规则生效时段）和 `time.occurred_at`（事件业务时间比较）两个内置 conditionType。
3. 把 `DATE` / `DATETIME` 升为一等 dataType，与 B19 的 `ComparisonStrategyFactory` 对接，让 `DATE_BEFORE` / `DATE_AFTER` 走类型化策略并支持 `$now` / `$today` 占位符。
4. 统一时区解析序，使上述三个路径共享同一套规则。

这是 B19 的**扩展**，B19 先落地，本框架在 B19 基础上叠加时间维度。

---

## 现状基线（实装证据）

以下差距从代码直接核实，不是推测。

### EvalContext 无 now 字段

`rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/EvalContext.java`：

```java
public final class EvalContext {
    private final String tenantId;
    private final RuleEvent event;
    private final Subject subject;
    private final Map<String, MetricValue> metrics;

    public EvalContext(String tenantId, RuleEvent event,
                       Subject subject, Map<String, MetricValue> metrics) { ... }
}
```

只有 4 字段、4 参构造器，**没有 `now`**。文档 `01-concepts.md :415` 已描述 `EvalContext.now`，但代码完全没有落地。

### time.window / time.occurred_at 未注册

`KernelEvaluators.defaults()` 注册了 17 个算子（EQ/NEQ/GT/GTE/LT/LTE/IN/NOT_IN/BETWEEN/NOT_BETWEEN/CONTAINS/NOT_CONTAINS/STARTS_WITH/ENDS_WITH/MATCHES/DATE_BEFORE/DATE_AFTER），**没有 `time.window` 和 `time.occurred_at`**。

### DateBeforeEvaluator / DateAfterEvaluator 无 $now / 无时区 / 无 DATE dataType 分支

`DateBeforeEvaluator.toInstant(Object)` 仅做：
- `Instant.parse(s)`（ISO-8601 带时区字符串）
- `LocalDate.parse(s).atStartOfDay().toInstant(ZoneOffset.UTC)`（裸日期，强制 UTC）

无任何 `$now` / `$today` 占位符解析，无 `params.timezone` 读取，无 `dataType=DATE` 对应 `LocalDate` 语义。

### dataType 枚举缺 DATE / DATETIME

`metric_definition.data_type ENUM('LONG','DOUBLE','STRING','BOOLEAN','LIST')`（`05-storage.md:113`）——没有 `DATE` / `DATETIME`。

`01-concepts.md:467` dataType 字段描述同样只列 5 种。

### RuleEvent.occurredAt 已有，§7.3 间接时间（SQL）可用

`RuleEvent.java` 第 13 行：`Instant occurredAt`——已实装，是目前唯一可用的时间字段。

`§7.3` 间接时间（把时间逻辑写进 Metric SQL，用 `NOW()` 或 `:now` 占位符）目前因为 `EvalContext` 没有 `now` 而无法注入，但 SQL 里直接用 `NOW()` 是数据库时间，与引擎时钟解耦——这是 §7.3 目前唯一可用的变通写法，也正是 B21（取数层）要规范化的内容。

---

## 设计决策

| # | 决策 | 取舍 |
|---|------|------|
| 1 | `EvalContext` 加注入式 `now`（`Instant`），评估入口由引擎注入**一次**，整棵 AST 共用 | 确定性 + 跨节点一致 + dry-run 可重放；注入点在 `EvalEngine`/`EvalServiceImpl` 的 `doEvaluate` 入口 |
| 2 | dry-run 把 `now` 纳入 `context_snapshot` 快照 | 重放时还原历史时刻；接续已有的 `contextSnapshot` 机制，无新列 |
| 3 | 时区解析序：字面量自带 offset > `params.timezone` > `Scene.defaultParams.timezone` > 引擎默认 `UTC` | 带 offset 的 DATETIME 自描述；DATE（纯日历日）比较与时区无关，不走此序 |
| 4 | `time.window` 作为 conditionType（内置路径，无 metricCode）：读 `ctx.now`，投影到解析时区的墙上时间 | 与已有闭合集 D20 §3 对齐；无需注册 Metric |
| 5 | `time.occurred_at` 作为 conditionType（内置路径，无 metricCode）：读 `event.occurredAt` | 同上 |
| 6 | `DATE` / `DATETIME` 升为一等 dataType（值 ISO-8601 字符串）；DATE=`LocalDate`（无时区），DATETIME=`Instant`/`OffsetDateTime` | 补全 §3.4 文档与实现的 gap；TYPE 级别在存储枚举和概念文档同步增加 |
| 7 | 时间比较策略并入 B19 的 `ComparisonStrategyFactory`（DATE 策略 / DATETIME 策略） | 统一策略入口；DATE_BEFORE / DATE_AFTER / EQ / NEQ / BETWEEN / NOT_BETWEEN 在 DATE / DATETIME 上委托策略 |
| 8 | 占位符只做 `$now`（→ `Instant`）/ `$today`（→ 当前时区下的 `LocalDate`），由**共享 resolver** 从 `EvalContext.now` + 解析时区计算 | 简单确定；不支持相对 duration（`$now-P7D`）——此类场景走 §7.3 Metric SQL |
| 9 | 近 N 天滚动聚合不进引擎，留 Metric SQL（§7.3）；B21（取数层）负责把引擎 `now` 注入 SQL 占位符 `:now` | 职责分离；B21 独立设计，本框架只引用 |
| 10 | 不用 DB `NOW()`，必须用引擎注入的 `now` | 否则 dry-run 重放失真——DB 时间不受 dry-run `now` 控制 |
| 11 | `ComparisonStrategy` 保持纯（不带 `EvalContext`）；`ctx` 只在 evaluator 的"解析段"流入 | 解析（上下文相关）与比较（纯）解耦——通用两段式管线，见下节 |

---

## 框架原则：解析 → 比较 两段式管线

所有"指标 + 算子 + 操作数"型条件（`metric.threshold` 一族，17 个算子，跨全部 dataType）共用同一条管线：

```
ConditionEvaluator.evaluate(node, ctx):
  actual  = ctx.getMetric(node.metricCode()).value()
  operand = resolve(node.params 原始操作数, dataType, ctx)   // 第一段：解析（上下文相关）
  return ComparisonStrategyFactory.forType(dataType)
             .compare/equals(actual, operand)                 // 第二段：比较（纯，无 ctx）
```

- **解析段**：把原始操作数变成类型化、可比的值；需要 `ctx` 的（`$now`/`$today`/时区）在此用掉。对 `LONG`/`DOUBLE`/`STRING`/`BOOLEAN` 此段为**恒等/直通**——无符号可解析，故 B19 的算子实际跳过此段、直接进策略，**B19 接口因此无需改动**。
- **比较段**：纯算法，只认类型化的值，永不接触 `ctx`。`ComparisonStrategy(Object, Object)` 保持纯，使每个策略可独立测，且 `ctx` 的流入点收敛于解析段、不向比较原语泄漏。

**时间是第一个让解析段非平凡的 dataType**（`$now`/`$today`/裸日期补时区）。管线形状通用，但**解析段当前唯一有活干的就是时间**——故 resolver 只按"时间引用解析"实现，不预造空泛的通用解析框架（YAGNI）。

**边界**：本管线只覆盖"actual vs operand"的二元比较算子。`time.window` / `time.occurred_at` 是对 `now` / `occurredAt` 的**谓词**（非二元比较），是独立 conditionType evaluator，各自直接用 `ctx`，不走 strategy、不在此管线内。

---

## §1 EvalContext 加 now 字段

### 1.1 变更点

在 `EvalContext` 加第 5 个字段 `Instant now`，**设为必填**（单一 5 参构造器，无便捷重载）。新项目不留兼容壳：所有调用点（含测试）都显式传 `now`，测试传一个固定 `Instant` 即可。**禁止**提供默认 `Instant.now()` 的重载——否则有人走该路径会让每次构造各自取时钟，破坏"整树统一时钟"这一不变量。

```java
public final class EvalContext {
    private final String tenantId;
    private final RuleEvent event;
    private final Subject subject;
    private final Map<String, MetricValue> metrics;
    private final Instant now;  // 引擎在评估入口注入一次，整棵 AST 共用；必填

    public EvalContext(String tenantId, RuleEvent event,
                       Subject subject, Map<String, MetricValue> metrics, Instant now) {
        this.tenantId = tenantId;
        this.event = event;
        this.subject = subject;
        this.metrics = Map.copyOf(metrics);
        this.now = now;
    }

    public Instant getNow() { return now; }
    /** record 风格 accessor。 */
    public Instant now()    { return now; }
    // ... 其余 getter 不变
}
```

> 现有 4 参构造器删除；所有调用点（EvalContextAssembler、各测试）改为传 `now`。新项目无存量负担，直接改，不留委托重载。

### 1.2 注入点

`EvalEngine.evaluate(RuleEvent event, ...)` 私有方法在 `contextAssembler.assemble(event, passed)` 之前记录 `Instant evalNow = Instant.now()`，并把它传给 `EvalContextAssembler.assemble(..., evalNow)`；`EvalContextAssembler` 在构建 `EvalContext` 时用这个值填充 `now` 字段。

注入一次，整棵 AST 评估期间所有 evaluator 通过 `ctx.now()` 获取同一时间点，不再自行调用 `Instant.now()`。

### 1.3 dry-run 快照

`EvalServiceImpl.doEvaluate(isDryRun=true)` 在写 `contextSnapshot` JSON 时追加 `"evalNow"` 字段（`now.toString()` ISO-8601），供重放时复原历史时刻。

---

## §2 时区解析序

三个时间路径（`time.window` / `time.occurred_at` / DATE/DATETIME 策略）**共用同一套时区解析序**，封装为静态工具 `TimeZoneResolver.resolve(String timezone, Scene scene)`：

| 优先级 | 来源 | 说明 |
|--------|------|------|
| 1 | 字面量自带 offset（如 `2026-06-01T00:00:00+08:00` / `"Z"`） | 字符串自描述，忽略后续所有配置；仅 DATETIME 适用 |
| 2 | 条件 `params.timezone` | 具体条件节点显式指定，IANA 名（如 `"Asia/Shanghai"`） |
| 3 | `Scene.defaultParams.timezone` | 场景级默认时区 |
| 4 | 引擎默认 `UTC` | 兜底 |

**DATE 类型例外**：DATE（`LocalDate`）是纯日历日，与时区无关，不走此序；两个 LocalDate 直接比较。

---

## §3 time.window conditionType

### 3.1 语义

对 `ctx.now()` 做时间窗口判断。读取 `now`，用时区解析序确定时区，投影为墙上时间（`ZonedDateTime`），依次判断：

1. 今天是否在 `datesExclude` 列表（节假日）→ 若是，`satisfied=false`，短路。
2. 今天是否在 `daysOfWeek` 允许列表 → 若配置且不在，`satisfied=false`。
3. 当前时刻是否在 `[start, end]` 时间窗口内（含两端）。

### 3.2 params 表

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `start` | `"HH:mm"` | 是 | 窗口开始时间（含） |
| `end` | `"HH:mm"` | 是 | 窗口结束时间（含）；`end < start` 表示跨午夜（如 `22:00–06:00`） |
| `timezone` | IANA 时区名 | 否 | 时区解析序第 2 级；缺省回落 Scene 级或 UTC |
| `daysOfWeek` | `String[]` | 否 | 子集 `["MON","TUE","WED","THU","FRI","SAT","SUN"]`；缺省 = 全周 |
| `datesExclude` | `"MM-DD"[]` | 否 | 排除日期（节假日），如 `["01-01","10-01"]`；匹配当天即整条件 false |

### 3.3 关键语义约定

- **跨午夜窗口**：`end < start` 时，`now 的时刻 >= start OR now 的时刻 <= end` 即命中。
- **`datesExclude` 优先**：先排除节假日，再判 `daysOfWeek`，再判时段，三级逐步过滤。
- **metricCode = null**：`time.window` 归内置路径闭合集，发布期校验不做算子×dataType 矩阵检查。

### 3.4 实装锚点

`KernelEvaluators.defaults()` 注册 `"time.window"` → `TimeWindowEvaluator`（新建）。`TimeWindowEvaluator` 实现 `ConditionEvaluator`，读 `ctx.now()`，解析 `params` 后执行上述三级判断。无 `metricCode`，不调用 `ctx.getMetric()`。

---

## §4 time.occurred_at conditionType

### 4.1 语义

对 `ctx.getEvent().occurredAt()` 做时间区间比较。`occurredAt` 是业务系统填入的事件时间，引擎不做修正。

### 4.2 params 表

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `operator` | 枚举 | 是 | `BEFORE` / `AFTER` / `BETWEEN` |
| `value` | ISO-8601 字符串 或 `"$now"` | `BEFORE`/`AFTER` 时必填 | 比较基准；`"$now"` → `ctx.now()` |
| `start` | ISO-8601 字符串 | `BETWEEN` 时必填 | 区间开始（含） |
| `end` | ISO-8601 字符串 | `BETWEEN` 时必填 | 区间结束（含） |
| `timezone` | IANA 时区名 | 否 | 仅对裸日期（无时区后缀的字面量）生效；带 offset 字符串以字符串自带时区为准 |

### 4.3 占位符解析

`value`/`start`/`end` 中的 `"$now"` 由**共享 PlaceholderResolver** 替换为 `ctx.now()`（`Instant`）。`"$today"` 在此 conditionType 中**不适用**（`occurredAt` 是时间点，不是日期）——若传入 `"$today"` 视为参数错误，`satisfied=false` + `CONDITION_EVAL_ERROR`。

### 4.4 实装锚点

`KernelEvaluators.defaults()` 注册 `"time.occurred_at"` → `OccurredAtEvaluator`（新建）。读 `ctx.getEvent().occurredAt()`，调 `PlaceholderResolver` 解析 `value`/`start`/`end`，按 `operator` 走 BEFORE/AFTER/BETWEEN 比较。`metricCode = null`，不走算子×dataType 矩阵。

---

## §5 DATE / DATETIME 一等 dataType

### 5.1 语义

| dataType | Java 表示 | 含义 | 时区相关性 |
|----------|----------|------|----------|
| `DATE` | `LocalDate` | 纯日历日（年月日），无时间部分 | **无关**：两个 LocalDate 直接比较，不需要时区换算 |
| `DATETIME` | `Instant` / `OffsetDateTime` | 带时区的时间点 | **有关**：裸日期字面量需要用时区解析序补全 offset |

**值编码**：Metric SQL / HTTP 返回的值以 ISO-8601 字符串存放：
- DATE → `"2026-06-01"`（`LocalDate.parse`）
- DATETIME → `"2026-06-01T00:00:00+08:00"` 或 `"2026-06-01T00:00:00Z"`（带 offset，优先）；或 `"2026-06-01T00:00:00"`（裸日期时间，需时区解析序补全）

### 5.2 存储变更

需要执行以下 DDL 变更（Flyway migration）：

```sql
-- V1_5__add_date_datetime_to_metric_datatype.sql
ALTER TABLE metric_definition
  MODIFY COLUMN data_type ENUM('LONG','DOUBLE','STRING','BOOLEAN','LIST','DATE','DATETIME') NOT NULL;
```

`01-concepts.md:467` 的 dataType 枚举列表同步追加 `DATE` / `DATETIME`。

### 5.3 比较策略

在 B19 的 `internal/condition/strategy/` 包下新增两个策略：

**`DateComparisonStrategy`**（对应 `dataType=DATE`）：

```java
// compare: LocalDate.compareTo
// equals:  LocalDate.equals
// 输入侧：String → LocalDate.parse；LocalDate → 原样；其他 → false
// $today → ctx.now() 投影到解析时区的 LocalDate
```

**`DateTimeComparisonStrategy`**（对应 `dataType=DATETIME`）：

```java
// compare: Instant.compareTo（统一转 Instant 后比较）
// equals:  Instant.equals
// 输入侧：带 offset 字符串 → OffsetDateTime.parse().toInstant()；
//         裸日期时间字符串 → LocalDateTime.parse() + 时区解析序补 offset → Instant；
//         "$now" → ctx.now()
```

`ComparisonStrategyFactory.forType(String dataType)` 增加两个分支：

```java
case "DATE"     -> DateComparisonStrategy.INSTANCE;
case "DATETIME" -> DateTimeComparisonStrategy.INSTANCE;
```

策略**保持纯**：`$now`/`$today` 和裸日期的时区补全在 evaluator 的**解析段**用 `ctx` 完成（见"框架原则：解析 → 比较 两段式管线"节），解析出 `LocalDate`/`Instant` 后再传给纯策略。`DateComparisonStrategy`/`DateTimeComparisonStrategy` 只接收**已类型化的 java.time 值**，不接收 `EvalContext`、不接收待解析的 `$` 占位符或裸日期+外部时区。`ComparisonStrategy(Object, Object)` 签名不变（B19 接口不破坏）。

### 5.4 算子范围

`DATE_BEFORE` / `DATE_AFTER` / `EQ` / `NEQ` / `BETWEEN` / `NOT_BETWEEN` 在 `dataType=DATE` 或 `dataType=DATETIME` 时委托对应策略。发布期兼容性矩阵同步扩展（见 §6）。

---

## §6 占位符 resolver（PlaceholderResolver）

**共享**：`PlaceholderResolver` 是"框架原则"节里**解析段的实现内容**，由 `time.occurred_at` evaluator 和 DATE/DATETIME 算子的解析段共同调用（**不是策略调用**——策略保持纯）。它是解析段当前唯一的内容（只解析时间引用，YAGNI，不做通用解析框架）。

| 占位符 | 语义 | 计算方式 |
|--------|------|---------|
| `$now` | 评估时刻（绝对时间点） | `ctx.now()`，返回 `Instant` |
| `$today` | 评估日期（无时间部分） | `ctx.now()` 投影到 `timezone`（时区解析序）得到 `LocalDate` |

**明确不支持**：

- `$now-P7D`（相对 duration 运算）——此类场景走 Metric SQL（§7.3 间接时间）。
- 其他任何动态计算表达式。

传入未识别的 `$` 前缀占位符 → 视为字面量，走正常 ISO-8601 解析；若解析失败 → `satisfied=false` + `CONDITION_EVAL_ERROR`。

---

## §7 兼容性矩阵扩展

本节扩展 B19 的矩阵（`03-rule-expression §3.1–3.4`）。**权威来源不变**，仍在 `03-rule-expression §3`；本框架新增时间行后需同步那份文档。

### 7.1 完整矩阵（B19 基础 + B20 时间行）

| 算子 | 允许的 dataType |
|------|----------------|
| EQ / NEQ | LONG, DOUBLE, STRING, BOOLEAN, **DATE, DATETIME** |
| GT / GTE / LT / LTE / BETWEEN / NOT_BETWEEN | LONG, DOUBLE |
| IN / NOT_IN | LONG, STRING |
| CONTAINS / NOT_CONTAINS | LIST |
| STARTS_WITH / ENDS_WITH / MATCHES | STRING |
| **DATE_BEFORE / DATE_AFTER** | **DATE, DATETIME** |
| **BETWEEN / NOT_BETWEEN**（时间版） | **DATE, DATETIME** |

> BETWEEN / NOT_BETWEEN 在 LONG/DOUBLE 时走数值策略，在 DATE/DATETIME 时走时间策略；发布期由 `AstDataTypeResolver` 按 dataType 路由到对应校验分支，无算子重名冲突。

### 7.2 内置路径闭合集（不参与矩阵校验）

以下 conditionType 无 `metricCode`，发布期校验归入 D20 §3 内置路径闭合集，不做算子×dataType 矩阵检查：

| conditionType | 读取来源 |
|---------------|---------|
| `time.window` | `ctx.now()` |
| `time.occurred_at` | `event.occurredAt` |

---

## §8 近 N 天滚动聚合（§7.3 间接时间 + B21）

近 N 天聚合**不进引擎**，留在 Metric SQL。推荐范式骨架：

```sql
-- 近 7 天转账次数（Metric SQL_AGGREGATE）
SELECT COUNT(*)
FROM transfer_history
WHERE user_id = :subjectId
  AND created_at >= :now - INTERVAL 7 DAY
```

其中 `:now` 是 B21（取数层）从 `EvalContext.now()` 注入的占位符。

**本框架与 B21 的边界**：

- 本框架（B20）：`EvalContext.now` 字段已实装，取数层可从 `EvalContext` 读取 `now`。
- B21（取数层）：负责把 `EvalContext.now` 绑定到 SQL `:now` 占位符（`MetricSourceExecutor` 的 `PARAM_BINDER`），保证 SQL 查询范围与引擎时钟一致，而非依赖 DB `NOW()`。
- **B21 是本框架的依赖**：§7.3 模式的 `:now` 注入只有 B21 实装后才能可靠运行；B21 未落地前可用 SQL `NOW()` 变通，但 dry-run 重放不保证一致。

---

## §9 与 B19 / B21 的关系和依赖

```
B19（比较策略 + 发布期校验框架）
  └── B20（本框架，时间扩展）
        ├── 枚举 +DATE/DATETIME
        ├── ComparisonStrategyFactory + 时间策略（DateComparison / DateTimeComparison）
        ├── §3 矩阵加时间行
        ├── EvalContext.now 注入
        ├── time.window evaluator（新）
        ├── time.occurred_at evaluator（新）
        └── PlaceholderResolver（$now / $today）

B21（取数层，独立 backlog）
  └── 依赖 EvalContext.now（B20 提供）
        └── 把 now 注入 MetricSource SQL `:now` 占位符
```

**B20 对 B19 的依赖**：`ComparisonStrategyFactory.forType` 已存在（B19 落地后），B20 只在工厂加两个分支，不重写工厂。`AstDataTypeResolver` 的兼容性矩阵校验逻辑（B19 已实装）在 B20 追加 DATE/DATETIME 允许集。

**B20 不依赖 B21**：`time.window` 和 `time.occurred_at` 不走 MetricSource，不依赖 B21。DATE/DATETIME Metric 策略依赖取数层返回正确 ISO-8601 字符串，但 `:now` 注入是 B21 的职责，B20 不实现。

---

## §10 落地锚点（实现导引，不是代码）

本节供后续实现参考，不构成本次 spec 的验收标准。

1. **`EvalContext`**：加 `Instant now` 字段 + 5 参构造器 + `getNow()` / `now()` getter；4 参构造器保留委托（`Instant.now()`）。

2. **`EvalEngine.evaluate(...)`**：在调 `contextAssembler.assemble()` 之前记录 `evalNow = Instant.now()`，传给 assembler 填进 `EvalContext`。

3. **`EvalContextAssembler`**：接受 `Instant now` 参数，构建 `EvalContext` 时填入。

4. **`KernelEvaluators.defaults()`**：注册 `"time.window"` → `TimeWindowEvaluator`，`"time.occurred_at"` → `OccurredAtEvaluator`。

5. **`internal/condition/strategy/`**（B19 包）：新增 `DateComparisonStrategy`、`DateTimeComparisonStrategy`；`ComparisonStrategyFactory.forType` 加 DATE/DATETIME 分支。

6. **`PlaceholderResolver`**：新建，`$now` / `$today` 解析，需接受 `EvalContext` + `timezone`。

7. **`DateBeforeEvaluator` / `DateAfterEvaluator`**：重做，走时间策略 + `PlaceholderResolver`；原 `toInstant()` 静态方法逻辑并入时间策略后**直接删除**（新项目不留 @Deprecated 兼容壳）。

8. **`AstDataTypeResolver`**（B19 组件）：兼容性矩阵追加 DATE/DATETIME 允许行；`DATE_BEFORE` / `DATE_AFTER` 从 B19 的"排除不校验"改为"允许 DATE/DATETIME，拒绝其他"。

9. **Flyway migration**：`V1_5__add_date_datetime_to_metric_datatype.sql`（ALTER ENUM）。

10. **dry-run snapshot**：`EvalSessionWriter` / `DryRunSessionWriter` 写 `contextSnapshot` 时追加 `evalNow` 字段。

---

## §11 不做（Out of scope）

- **相对 duration 运算**（`$now-P7D`、`$today+7d` 等）：表达式引擎不做时间算术，留 Metric SQL 处理。
- **近 N 天聚合进引擎**：始终走 §7.3 Metric SQL（B21 负责 `:now` 注入）。
- **DB `NOW()` 注入**：必须用引擎注入的 `now`；Metric SQL 里的 `NOW()` 在 dry-run 场景下不受控制，是已知限制（B21 落地后修复）。
- **时间占位符以外的动态参数绑定**：D20 §3 明确"params 里的变量在发布时已知"，本框架不放开。
- **`$today` 在 `time.occurred_at` 中使用**：`occurredAt` 是时间点，`$today` 语义不适配，明确拒绝。
- **Metric 版本号绑定时间类型**：指标版本化属于 `08-evolution.md §2.2`，本框架不涉及。

---

## §12 影响文档

本框架落地后需同步：

| 文档 | 变更内容 |
|------|---------|
| `01-concepts.md:467` | dataType 枚举追加 `DATE` / `DATETIME` |
| `01-concepts.md:415` | EvalContext.now 字段标注"已实装"（原仅文档描述） |
| `05-storage.md:113` | `metric_definition.data_type` ENUM 追加 `DATE` / `DATETIME`；Flyway migration 说明 |
| `03-rule-expression.md §3.4` | DATE_BEFORE / DATE_AFTER 标注"已实装"；`$now` / `$today` 占位符语义正式对齐 |
| `03-rule-expression.md §3.1` | EQ / NEQ 允许集追加 DATE / DATETIME |
| `03-rule-expression.md §7` | time.window / time.occurred_at 标注"已实装"，补充实装说明 |
| `03-rule-expression.md §7.3` | 追加 B21 依赖说明（`:now` 占位符由 B21 注入） |
| `00-decisions.md` | 追加 B20 决策条目（now 注入 / 时区解析序 / DATE-DATETIME 一等类型 / PlaceholderResolver） |
| `08-evolution.md` | B20 标记"已实装"，B19 依赖关系说明 |

---

## §13 测试要点

### EvalContext.now 注入

- 构造 `EvalContext` 时显式传入固定 `Instant`，evaluator 通过 `ctx.now()` 读出同一值——确定性验证。
- dry-run 路径下 `contextSnapshot` JSON 含 `"evalNow"` 字段，值与 `ctx.now()` ISO-8601 表示一致。

### time.window

- 工作日 09:00–22:00（Asia/Shanghai）：`now=09:00:00+08` 命中，`now=08:59:59+08` 未命中，`now=22:00:00+08` 命中，`now=22:00:01+08` 未命中。
- 跨午夜 `22:00–06:00`：`now=23:00:00+08` 命中，`now=01:00:00+08` 命中，`now=07:00:00+08` 未命中。
- `daysOfWeek=["MON","FRI"]`：周六 `now` 不命中。
- `datesExclude=["10-01"]`：10 月 1 日 `now` 不命中，无论时段。
- `timezone` 缺省时回落 UTC。

### time.occurred_at

- `BEFORE $now`：`occurredAt < now` 命中，`occurredAt > now` 未命中。
- `BETWEEN`：`start <= occurredAt <= end` 命中，两端边界正确。
- 裸日期 `"2026-06-01"` + `params.timezone=Asia/Shanghai` → 解析为 `2026-06-01T00:00:00+08:00`（`Instant`）。
- `value="$today"` → `CONDITION_EVAL_ERROR`（不适用于此 conditionType）。

### PlaceholderResolver

- `$now` → `ctx.now()` 的 Instant。
- `$today` 在 Asia/Shanghai 时区下 → `ctx.now()` 投影为正确 LocalDate。
- 无识别占位符 `$unknown` → 走 ISO-8601 解析；解析失败 → 返回 null。

### DATE / DATETIME 策略

- `DATE` 策略：`LocalDate.of(2026,1,1).isBefore(LocalDate.of(2026,6,1))` 正确；`$today` 解析为当天 LocalDate。
- `DATETIME` 策略：带 offset 字符串正确比较；裸日期时间 + 时区解析序正确补全 offset。
- `BETWEEN`（DATE）：`2026-01-01 <= 2026-03-01 <= 2026-06-30` 命中；两端边界正确。
- 发布期：`DATE_BEFORE` 搭配 `dataType=LONG` → `AstDataTypeResolver` 抛 `IllegalArgumentException`；搭配 `dataType=DATE` → 通过校验。

### 兼容性矩阵（AstDataTypeResolver）

- 新行验证：`DATE_BEFORE` / `DATE_AFTER` 允许 DATE / DATETIME，拒绝 LONG / STRING / BOOLEAN / LIST。
- `EQ` / `NEQ` 追加 DATE / DATETIME：验证发布通过。
- 原有 B19 矩阵行不回归（由 B19 覆盖）。
