# 规则模板系统 V2 — 设计文档

## 1. 设计目标

模板是独立于租户体系的 **Platform 层能力**，不是某个租户的私有数据。规则引擎的核心链路（scene → rule → metric → eval）**完全不知道模板的存在**，有没有模板功能核心运行零影响。

三条不可破坏的红线：
1. **核心表零污染**：`rule_version`、`rule_definition`、`scene`、`metric_definition` 等核心表无任何模板字段
2. **单向依赖**：模板子系统依赖核心，核心不依赖模板
3. **零魔法值**：所有状态用枚举表达，不用 `tenant_id=0` 之类的约定数字

---

## 2. 分层架构与数据流

```
Platform 层 (SYSTEM tenant)
  rule_template              ← 身份层：code / name / kind / status（同 rule_definition）
  rule_template_version      ← 快照层：body_skeleton / slots / bindings，不可变（同 rule_version）
        │
        │ 实例化（单向）
        ▼
Tenant 层
  rule_definition            ← 核心，零模板字段
  rule_version               ← 核心，零模板字段
  rule_template_instantiation ← 溯源表，单向持有(template_version → rule_version)，可删
        │
        ▼
Scene 层
  eval pipeline              ← 完全不知道模板存在
```

依赖方向严格单向向下。`rule_template_instantiation` 是唯一跨层的表，删掉它两层完全解耦，核心功能零影响。`rule_template`/`rule_template_version` 整体删除，核心同样零影响。

---

## 3. 数据模型

### 3.1 Tenant 类型扩展

```sql
ALTER TABLE tenant
  ADD COLUMN type VARCHAR(16) NOT NULL DEFAULT 'STANDARD'
  COMMENT 'STANDARD=普通租户, SYSTEM=平台系统租户';

-- SYSTEM tenant 初始化（迁移文件）
INSERT INTO tenant (code, name, type, status, ...)
  VALUES ('SYSTEM', '平台系统', 'SYSTEM', 'ACTIVE', ...);
```

Java 枚举（不使用魔法数字）：
```java
public enum TenantType { STANDARD, SYSTEM }
```

### 3.2 模板版本化（与规则完全同构）

模板的身份层与快照层分离，和 `rule_definition` / `rule_version` 的设计完全镜像：

```
规则：  rule_definition（身份）  ←→  rule_version（不可变快照）
模板：  rule_template（身份）    ←→  rule_template_version（不可变快照）
```

生命周期：
- 每次编辑 → 新建一个 `rule_template_version` 行（不覆盖旧版本）
- PUBLISHED 版本永不修改（同 `rule_version`）
- 身份层 `rule_template.status` 跟踪最新状态

**rule_template（身份层，精简）**

```sql
CREATE TABLE rule_template (
  id          BIGINT        NOT NULL AUTO_INCREMENT,
  tenant_id   BIGINT        NOT NULL COMMENT '所属租户；SYSTEM tenant = 平台级模板',
  code        VARCHAR(128)  NOT NULL,
  name        VARCHAR(256)  NOT NULL,
  description VARCHAR(1024),
  kind        VARCHAR(32)   NOT NULL COMMENT 'RuleKind 枚举',
  status      VARCHAR(16)   NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/PUBLISHED/DISABLED',
  created_by  VARCHAR(64),
  created_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_by  VARCHAR(64),
  updated_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_tenant_code (tenant_id, code)
) COLLATE = utf8mb4_unicode_ci;
```

**rule_template_version（快照层，不可变，同 rule_version）**

```sql
CREATE TABLE rule_template_version (
  id            BIGINT  NOT NULL AUTO_INCREMENT,
  template_id   BIGINT  NOT NULL COMMENT '→ rule_template.id',
  version       INT     NOT NULL COMMENT '同一模板内单调递增',
  body_skeleton JSON    NOT NULL COMMENT '合法 body，所有值位已填默认值，无 token',
  slots         JSON    NOT NULL COMMENT 'TemplateSlot[]',
  bindings      JSON    NOT NULL COMMENT 'SlotBinding[]',
  status        VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/PUBLISHED',
  created_by    VARCHAR(64),
  created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_template_version (template_id, version)
) COLLATE = utf8mb4_unicode_ci;
```

