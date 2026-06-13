# 规则身份引入 (code, version) — 阶段甲:核心 + trace 采纳

> 状态:设计待评审 · 日期 2026-06-11

## 一、背景与动机

当前 kernel 只用 `ruleVersionId`(`rule_version.id` 代理键,本地模式为任意 Long)标识规则版本,trace / 决策溯源里出现的是一串无意义数字;`@RuleDef` 还要求使用者手编全局唯一的 `long id`。诉求:让**人类可读的逻辑身份 `code` + 版本号 `version`** 成为一等概念,贯穿运行时与审计。

调研主流开源(Camunda DMN/BPMN、Kubernetes、Confluent Schema Registry):它们统一用 `(key/code, version)` 作为对外与审计身份,但**存储层保留代理主键**,审计行**代理 FK + 冗余自然键并存(supplement)**,不用复合自然键当物理主键。本设计采用同一范式。

## 二、统一身份模型(定版)

| 维度 | 标识 | 用途 |
|---|---|---|
| **逻辑身份**(人看 / 对外 / 审计冗余) | `(tenantId, sceneCode, code, version)` 自然键 | trace / 决策溯源 / API 寻址(乙)|
| **物理身份**(存储 PK / FK / 索引去重) | `rule_version.id` 代理键(= 现 `ruleVersionId`) | DB 主键、`node_trace` 等 FK 锚点、`SceneRuleIndex` 去重 |

- 两者**并存(supplement)**,互不替代。
- **不做**:不移除任何代理主键(丙);不把审计行的代理 id 替换成 (code,version)(选 P 不选 Q)。
- `(tenantId, sceneCode, code, version)` 是 `rule_version` 的自然键:`rule_definition.code` 在 (tenant, scene) 内唯一,`rule_version.version` 在 rule_definition 内单调——与代理 id 一一对应。

## 三、范围

- **本 spec = 阶段甲**:核心模型 + trace/审计采纳 (code, version);`@RuleDef` 人机工程学改造一并完成。
- **阶段乙(后续单独 spec)**:admin API 改为按 (code, version) 寻址(publish / rollback / dry-run targeting / 版本端点 / 契约文档)。引用本模型,不在本次范围。
- 模型本身追加一条 `00-decisions.md` 决策,甲乙共享。

## 四、详细设计(阶段甲)

### 4.1 kernel 模型(补充字段,保留 ruleVersionId)

- `RuleVersionSnapshot`(record + Builder + 便利构造器):新增 `String code`、`long version`;保留 `Long ruleVersionId`。
- `NodeTrace`(record + `container`/`leaf` 工厂方法):新增 `String ruleCode`、`long ruleVersion`;保留 `Long ruleVersionId`。
- `Decision`(record + 便利构造器):新增 `String fromRuleCode`、`long fromRuleVersion`;保留 `Long fromRuleVersionId`。
- **version 类型用 `long`**(对齐 `rule_version.version` bigint)。

### 4.2 trace 注入(4 个 executor)

`ScorecardExecutor` / `DecisionTreeExecutor` / `InterpretedExecutor` / `DecisionTableExecutor`:构建 `NodeTrace` 与 `Decision` 时,从持有的 `RuleVersionSnapshot` 取 `code`/`version` 一并写入(它们已从 snapshot 取 `ruleVersionId`,新增字段同源)。

### 4.3 SceneRuleIndex

**不改**:`match()` 去重仍用 `Set<Long>` on `ruleVersionId`;路由 key 仍 `tenantId:sceneCode:eventType`。supplement 模型下零改动、零风险。

### 4.4 snapshot 各来源填充 code+version

