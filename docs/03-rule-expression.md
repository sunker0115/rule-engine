# 03 — 规则表达式

> **位置定位**：本文档承载规则的**可写性边界**——AST 节点结构 / 操作符清单 / 短路求值规则 / 节点级 trace 落点 / 哪些表达式 v1 不支持。
>
> **前置阅读**：[`01-concepts.md`](./01-concepts.md) §3.5 AST 与"分组心智" + §3.6 Condition（含 ConditionType）、[`00-decisions.md`](./00-decisions.md) D12 / D15 / D20
>
> **解决什么疑问**："我能写多复杂的规则？""嵌套 AND/OR/NOT 怎么表达？""短路求值的边界是什么？""trace 在哪儿能看到节点求值结果？"
>
> **职责边界**——
> - ✅ AST 节点字段语义 / 操作符语义 / 短路规则 / trace 输出 / v1 不支持的表达式（指向 08-evolution）
> - ❌ 不写运行时调度（→ 02-runtime）、不写 ConditionType 扩展指南（→ 04-extension）、不写 node_trace 表结构（→ 05-storage）、不写编辑器 UI（→ 06-frontend）

---

## 一、文档状态

| 章节 | 状态 |
|------|------|
| §二 AST 节点结构 | ✅（含 XorNode §2.5） |
| §三 操作符清单 | ✅（含 DATE_BEFORE/DATE_AFTER §3.4） |
| §四 短路求值规则 | ✅ |
| §五 节点级 trace | ✅ |
| §六 v1 不支持的表达式 | ✅ |
| §七 内置时间类 conditionType | ✅ 已展开（time.window / time.occurred_at / 间接时间） |

---

## 二、AST 节点结构

AST 由五种节点类型组成，每种节点字段如下。

### 2.1 AndNode（与节点）

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `type` | `"AndNode"` | 是 | 固定值 |
| `displayLabel` | `string` | 否 | UI 显示名，不影响求值（原 RuleGroup 降级为可选 AST 标签，见 README §七 版本史） |
| `children` | `Node[]` | 是 | 子节点列表，至少 1 个；按 `sortOrder`（列表顺序）依次求值 |
| `weight` | `number` | 否 | SCORECARD kind 专用（D12），v1 AST_BOOLEAN 忽略此字段 |

**求值语义**：所有子节点 satisfied=true 时整体 true；遇第一个 false 则短路，剩余子节点不再求值（见 §四）。

### 2.2 OrNode（或节点）

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `type` | `"OrNode"` | 是 | 固定值 |
| `displayLabel` | `string` | 否 | UI 显示名 |
| `children` | `Node[]` | 是 | 子节点列表，至少 1 个 |
| `weight` | `number` | 否 | SCORECARD kind 专用，v1 忽略 |

**求值语义**：任一子节点 satisfied=true 时整体 true；遇第一个 true 则短路，剩余子节点不再求值。

### 2.3 NotNode（非节点）

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `type` | `"NotNode"` | 是 | 固定值 |
| `child` | `Node` | 是 | 单个子节点（AndNode / OrNode / ConditionNode 均可） |

**求值语义**：对 `child.satisfied` 取反；child 出错时 NotNode 也归 ERROR（不翻转错误状态）。

### 2.4 ConditionNode（条件叶子节点）

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `type` | `"ConditionNode"` | 是 | 固定值 |
| `conditionType` | `string` | 是 | ConditionType 注册码，如 `metric.threshold` / `event.payload.compare` / `time.window` |
| `displayLabel` | `string` | 否 | UI 显示名 |
| `metricCode` | `string` | 仅 metric 类需填 | 引用的指标码；`conditionType` 非 metric 类时留 null |
| `params` | `object` | 是 | 传给 ConditionEvaluator 的参数对象，结构由各 conditionType 定义 |
| `weight` | `number` | 否 | SCORECARD kind 专用（D12），v1 AST_BOOLEAN 忽略此字段 |

**嵌套约束**：ConditionNode 是叶子节点，不能有 `children`；AndNode / OrNode / NotNode / XorNode 是中间节点，不能作为最终叶子（children 不能为空）。

### 2.5 XorNode（异或节点）

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `type` | `"XorNode"` | 是 | 固定值 |
| `displayLabel` | `string` | 否 | UI 显示名 |
| `children` | `Node[]` | 是 | 子节点列表，至少 2 个；建议 2–4 个，超大列表可读性差 |
| `weight` | `number` | 否 | SCORECARD kind 专用（D12），v1 AST_BOOLEAN 忽略此字段 |