### 3.3 rule_template_instantiation 表（溯源，可插拔）

FK 指向 `rule_template_version`——现在有真正的语义（知道从哪个版本实例化出来的）。

```sql
CREATE TABLE rule_template_instantiation (
  id                  BIGINT   NOT NULL AUTO_INCREMENT,
  template_id         BIGINT   NOT NULL COMMENT '→ rule_template.id',
  template_version_id BIGINT   NOT NULL COMMENT '→ rule_template_version.id（真正的 FK）',
  template_version    INT      NOT NULL COMMENT '冗余版本号，便于查询',
  rule_definition_id  BIGINT   NOT NULL COMMENT '→ rule_definition.id（tenant 层）',
  rule_version_id     BIGINT   NOT NULL COMMENT '→ rule_version.id（tenant 层）',
  slot_values         JSON     NOT NULL COMMENT '实例化时的填值快照',
  instantiated_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  instantiated_by     VARCHAR(64),
  PRIMARY KEY (id),
  KEY idx_template_id (template_id),
  KEY idx_rule_version_id (rule_version_id)
) COLLATE = utf8mb4_unicode_ci;
```

### 3.4 核心表变更（删除污染字段）

```sql
ALTER TABLE rule_version
  DROP COLUMN template_id,
  DROP COLUMN template_version;
```

Java 实体同步：`RuleVersion.templateId`、`RuleVersion.templateVersion` 字段删除。  
`PublishService.createDraft` 签名恢复干净，不带任何模板参数。

---

## 4. Slot 类型体系

### 4.1 SlotKind（分类，不混入值类型）

```java
/**
 * Slot 的种类。决定实例化时的验证逻辑和前端渲染的 picker 控件。
 * kind 本身编码了解析作用域，无需额外 scope 字段。
 */
public enum SlotKind {
  /** 具体值，dataType 字段指定类型 */
  VALUE,
  /** 指标引用，tenant 层解析，constraint.allowedDataTypes 限制兼容类型 */
  METRIC_REF,
  /** 决策引用，scene 层解析（实例化时由 target sceneCode 确定） */
  DECISION_REF,
  /** 规则引用，tenant 层解析（Flow RuleRefNode.ruleCode） */
  RULE_REF
}
```

作用域由 `kind` 隐含：
- `METRIC_REF` / `RULE_REF` → tenant 层，实例化时在 tenant 内验证存在性
- `DECISION_REF` → scene 层，实例化时在 target scene 内验证存在性

### 4.2 ValueDataType（仅 VALUE kind 使用）

```java
/** 值类型，仅 SlotKind.VALUE 时有意义。 */
public enum ValueDataType {
  LONG, DOUBLE, DECIMAL, STRING, BOOLEAN, DATE, DATETIME, LIST
}
```

### 4.3 TemplateSlot record

```java
/**
 * 模板参数 schema。
 * kind=VALUE 时 dataType 必填；kind=*_REF 时 dataType 为 null。
 */
public record TemplateSlot(
    String key,
    String label,
    SlotKind kind,
    @Nullable ValueDataType dataType,   // 仅 VALUE 时有效
    boolean required,
    @Nullable SlotConstraint constraint
) {}
```

### 4.4 SlotConstraint

```java
/**
 * Slot 值的约束条件。不同 kind 使用不同字段：
 * VALUE(数值类型) → min / max
 * VALUE(枚举类型) → enumValues
 * METRIC_REF     → allowedDataTypes（限制 metric 的 dataType 兼容性）
 * DECISION_REF / RULE_REF → 暂无约束（存在性校验由 SlotRefResolver 完成）
 */
public record SlotConstraint(
    @Nullable BigDecimal min,
    @Nullable BigDecimal max,
    @Nullable List<String> enumValues,
    @Nullable List<String> allowedDataTypes   // METRIC_REF 专用
) {}
```

