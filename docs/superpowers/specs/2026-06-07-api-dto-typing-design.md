# API DTO 类型化设计（弱类型 Object/String → typed 对象）

> 状态：设计已与用户逐段确认，待复核后进 writing-plans。

## 目标

把 API DTO 层里"明明有 typed 模型却摊成 `Object` / `String`"的字段还原成对象，让**入口校验**和 **OpenAPI/Swagger 契约**恢复类型。内核模型层已全是对象、不动；持久化实体层留二期。

**一句话边界**：纯内部类型重构——线上 JSON 契约字节级不变，只把 Java 绑定类型从 `Object`/`String` 换成 typed。

## 背景与现状

三层类型化现状：

```
API DTO 层 (web.*.dto / config.api.dto)   ← 本期改这里（Object/String → typed）
   ▲ Jackson 绑定（多态 AstNode 在入口直接反序列化 + 校验）
内核模型层 (kernel.api.model)              ← 不动（AstNode / DecisionBinding / PreGateConfig 已就绪）
   ▲ 已有 AstSerializer / AstJsonCodec 顶着 typed↔String
持久化实体层 (*.internal.domain, MyBatis)  ← 本期不动（仍 String）；二期 TypeHandler 收口
```

关键事实：**kernel 早已备齐 typed 模型与编解码器**——`AstNode`（多态 sealed，`@JsonTypeInfo` 带 `type` 字段）、`AstJsonCodec` 能反序列化 `AstNode` / `List<PreGateConfig>` / `List<DecisionBinding>` / `List<String>` / `List<MetricDependency>`。缺的不是模型，而是 API DTO 用了裸 `Object`、把已有类型扔掉了。

当前往返（以 `conditionAst` 为例）：

```
写: HTTP JSON →[Jackson]→ CreateRuleRequest.conditionAst : Object(=LinkedHashMap)
    → service writeValueAsString → rule_version.condition_ast : String
读: rule_version.condition_ast : String →[AstSerializer.fromJson]→ AstNode → RuleDetailVO.conditionAst : Object → HTTP JSON
```

痛点：`Object` 让入口校验、类型安全、Swagger schema 全失效——前端拿到的契约就是个 `{}`。

## 范围红线

### 该类型化（稳定 schema，有现成模型）

| 模块 | DTO | 字段 | 现在 | 改成 |
|---|---|---|---|---|
| rule-api | `CreateRuleRequest` | `conditionAst` | `Object` | `AstNode`（kernel 多态） |
| rule-api | `CreateRuleRequest` | `decisionBindings` | `Object` | `List<DecisionBindingInput>`（新建，见下） |
| rule-api | `CreateRuleRequest` | `preGates` | `Object` | `List<PreGateConfig>`（kernel `RuleVersionSnapshot.PreGateConfig`） |
| rule-api | `CreateRuleRequest` | `triggerEventTypes` | `Object` | `List<String>` |
| rule-config-svc | `RuleDetailVO` | `conditionAst` | `Object` | `AstNode` |
| rule-config-svc | `RuleDetailVO` | `decisionBindings` | `Object` | `List<DecisionBinding>`（读回带 priority，复用 kernel `RuleVersionSnapshot.DecisionBinding`） |
| rule-job-svc | `JobDefinitionDto` | `subjectQuery` | `String` | sealed `SubjectQuery`（见 §subjectQuery） |
| rule-job-svc | `JobDefinitionDto` | `payloadTemplate` | `String` | **删字段**（D49 遗留，greenfield 直接去） |

### 坚决保持 Map / 不动（动态 by design，红线）

- `RuleEvent.payload` / `providedMetrics` —— CloudEvents 信封模式（typed 信封 + 开放 data），A 原则。
- `PreGateConfig.params` —— 按 `gateType` 变形的 seam：**外层 List/wrapper 类型化，params 内层仍 `Map<String,Object>`**。
- `SceneDetailDto.defaultParams` —— Scene 开放配置（timezone/currency...），保持 `Map<String,Object>`。
- `ConditionNode.params` / `MetricDescriptor.params` —— SPI 插件 seam，不动。