**求值语义**：恰好 **1 个**子节点 satisfied=true 时整体 true；0 个或 ≥ 2 个 true 时整体 false。不做短路——所有子节点均需求值才能确定"恰好 1 个"，但内部有优化：已满足 2 个 true 时提前退出（结果已确定为 false）。

**典型用途**：互斥条件——多个分支中有且仅有一个成立，如"只走其中一条风控路径"或"恰好命中一类黑名单"。

**与 OrNode 的区别**：

| | `OrNode` | `XorNode` |
|--|--|--|
| 1 个 true | ✅ true | ✅ true |
| 2 个 true | ✅ true | ❌ false |
| 0 个 true | ❌ false | ❌ false |

---

## 三、操作符清单

以下操作符由内置 `metric.threshold` 和 `event.payload.compare` ConditionType 使用（通过 `params.operator` 字段传入）。自定义 ConditionType 可定义私有操作符，不受本表约束。

### 3.1 通用比较操作符

| operator | 适用数据类型 | 语义 | null 处理 |
|----------|------------|------|-----------|
| `EQ` | LONG / DOUBLE / STRING / BOOLEAN | 相等 | 参数为 null → satisfied=false |
| `NEQ` | LONG / DOUBLE / STRING / BOOLEAN | 不相等 | 同上 |
| `GT` | LONG / DOUBLE | 严格大于 | 参数为 null → ERROR |
| `GTE` | LONG / DOUBLE | 大于等于 | 同上 |
| `LT` | LONG / DOUBLE | 严格小于 | 同上 |
| `LTE` | LONG / DOUBLE | 小于等于 | 同上 |
| `BETWEEN` | LONG / DOUBLE | `min <= value <= max`（双端闭区间） | 参数为 null → ERROR |
| `NOT_BETWEEN` | LONG / DOUBLE | `value < min 或 value > max`（BETWEEN 取反） | 参数为 null → ERROR |

### 3.2 集合操作符

| operator | 适用数据类型 | 语义 | null 处理 |
|----------|------------|------|-----------|
| `IN` | LONG / STRING | value 在 `values[]` 集合内 | value 为 null → satisfied=false |
| `NOT_IN` | LONG / STRING | value 不在 `values[]` 集合内 | value 为 null → satisfied=true |
| `CONTAINS` | LIST | LIST 中包含指定元素 | list 为 null → satisfied=false |
| `NOT_CONTAINS` | LIST | LIST 中不包含指定元素 | list 为 null → satisfied=true |

### 3.3 字符串操作符

| operator | 适用数据类型 | 语义 | null 处理 |
|----------|------------|------|-----------|
| `STARTS_WITH` | STRING | 前缀匹配 | value 为 null → satisfied=false |
| `ENDS_WITH` | STRING | 后缀匹配 | 同上 |
| `MATCHES` | STRING | 正则匹配（Java `Pattern.matches`）| value 为 null → satisfied=false |

### 3.4 日期操作符

> 适用于 `dataType=DATE` 或 `dataType=DATETIME` 的 Metric；参数格式 ISO-8601（`"2026-06-01"` / `"2026-06-01T00:00:00+08:00"`）或特殊占位符 `"$now"`（评估时刻）/ `"$today"`（评估日期，无时间部分）。

| operator | 适用数据类型 | 语义 | null 处理 |
|----------|------------|------|-----------|
| `DATE_BEFORE` | DATE / DATETIME | `value < threshold`（严格早于） | value 为 null → satisfied=false |
| `DATE_AFTER` | DATE / DATETIME | `value > threshold`（严格晚于） | value 为 null → satisfied=false |

**示例**：账户创建时间早于 2024-01-01（老账户识别）：

```json
{
  "type": "ConditionNode",
  "conditionType": "metric.threshold",
  "metricCode": "account.created_at",
  "params": {
    "operator": "DATE_BEFORE",
    "threshold": "2024-01-01"
  }
}
```

**示例**：用户最后登录时间晚于 $now（异常时间戳检测）：

```json
{
  "type": "ConditionNode",
  "conditionType": "metric.threshold",
  "metricCode": "user.last_login_at",
  "params": {
    "operator": "DATE_AFTER",
    "threshold": "$now"
  }
}
```

**与 `time.occurred_at` 的区别**：`time.occurred_at` 检查事件本身的业务时间（`EvalContext.event.occurredAt`）；`DATE_BEFORE` / `DATE_AFTER` 检查任意 DATE/DATETIME 类型 Metric 的值——可以是账户创建时间、上次登录时间、到期时间等任何时间类指标。