### 4.5 SlotBinding / SlotTarget（沿用，不改）

```java
sealed interface SlotTarget permits JsonPointerTarget {}
record JsonPointerTarget(String jsonPointer) implements SlotTarget {}
record SlotBinding(String slotKey, SlotTarget target) {}
```

`JsonPointerTarget` 统一寻址 body skeleton 内任意 JSON 位置：
- AST 值位：`/conditionAst/children/0/params/threshold`
- script 常量：`/script/params/threshold`
- flow 常量：`/flowGraph/params/threshold`
- flow 节点字段：`/flowGraph/nodes/0/ruleCode`（RULE_REF）

---

## 5. 后端代码架构

### 5.1 SlotRefResolver SPI（新增，仿 TemplateBinder 定式）

```java
/**
 * 引用类型 Slot 的验证器 SPI。
 * Spring 注入 List<SlotRefResolver>，按 supports() 分派，零 switch。
 * 加新 kind = 加一个实现类 + 注册，Service 代码零改动。
 */
public interface SlotRefResolver {
    /** 是否处理此 kind */
    boolean supports(SlotKind kind);

    /**
     * 验证引用值在目标作用域内合法存在且满足约束。
     * 不合法抛带错误码的 IllegalArgumentException。
     * @param value  slot 填入的字符串值（metricCode / decisionCode / ruleCode）
     * @param slot   slot schema（含 constraint.allowedDataTypes 等约束）
     * @param ctx    解析上下文（tenantId 必有，sceneCode DECISION_REF 时有效）
     */
    void validate(String value, TemplateSlot slot, SlotResolutionContext ctx);
}

/** 解析上下文——携带验证所需的作用域信息 */
public record SlotResolutionContext(Long tenantId, @Nullable String sceneCode) {}
```

V1 实现（Phase 1，只做存在性校验）：
- `MetricRefResolver`：验证 metric code 在 tenant 内存在且 ACTIVE（Phase 2 补 allowedDataTypes 兼容性深度校验）
- `DecisionRefResolver`：验证 decision code 在 target scene 内存在且 ACTIVE
- `RuleRefResolver`：验证 rule code 在 tenant 内存在且有 PUBLISHED 版本

### 5.2 实例化流水线（关注点分离，每步独立）