- **config 路径**:`SnapshotAssembler.assemble(RuleVersionRow)` + `RuleVersionRow` 增 `code`/`version` 字段;构建 `RuleVersionRow` 的 Mapper SQL 增 JOIN 取 `rule_definition.code` 与 `rule_version.version`。
- **`AnnotationRuleSource`**:`code` ← `@RuleDef.code`;`version` = 1;`ruleVersionId` 由 `stableHash(tenantId:sceneCode:code)` 派生(取代原手编 `id`)。
- **DSL / File 路径**:本地规则 JSON(`RuleVersionSnapshot` 序列化)新增 `code`/`version` 字段;`AstJsonCodec` 反序列化兼容(开发阶段无存量,缺失即报错或默认)。
- **Polling 路径**:`rule-api` 的 `/sdk/v1/snapshots` 序列化 `RuleVersionSnapshot` 时带上 `code`/`version`;SDK 端反序列化同步。

### 4.5 DB 落库(eval-svc,supplement)

- 新增迁移 `V1_26`:`node_trace` / `evaluation_session` / `dry_run_node_trace` 各**新增** `rule_code VARCHAR(128)`、`rule_version BIGINT`,**保留** `rule_version_id`。
- 落库 writer(node_trace / evaluation_session / dry-run persister)填充新列。
- admin trace 读出:trace 树 VO + 读接口暴露 `ruleCode` / `ruleVersion`。

### 4.6 `@RuleDef` 人机工程学(本轮起点,并入甲)

- `RuleDef` 注解:删除 `long id()`;新增 `String code()`;新增 `long version() default 1`;`String tenantId() default ""`(空=用 `RuleEngineClient` 配置的租户)。
- `AnnotationRuleSource`:按上述派生 `ruleVersionId`,`tenantId` 为空时取客户端租户(starter 把 client 租户传入)。
- `Condition`:新增重载 `of(String conditionType, Map<String,Object> params)`(无 metric,供直接读 payload/context 的自定义算子);保留带 metric 的重载。
- 更新 `rule-samples` annotation demo(`LargeTradeRule` 用 `code`、去 `id`/`tenantId`;`Condition.of("BUSINESS_HOURS", Map.of())`)。

### 4.7 文档

- `00-decisions.md`:追加"规则身份 (code, version) 逻辑键 + 代理键并存(supplement),阶段甲/乙"决策条目。
- `01-concepts.md`(规则身份 / trace 段)、`03-rule-expression.md`(若涉及 AST JSON 的 code/version)、`04-extension.md`(补 `@RuleDef` 注解模式说明,现缺)、D40 文档(`@RuleDef` 字段更新)。
- `10-api-contract.md`:**本期不动**(归阶段乙)。

## 五、非目标(YAGNI / 留给后续)

- 阶段乙的 admin API (code, version) 寻址 —— 另立 spec。
- 移除代理主键 / 复合自然键当 PK(丙)—— 明确不做。
- 审计行用 (code,version) 替换代理 id(Q 方案)—— 不做,采 supplement(P)。
- `current_version` 指针、dry-run targeting、rollback 的寻址方式 —— 维持现状(用代理 id),归阶段乙。

## 六、风险与对策

- **JSON snapshot 兼容**:开发阶段无存量快照(greenfield),`code`/`version` 直接作为必填新增,缺失视为错误;轮询端与服务端同版本部署。
- **派生 ruleVersionId 碰撞**(@RuleDef):`stableHash(tenantId:sceneCode:code)` 用 64-bit,本地规则数量级极小,碰撞可忽略;且与 DB 自增 id 不共用 match() 同一 (scene,eventType) 桶的概率极低。
- **改动面大(跨 kernel/config/eval/api/sdk + DB + 文档 + 几十个测试)**:由 writing-plans 拆成可独立验证的小任务,逐个编译/测试通过再推进;跨模块改动用 `clean test` 兜底。
- 改完派 `rule-engine-reviewer` 审"代码 ↔ 文档对齐"。

## 七、验收

- kernel/config/eval/api/sdk 全量 `clean test` 通过。
- 端到端:真起服务跑评估,`EvalResult` 的 `finalDecision` 含 `fromRuleCode`/`fromRuleVersion`,`node_trace` 落库 `rule_code`/`rule_version` 真写入(查库核对),admin trace 接口读出 code/version。
- `rule-samples` annotation demo 用新 `@RuleDef`(无 id/tenantId)实跑命中。
