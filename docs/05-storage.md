# 05 — 存储模型与 DDL

> 本文档描述持久化表、关键字段、索引、不变性和数据保留契约。可执行 DDL 的单一真相源是 [`V1__baseline.sql`](../rule-config-svc/src/main/resources/db/migration/V1__baseline.sql)。

## 一、数据库与迁移边界

- 默认运行、集成测试与生产部署统一使用 MySQL 8.0+（D79）；默认启动无需指定 profile，连接信息可通过 `SPRING_DATASOURCE_URL`、`SPRING_DATASOURCE_USERNAME`、`SPRING_DATASOURCE_PASSWORD` 覆盖，首次启动自动执行 Flyway。
- 生产部署使用 `mysql` Spring profile，显式提供上述三项数据源环境变量，不使用开发默认连接信息；目标库须使用 `utf8mb4` 字符集，服务器默认存储引擎须为 `InnoDB`。基线继承这两个默认值，执行前必须核对。
- 首个公开版本以前的 43 个开发期迁移已收敛为 `V1__baseline.sql`，基线只包含最终结构，不包含过渡 `ALTER`、数据搬运或已删除对象。
- 首个公开版本发布后，`V1__baseline.sql` 不再修改；所有结构变化按顺序新增 `V2__...sql`、`V3__...sql`。
- 已有开发库不得重放基线。确认结构已处于原 `v1.42` 最终状态后，只重建 `flyway_schema_history` 并显式标记 `baselineVersion=1`，业务表和业务数据保持不动。
- 默认启动测试和数据库集成测试均使用 Testcontainers 临时 MySQL，验证真实方言、JSON、时间精度和索引行为；CI 镜像烟测同样只使用 Docker 临时 MySQL，不连接已有业务库。

### 1.1 已有开发库切换到公开基线

这一步只适用于已完整执行旧 43 个 migration、结构等同原 `v1.42` 的开发库。D79 统一运行数据库不触发此流程，原业务库及 Flyway 历史保持不动；若另行安排基线切换，不得通过删除业务表、清空 schema 或重放 `V1__baseline.sql` 完成。

1. 停止所有连接该库的应用实例和写入任务，保持停写直至核对完成；对整个数据库做可恢复备份，并把 `flyway_schema_history` 单独导出到数据库外；
2. 核对下方 16 张业务表、关键列和索引语义均与 `V1__baseline.sql` 一致；索引名不同不要求重命名，存在缺表、缺列或额外过渡结构时立即停止；
3. 仅删除旧 `flyway_schema_history`，不要删除、重命名或清空任何业务表；
4. 使用 Flyway `baseline` 命令，以 `baselineVersion=1` 建立新的历史记录；
5. 运行 `validate` 和 `migrate`。此时应报告 schema 已处于最新状态，不应执行 `V1__baseline.sql`；
6. 对比切换前后的业务表数量、各表行数和关键抽样数据。任何差异都应从备份恢复并停止操作。

不要在应用配置中长期打开 `baseline-on-migrate`。显式的一次性 `baseline` 更容易审计，也能避免把结构不完整的数据库误标为已迁移。

## 二、表清单

### 2.1 配置与治理

| 表 | 职责 | 写入语义 |
|---|---|---|
| `tenant` | 租户注册；包含承载平台模板的 `SYSTEM` tenant | 同步事务，永久 |
| `scene` | 场景元数据、输入 schema 和执行策略 | 同步事务，永久 |
| `metric_definition` | 可版本化指标定义与敏感标志 | 同步事务，永久 |
| `connector_definition` | 声明式 HTTP 连接器描述符 | 同步事务，永久 |
| `rule_definition` | 规则身份、归属场景和当前版本指针 | 同步事务，永久 |
| `rule_version` | 不可变规则快照、RuleBody 和依赖冻结 | 同步事务，不删除已发布版本 |
| `decision_definition` | 租户级 Decision 定义 | 同步事务，永久 |
| `audit_log` | 配置变更审计 | 与主业务同事务，永久 |
| `rule_template` | 规则模板身份 | 同步事务，永久 |
| `rule_template_version` | 不可变模板版本快照 | 同步事务，永久 |
| `rule_template_instantiation` | 模板实例化溯源 | best-effort，核心不依赖 |

### 2.2 运行与调度

| 表 | 职责 | 写入语义 |
|---|---|---|
| `evaluation_session` | 生产评估结果和可选重放快照；唯一键防重复记录 | 异步 best-effort 批写，默认保留 90 天 |
| `node_trace` | 节点级求值 trace | 异步批写，默认保留 30 天 |
| `decision_outcome` | 业务真实结果标签回灌 | 同步或调度导入，永久 |
| `scheduled_task` | 通用调度任务定义与运行游标 | 同步事务，永久 |
| `scheduled_task_execution` | 调度任务运行记录 | 运行期写入，永久 |

dry-run 只即时返回结果，不写专用历史表。动作子系统、`rule_decision_binding`、`scene_metric_binding`、`scene_action_binding`、`job_*` 和 `dry_run_*` 均已从最终结构移除。

## 三、关键字段契约

### 3.1 规则快照

`rule_version` 是运行时下发的不可变真相源：