```java
// 1. 加载身份（带可见性：tenant 自有 OR SYSTEM tenant，JOIN tenant.type）
//    同名 code 时 STANDARD 自有模板优先于 SYSTEM 模板（业务优先级约定）
RuleTemplate tmpl = templateMapper.findVisibleByCode(callerTenantId, templateCode);
if (tmpl == null) throw new NotFoundException("模板不存在: " + templateCode);
if (tmpl.status() == TemplateStatus.DISABLED) throw new IllegalStateException("模板已禁用");

// 加载最新 PUBLISHED 快照（版本号最大的 PUBLISHED 行）
// 也可指定：findByVersion(tmpl.id(), requestedVersion)
RuleTemplateVersion tmplVer = templateVersionMapper.findLatestPublished(tmpl.id());
if (tmplVer == null) throw new IllegalStateException("模板无已发布版本");

// 2. 构造 coercedValues：VALUE slot 做强转，REF slot 原样字符串 pass-through
//    bind() 需要所有 slot 的值（VALUE + REF 都要），不能只传 VALUE 的
Map<String, Object> coercedValues = new HashMap<>();
for (TemplateSlot slot : tmplVer.slots()) {
    Object raw = slotValues.get(slot.key());
    if (slot.kind() == SlotKind.VALUE) {
        coercedValues.put(slot.key(), coerce(raw, slot));  // 强转 + VALUE constraint 校验
    } else {
        // REF slot：值是字符串（metricCode/decisionCode/ruleCode），原样放入
        coercedValues.put(slot.key(), raw);
    }
    if (slot.required() && raw == null) throw new IllegalArgumentException("必填 slot 未提供: " + slot.key());
}

// 3. 引用类型 slot 验证（SlotRefResolver SPI 分派，零 switch）
//    注意：callerTenantId 是调用方租户（STANDARD tenant），不是模板归属的 tenant
//    metric/rule/decision 均在调用方租户 + 目标场景内验证，与模板来源无关
for (TemplateSlot slot : tmplVer.slots()) {
    if (slot.kind() != SlotKind.VALUE) {
        SlotRefResolver resolver = pick(slot.kind());
        resolver.validate((String) coercedValues.get(slot.key()), slot,
                          new SlotResolutionContext(callerTenantId, sceneCode));
    }
}

// 4. bind：把值填入 skeleton（TemplateBinder SPI 不改）
//    可选 slot 未提供时 coercedValues.containsKey 为 false → binder 跳过 → 保留 skeleton 默认值
RuleBody bound = binder.bind(tmplVer.bodySkeleton(), tmplVer.bindings(), coercedValues);

// 5. 创建草稿（PublishService.createDraft 签名干净，不带模板参数）
RuleContent content = new RuleContent(ruleName, tmpl.kind().tag(), bound, ...);
DraftCreatedResult result = publishService.createDraft(callerTenantId, sceneCode, ruleCode, content, actorId);

// 6. 写溯源（best-effort，独立事务；失败记录错误日志但不回滚步骤 5 已创建的规则）
try {
    instantiationMapper.insert(new RuleTemplateInstantiation(
        tmpl.id(), tmplVer.id(), tmplVer.version(),
        result.ruleDefinitionId(), result.ruleVersionId(),
        slotValues   // 保存原始填值快照（未强转），便于人工排查
    ));
} catch (Exception e) {
    log.error("溯源写入失败，规则已创建，可人工补录: ruleVersionId={}", result.ruleVersionId(), e);
}
```

**关键约束说明：**
- `callerTenantId`（调用方 STANDARD tenant）贯穿整个流程：metric/rule/decision 验证、`createDraft` 归属、溯源记录。模板来自 SYSTEM tenant 只影响可见性查询，不影响实例化产物的归属。
- 可选 slot（required=false）未提供值时，步骤 2 不放入 coercedValues，步骤 4 的 binder 检测到 key 缺失跳过该 binding，skeleton 默认值保留——这是期望行为，不是 bug。

### 5.3 TemplateBinder SPI（沿用现有，不改）

`TemplateBinder.bind()` 处理 VALUE 和 REF 两种 slot 的 body 填充逻辑完全相同——都是 JsonPointer 替换 JSON 节点。REF 类型的值（metricCode 字符串）和 VALUE 类型的值（100L）在 bind 层面没有区别，bind 层不需要知道 kind。

已通过 Phase 1 实现和 e2e 验证，零改动。

### 5.4 模板状态机与双表同步协议

`rule_template`（身份层）和 `rule_template_version`（快照层）各有 status，同步规则如下：

**状态流转：**
```
rule_template.status:         DRAFT → PUBLISHED → DISABLED
rule_template_version.status: DRAFT → PUBLISHED（每个 version 行独立）
```

**操作 → 两表变更：**

| 操作 | rule_template | rule_template_version |
|---|---|---|
| 创建 | status=DRAFT | 新增 v1 行，status=DRAFT |
| 保存草稿 | status 不变（DRAFT）| 更新当前 DRAFT 行（同 version 号） |
| 发布 | status→PUBLISHED | 当前 DRAFT version→PUBLISHED；旧 PUBLISHED 版本保留原状（不变为 SUPERSEDED，靠 max version 消歧） |
| 发布后再编辑 | status→DRAFT | 新增 v(n+1) 行，status=DRAFT；原 PUBLISHED 行不动 |
| 再次发布 | status→PUBLISHED | 新 DRAFT version→PUBLISHED |
| 禁用 | status→DISABLED | 所有 version 行不变（历史快照完整保留） |

