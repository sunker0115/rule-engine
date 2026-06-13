# Schema 线收口设计（payload 契约 + 算子契约 + 审计统一）

> 日期：2026-06-13。背景：收尾 ConditionParams param 键工作时，发现"schema"在系统里散成三摊、且多处声明了不强制 / 不受控。本设计把整条线收口到统一原则：**建场景 / 写规则 / 发事件三处的松散输入，收成 typed 契约 + 分层校验**。
>
> 对标 DMN（itemDefinition + 决策表列 typeRef）、Apache Calcite（SqlOperatorTable + SqlOperandTypeChecker）、JSON Schema（输入数据校验）。

## 1. 目标与非目标

**目标**：消除"配置看着对、上线后静默失效"这一类失败模式。具体：
- payloadSchema 从"声明了但只校 required+类型"升为**完整 typed 输入契约**（type 受控 enum + enum/min/max/pattern 运行期强制）。
- 算子 param 键从"两端裸串、发布期零校验"升为**目录化 + 发布期校验**。
- 三类"schema"元数据（payload 字段、算子、输入清单）有**单一真相源**并对 UI 暴露。
- scene 变更历史**统一进 audit_log**，删除专用历史表。

**非目标**：
- 不做 payloadSchema 完整 JSON Schema（oneOf/$ref/嵌套对象校验）——只做 PayloadFieldSpec 现有字段集的强制。
- 不动 EXPRESSION_SCRIPT（CEL 等表达式层，自带类型系统）。
- 不引入 AST 之外的新规则形态。
- 不做"scene schema 升版兼容存量规则"——模型 2 的冻结快照已让此问题消失。

**成功判据**：
1. 建场景填非法 `type` → 创建期拒绝。
2. 写规则缺算子必填 param 键 → 发布期拒绝。
3. 发事件违反 enum/min/max/pattern → 运行期 HTTP 400。
4. scene 变更前后快照落 `audit_log`，专用历史表删除后历史不丢。
5. UI 元数据接口返回非空算子目录（conditionTypes + paramsSchema）。

## 2. 统一原则与分层校验

从"松散 map、哪儿都不校" → "typed 契约 + 三期分层校验"：

| 时机 | 校验内容 | 责任组件 |
|---|---|---|
| **建场景**（config 写） | `payloadSchema[].type ∈ PayloadFieldType` | SceneService（新增校验） |
| **写/发布规则**（publish） | 算子必填 param 键齐（B2）、算子×dataType 兼容（B3）、payload 约束冻结（A2） | PublishService 链 |
| **运行**（每事件，eval） | payload 必填 + 基础类型 + enum/min/max/pattern（A3） | PayloadInputValidator |

## 3. Schema 现状地图（收口前）

三类"schema"：
1. **Scene payloadSchema**：`scene.payload_schema`（JSON，`List<PayloadFieldSpec>`：name/type/required/enum/min/max/pattern/description）+ `payload_schema_version` 列 + `scene_payload_schema_history` 表。
2. **算子元数据**：`AstDataTypeResolver.ALLOWED`（conditionType→允许 dataType，发布期校验，硬编码 config-svc）+ 算子 param 键（**无任何声明/校验**）。
3. **输入清单**：`rule_version.payload_dependencies`（发布期冻结的 `{name,dataType,required}` 子集）。

收口前缺口：
- payloadSchema 的 enum/min/max/pattern **声明了但从未强制**（发布冻结时丢弃，运行期不校）。
- `PayloadFieldSpec.type` 是**裸 String，无 enum、无校验**——拼错静默落 UNKNOWN。
- 算子 **param 键发布期零校验**——错键静默死规则（已由 ConditionParams + 往返测试堵住代码生产者，但 API raw-JSON 仍可绕过）。
- scene 变更审计 `OperationAuditedEvent` 传了 `null,null` 快照，专用历史表只存 payloadSchema 一摊残缺历史。

