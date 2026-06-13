# Trace PII 脱敏(读时遮蔽)— 设计

> 状态:设计待评审 · 日期:2026-06-13 · 关联决策:D71(草案,编号落库前再核)
> 前置:D69(scene payloadSchema typed 约束 + `PayloadDependency`)、D54(metric 租户级共享)、D70(payload 默认落库,raw PII 暴露来源)、D21(node_trace)、D59(trace 带 ruleCode/ruleVersion)

## 1. 背景与动机

D70 把 raw payload 默认落库、且 `node_trace.actualValue` 一直存条件比较用的原值——这些里可能含 PII(手机号/证件号/地址等)。当前**没有任何脱敏**:trace 查询 / dry-run / replay 的返回里 `actualValue` 直接暴露原值。

目标:在**读/展示出口**对敏感字段的 `actualValue` 做遮蔽,raw 值仍按原样落库(不碰 D70 / 重放忠实度),只在 API 响应里抹成固定串。

### 已对齐决策

| 维度 | 选择 | 依据 |
|---|---|---|
| 脱敏落点 | **存原值、读时脱敏** | 不碰落库路径,重放仍忠实;raw PII 的库内防护交加密/权限 |
| 策略时效 | **live 当前策略**(非冻结进版本快照) | 脱敏是活的安全控制,"事后标敏感即刻全量(含历史)生效";脱敏只影响展示不影响判定,无需 D69 的冻结确定性。对标 DB 动态脱敏 / Apache Ranger 列脱敏 |
| 声明位置 | **各在定义点**:payload 字段 → scene payloadSchema;metric → metric 定义 | payload 是 scene 级、metric 是租户级共享(D54),敏感性各自固有;对标 DDM/Ranger 把脱敏规则声明在列/字段定义上 |
| 脱敏方式 | **全抹为固定串 `"***"`** | 最简最安全、零歧义 |
| 脱敏位置 | **rule-api web 层** | 展示边界;rule-api 已依赖 config-svc,集中在响应组装处单一出口 |
| 查询失败 | **fail-closed:全抹** | 脱敏失败不得退化成 PII 泄露 |

### 非目标

- **角色级"看原值"豁免**:引擎无内置 RBAC(D14 鉴权交网关),一律抹、原值只在库内。后续真要再单独引权限模型。
- 不动落库路径 / node_trace 表 / kernel `NodeTrace` / TraceWriter / 评估热路径 / 重放逻辑。
- 不做部分遮蔽(138****1234)/ 哈希;只全抹。

## 2. 架构与数据流

```
声明(config-svc,live;各在定义点)
  scene.payloadSchema 字段  →  sensitive=true
  metric 定义              →  sensitive=true
  查询:getSensitiveRefs(tenant, scene)
        → { sensitivePayloadFields(取自 scene), sensitiveMetricCodes(取自该租户 metric 定义) }
        │ 读时(无冻结、node_trace 不存标志)
        ▼
脱敏(rule-api web 层)
  TraceMasker(sensitiveRefs, trace):递归遍历 NodeTrace/TraceNode,叶子节点若
    (valueSource=PAYLOAD ∧ metricCode ∈ sensitivePayloadFields) 或
    (valueSource=METRIC  ∧ metricCode ∈ sensitiveMetricCodes)
    → actualValue 置 "***"
  出口:
    - GET /admin/v1/evaluation-sessions/{id}/trace            (落库 trace,扁平)
    - GET /admin/v1/evaluation-sessions/{id}/trace/tree       (落库 trace,嵌套)
    - POST /api/v1/rule/dry-run                               (内存 trace)
    - POST /admin/v1/evaluation-sessions/{id}/replay          (内存 trace)
```

**为什么不冻进快照 / 不加 node_trace 列**:脱敏走 live 当前策略,只需读时拿**当前** scene 敏感字段集 + 当前敏感 metric 集即可;node_trace 行已带 sceneCode(经 session)与 metricCode/valueSource,足够匹配。不加列 = 零 node_trace 改动、零写路径改动、且"标敏感即刻回溯遮蔽历史"。

**为什么放 rule-api web 层**:脱敏是展示边界的事;rule-api 已注入 config-svc 服务(如 `EvalController` 用 `TenantQueryService`),可直接取敏感集;集中在响应组装处,落库/审计/评估三侧都不需要感知脱敏。

## 3. 组件改动

1. **config-svc 声明侧**:
   - scene payloadSchema 字段模型 + 写 API DTO 加 `sensitive`(布尔,默认 false),存进 `scene.payload_schema` JSON(沿用 D69 字段约束的存放方式)。
   - metric 定义实体 + 写 API 加 `sensitive`(布尔,默认 false)。
   - 新查询 `SceneService.getSensitiveRefs(tenantId, sceneCode)` → `SensitiveRefs(Set<String> payloadFields, Set<String> metricCodes)`:payloadFields 取自该 scene payloadSchema 中 `sensitive=true` 的字段;metricCodes 取自该租户 metric 定义中 `sensitive=true` 的码。