**findLatestPublished 逻辑：**
```sql
SELECT * FROM rule_template_version
WHERE template_id = #{templateId} AND status = 'PUBLISHED'
ORDER BY version DESC LIMIT 1
```
两个 PUBLISHED 版本并存时取 version 最大的，保证实例化用最新发布版本。

**约定：**
- `rule_template.status` 跟踪"当前最新版本的状态"，是显示给用户的聚合状态，不作为实例化资格判断的唯一依据——实例化检查路径：`tmpl.status != DISABLED && tmplVer != null`。
- **同一模板同时只能有一个 DRAFT 版本**（应用层保证）：新建 DRAFT 前先检查是否已有 DRAFT 行，有则复用（更新），无则新建。DB 层不加唯一约束（允许 DRAFT+PUBLISHED 并存），业务逻辑保证 DRAFT 行最多一条。

### 5.6 可见性查询

```java
// 列表查询：当前 tenant 自有模板 + SYSTEM tenant 模板，单次 JOIN，无 UNION
@Select("""
    SELECT t.* FROM rule_template t
    JOIN tenant tn ON t.tenant_id = tn.id
    WHERE tn.id = #{tenantId} OR tn.type = 'SYSTEM'
    ORDER BY tn.type DESC, t.created_at DESC
""")
List<RuleTemplate> findVisibleByTenant(Long tenantId);

// 单条查询（实例化用）：同名 code 时 STANDARD 自有模板优先于 SYSTEM 模板
@Select("""
    SELECT t.* FROM rule_template t
    JOIN tenant tn ON t.tenant_id = tn.id
    WHERE t.code = #{code}
      AND (tn.id = #{tenantId} OR tn.type = 'SYSTEM')
    ORDER BY CASE WHEN tn.id = #{tenantId} THEN 0 ELSE 1 END
    LIMIT 1
""")
RuleTemplate findVisibleByCode(Long tenantId, String code);
```

---

## 6. 前端设计

### 6.1 模板编辑器布局（三栏，与规则编辑器对齐）

模板编辑器整体骨架复用规则编辑器的三栏布局，概念一一对应：

```
规则编辑器                       模板编辑器
─────────────────────────────────────────────────────────
左栏：规则元数据                 左栏：模板元数据
      (name/kind/scene)               (name/kind/description)

中栏：body 编辑器                中栏：body skeleton 编辑器
      RuleBodyEditor /                RuleBodyEditor /
      FlowCanvasEditor                FlowCanvasEditor（已复用）

右栏：RightPanel                 右栏：参数化面板
      - 节点 inspector                 - Slots 列表（已声明参数）
      - pre-gate 配置                  - "+ 参数化" 位置选择器
      - decision binding               - Slot schema 编辑（kind/dataType/约束/required）
```

**结构语义对应**：规则右栏的 decision binding / pre-gate 是"规则的配置 sidecar"，与 body 是两件事但共同定义一条规则；模板右栏的 slots/bindings 也是"模板的配置 sidecar"，与 body skeleton 共同定义一个模板。语义一致，位置就一致。

**一处差异（不影响布局）**：规则右栏是 context-sensitive（点画布节点 → 右栏内容切换到该节点属性）；模板右栏是静态列表（slots 一张表，不随选中节点变化）。右栏仍是独立区域，只是内容驱动方式不同。

**实现层面**：页面骨架（左中右三栏 CSS 布局）复用规则编辑器那套；中栏已是同一组件；左栏换 Form 内容；右栏换成 slots/bindings 面板。不重造布局。

> 注：flow 节点属性编辑（SwitchNode expression / OutputNode decision 等）复用已抽出的 `FlowNodeInspector` 组件（prop 驱动、无 store 依赖），规则编辑器和模板编辑器共用，见 §6.2。

### 6.2 组件分层与复用边界

**原则：共享逻辑，不共享上下文。** 相同的 picker 组件在两个不同上下文（模板编辑器的约束预览 vs 实例化表单的值填写）中复用，但它们各自的容器（`SlotValueInput` vs `SlotFormItem`）保持独立，因为两个上下文的数据流和 Form.Item 绑定需求根本不同——强行合并会很别扭。

