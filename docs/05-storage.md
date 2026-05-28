# 05 — 存储模型与 DDL（占位草稿）

> **位置定位**：本文档承载 rule-engine 的**持久化层契约**——表清单 / 各表 DDL / 索引设计 / 数据迁移与不可变快照策略。当前**占位**，仅章节就位，内部具体内容待定。
>
> **前置阅读**：[`01-concepts.md`](./01-concepts.md) 各章节字段表、[`00-decisions.md`](./00-decisions.md) D17 / D19 / D21
>
> **解决什么疑问**："数据库里都有哪些表？""哪些字段有索引？""rule_version 怎么做不可变快照？""node_trace / audit_log 写入路径有什么区别？"
>
> **职责边界**——
> - ✅ 表清单 / DDL / 索引 / 数据迁移路径 / 不可变快照实现
> - ❌ 不写概念字段语义（→ 01-concepts，本文档只贴 SQL 类型 + 索引）、不写决策权衡（→ 00-decisions）、不写运维参数（→ 07-operability）、不写 API 字段（→ 10-api-contract）

---

## 一、文档状态

| 章节 | 状态 |
|------|------|
| §二 表清单总览 | ⏳ 未展开 |
| §三 各表 DDL | ⏳ 未展开 |
| §四 索引设计 | ⏳ 未展开 |
| §五 数据迁移与不可变快照 | ⏳ 未展开 |

---

## 二、表清单总览

⏳ 未展开。

> 展开时落定：v1 全部表的一句话职责 + 写入路径（同步事务 / 异步队列）+ 数据生命周期（永久 / TTL）+ 估算量级（行数 / QPS）——
>
> | 表名 | 职责 | 写入路径 | 量级估算 |
> |------|------|---------|---------|
> | `rule_definition` | 规则主表（含 status / current_version） | 同步事务 | 千~万 |
> | `rule_version` | 不可变版本快照（D19） | 同步事务（发布时） | 万~十万 |
> | `scene_definition` | Scene 元数据 + payloadSchema 等 4 字段（D13） | 同步事务 | 百 |
> | `scene_metric_binding` | Scene 可见 metric 白名单 | 同步事务 | 千~万 |
> | `scene_action_binding` | Scene 可见 action 白名单 | 同步事务 | 千~万 |
> | `metric_definition` | metric 注册表 + cachePolicy / params schema | 同步事务 | 百~千 |
> | `condition_type` | ConditionType 注册表 + params schema | 同步事务 | 百 |
> | `action_type` | ActionType 注册表 + failFast 等 | 同步事务 | 百 |
> | `audit_log` | 人的行为审计（D14 同步事务红线） | 同步事务 | 万~百万/天 |
> | `node_trace` | 节点级评估 trace（D21 异步批写） | 异步批量 insert | 百万~亿/天 |
> | `evaluation_session` | 评估会话（幂等收口 / 对账分母 / 外键时序，v1 同步） | 同步事务 | 百万~亿/天 |
> | `job_definition` | xxl-job Job 注册 + cron + scene 绑定（D11） | 同步事务 | 百 |
> | `job_execution` | Job 执行流水 | 同步事务 | 万~十万/天 |
> | `subject` | Subject 主体（v1 仅 USER） | 异步同步 | 万~千万 |

---

## 三、各表 DDL

⏳ 未展开。

> 展开时落定：每张表的完整 DDL（MySQL 5.7+ 语法） + 字段注释（与 01-concepts 字段表保持一致，本文档不重复语义说明，仅列 SQL 类型 + NOT NULL / DEFAULT / COMMENT）。

---

## 四、索引设计

⏳ 未展开。

> 展开时落定：每张表的索引清单 + 查询模式映射——
>
> - **rule_definition**：`(scene_id, event_type, status)` 复合索引承载 D17 倒排索引初始化扫描
> - **rule_version**：`(rule_id, version)` 唯一索引（不可变快照查找）
> - **node_trace**：`(evaluation_session_id, created_at)` + 按 trace 落点查询模式优化
> - **audit_log**：`(target_type, target_id, created_at)` + 按 actor 查询的副索引
> - **evaluation_session**：`(scene_id, event_id, created_at)` 唯一约束（幂等收口）
>
> 注：v1 索引以"覆盖 D17 倒排索引 + 主键 / 唯一约束"为主，宽表查询走 OLAP（v2 演进，[`08-evolution.md`](./08-evolution.md) §2.5 trace 冷热分级）。

---

## 五、数据迁移与不可变快照

⏳ 未展开。

> 展开时落定：
>
> - **不可变快照**（D19）：`rule_version` 行写入后永不 UPDATE / DELETE，回滚 = 用旧 version 内容建新草稿走标准发布产新 version 号
> - **DISABLED 切换不变更 current_version**（[`01-concepts.md`](./01-concepts.md) §3.4）
> - **PUBLISHING 残留兜底清扫**（D19 v1 落地范围派生）
> - **跨大版本字段迁移**（如 D12 `Rule.kind` v1 仅 AST_BOOLEAN，未来扩展不需要 alter）
> - **schema 演进**（D13 payloadSchema 版本化指向 [`08-evolution.md`](./08-evolution.md) §2.12）

---

## 六、维护原则

- 本文档**唯一持有 DDL**——01-concepts 字段表与本文档 SQL 类型变更必须同步。
- 新增表必须在 §二 + §三 + §四 三处同步登记。
- 索引变更要在 §四 注明"承载哪个查询模式"，避免后人不敢删未知用途索引。
- 字段语义讨论留 01-concepts，本文档只列"SQL 类型 + 索引 + 写入路径"。