## 4. 设计 —— A. Payload 半（输入数据契约）

### A1. PayloadFieldType enum + 创建期校验
- 新增 `PayloadFieldType` enum（config-svc `api/dto`，`PayloadFieldSpec` 同包）：`STRING / INTEGER / NUMBER / BOOLEAN / ARRAY / OBJECT`，作为 type 取值的**封闭真相源**。
- `PayloadFieldSpec.type` **保持 `String`**（API 边界契约不变，按 CLAUDE.md"出边界 String"规范；避免 Jackson 对非法枚举抛不可控反序列化错）。
- SceneService 的 create/update **逐字段校验** `type` 可解析为 `PayloadFieldType`（如 `PayloadFieldType.fromTag(type)`），非法抛 `IllegalArgumentException`（→ HTTP 400，message 含字段名 + 非法值 + 合法集）。这是受控的 fail-fast，错误信息友好。
- `PayloadDataTypeMapper` 改用 `PayloadFieldType` 做 type→`DataType` 映射（消除其内部的字符串 switch）。
- **词汇分层**：PayloadFieldType（JSON-shape 的 authoring 词汇）经 `PayloadDataTypeMapper` 桥接到 kernel `DataType`（eval 语义词汇），两套不混。kernel 不引入 PayloadFieldType。

### A2. 约束冻结（模型 2）
- `PayloadDependency`（kernel `api/model`）从 `(name, dataType, required)` 扩为携带约束：新增 `enumValues: List<Object>`、`minimum: Double`、`maximum: Double`、`pattern: String`（均可空）。
- `PublishService` 冻结 payload 依赖时（现 ~L565 `new PayloadDependency(field, dataTypeTag, spec.required())`），改为从对应 `PayloadFieldSpec` 取全量约束一并冻入。
- `rule_version.payload_dependencies` 是 JSON 列，record 扩字段即序列化形状扩展，**无 DDL 迁移**（Jackson3TypeHandler）。
- 模型 2 语义：约束随规则发布冻结，运营事后改 scene payloadSchema **不影响已发布规则**（可复现）。

### A3. 运行期值校验
- `PayloadInputValidator`（eval-svc）从"required + 基础类型"扩为额外校：
  - `enumValues` 非空 → 值必须 ∈ 列表，否则 `INPUT_ENUM_VIOLATION`；
  - `minimum`/`maximum` 非空且值为数值 → 越界 `INPUT_RANGE_VIOLATION`；
  - `pattern` 非空且值为字符串 → 不匹配 `INPUT_PATTERN_VIOLATION`（用 **RE2J**，与 MATCHES 同引擎，防 ReDoS）。
  - 违约抛 `IllegalArgumentException`（前缀错误码）→ GlobalExceptionHandler → HTTP 400。
- **并集合并规则**：候选快照 payload 依赖按 `name` 去重，**取首次出现的约束**（沿用现 `putIfAbsent`）；同一 scene 字段各规则冻结的约束本应一致，文档写明此确定性策略。
- **热路径性能（强制）**：校验是 O(去重后引用字段数)、不随规则数放大；无约束字段仅多一次 null 判断即短路。唯一有量级的是 pattern 正则——**`Pattern` 必须按 pattern 串缓存编译产物（复用 MatchesEvaluator 同款 RE2J 缓存），严禁每事件重编译**（否则重演已修的重编译灾难）。enum/min/max 为纳秒级廉价检查。

