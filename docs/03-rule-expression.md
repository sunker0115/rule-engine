# 03 — 规则表达式（占位草稿）

> **位置定位**：本文档承载规则的**可写性边界**——AST 节点结构 / 操作符清单 / 短路求值规则 / 节点级 trace 落点 / 哪些表达式 v1 不支持。当前**占位**，仅章节就位，内部具体内容待定。
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
| §二 AST 节点结构 | ⏳ 未展开 |
| §三 操作符清单 | ⏳ 未展开 |
| §四 短路求值规则 | ⏳ 未展开 |
| §五 节点级 trace | ⏳ 未展开 |
| §六 v1 不支持的表达式 | ⏳ 未展开 |
| §七 内置时间类 conditionType | ✅ 已展开（time.window / time.occurred_at / 间接时间） |

---

## 二、AST 节点结构

⏳ 未展开。

> 展开时落定：AndNode / OrNode / NotNode / ConditionNode 各自的字段表 + 嵌套约束 + `displayLabel`（D19 派生：原 RuleGroup 信息降级为 AndNode/OrNode 的可选字段）+ `weight`（D12 派生：SCORECARD kind 启用）。

---

## 三、操作符清单

⏳ 未展开。

> 展开时落定：v1 支持的操作符（==/!=/>/</>=/<=/IN/NOT_IN/CONTAINS/MATCHES/...）+ 每个操作符的入参类型矩阵 + null 处理语义 + 类型强转规则（D20 §3 闭合校验前置：所有变量类型在发布时已知）。

---

## 四、短路求值规则

⏳ 未展开。

> 展开时落定：AND/OR/NOT 三种节点的短路顺序（按 sortOrder） + 节点失败时是否影响兄弟节点（D15 单节点失败 → satisfied=false 但整树继续）+ `EvalResult.partial=true` 何时置位 + `failedNodeIds` 收集规则。

---

## 五、节点级 trace

⏳ 未展开。

> 展开时落定：每个节点求值后输出的 trace 结构（nodeId / nodeType / satisfied / inputs / errorCode? / errorMessage?） + Pre-Gate 失败节点的 `PRE_GATE_BLOCKED` 类型标记（[`01-concepts.md`](./01-concepts.md) §3.14 派生）+ 落库通道（D21 TraceWriter 异步批写，指向 [`05-storage.md`](./05-storage.md) node_trace 表）。

---

## 六、v1 不支持的表达式

⏳ 未展开。

> 展开时落定：v1 明确不支持的表达式形态 + 替代方案 + 演进锚点——
>
> - 用户自定义 Java 函数调用（urule 风格 FunctionLibrary，已否决见 [`08-evolution.md`](./08-evolution.md) §四）
> - EXPRESSION_SCRIPT 叶子节点（D20 v1 不做，演进锚点 [`08-evolution.md`](./08-evolution.md) §2.1 / §2.13）
> - 跨规则引用 / 子规则调用（D6 评估即版本快照不可变 → 跨规则引用违背快照语义，留 v2）

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
