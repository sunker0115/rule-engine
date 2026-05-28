# 04 — 扩展指南（占位草稿）

> **位置定位**：本文档承载"我要加一个新条件 / 动作 / 指标源，怎么落地"的**复制粘贴级指南**——SPI 接口签名 / Bean 注册 / 注解声明 / 元数据契约 / 实现建议（timeout / retry / 熔断 默认值）。当前**占位**，仅章节就位，内部具体内容待定。
>
> **前置阅读**：[`01-concepts.md`](./01-concepts.md) §3.6 / §3.7 / §3.9、[`09-skeleton.md`](./09-skeleton.md) §四 SPI 接口落点
>
> **解决什么疑问**："加一个新 ConditionType 要改哪些文件？""ActionHandler 的返回值契约是什么？""MetricSource 怎么声明缓存策略 / 类型级 params schema？""前端怎么知道我的新条件有哪些参数？"
>
> **职责边界**——
> - ✅ SPI 接口签名 / Bean 注册 / 元数据声明 / 实现建议值
> - ❌ 不写 SPI 模块归属（→ 09-skeleton §四）、不写运行时调度（→ 02-runtime）、不写 AST 操作符（→ 03-rule-expression）、不写表结构（→ 05-storage）、不写前端 UI（→ 06-frontend）、不写运维参数默认值（→ 07-operability）

---

## 一、文档状态

| 章节 | 状态 |
|------|------|
| §二 加 ConditionType | ⏳ 未展开 |
| §三 加 ActionType | ⏳ 未展开 |
| §四 加 MetricSource | ⏳ 未展开 |
| §五 元数据契约 | ⏳ 未展开（前端拉元数据渲染编辑器的契约） |
| §六 实现指南 | ⏳ 未展开（timeout / retry / 熔断 建议默认值） |

---

## 二、加 ConditionType

⏳ 未展开。

> 展开时落定：`ConditionType` 接口签名（evaluate 方法 + 入参 schema 声明 + 元数据 getter） + Bean 注册（`@ConditionType` 注解 + Spring 扫描）+ 类型级 `params` schema 声明 + 注册中心元数据回填路径 + 单元测试模板。

---

## 三、加 ActionType

⏳ 未展开。

> 展开时落定：`ActionType` 接口签名 + `ActionHandler.execute` 返回 `ActionResult { status, errorCode?, errorMessage?, retryable }` 契约（D16 + D18 派生）+ `failFast` 声明 + 重试语义（[`07-operability.md`](./07-operability.md) §六 Prometheus 指标清单 + §七 告警阈值同步关注）+ errorCode 集中表登记规范（[`01-concepts.md`](./01-concepts.md) §3.7）。

---

## 四、加 MetricSource

⏳ 未展开。

> 展开时落定：`MetricSource` 接口签名 + `sourceType` 枚举（ATTRIBUTE / SQL_AGGREGATE / EXTERNAL_HTTP / 自定义）+ 类型级 `params` schema 声明 + `cachePolicyDefault` 声明（ttl=0 强一致 / ttl>0 可放宽）+ Scene 级 `cache_policy_override` 收紧机制 + metric 版本化字段 `metricVersion`（语义变更走版本化，[`08-evolution.md`](./08-evolution.md) §2.2）+ 取数异常归 `METRIC_FETCH_FAIL`（D15）。

---

## 五、元数据契约

⏳ 未展开。

> 展开时落定：前端从后端拉取 ConditionType / ActionType / MetricSource 元数据的接口约定（[`10-api-contract.md`](./10-api-contract.md) 同步登记）+ 元数据 JSON schema（type / displayName / paramSchema / i18nKey）+ 多语言支持策略（i18n key 在元数据声明，文案在前端）。

---

## 六、实现指南

⏳ 未展开。

> 展开时落定：不同 sourceType 的实现建议——
>
> - **EXTERNAL_HTTP**：短超时（建议 ≤ 200ms）+ 自管重试（建议至多 1 次）+ 熔断（建议 Hystrix / Sentinel 等）
> - **SQL_AGGREGATE**：中等超时（建议 ≤ 1s）+ 不重试 + 慢查询监控
> - **ATTRIBUTE**：通常无 IO 同步返回 + ttl 可适度放宽
>
> 这些是**建议值**，由 metric 注册者按业务场景自决，引擎不强制；具体参数默认值清单见 [`07-operability.md`](./07-operability.md) §九。

---

## 七、维护原则

- 本文档只描述**SPI 接口契约 + 注册指南**，不重复 SPI 模块归属（→ 09-skeleton §四）、不写运维参数默认值（→ 07-operability §九）。
- 新增第四类 SPI（如未来 Watcher / Scheduler / TraceWriter 开放给业务方实现）必须在本文档增章节 + 同步 09-skeleton §四 SPI 落点表。
- 实现建议值（§六）只列**建议**，不列默认值；默认值由 07-operability 集中管理。