### A4. 删版本/历史表 + scene 变更走 audit
- **删**：`scene_payload_schema_history` 表 + `scene.payload_schema_version` 列（前向迁移 `V1_30__drop_scene_payload_schema_history.sql`）；`ScenePayloadSchemaHistory` 实体 + Mapper；SceneServiceImpl 的 `snapshotSchema` + 版本自增逻辑；`SceneDetailDto.version` 字段。
- **新增 `SceneSnapshot`**（config-svc，implements `AuditSnapshot`）：承载 scene 关键状态（name / eventTypes / payloadSchema / defaultParams / status）。
- `createScene`（after 快照）/ `updateScene`（before+after 快照）/ `disableScene`（before+after）把快照传进 `OperationAuditedEvent`，由 `AuditLogWriter` 序列化落 `audit_log.before/after_snapshot`（机制现成，scene 此前传 null 未用）。
- 效果：scene 变更历史统一进 audit_log，且记录整 scene 前后快照（比旧表只存 payloadSchema 更全）。

### A5. NON_NULL 止血
- `PayloadFieldSpec` 加 `@JsonInclude(JsonInclude.Include.NON_NULL)`：未设的可选约束（enum/min/max/pattern/description）不再序列化为 null，清掉 `scene.payload_schema` 与审计快照里的 null 杂乱。

## 5. 设计 —— B. Operator 半（逻辑操作数契约）

### B1. ConditionTypeCatalog
- `ConditionTypeCatalog`（config-svc `internal/publish`，public）：每内置算子 → `Spec(code, displayName, requiredParamKeys[ConditionParams], allowedDataTypes[DataType], requiresMetric)`。单一真相源。
- 覆盖 17 个矩阵算子（EQ/NEQ/GT/GTE/LT/LTE/BETWEEN/NOT_BETWEEN/IN/NOT_IN/CONTAINS/NOT_CONTAINS/STARTS_WITH/ENDS_WITH/MATCHES/DATE_BEFORE/DATE_AFTER）。
- `spec(conditionType)` 缺席（SPI 自定义 / time.* 内置路径）返回 null → 调用方放行。

### B2. ConditionParamValidator
- `ConditionParamValidator`（config-svc `internal/publish`）：遍历 AST 的 ConditionNode（仿 PayloadFieldCollector 的 sealed switch 走法），按 catalog 校 `params` 含全部必填键；缺键 → 抛 `IllegalArgumentException`（含 conditionType + 缺失键名）拒绝发布。
- catalog 缺席的 conditionType → 放行（SPI 开放，照 `ALLOWED 缺席即放行` 先例）。
- 接进 `PublishService` 发布流程（`AstDataTypeResolver` 调用点旁）。

### B3. AstDataTypeResolver 收敛
- `AstDataTypeResolver` 的私有 `ALLOWED` map 删除，允许 dataType 改读 `ConditionTypeCatalog.spec(type).allowedDataTypes()`（缺席仍放行，行为不变）。
- 既有 AstDataTypeResolverTest 全绿即证行为一致（allowed 集合从 catalog 取，值与原 ALLOWED 一致）。

### B4. 填 metadata
- `MetadataServiceImpl.getSceneMetadata`：`conditionTypes` 从 `ConditionTypeCatalog.all()` 填（现返回空 List）：
  - `ConditionTypeMeta(code, displayName, paramsSchema, requiresMetric)`；
  - `paramsSchema = Map.of("required", List.copyOf(requiredParamKeys))`（最小有用形态，UI 知道必填键）。

## 6. 组件清单

新建：
- `rule-config-svc/.../api/dto/PayloadFieldType.java`（enum）
- `rule-config-svc/.../internal/publish/ConditionTypeCatalog.java`（已起草）
- `rule-config-svc/.../internal/publish/ConditionParamValidator.java`
- `rule-config-svc/.../internal/event/SceneSnapshot.java`（implements AuditSnapshot）
- `rule-config-svc/src/main/resources/db/migration/V1_30__drop_scene_payload_schema_history.sql`