### 3.5 类型转换规则（D20 §3 闭合校验前置）

发布时引擎静态校验 metric 数据类型与操作符是否匹配（如 STRING metric 不能用 GT/LT）。v1 **不做运行时类型转换**——metric 返回 DOUBLE 但配置了 LONG 类型 metric 时，引擎在 EvalContext 内以 DOUBLE 处理，ConditionEvaluator 收到的就是原始类型。类型不匹配在发布期拒绝，不在运行期推断。

---

## 四、短路求值规则

### 4.1 短路规则

| 节点类型 | 短路条件 | 短路后行为 |
|---------|---------|-----------|
| `AndNode` | 子节点求值结果为 `false` | 停止求值剩余子节点；剩余节点 trace 中 `result=null`（"短路跳过"） |
| `OrNode` | 子节点求值结果为 `true` | 停止求值剩余子节点；剩余节点 trace 中 `result=null` |
| `NotNode` | 无短路 | 始终求值 child |
| `XorNode` | 已有 ≥ 2 个子节点 satisfied=true | 提前退出（结果已确定为 false）；剩余节点 trace 中 `result=null` |

子节点按列表顺序（`sortOrder`）依次求值，无并发求值。

### 4.2 错误不短路（D15）

若某子节点求值出错（MetricSource 取数失败 / ConditionEvaluator 抛异常）：
- 该节点 `satisfied=false`，`errorCode` 记录失败原因
- **兄弟节点继续求值**（不因单节点错误中断整棵树）
- 父节点收到 `false` 后可能触发短路（如父节点是 AndNode 且已得到 false）

这与"短路"的区别：短路是因为结果已确定（AND+false / OR+true）可以提前终止；错误是意外失败，引擎选择继续完成其他节点以收集最多信息。

### 4.3 EvalResult 聚合

> **命名说明**：`satisfied` 是 Java POJO 内部字段名（`EvalResult.satisfied`）；`ruleHit` 是对外 API JSON 字段名（10-api-contract §3.1）。两者语义相同，均表示整棵 AST 的求值结果。

| 情形 | `ruleHit`（API）/ `satisfied`（内部） | `errorCode` |
|------|-----------|-------------|
| 根节点 satisfied=true，无节点出错 | true | null |
| 根节点 satisfied=true，但有节点出错 | true | 第一个失败节点的 errorCode（`METRIC_FETCH_FAIL` / `CONDITION_EVAL_ERROR`） |
| 根节点 satisfied=false，无节点出错 | false | null |
| 根节点 satisfied=false，有节点出错 | false | 第一个失败节点的 errorCode（`METRIC_FETCH_FAIL` / `CONDITION_EVAL_ERROR`） |

调用方若看到 `errorCode` 非 null，应查 node_trace 中 `errorCode` 非 null 的节点定位根因；`METRIC_FETCH_FAIL` 表示取数失败，`CONDITION_EVAL_ERROR` 表示条件评估器异常（见 10-api-contract §七）。

---

## 五、节点级 trace

每个节点求值后输出一条 trace 记录，落入 `node_trace` 表（D21 TraceWriter 异步批写，见 [`05-storage.md`](./05-storage.md) §3.2 node_trace 表）。

### 5.1 trace 字段

| 字段 | 类型 | 说明 |
|------|------|------|
| `node_path` | `string` | AST 路径，如 `"0"` = 根，`"0.1"` = 根的第 2 子节点（0-indexed） |
| `node_type` | `string` | `AndNode` / `OrNode` / `NotNode` / `XorNode` / `ConditionNode` / `PRE_GATE_BLOCKED` |
| `condition_type` | `string` | 仅 ConditionNode，conditionType 注册码；其余节点为 null |
| `metric_code` | `string` | 仅 metric 类 ConditionNode；其余为 null |
| `params` | JSON | 节点参数快照（发布时冻结） |
| `actual_value` | JSON | 节点实际取值；短路跳过的节点为 null |
| `result` | `1 / 0 / null` | 1=满足 / 0=不满足 / null=短路跳过 |
| `error_code` | `string` | nullable；`METRIC_FETCH_FAIL` / `CONDITION_EVAL_ERROR` 等 |
| `value_source` | `PROVIDED / FETCHED / null` | D30：metric 取值来源；非 metric 类节点为 null |

### 5.2 Pre-Gate 失败节点

