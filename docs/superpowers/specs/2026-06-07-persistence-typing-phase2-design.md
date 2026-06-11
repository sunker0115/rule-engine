# 持久化层类型化（二期）设计

> 接续一期 `2026-06-07-api-dto-typing-design.md`。一期把 API DTO 层 typed，实体仍 String。二期把持久化实体层 String JSON 列收成 typed 对象，并补齐与之耦合的 service 写签名。

## 背景与关键技术前提

**用 MP 3.5.16 的 `Jackson3TypeHandler`（无需自定义）**：MP extension 3.5.16 同时提供 Jackson 2 版 `JacksonTypeHandler` 和 **Jackson 3 版 `Jackson3TypeHandler`**。字节码确认后者引用 `tools.jackson.databind.ObjectMapper`（与本项目一致），且构造器 `(Class<*>, java.lang.reflect.Field)` 通过 `TypeFactory.constructType(field.getGenericType())` **从字段捕获泛型**——故 `List<DecisionBinding>` 不会退化为 `List<LinkedHashMap>`，**Jackson 3 兼容 + List 擦除两个问题一并解决**。用法：`@TableName(autoResultMap=true)` + `@TableField(typeHandler=Jackson3TypeHandler.class)`。`autoResultMap` 仅对 BaseMapper 内置方法生效（config-svc `RuleVersionMapper` 是纯 BaseMapper，覆盖；eval-svc 自定义 @Select 需 @Results，见二期 B）。多态由 `AstNode` 等类型上的 Jackson 注解驱动，任何 Jackson 3 mapper 均可解析。

**实体类型化与 service 写签名耦合**：若只 typed 实体而 service 仍收 String，写链路出现 `typed→String→typed→String` 的多余往返。故二期把 `RuleVersion` 实体 typed + `ConfigService.createDraft` 写签名 typed **一起做**。

**两条读 rule_version 路径不同**：
- config-svc `RuleVersionMapper`：纯 BaseMapper（唯一 @Select 返 Long）→ `autoResultMap` 直接覆盖。**二期主战场**。
- eval-svc `RuleVersionReadMapper`：自定义 @Select 3 表 JOIN → `RuleVersionRow`（String）→ kernel `SnapshotAssembler` 反序列化。是与 rule-sdk **共享**的 canonical 边界。

## TypeHandler：直接用 MP `Jackson3TypeHandler`

无需自定义类。实体字段统一标注 MP 的 `com.baomidou.mybatisplus.extension.handlers.Jackson3TypeHandler`：

```java
@TableName(value = "rule_version", autoResultMap = true)
class RuleVersion {
    @TableField(typeHandler = Jackson3TypeHandler.class)
    private AstNode conditionAst;
    @TableField(typeHandler = Jackson3TypeHandler.class)
    private List<DecisionBinding> decisionBindings;   // field 泛型被 constructType 捕获，不退化为 Map
    // preGates: List<PreGateConfig> / metricDependencies: List<MetricDependency> / triggerEventTypes: List<String> 同理
    // 动态字段 defaultParams / params: Map<String,Object>，同一 handler
}
```

> 校验点（集成测覆盖）：`List<DecisionBinding>` 读回元素必须是 `DecisionBinding` 而非 `LinkedHashMap`——这是 MP `Jackson3TypeHandler` 用 `field.getGenericType()` 的关键验证。

## 范围

### 二期 A（config-svc RuleVersion，主交付）

| 实体.字段 | String → | 备注 |
|---|---|---|
| `RuleVersion.conditionAst` | `AstNode` | AstNodeTypeHandler |
| `RuleVersion.decisionBindings` | `List<DecisionBinding>` | |
| `RuleVersion.preGates` | `List<PreGateConfig>` | |
| `RuleVersion.metricDependencies` | `List<MetricDependency>` | |
| `RuleVersion.triggerEventTypes` | `List<String>` | 若实体当前有该列 |