### 不在本期（独立 spec / 二期）

- **二期**：持久化实体层 String → typed field（MyBatis `JacksonTypeHandler` + `autoResultMap`，自定义 mapper 加 `@Results`，泛型 List 写自定义 handler 绕类型擦除），消灭散落的 `objectMapper.readValue/writeValueAsString`。
- **独立可选 spec**：CloudEvents 入口适配器（仅当出现"外部系统按 CE 格式推事件进来"的真实场景时拉起；域模型 RuleEvent 不动，在 HTTP/MQ 入口 `CloudEvent → RuleEvent` 映射）。

## 为什么"一个字段三种形状"要定专用 record

`decisionBindings` 在三处形状不同：

| 场景 | 实际形状 | 出处 |
|---|---|---|
| 创建请求（CreateRule） | `[{ "decisionCode": "REVIEW" }]` —— 只有 code，无 priority | 10-api-contract:186 |
| 存储/快照（rule_version） | `[{ "decisionCode": "BLOCK", "priority": 100 }]` | 10-api-contract:722 |
| kernel 模型 | `DecisionBinding(String decisionCode, int priority)` —— priority 必填 int | RuleVersionSnapshot.java:59 |

根因（D26）：`priority` 不归规则配，属于 **Decision 实体**（Tenant 级），发布时引擎从 `decision_definition.priority` 回填进快照。所以创建态直接绑 kernel `DecisionBinding` 会凭空要求前端传 priority、且 `int` 无法表达"由后端填"。

解法：创建入参定专用 record，读回复用 kernel 模型。

```java
// 与 CreateRuleRequest 同包（rule-api web.admin.dto），仅创建入参使用；
// 若 config service 入参签名也需要它，则在 config api 定义对应 command，由 RuleController 映射
public record DecisionBindingInput(
        String decisionCode,        // 必填
        BigDecimal scoreRangeMin,   // 可选，SCORECARD 用
        BigDecimal scoreRangeMax    // 可选，SCORECARD 用
) {}
```

读回（`RuleDetailVO.decisionBindings`）形状是 `{decisionCode, priority}`，与 kernel `RuleVersionSnapshot.DecisionBinding` 一致，直接复用。

> 待写 plan 时核对：`DecisionBindingInput` 的 scoreRange 字段以 10-api-contract §4.1 当前实际契约为准；若契约只收 `decisionCode`，则 record 只留 `decisionCode` 一个字段。

## subjectQuery 判别联合（sealed + Jackson 多态）

现状：存 `{"type":"BEAN_METHOD","ref":"<bean>#<method>"}` 字符串，`BeanMethodSubjectQueryRunner` 手写 `parse→检查 type==BEAN_METHOD→取 ref`。

改为：

```java
// rule-job-svc api 包
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(@JsonSubTypes.Type(value = BeanMethodQuery.class, name = "BEAN_METHOD"))
public sealed interface SubjectQuery permits BeanMethodQuery {}

public record BeanMethodQuery(String ref) implements SubjectQuery {}
```

- `JobDefinitionDto.subjectQuery` 字段类型变 `SubjectQuery`，Jackson 按 `type` 自动还原。
- `BeanMethodSubjectQueryRunner` 解析从"`readValue→Map→手检 type→取 ref`"换成 `readValue(json, SubjectQuery.class)` + sealed `switch` 模式匹配，少一处手写 type 判断（in-scope，job-svc；`job_definition.subject_query` 实体字段一期仍 String）。
- 将来加 `EXTERNAL_HTTP` 只是加一个 `permits` 子类型 + `switch` 分支，编译器逼你覆盖完整。

## 数据流（一期，实体仍 String）