```
共享 picker（headless，无 Form.Item）
  ├── SlotValueInput    已有，VALUE kind，按 dataType 渲染原始值输入
  ├── MetricPicker      新建，加载 tenant ACTIVE metrics，按 allowedDataTypes 过滤
  ├── DecisionPicker    新建，响应式依赖 sceneCode，动态加载 scene decisions
  └── RulePicker        新建，加载 tenant 已发布规则

  ↓ 模板编辑器用（约束预览，参数表格里）
  SlotValueInput（直接用，不经 Form.Item，已有）

  ↓ 实例化表单用（带 Form.Item、验证、dayjs 转换）
  SlotFormItem（新建，统一封装，替换 renderSlotInput switch）
```

### 6.3 共享 picker 接口

```typescript
/** 所有 picker 的统一 props——headless，不含 Form.Item */
interface SlotPickerProps {
  value: unknown;
  onChange: (v: unknown) => void;
  disabled?: boolean;
  context: {
    tenantId: number;
    sceneCode?: string;    // DecisionPicker 需要，其他可忽略
  };
  constraint?: SlotConstraint;
}

// MetricPicker：tenant 全量 ACTIVE metric，按 constraint.allowedDataTypes 过滤
// DecisionPicker：useEffect([sceneCode]) 响应式拉取，sceneCode 空时 disabled
// RulePicker：tenant 全量 PUBLISHED 规则

/** kind → picker 映射，加新 kind 只在这里加一行，渲染逻辑不改 */
const SLOT_PICKER: Record<SlotKind, React.ComponentType<SlotPickerProps>> = {
  VALUE:        SlotValueInput,
  METRIC_REF:   MetricPicker,
  DECISION_REF: DecisionPicker,
  RULE_REF:     RulePicker,
};
```

### 6.4 SlotFormItem（实例化表单专用，替换 renderSlotInput）

```typescript
/**
 * 实例化表单的 slot 输入项。
 * 封装 Form.Item + 按 SlotKind 分派 picker + dayjs 转换。
 * 替换 template-instantiate/index.tsx 里手写的 renderSlotInput switch。
 */
interface SlotFormItemProps {
  slot: TemplateSlot;               // kind + dataType + constraint + required
  context: { tenantId: number; sceneCode?: string };
}

export default function SlotFormItem({ slot, context }: SlotFormItemProps) {
  const Picker = SLOT_PICKER[slot.kind];  // 按 kind 分派，零 switch
  return (
    <Form.Item
      name={`slot_${slot.key}`}
      label={slot.label}
      rules={slot.required ? [{ required: true }] : []}
      getValueFromEvent={slot.kind === 'VALUE' && (slot.dataType === 'DATE' || slot.dataType === 'DATETIME')
        ? (v: Dayjs) => v?.toISOString()   // dayjs 转换只在需要时做
        : undefined}
    >
      <Picker context={context} constraint={slot.constraint} />
    </Form.Item>
  );
}
```

实例化表单里原来的 `renderSlotInput` switch **整段删除**，替换为：
```tsx
{tmpl.slots.map((slot) => (
  <SlotFormItem key={slot.key} slot={slot} context={{ tenantId: currentId, sceneCode: watchedSceneCode }} />
))}
```

### 6.5 实例化表单结构（响应式，无 wizard 分步）

行业标准（CloudFormation / Backstage）：参数全部展示在一个表单，依赖关系用响应式联动，不做分步 wizard。

```
┌─────────────────────────────────────────────┐
│ 目标场景   [Select] ← 填了这个，             │
│                      DecisionPicker 自动更新  │
│ 规则编码   [Input]                           │
│ 规则名称   [Input]                           │
│ 触发事件   [Select tags]                     │
├─────────────────────────────────────────────┤
│ Slot 参数                                    │
│                                             │
│ threshold  [SlotFormItem → SlotValueInput]  │  kind=VALUE
│ metric     [SlotFormItem → MetricPicker]    │  kind=METRIC_REF
│ outcome    [SlotFormItem → DecisionPicker]  │  kind=DECISION_REF，依赖上面的场景
│ refRule    [SlotFormItem → RulePicker]      │  kind=RULE_REF
│                                             │
│           [实例化] →跳转规则编辑器           │
└─────────────────────────────────────────────┘
```

