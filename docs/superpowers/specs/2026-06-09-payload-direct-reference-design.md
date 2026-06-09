# Payload 直接引用 — 设计

> 状态:设计待评审(2026-06-09)。落地后开 writing-plans。

## 一、背景与目标

当前 `ConditionNode` 只有 `metricCode` 一个值引用通道,导致**事件自带的事实**(`amount` / `currency` / `dest_account`)被迫"注册成 metric + 在 `providedMetrics` 喂值",同一字段填三遍;`scene.payloadSchema` 声明的字段也没有任何规则侧的闭合校验落点。

**目标**:引入 payload 直接引用,恢复 **"事件事实(payload) vs 受治理指标(metric)"** 的语义区分:

- 事件事实(`amount`)直接 `GT(payload.amount, 1000)` 引用,不注册 metric、不进 `providedMetrics`;
- 受治理指标(`user.risk.score`)继续走 metric(取数 / `allowProvided` 权威闸 / 版本 / SDK 下发不变);
- `UNRESOLVED_VARIABLE` 的 payload 维度由此获得真实校验落点(规则引用的 payload 字段必须在 `payloadSchema` 声明)。

**配置判据(谁走 payload / 谁走 metric)** —— "指标身份"测试,任一为 yes → metric,全 no → payload:
需要取数 / 需要权威保护(`allowProvided=false`) / 跨规则复用同一定义 / 要版本化 / 要下发 SDK / 要影响面查询。
> `amount` 永远是 payload(这笔交易的事实);`user.risk.score` 永远是 metric(哪怕这次值由上游注入,身份仍是受治理指标)。

## 二、范围

**做**:`ConditionNode` 值来源字段 + 装配阶段 payload 取值 + 发布期 payload schema 闭合校验 + collector/trace/序列化/SDK DSL 同步 + 迁移 demo/examples 的 `amount`。

**不做(YAGNI)**:嵌套 payload(`payload.user.x`);`SUBJECT` 字段引用;`ACTION_TYPE_NOT_BOUND`、`METRIC_NOT_BOUND`、`DECISION_CODE_NOT_FOUND` 与 decision / metric-binding 写 API(这些留作 A 完成后的下一轮"B")。

## 三、设计

### 3.1 AST 模型
- 新枚举 `ValueRef { METRIC, PAYLOAD }`(`rule-kernel` api model),默认 `METRIC`。
- `ConditionNode` 增加字段 `valueRef`(默认 `METRIC`)。payload 引用时 `valueRef=PAYLOAD`,`metricCode` 复用为 **payload 字段名**。
- 序列化:`valueRef=METRIC` 时可省略(缺省即 METRIC);反序列化缺省为 `METRIC`。

### 3.2 取值(核心:零碰 13 个比较算子)
取值口径统一在 `EvalContext.metrics`——13 个比较 evaluator 全部 `ctx.getMetric(node.metricCode())`。因此 payload 引用只在装配阶段注入:

- `EvalContextAssembler.assemble()` 扫描候选快照中 `valueRef=PAYLOAD` 的节点,收集字段名;从 `event.payload().get(字段)` 取值,包成 `MetricValue(value, dataType=AST 注入的 payload dataType, source=PAYLOAD)`,`putIfAbsent` 进 `metrics`(key = 字段名)。
- 13 个比较 evaluator **一行不改**:`getMetric(字段名)` 自动命中。
- `ValueSource` 枚举加 `PAYLOAD`(落 `node_trace.value_source`,VARCHAR 列免迁移)。
- **命名空间约束**:同一 scene 下 payload 字段名与 metric code 不得同名(`metrics` map key 用裸字段名);由发布校验兜底(payload 字段必在 payloadSchema、metric 必注册,两套命名空间分离)。

### 3.3 发布期校验(`UNRESOLVED_VARIABLE` 落点)
`PublishService` 新增 payload 闭合校验,遍历 AST 对 `valueRef=PAYLOAD` 节点:
- 字段必须在 `scene.payloadSchema` 声明 → 否则拒绝发布(`IllegalArgumentException` → `UNRESOLVED_VARIABLE`,message 指明未声明字段)。
- dataType 从 payloadSchema 字段 `type` 映射并注入 AST(扩展 `AstDataTypeResolver`):`number→DECIMAL`、`integer→LONG`、`string→STRING`、`boolean→BOOLEAN`、其他→`UNKNOWN`。
- `MetricDependencyCollector` 只收集 `valueRef=METRIC`(默认)的 `metricCode`;`PAYLOAD` 节点不计入 metric 依赖(不要求 ACTIVE metric)。

### 3.4 SDK DSL
`Condition` 加一组与现有 metric 工厂对称的 payload 工厂:`payloadGt/payloadGte/payloadLt/payloadLte/payloadEq/payloadNeq/payloadIn/payloadBetween…`,生成 `valueRef=PAYLOAD` 的 `ConditionNode`。

### 3.5 API 契约与文档
- rule 创建请求体 `conditionAst` 支持 `valueRef`;`10-api-contract` / `03-rule-expression` 增补 payload 引用写法 + 配置判据;`01-concepts` 补 metric/payload 区分。

### 3.6 数据迁移(demo + examples)
- demo:删 `metric_definition` 的 `amount`;rule 867 的 `amount` 节点改 `valueRef=PAYLOAD`;评估不再在 `providedMetrics` 传 `amount`。
- `examples/risk-control/high-risk-login`:`amount` 留在 `payloadSchema`,规则节点改 payload 引用,`metrics.json` 删 `amount`,README 增补 payload vs metric 判据。

## 四、影响面清单
- **rule-kernel**:`ValueRef`(新)、`ConditionNode`(+字段)、`ValueSource`(+PAYLOAD)、`EvalContextAssembler`、`AstDataTypeResolver`、`AstJsonCodec`/序列化、各 `ConditionNode` 构造点(含测试)。
- **rule-config-svc**:`PublishService`(payload 校验 + dataType 注入)、`MetricDependencyCollector`。
- **rule-sdk**:`Condition`(payload 工厂)。
- **docs / 数据**:契约文档 + demo/examples 迁移。

## 五、测试
- `ConditionNode.valueRef` 序列化往返(METRIC 省略 / PAYLOAD)。
- `EvalContextAssembler`:`valueRef=PAYLOAD` 字段从 payload 取值进 metrics,`source=PAYLOAD`。
- 评估:payload 引用 + metric 混合,命中 / 未命中。
- `MetricDependencyCollector`:PAYLOAD 节点不被收集。
- 发布校验:payload 字段不在 schema → 拒绝;在 schema → 通过且 dataType 注入正确。
- payloadSchema `type` → `DataType` 映射。
- SDK `payloadGt` 等构造 `valueRef=PAYLOAD`。

## 六、待评审拍板的开放点
1. 字段名 `valueRef`(备选 `source` / `ref`)。
2. payloadSchema `number` 统一映射 `DECIMAL`(精确,BigDecimal)是否可接受,还是要区分 `integer→LONG`。
3. SDK payload 工厂命名:`payloadGt(...)` 一组 vs `gt(...).onPayload()` 链式修饰。