Pre-Gate 被拦截时不进入 AST 求值，但会在 `node_trace` 中写入一条 `node_type=PRE_GATE_BLOCKED` 记录：
- `condition_type = null`（Pre-Gate 不是 ConditionType）
- `actual_value = null`
- `result = 0`（拦截即"不满足继续"）
- `error_code = null`（拦截不是错误）

该记录与普通 AST 节点共用 `node_trace` 表，通过 `node_type` 区分（见 [`01-concepts.md`](./01-concepts.md) §3.14 Pre-Gate）。

### 5.3 异步写入语义

trace 行在评估结束后异步入队 TraceWriter，失败降级丢弃（不影响 EvalResult）。生产排障时，trace 可能有秒级延迟，查询时注意。

---

## 六、v1 不支持的表达式

以下表达式形态 v1 明确不支持，列出替代方案和演进锚点。

| 不支持的形态 | 原因 | v1 替代方案 | 演进锚点 |
|------------|------|-----------|---------|
| 用户自定义 Java 函数调用（urule FunctionLibrary 风格） | 与闭合校验（D20 §3）、禁止副作用（D16）、metric 只读（§3.9）三条决策正面冲突；已否决（见 [`08-evolution.md §四`](./08-evolution.md)） | 封装为 `@ConditionType` SPI（[`04-extension.md`](./04-extension.md)） | — |
| `EXPRESSION_SCRIPT` 叶子节点（动态脚本表达式） | D12 占位，v1 发布时拒绝 kind=EXPRESSION_SCRIPT | 用 ConditionNode + 现有 conditionType 组合表达 | [`08-evolution.md §2.1`](./08-evolution.md)（kind 多态） |
| 跨规则引用 / 子规则调用 | D6 评估即版本快照不可变；跨规则引用违背快照语义（被引用规则可能在引用期间发布新版） | 将共享逻辑提取为 Metric（SQL 或 HTTP）或拆分成多条独立规则 | [`08-evolution.md §2`](./08-evolution.md) |
| 运行时动态参数绑定（`params` 字段引用 payload 变量） | D20 §3：所有变量类型在发布时已知，不做运行期参数解析 | 用 `event.payload.compare` conditionType（params 直接写死比较值）；动态值走 Metric | — |
| 结果聚合函数（SUM/AVG/COUNT over 多节点结果） | AST 是布尔树，不产生数值聚合 | SCORECARD kind（D12，v2 演进）；v1 用 metric 预计算聚合值 | [`08-evolution.md §2`](./08-evolution.md)（SCORECARD） |

---

## 七、内置时间类 conditionType

> 时间条件不走 Metric 取数，直接从 `EvalContext.now`（引擎注入统一时钟）或 `EvalContext.event.occurredAt`（业务事件时间）读值，发布期校验时归入内置路径闭合集合（D20 §3），无需 `metricCode`。

### 7.1 time.window — 当前时刻在指定时间窗口内

**语义**：对 `EvalContext.now` 做时间窗口判断，常用于"规则只在营业时间 / 高峰时段生效"。

**参数表**：

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `start` | `"HH:mm"` | 是 | 窗口开始时间（含） |
| `end` | `"HH:mm"` | 是 | 窗口结束时间（含）；`end < start` 表示跨午夜窗口（如 `22:00–06:00`） |
| `timezone` | IANA 时区名 | 否 | 缺省回落 `Scene.defaultParams.timezone`，仍未配则 `UTC` |
| `daysOfWeek` | `String[]` | 否 | `["MON","TUE","WED","THU","FRI","SAT","SUN"]` 子集；缺省 = 全周 |
| `datesExclude` | `"MM-DD"[]` | 否 | 排除日期列表（节假日豁免），如 `["01-01","10-01"]` |

**示例**：工作日 09:00–22:00（上海时间）：

```json
{
  "type": "ConditionNode",
  "conditionType": "time.window",
  "displayLabel": "工作时段",
  "params": {
    "start": "09:00",
    "end": "22:00",
    "timezone": "Asia/Shanghai",
    "daysOfWeek": ["MON", "TUE", "WED", "THU", "FRI"]
  }
}
```

**跨午夜写法**（夜间高风险时段 22:00–06:00）：

```json
{
  "type": "ConditionNode",
  "conditionType": "time.window",
  "displayLabel": "夜间时段",
  "params": {
    "start": "22:00",
    "end": "06:00",
    "timezone": "Asia/Shanghai"
  }
}
```