- `@TableName(value="rule_version", autoResultMap=true)` + 每字段 `@TableField(typeHandler=...)`。
- `ConfigService.createDraft` 写签名 String → typed（`AstNode conditionAst, List<DecisionBindingInput> decisionBindings, ...`），`RuleController` 不再 `nodeToString`，直接传 typed；`createDraft` 内直接 `rv.setConditionAst(typed)`，handler 落库。
- `getRuleDetail` 删手写 `parseAst`/`parseDecisionBindings`，直接 `active.getConditionAst()`（已 typed）。
- `PublishService` 删对这些字段的 `toJson`/手写反序列化，直接读写 typed 实体字段。

### 二期 B（eval-svc 拆 JOIN，性能已确认无虑）

- 拆 `RuleVersionReadMapper` 的 3 表 JOIN 为批量查询：BaseMapper 查 ACTIVE `RuleVersion`（typed）+ 按 id 集合批量查 `rule_definition`(tenantId/kind/sceneId) + `scene`(code/decisionStrategy)。启动/热更新期批量（非 N+1），评估热路径走内存索引不碰 DB，**无性能问题**。
- 由 typed `RuleVersion` + ruleDef/scene 查询组装 `RuleVersionSnapshot`。
- **rule-sdk 仍用 `SnapshotAssembler`**（它从 HTTP JSON 反序列化，非 DB），kernel `SnapshotAssembler` 保留。

### 二期 C（其余实体）

- `SceneDef.payloadSchema` → `List<PayloadFieldSpec>`、`SceneDef.defaultParams` → `Map<String,Object>`；`MetricDefinition.params` → `Map<String,Object>`（动态，Map handler）。
- `JobDefinition.subjectQuery` → `SubjectQuery`（一期已有 typed 模型）。
- `EvaluationSession`/`DryRunSession`.`hitDecisions` → `List<String>`、`contextSnapshot` → typed record（视风险，可后置）。

### 红线（保持，不类型化）

- `AuditLog.before/afterSnapshot` —— 异构配置快照，无统一形状，**保持 String**。
- 所有动态 by design 字段用 Map handler，不定具体 record（params/defaultParams）。

## 错误处理

- handler 反序列化失败：库中 JSON 引擎自写、可信，失败 = 不该发生的异常 → 抛 `IllegalStateException`，500（与一期读路径一致）。
- 写路径 typed → handler 序列化基本不失败（typed 对象合法）。

## 测试策略

- **handler 单测**：每个 handler 用真实 H2/字符串往返（typed→String→typed），验多态 type 字段、List 泛型不退化为 Map。
- **RuleVersion 集成测（Testcontainers）**：insert typed RuleVersion → 直接 selectById 读回 typed，断言 conditionAst instanceof AndNode、decisionBindings 元素是 DecisionBinding（非 LinkedHashMap）。这是 List 擦除的关键验证。
- **回归**：现有 createDraft/publish/getRuleDetail/eval 全链路集成测全绿；存储 JSON 形状与改前一致（除一期已知 AstNode 补 null）。
- 按 CLAUDE.md：每模块提交前全量测试绿。

## 验收

- `RuleVersion` 等实体无 String JSON 字段（异构/动态除外）；`ConfigService.createDraft` 收 typed；`getRuleDetail`/`PublishService` 无手写 JSON 转换。
- 自定义 Jackson 3 handler 正确往返，List 不退化为 LinkedHashMap。
- eval 拆 JOIN 后快照组装结果与改前一致；rule-sdk SnapshotAssembler 路径不变。
- 散落的 `objectMapper.readValue/writeValueAsString`（实体 hydration 类）显著减少。
- AuditLog 异构快照保持 String。

## 不做

- 不动 rule-sdk 的 HTTP→SnapshotAssembler 路径。
- 不为动态/异构字段定具体 record。
- 不引入 Jackson 2 依赖去迁就 MP 内置 handler。