2. **rule-api web 层**:
   - `TraceMasker`:纯函数,入参敏感集 + 一棵/一列 trace,递归把命中的叶子 `actualValue` 置 `"***"`,返回新结构(不改原对象)。对扁平 `List<TraceNodeEntry>`、嵌套 `List<TraceTreeNode>`、kernel `EvalResult.nodeTrace`(`List<NodeTrace>`)各有一个适配重载(三种 trace 形状)。
   - 接入四处出口(见 §2),每处:先 `getSensitiveRefs(tenant, scene)`,再 `TraceMasker.mask(refs, trace)`。
   - sceneCode 来源:trace 查询从 `evaluation_session.scene_code`;dry-run/replay 从请求/session 的 sceneCode。
3. **不动**:metric 取数/`MetricDescriptor` 运行时(只在 config 定义侧加标志)、node_trace 表/实体、kernel `NodeTrace`、TraceWriter、评估热路径、replay/落库。

## 4. 错误处理 / 边界

| 情形 | 处理 |
|---|---|
| `getSensitiveRefs` 查询失败(config 不可用) | **fail-closed:全抹**——把该响应所有叶子 `actualValue` 置 `"***"` + `log.warn`;宁可过度遮蔽,不漏 PII |
| scene 无敏感字段、无敏感 metric | masker no-op,trace 原样返回 |
| 容器节点(And/Or/Not/Xor/If/Table/Scorecard) | `actualValue` 本就 null,不受影响;只叶子可能被抹 |
| payload 字段名与 metric 码同名 | 靠 `valueSource` 区分(PAYLOAD vs METRIC),互不误伤 |
| `valueSource` 为 PROVIDED(SDK 注入 metric) | 归入 METRIC 集判定(provided 是 metric 的一种来源);按敏感 metric 码匹配 |
| 节点 `metricCode` 为 null(容器/无引用) | 不匹配任何敏感集,不抹 |

## 5. 测试策略

遵循项目测试纪律(`mvn-env`,`$MVN -pl <module> -am test`,收尾 `clean test`)。

- **单元(rule-api)`TraceMasker`**:
  - PAYLOAD 敏感字段 → `actualValue` 抹为 `"***"`;非敏感 PAYLOAD 原样。
  - METRIC 敏感码 → 抹;非敏感 metric 原样。
  - PROVIDED 来源按 metric 集判定。
  - 嵌套树递归:深层敏感叶子被抹,容器不受影响。
  - 空敏感集 → 全 no-op;三种 trace 形状(扁平/树/NodeTrace)各覆盖。
  - fail-closed:传入"查询失败"标记(或 masker 的全抹入口)→ 所有叶子 actualValue 抹。
- **config-svc**:scene payloadSchema 字段 `sensitive` 往返;metric 定义 `sensitive` 往返;`getSensitiveRefs` 返回当前 scene 敏感 payload 字段 + 租户敏感 metric 码。
- **集成(端到端,涉读出口)**:
  1. 配一个含敏感 payload 字段(如 `phone` sensitive=true)+ 一个敏感 metric 的 scene/规则,发布。
  2. dry-run 一个命中事件 → 断言返回 `nodeTrace` 中 `phone`/敏感 metric 节点 `actualValue=="***"`,非敏感节点保留原值。
  3. 真实评估后查 `GET .../trace` 与 `/trace/tree` → 同上断言。
  4. replay 返回的 nodeTrace 同样被抹。
  5. config 不可用时(模拟 getSensitiveRefs 抛错)→ 该出口全抹。

## 6. 决策日志条目草案(D71,编号待核)

**D71 Trace PII 读时脱敏 | A**

`node_trace.actualValue` 与 dry-run/replay 返回的 trace 含 PII(payload 原值 / 敏感 metric 值,D70 后 raw 默认落库)。做**读时脱敏**:raw 照常落库(不碰落库/重放忠实度),仅在 rule-api 展示出口把敏感叶子的 `actualValue` 抹成 `"***"`。**声明各在定义点**:payload 字段敏感性在 scene payloadSchema(`sensitive` 标志,沿用 D69),metric 敏感性在 metric 定义(metric 租户级共享,D54)。**live 当前策略**(非冻结进版本快照,脱敏只影响展示不影响判定,无需 D69 确定性;事后标敏感即刻回溯遮蔽历史),故 **node_trace/kernel NodeTrace/TraceWriter 全不动**。masker 在 rule-api web 层,按 `valueSource`(PAYLOAD vs METRIC/PROVIDED)+ `metricCode` 匹配 `getSensitiveRefs(tenant,scene)` 的敏感集;接入 trace 扁平/树查询 + dry-run + replay 四出口。**fail-closed**:敏感集查询失败全抹。**非目标**:角色级看原值豁免(引擎无 RBAC)、部分遮蔽/哈希。对标 DB 动态数据脱敏 / Apache Ranger 列脱敏(声明在字段定义、读时按策略遮蔽)。设计见 `specs/2026-06-13-trace-pii-masking-design.md`。