**关键约束**：
- `now` 由引擎在评估入口注入一次，整棵 AST 共用同一个 `now`，不受节点求值耗时影响。
- `datesExclude` 匹配逻辑：先判断日期是否排除，排除则整条件 `satisfied=false`（不论时间段）。

---

### 7.2 time.occurred_at — 事件业务时间落在指定范围

**语义**：对 `EvalContext.event.occurredAt`（业务事件时间，可早于 `now`）做比较，常用于"延迟事件 / 补录数据"场景，或"活动有效期"判断。

**参数表**：

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `operator` | 枚举 | 是 | `BEFORE` / `AFTER` / `BETWEEN` |
| `value` | ISO-8601 字符串 或 `"$now"` | `operator` 为 `BEFORE`/`AFTER` 时必填 | 比较基准；`"$now"` 表示评估时刻 |
| `start` | ISO-8601 字符串 | `BETWEEN` 时必填 | 区间开始（含） |
| `end` | ISO-8601 字符串 | `BETWEEN` 时必填 | 区间结束（含） |
| `timezone` | IANA 时区名 | 否 | 仅对裸日期（无时区后缀）的 `value`/`start`/`end` 生效；有时区后缀以字符串自带时区为准 |

**示例 A**：事件发生在活动期间（2026-06-01 至 2026-06-30）：

```json
{
  "type": "ConditionNode",
  "conditionType": "time.occurred_at",
  "displayLabel": "六月活动期间",
  "params": {
    "operator": "BETWEEN",
    "start": "2026-06-01T00:00:00+08:00",
    "end":   "2026-06-30T23:59:59+08:00"
  }
}
```

**示例 B**：事件发生在评估时刻之前（排除未来时间戳的补录异常）：

```json
{
  "type": "ConditionNode",
  "conditionType": "time.occurred_at",
  "displayLabel": "非未来事件",
  "params": {
    "operator": "BEFORE",
    "value": "$now"
  }
}
```

**关键约束**：
- `occurredAt` 是业务系统填入的事件时间，引擎**不做修正**；调用方保证其语义正确性。
- 与 `time.window` 的区别：`time.window` 看的是"引擎当前几点"，`time.occurred_at` 看的是"事件发生在哪个时间点"。

---

### 7.3 间接时间：Metric 内嵌时间窗口

**语义**：时间窗口逻辑封装在 Metric 的 SQL / HTTP 查询里，条件层只看聚合结果值，不感知"时间"。这是三种写法中**最常用**的模式。

**适用场景**：近 N 天交易次数、近 N 小时登录失败次数、30 天内累计金额等。

**Metric 侧**（SQL_AGGREGATE）：

```json
{
  "metricCode": "user.transfer.count.7d",
  "sourceType": "SQL_AGGREGATE",
  "dataType": "LONG",
  "params": {
    "sql": "SELECT COUNT(*) FROM transfer_history WHERE user_id = :subjectId AND created_at >= NOW() - INTERVAL 7 DAY"
  },
  "cachePolicyDefault": { "ttl": 0 }
}
```

**条件侧**（METRIC_COMPARE，与普通阈值条件写法完全一致）：

```json
{
  "type": "ConditionNode",
  "conditionType": "metric.threshold",
  "displayLabel": "近 7 天转账次数 < 3",
  "metricCode": "user.transfer.count.7d",
  "params": {
    "operator": "LT",
    "value": 3
  }
}
```

**与前两种的核心区别**：

| | `time.window` | `time.occurred_at` | 间接时间（Metric） |
|--|--|--|--|
| 时间来源 | `EvalContext.now` | `EvalContext.event.occurredAt` | SQL/HTTP 查询内部 |
| 条件层是否感知时间 | 是（params 里配时区/时段） | 是（params 里配时间戳） | **否**（只看指标数值） |
| 适合场景 | 规则生效时段 | 事件有效期/活动期 | 历史聚合行为 |
| 时间精度 | 分钟级（HH:mm） | 秒级（ISO-8601） | 由 SQL 决定 |
| cacheTtl 推荐 | N/A（无 Metric） | N/A（无 Metric） | 实时计数设 `0`；宽松统计可设 60–300s |

---

## 八、维护原则

- 本文档只描述**可写性边界**，不重复 ConditionType 接口（→ 04-extension）、不写运行时短路实现细节（→ 02-runtime）。
- 新增操作符或节点类型必须更新 §二 + §三 + §五 trace 输出结构。
- v1 明确"不支持"的表达式如果未来在 08-evolution 中开放，§六 同步迁移到 §三。