```
写: HTTP JSON →[Jackson 直接绑定多态]→ CreateRuleRequest.conditionAst : AstNode   ← 校验回到入口
    → config service 用现成 AstSerializer.toJson(node) → rule_version.condition_ast : String（不变）
       List 字段用 objectMapper.writeValueAsString(list) → 对应 String 列
读: String →[AstSerializer.fromJson / AstJsonCodec]→ AstNode / List<DecisionBinding>
    → RuleDetailVO（typed）→ HTTP JSON
```

转换点**不新增**，复用现成 `AstSerializer` / `AstJsonCodec`；只是 controller↔service 之间传的东西从 `Object` 换成 typed，Jackson 在入口完成多态反序列化 + 校验。

**触及范围**：rule-api（`CreateRuleRequest` + `RuleController` 绑定）、rule-config-svc（消费 CreateRule / 产出 RuleDetailVO 的 service + 新 `DecisionBindingInput`）、rule-job-svc（`JobDefinitionDto` + `SubjectQuery` + `BeanMethodSubjectQueryRunner`）。内核、持久化实体、动态 Map 字段都不动。

## 错误处理

本次唯一**新增的失败模式**：`Object` 永远绑定成功（垃圾进、垃圾存）；换 typed 后入口多了"反序列化失败"路径，必须映射成干净 400，而非 500。

- **入口**：非法 AST 节点 type / 未知 subjectQuery type / 结构不符 → Jackson 抛 `HttpMessageNotReadableException`。确认现有 `@RestControllerAdvice` 把它映射成 **400 + 统一 errorCode**（复用现有校验错误码，或加 `PAYLOAD_DESERIALIZE_ERROR`）。**不接住就是把"静默接受垃圾"换成"500 崩"**——plan 必须明确这一条。
- **ObjectMapper 一致性**：HTTP 绑定全局 mapper、`AstSerializer`、`AstJsonCodec` 三者对多态配置与 `FAIL_ON_UNKNOWN_PROPERTIES` 策略须一致（`AstJsonCodec` 现禁用未知字段报错）。多态注解都打在 `AstNode` 接口上，任何 mapper 都能认；unknown-property 策略统一表态。
- **读路径**（String→typed 回填 VO）：库里 JSON 是引擎自写、可信，失败=不该发生的 500，维持现状。
- **greenfield**：不写兼容垫片，旧的非法 `{}` 直接拒——这正是目的。

## 测试策略

- **铁律不变量**：线上 JSON 契约字节级不变（字段名、形状全不变）。10-api-contract 示例继续有效——纯内部类型重构，非 API 契约变更（唯一例外：`JobDefinitionDto` 故意删 `payloadTemplate` 死字段）。
- **web 层（MockMvc）**：合法 typed JSON 正确绑定；非法 AST type → 400 + 正确 errorCode（新增用例）。
- **DTO 往返序列化**：`AstNode` / `DecisionBindingInput` / `SubjectQuery` 经全局 ObjectMapper 序列化↔反序列化（带 type 字段）。
- **subjectQuery**：`BeanMethodQuery ↔ {type:BEAN_METHOD,ref}` 往返 + runner 用 typed 解析。
- **读回**：`RuleDetailVO`（typed）序列化出的 JSON 与改前形状一致。
- **回归**：现有规则 create/publish/get 集成测试全绿（JSON 形状一致是关键证明）。
- 按 CLAUDE.md：每模块提交前全量测试绿，不用 `-DskipTests` 绕过。

## 验收

- `CreateRuleRequest` / `RuleDetailVO` / `JobDefinitionDto` 不再出现 `Object` / 弱类型 `String` JSON 字段（`payloadTemplate` 删除）。
- 非法事件/规则体在入口被拒为 400 + errorCode，不再静默存垃圾、也不 500。
- 线上 JSON 契约不变；现有集成测试全绿。
- 动态 by design 字段（payload / providedMetrics / *.params / defaultParams）保持 Map，未被强行类型化。

## 不做（YAGNI）

- 不动内核模型层（已是对象）。
- 不动持久化实体层（二期）。
- 不引入 CloudEvents 域模型；不做 CE 入口适配器（独立可选 spec）。
- 不类型化动态 SPI seam 的 params / payload / defaultParams。