**sceneCode 联动**：`Form.useWatch('sceneCode')` 得到实时场景值，传入 `SlotFormItem` context，DecisionPicker 的 `useEffect([sceneCode])` 自动重拉该场景的决策列表。场景未选时 DecisionPicker disabled 并给提示。提交时后端 SlotRefResolver 做完整校验（前端联动是体验层）。

### 6.6 模板编辑器保存语义（随版本化调整）

和规则编辑器的草稿保存完全同构：

| 操作 | 行为 |
|---|---|
| 保存草稿 | 更新当前 DRAFT `rule_template_version` 行（同一 version 号） |
| 发布 | 当前 DRAFT version 改为 PUBLISHED，不可再修改 |
| 发布后再编辑 | 新建一个 DRAFT `rule_template_version` 行（version+1） |

前端只需在保存时区分"草稿 PUT"vs"新版本 POST"，与现有规则编辑器的行为模式一致，用户心智零增量。

### 6.7 模板列表

- SYSTEM tenant 的模板带 `[系统]` 标签区分，租户自有模板无标签
- 实例化入口：列表行"实例化"按钮 → 跳 `template-instantiate/:code`（默认最新 PUBLISHED 版本）
- 版本历史：点模板名可查历史 `rule_template_version` 列表，可选择从某个历史版本实例化
- Phase 1：SYSTEM 模板由 API 创建，列表只读展示；创建/编辑只对 STANDARD tenant 自有模板开放

### 6.8 类型定义更新（一次性改对）

```typescript
// types/template.ts

export type SlotKind = 'VALUE' | 'METRIC_REF' | 'DECISION_REF' | 'RULE_REF';

// ValueDataType 独立，不再和 SlotKind 混在一个 DataType 里
export type ValueDataType =
  'LONG' | 'DOUBLE' | 'DECIMAL' | 'STRING' | 'BOOLEAN' | 'DATE' | 'DATETIME' | 'LIST';

export interface SlotConstraint {
  min?: number | null;
  max?: number | null;
  enumValues?: string[] | null;
  allowedDataTypes?: string[] | null;   // METRIC_REF 专用：限制 metric 的 dataType
}

export interface TemplateSlot {
  key: string;
  label: string;
  kind: SlotKind;
  dataType?: ValueDataType;   // 仅 kind=VALUE 时有值
  required: boolean;
  constraint?: SlotConstraint | null;
}
```

旧的 `DataType` 类型从 `TemplateSlot.dataType` 上删除，改为 `ValueDataType`（可选）。
`SlotValueInput` props 里的 `dataType: DataType` 改为 `dataType?: ValueDataType`，组件内部不变。

---

## 7. Phase 边界

### Phase 1（当前可实现）

- `TenantType` 枚举 + `tenant.type` 字段
- SYSTEM tenant 初始化
- 核心表清理（删 `rule_version.template_id/version`，`PublishService` 签名还原）
- `rule_template`（身份层）+ `rule_template_version`（快照层，不可变）双表设计
- `rule_template_instantiation` 溯源表（FK 指向 `rule_template_version`）
- 模板版本化编辑行为：DRAFT 版本可直接更新同一行；PUBLISH 后新编辑产生新 version 行（同 rule_version 模式）
- 模板可见性查询（JOIN tenant.type）
- `SlotKind` 枚举拆分（VALUE + METRIC_REF + DECISION_REF + RULE_REF schema 定义）
- `SlotRefResolver` SPI 框架 + 三个实现（Phase 1 只做存在性校验，不做深度类型兼容）
- 前端：`SLOT_KIND_WIDGET` Record + DecisionPicker / MetricPicker / RulePicker 组件（骨架）；`SlotFormItem` 替换 renderSlotInput switch；模板编辑器保存行为同步版本化语义
- bodySkeleton 里 metricCode 仍为具体字符串（SYSTEM tenant 建自己的示例 metric）