修改：
- `rule-kernel/.../api/model/PayloadDependency.java`（+enum/min/max/pattern）
- `rule-config-svc/.../api/dto/PayloadFieldSpec.java`（type→enum 边界、@JsonInclude NON_NULL）
- `rule-config-svc/.../internal/service/SceneServiceImpl.java`（type 校验 + 删快照/版本逻辑 + audit 前后快照）
- `rule-config-svc/.../internal/publish/PublishService.java`（冻全量约束 + 接 ConditionParamValidator）
- `rule-config-svc/.../internal/publish/AstDataTypeResolver.java`（读 catalog）
- `rule-config-svc/.../internal/service/MetadataServiceImpl.java`（填 conditionTypes）
- `rule-config-svc/.../api/dto/SceneDetailDto.java`（删 version）
- `rule-eval-svc/.../internal/validate/PayloadInputValidator.java`（enum/min/max/pattern）

删除：
- `rule-config-svc/.../internal/domain/ScenePayloadSchemaHistory.java` + `repository/ScenePayloadSchemaHistoryMapper.java`

## 7. 测试

单测：
- `PayloadFieldType` 解析 + SceneService 非法 type 拒绝。
- `ConditionTypeCatalog`（必填键/允许 dataType per 算子）。
- `ConditionParamValidator`（缺键拒、齐过、未知算子放行）。
- `AstDataTypeResolver`（既有用例全绿，证收敛后行为不变）。
- `PayloadInputValidator`：enum 违例 / min-max 越界 / pattern 不匹配 / 合法通过 / 约束为空放行。
- `SceneSnapshot` 审计：create/update/disable 落前后快照。
- `MetadataServiceImpl`：conditionTypes 非空且含 paramsSchema。
- `PublishService`：冻结 payload 依赖带约束。

性能基准（rule-benchmark，延续"先量再下结论"）：
- `PayloadInputValidatorBenchmark`：N 个引用字段下，(a) 无约束、(b) 带 enum/min/max、(c) 带 pattern 三档的 ns/op + 分配，验证 pattern 缓存生效、整体相对取数/AST 求值可忽略。

**DB 端到端功能测试（起真实服务走 DB）**：
1. 起服务，确认迁移到 V1_30、`scene_payload_schema_history` 已删。
2. 建场景：坏 `type`（如 "STRIGN"）→ 400；正确 schema（含 enum/min/max/pattern 字段）→ 成功。
3. 建规则：缺算子必填键（raw JSON）→ 发布 400；正确规则 → 发布成功，查 `rule_version.payload_dependencies` 真带约束。
4. 发事件：违反 enum/min/max/pattern → 400（对应错误码）；合法事件 → 命中。
5. 查 `audit_log`：scene create/update 有 before/after 快照（含 payloadSchema）。
6. 清理测试数据，恢复基线。

## 8. 迁移与兼容

- 开发期无存量数据（greenfield）：`V1_30` 前向 drop 历史表 + 版本列；`payload_dependencies` JSON 形状扩展无需 DDL。
- 无向后兼容包袱：API 契约去 `SceneDetailDto.version`、`PayloadFieldSpec.type` 收 enum，均按 greenfield 直接改。

## 9. 实施顺序（分阶段，独立提交）

1. **算子半**：ConditionTypeCatalog + ConditionParamValidator + AstDataTypeResolver 收敛 + 填 metadata（不依赖 payload 半）。
2. **payload 半 - 静态**：PayloadFieldType enum + SceneService type 校验 + @JsonInclude NON_NULL。
3. **payload 半 - 冻结+运行期**：PayloadDependency 扩约束 + PublishService 冻结 + PayloadInputValidator 强制。
4. **审计收口**：SceneSnapshot + scene 前后快照 + 删历史表/版本列/实体（V1_30）。
5. **全量 clean test + DB 端到端功能测试**。

## 10. 后续（本设计不含）

- 长期可把 `ConditionTypeCatalog`（算子）+ payloadSchema（输入）合成统一 `OperatorCatalog`/Schema 注册中心；本设计先各自收口。
- 自定义 SPI 算子经 SPI 暴露自身 param schema（留缝，不强制）。
- payloadSchema 嵌套对象 / JSON Schema 完整子集。