- `body`：多态 `RuleBody`，承载 AST、脚本或决策图；
- `decision_bindings`：创建或编辑草稿时解析冻结的 Decision 绑定；
- `pre_gates`：草稿快照中的前置门，当前包含 ROLLOUT；
- `metric_dependencies`：指标编码和版本依赖；
- `payload_dependencies`：输入字段、类型与必填约束；
- `trigger_event_types`：规则接受的事件类型；
- `kind`：与 `body.type` 一致的 `RuleKind`；
- `status`：`DRAFT`、`ACTIVE`、`SUPERSEDED` 或 `DISABLED`。

草稿创建即解析并冻结依赖，最新 DRAFT 可原地编辑；发布仅激活该草稿，不重新解析。已发布版本的内容不可修改，其生命周期状态可变更。修改和回滚通过新草稿完成，`rule_definition.current_version` 保存当前生效 `rule_version.id`（D56），不是业务版本号。

### 3.2 评估与 trace

`evaluation_session` 使用 `(tenant_id, event_id)` 唯一约束防止重复审计行，不保证同一事件只求值一次。正式评估完成后发布事件，`AuditPersister` 非阻塞入队并异步插入终态记录；队满、进程退出或写库失败可能丢失记录，响应成功不等于审计已落库。无候选和 dry-run 不写入。记录保存：

- 渠道 `source` 与评估模式 `mode`；
- `final_decision`、`hit_decisions`、`score` 和 `category`；
- `context_snapshot`、原始 `payload` 与 `candidate_rule_version_ids`，用于忠实重放。

`node_trace` 保留 `rule_version_id`，同时冗余 `rule_code` 和业务版本号以便排障。`actual_value` 可能包含敏感数据，展示出口按 scene/metric 敏感声明进行读时脱敏。

### 3.3 JSON 与枚举

- 结构化字段使用数据库 `JSON` 列，在 Java 实体中使用具体类型和 `Jackson3TypeHandler`。
- 数据库不使用 MySQL `ENUM`；封闭取值由 Java enum 作为单一真相源，数据库列使用 `VARCHAR`。

## 四、索引契约

| 表 | 索引或约束 | 查询语义 |
|---|---|---|
| `tenant` | UK `(code)` | 租户编码唯一 |
| `scene` | UK `(tenant_id, code)` | 租户内场景唯一 |
| `metric_definition` | UK `(tenant_id, metric_code, version)` | 指标版本唯一 |
| `connector_definition` | UK `(tenant_id, connector_code)` | 租户内连接器唯一 |
| `rule_definition` | UK `(tenant_id, code)`；IDX `(tenant_id, scene_code)` | 规则唯一；按场景查询 |
| `rule_version` | UK `(rule_definition_id, version)`；IDX `(status)` | 版本唯一；按状态筛选 |
| `decision_definition` | UK `(tenant_id, code)` | 租户内决策唯一 |
| `audit_log` | IDX `(tenant_id, target_type, target_id)`；IDX `(operated_at)` | 按对象和时间审计 |
| `evaluation_session` | UK `(tenant_id, event_id)`；IDX `(scene_code, subject_id)`；IDX `(started_at)` | 幂等、历史查询、清理 |
| `node_trace` | IDX `(evaluation_session_id)`；IDX `(tenant_id, evaluated_at)` | session trace 与保留期清理 |
| `decision_outcome` | UK `(tenant_id, event_id)`；IDX `(tenant_id, labeled_at)` | 标签幂等与效果统计 |
| `scheduled_task` | UK `(tenant_id, code)` | 调度任务唯一 |
| `scheduled_task_execution` | IDX `(scheduled_task_id, trigger_at)` | 任务运行历史 |
| `rule_template` | UK `(tenant_id, code)`；IDX `(tenant_id, status)` | 模板唯一与列表查询 |
| `rule_template_version` | UK `(template_id, version)` | 模板版本唯一 |
| `rule_template_instantiation` | IDX `(template_id)`；IDX `(rule_version_id)` | 双向溯源 |

公开基线保留现有带表名前缀的索引名，D79 不修改基线 DDL，也不重命名已有库索引。索引名不属于业务 API，业务约束由列组合定义。

## 五、数据保留

| 表 | 默认保留期 | 时间列 | 配置键 |
|---|---:|---|---|
| `evaluation_session` | 90 天 | `started_at` | `engine.rule.retention.evaluation-session-days` |
| `node_trace` | 30 天 | `evaluated_at` | `engine.rule.retention.node-trace-days` |
| `audit_log` | 永久 | — | 不清理 |
| 配置与模板表 | 永久 | — | 不清理 |

清理任务按 `engine.rule.retention.batch-size` 分批提交。`node_trace` 先于对应 `evaluation_session` 清理，不建立数据库外键，模块通过逻辑顺序维护一致性。

## 六、维护规则

- 可执行结构只修改 Flyway migration；本文档同步表、字段和索引语义，不复制整份 DDL。
- 新增表必须同步 §二和 §四。
- 新增 JSON 列必须给出具体 Java 类型和 TypeHandler。
- 新增封闭状态必须先定义 Java enum，数据库继续使用 `VARCHAR`。
- 任何数据库改动都必须通过默认配置 MySQL 启动测试、Testcontainers MySQL 集成测试及完整数据库门禁；测试清单和执行方式见 [贡献指南](../CONTRIBUTING.md)。