### Phase 2（另立项）

- `METRIC_REF` slot 的实例化深度验证（metricCode 对应的 metric dataType 与 allowedDataTypes 兼容）
- bodySkeleton 中 `metricCode` 改为参数化（从写死字符串改为 METRIC_REF slot）
- 前端 MetricPicker 按 `allowedDataTypes` 过滤
- 模板真正做到 tenant-agnostic（实例化时 tenant 填自己的 metric）
- admin 角色管理（SYSTEM tenant 模板的创建/编辑权限）

---

## 8. 迁移方案

### V1_42（改写，当前未 apply）

```sql
-- 1. tenant 表加 type 字段
ALTER TABLE tenant ADD COLUMN type VARCHAR(16) NOT NULL DEFAULT 'STANDARD';

-- 2. 插入 SYSTEM tenant
INSERT INTO tenant (code, name, type, status) VALUES ('SYSTEM', '平台系统', 'SYSTEM', 'ACTIVE');

-- 3. rule_template 身份表（精简，无内容字段）
CREATE TABLE rule_template (...);          -- 见 §3.2

-- 4. rule_template_version 快照表（不可变，同 rule_version 设计）
CREATE TABLE rule_template_version (...);  -- 见 §3.2

-- 5. rule_template_instantiation 溯源表（FK 指向 rule_template_version）
CREATE TABLE rule_template_instantiation (...);  -- 见 §3.3

-- 注意：V1_42 不再包含 ALTER TABLE rule_version，核心表不动
```

### rule_version 清理（单独迁移文件 V1_43）

```sql
ALTER TABLE rule_version
  DROP COLUMN template_id,
  DROP COLUMN template_version;
```

---

## 9. 不做的事

- **B 活链接**：模板改动不传播到已实例化的规则（快照式，D6 红线）
- **反向导出**（`exportFromRule`）：已删，Phase 2 如需另立
- **Flow 表达式 params UI**（flow 表达式常量参数化）：后端已支持，前端待真需求
- **跨租户模板共享**（tenant A 模板给 tenant B 用）：超出 SYSTEM/STANDARD 两级设计，不在范围内
- **模板版本 diff UI**：版本化数据已有，diff 展示留后续

---

## 10. 关键不变量

1. 删除 `rule_template_instantiation` 表 → 规则引擎核心功能零影响
2. 删除 `rule_template` + `rule_template_version` 两表 → 规则引擎核心功能零影响
3. `SlotRefResolver` 新增实现 → `Service` / `Controller` 代码零改动（SPI 自动收集）
4. 新增 `SlotKind` 枚举值 → 前端只改 `SLOT_KIND_WIDGET` Record 一行
5. `TemplateBinder.bind()` 对 VALUE 和 REF slot 行为完全一致 → bind 层不需要知道 kind
6. `rule_template_version` 发布后不可变 → 任何版本的实例化产物都可追溯到当时的 skeleton/slots/bindings 快照
7. 增加模板版本化 → `TemplateBinder`/`SlotRefResolver`/`PublishService`/前端 picker 全部不改，只改模板子系统内部 mapper/service

---

## References

- 当前 D74 实现：`2026-07-24-parameterized-rule-template-redesign-design.md`
- 模板编辑器 UX：`2026-07-24-template-editor-authoring-ux-design.md`
- TemplateBinder SPI 现有实现：`rule-config-svc/internal/template/JsonPointerBinder.java`
- FlowNodeInspector 组件化：`rule-editor/FlowNodeInspector.tsx`
- 行业参照：[CloudFormation Rules](https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/rules-section-structure.html) · [Backstage Templates](https://backstage.io/docs/features/software-templates/writing-templates/)
