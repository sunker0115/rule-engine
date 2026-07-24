# 模板系统 V2 后端实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development(推荐)或 superpowers:executing-plans 逐 task 执行。步骤用 `- [ ]` 勾选。

**Goal:** 把 D74 模板重构为独立于 tenant 的 Platform 层子系统——核心表零污染、身份/快照分离版本化、SlotKind 拆分、SlotRefResolver SPI，greenfield 无兼容负担直奔最终态。

**Architecture:** 模板子系统单向依赖核心（`rule_definition`/`rule_version`/`metric`/`decision` 零模板字段）。`rule_template`(身份) + `rule_template_version`(快照,不可变) 同构 `rule_definition`/`rule_version`。实例化经 SlotRefResolver SPI 验证 REF slot + TemplateBinder 填 skeleton + PublishService.createDraft 产规则 + instantiation 表溯源。

**Tech Stack:** Java 25 / Spring Boot 4 / Modulith / MyBatis-Plus / Flyway / MySQL；测试 JUnit5 + Mockito。用 `mvn-env` skill 设环境跑测。

**Spec:** `docs/superpowers/specs/2026-07-24-template-system-v2-design.md`

## Global Constraints

- **核心表零污染**：`rule_version`/`rule_definition`/`scene`/`metric_definition` 无任何模板字段。`RuleVersion` 实体删 `templateId`/`templateVersion`。`PublishService.createDraft` 签名不带模板参数。
- **单向依赖**：模板子系统依赖核心，核心不 import 任何模板类型。
- **零魔法值**：`TenantType`/`SlotKind`/`ValueDataType`/`TemplateStatus` 全用枚举；不用 `tenant_id=0`。
- **greenfield**：无数据迁移、无代码兼容。V1_42 改写为 V2 最终态，`flyway clean` 重建；旧 D74 代码直接删改重写。
- **枚举出边界转 String**：实体字段用 Java enum（枚举名==varchar 值），出 VO/DTO/API `.name()` 转 String（项目 CLAUDE.md 数据类型规范）。
- **typed JSON 列**：`@TableName(autoResultMap=true)` + `@TableField(typeHandler=Jackson3TypeHandler.class)`，不手写 ObjectMapper。
- **SPI 零 switch**：`SlotRefResolver` 按 `supports(kind)` 分派，加 kind = 加实现类，service 不改。
- **测试纪律**：每 task 提交前 `$MVN -pl <module> -am test` 全绿；跨模块改动带 `-am`；一轮结束 `$MVN clean test` 兜底。

## 现有 D74 代码盘点（重构目标，实现者先读）

| 文件 | 处置 |
|---|---|
| `rule-config-svc/.../internal/domain/RuleTemplate.java` | 拆分：身份字段留，body/slots/bindings/version 移到新 `RuleTemplateVersion` |
| `rule-config-svc/.../internal/domain/RuleVersion.java` | 删 `templateId`/`templateVersion` 字段 |
| `rule-config-svc/.../api/dto/TemplateSlot.java` | 加 `SlotKind kind`，`dataType` 改 `ValueDataType` 可空 |
| `rule-config-svc/.../api/dto/SlotBinding.java` / `SlotConstraint.java` | SlotConstraint 加 `allowedDataTypes` |
| `rule-config-svc/.../api/service/RuleTemplateService.java` | 接口调整（见 Task） |
| `rule-config-svc/.../internal/service/RuleTemplateServiceImpl.java` | 重写：版本化 + SlotRefResolver 接入 |
| `rule-config-svc/.../internal/repository/RuleTemplateMapper.java` | 拆 + 加 `findVisibleByTenant`/`findVisibleByCode` |
| `rule-config-svc/.../internal/publish/PublishService.java` | `createDraft` 删模板参数 |
| `rule-config-svc/.../internal/template/TemplateBinder.java` / `JsonPointerBinder.java` | 不改（bind 层不知 kind） |
| `rule-config-svc/.../internal/event/RuleTemplateSnapshot.java` / `AuditSnapshot.java` | 随实体调整 |
| `rule-api/.../web/admin/RuleTemplateController.java` + `dto/*.java` | 请求 DTO 加 kind/version 相关字段 |
| `rule-config-svc/src/main/resources/db/migration/V1_42__rule_template.sql` | 改写为 V2 最终 schema |
| kernel `DataType` enum | 复用为值类型来源（LONG..LIST，排除 UNKNOWN）→ 新 `ValueDataType` |

---

## Task 1: V1_42 迁移改写为 V2 最终 schema

**Files:**
- Modify(改写): `rule-config-svc/src/main/resources/db/migration/V1_42__rule_template.sql`

**Interfaces:**
- Produces: `tenant.type` 列；SYSTEM tenant 行；`rule_template`(身份)/`rule_template_version`(快照)/`rule_template_instantiation`(溯源) 三表；`rule_version` 无模板列。

- [ ] **Step 1: 改写 V1_42 全文**

```sql
-- 模板系统 V2：Platform 层独立子系统，核心表零污染
-- 1. tenant 加 type（区分 SYSTEM/STANDARD，不用 tenant_id=0 魔法值）
ALTER TABLE tenant
    ADD COLUMN type VARCHAR(16) NOT NULL DEFAULT 'STANDARD'
    COMMENT 'STANDARD=普通租户, SYSTEM=平台系统租户';

-- 2. SYSTEM tenant 初始化（模板归属；具体列按 tenant 表实际结构补齐）
INSERT INTO tenant (code, name, type, status, created_at, updated_at)
VALUES ('SYSTEM', '平台系统', 'SYSTEM', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- 3. rule_template 身份层（同 rule_definition）
CREATE TABLE rule_template (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id   BIGINT       NOT NULL COMMENT '所属租户；SYSTEM tenant = 平台级模板',
    code        VARCHAR(128) NOT NULL,
    name        VARCHAR(256) NOT NULL,
    description VARCHAR(1024) DEFAULT NULL,
    kind        VARCHAR(32)  NOT NULL COMMENT 'RuleKind 枚举',
    status      VARCHAR(16)  NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/PUBLISHED/DISABLED',
    created_by  VARCHAR(64)  DEFAULT NULL,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by  VARCHAR(64)  DEFAULT NULL,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_tenant_code (tenant_id, code),
    KEY idx_tenant_status (tenant_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- 4. rule_template_version 快照层（不可变，同 rule_version）
CREATE TABLE rule_template_version (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    template_id   BIGINT      NOT NULL COMMENT '→ rule_template.id',
    version       INT         NOT NULL COMMENT '同一模板内单调递增',
    body_skeleton JSON        NOT NULL COMMENT '合法 body，所有值位已填默认值，无 token',
    slots         JSON        NOT NULL COMMENT 'TemplateSlot[]',
    bindings      JSON        NOT NULL COMMENT 'SlotBinding[]',
    status        VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/PUBLISHED',
    created_by    VARCHAR(64) DEFAULT NULL,
    created_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_template_version (template_id, version)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- 5. rule_template_instantiation 溯源（可删，删了核心零影响）
CREATE TABLE rule_template_instantiation (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    template_id         BIGINT   NOT NULL COMMENT '→ rule_template.id',
    template_version_id BIGINT   NOT NULL COMMENT '→ rule_template_version.id',
    template_version    INT      NOT NULL COMMENT '冗余版本号，便于查询',
    rule_definition_id  BIGINT   NOT NULL COMMENT '→ rule_definition.id',
    rule_version_id     BIGINT   NOT NULL COMMENT '→ rule_version.id',
    slot_values         JSON     NOT NULL COMMENT '实例化填值快照',
    instantiated_at     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    instantiated_by     VARCHAR(64) DEFAULT NULL,
    KEY idx_template_id (template_id),
    KEY idx_rule_version_id (rule_version_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- 注意：不含 ALTER TABLE rule_version（核心表天然无模板列，flyway clean 重建）
```
（先读 `tenant` 表建表迁移确认 INSERT 的列清单——补齐 NOT NULL 无默认值的列。COLLATE 必须 utf8mb4_unicode_ci，与既有表 join 兼容，见 memory project_migration_collation。）

- [ ] **Step 2: 验证迁移语法（起服务或 flyway clean+migrate）**

```bash
# mvn-env 设 JDK25 环境后，dev 库 flyway clean 重建
# 用打包产物起服务或 mvn flyway:clean flyway:migrate（若配了 flyway plugin）
```
预期：迁移成功，`SHOW TABLES` 有 rule_template/rule_template_version/rule_template_instantiation；`DESC rule_version` 无 template_id/template_version；`DESC tenant` 有 type；`SELECT * FROM tenant WHERE type='SYSTEM'` 有一行。

- [ ] **Step 3: Commit**

```bash
git add rule-config-svc/src/main/resources/db/migration/V1_42__rule_template.sql
git commit -m "feat(migration): V1_42 改写为模板系统 V2 schema——tenant.type/SYSTEM tenant/rule_template 身份+version 快照+instantiation 溯源,核心表零模板列"
```

---

## Task 2: TenantType 枚举 + tenant 实体/查询

**Files:**
- Create: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/domain/TenantType.java`
- Modify: tenant 实体（`find … Tenant.java`，加 `type` 字段，enum 类型）
- Test: tenant mapper/service 测（若有）

**Interfaces:**
- Produces: `enum TenantType { STANDARD, SYSTEM }`；tenant 实体 `TenantType type` 字段。

- [ ] **Step 1: 建枚举**

```java
package com.sstlfsj.rule.config.internal.domain;

/** 租户类型。SYSTEM = 平台系统租户（承载平台级模板），STANDARD = 普通业务租户。 */
public enum TenantType { STANDARD, SYSTEM }
```

- [ ] **Step 2: tenant 实体加字段**

`grep -rl "class Tenant" rule-config-svc/src/main/java` 找实体，加 `private TenantType type;`（MyBatis-Plus 默认按 name 转 varchar）。确认 `@TableName` 无需 autoResultMap（enum 非 JSON）。

- [ ] **Step 3: 验证 + Commit**

```bash
$MVN -pl rule-config-svc -am test
git add rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/domain/TenantType.java <tenant 实体>
git commit -m "feat(config): TenantType 枚举 + tenant.type 字段"
```

---

## Task 3: SlotKind / ValueDataType 枚举 + TemplateSlot/SlotConstraint 改造

**Files:**
- Create: `rule-config-svc/.../api/dto/SlotKind.java`
- Create: `rule-config-svc/.../api/dto/ValueDataType.java`
- Modify: `rule-config-svc/.../api/dto/TemplateSlot.java`
- Modify: `rule-config-svc/.../api/dto/SlotConstraint.java`
- Test: `rule-config-svc/.../api/dto/TemplateSlotTest.java`

**Interfaces:**
- Produces: `SlotKind{VALUE,METRIC_REF,DECISION_REF,RULE_REF}`；`ValueDataType{LONG,DOUBLE,DECIMAL,STRING,BOOLEAN,DATE,DATETIME,LIST}`；`TemplateSlot(key,label,kind,dataType?,required,constraint?)`；`SlotConstraint(min,max,enumValues,allowedDataTypes)`。

- [ ] **Step 1: 建两枚举**

```java
// SlotKind.java
package com.sstlfsj.rule.config.api.dto;
/** Slot 种类，决定实例化验证与前端 picker；kind 隐含解析作用域，无需 scope 字段。 */
public enum SlotKind { VALUE, METRIC_REF, DECISION_REF, RULE_REF }
```
```java
// ValueDataType.java
package com.sstlfsj.rule.config.api.dto;
/** VALUE kind 的值类型（对齐 kernel DataType，排除 UNKNOWN 哨兵）。 */
public enum ValueDataType { LONG, DOUBLE, DECIMAL, STRING, BOOLEAN, DATE, DATETIME, LIST }
```

- [ ] **Step 2: 改 TemplateSlot record**

```java
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TemplateSlot(
        String key, String label,
        SlotKind kind,
        ValueDataType dataType,      // 仅 kind=VALUE 时非空
        boolean required,
        SlotConstraint constraint
) {
    public TemplateSlot {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("slot key 不能为空");
        if (kind == null) throw new IllegalArgumentException("slot kind 不能为空");
        if (kind == SlotKind.VALUE && dataType == null)
            throw new IllegalArgumentException("VALUE slot 必须指定 dataType");
    }
}
```
（原 import `kernel.api.model.DataType` 删除。Jackson 反序列化走规范构造器——`@JsonSetter(nulls=AS_EMPTY)` 若有 primitive 字段需加,此处 boolean required 缺键会 400,加 `@JsonSetter(nulls=Nulls.AS_EMPTY)` 或确保请求总带；参照 memory feedback_jackson3_primitive_nulls。）

- [ ] **Step 3: 改 SlotConstraint 加 allowedDataTypes**

读现有 SlotConstraint，加 `List<String> allowedDataTypes`（METRIC_REF 专用，其余 null）。

- [ ] **Step 4: 测试**

`TemplateSlotTest`：VALUE 无 dataType 抛异常；METRIC_REF 无 dataType 合法；kind null 抛异常；JSON 反序列化 round-trip（真实 JSON 字符串，覆盖 primitive required 缺键）。

- [ ] **Step 5: 验证 + Commit**

```bash
$MVN -pl rule-config-svc -am test -Dtest=TemplateSlotTest -Dsurefire.failIfNoSpecifiedTests=false
git add rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/dto/SlotKind.java rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/dto/ValueDataType.java rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/dto/TemplateSlot.java rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/dto/SlotConstraint.java rule-config-svc/src/test/java/com/sstlfsj/rule/config/api/dto/TemplateSlotTest.java
git commit -m "feat(config): SlotKind/ValueDataType 枚举拆分,TemplateSlot 加 kind,SlotConstraint 加 allowedDataTypes"
```

---

## Task 4: RuleVersion 核心表清理

**Files:**
- Modify: `rule-config-svc/.../internal/domain/RuleVersion.java`
- Modify: `rule-config-svc/.../internal/publish/PublishService.java`
- Modify: 所有 `createDraft` 调用点（grep 确认）
- Test: 现有 PublishService/RuleVersion 相关测试更新

**Interfaces:**
- Produces: `RuleVersion` 无 templateId/templateVersion；`PublishService.createDraft(...)` 签名不含模板参数。

- [ ] **Step 1: grep 影响面**

```bash
grep -rn "templateId\|templateVersion\|template_id\|template_version" rule-config-svc/src rule-api/src rule-eval-svc/src --include=*.java | grep -v "rule_template\|RuleTemplate"
grep -rn "createDraft" rule-config-svc/src rule-api/src --include=*.java
```
列出所有引用点。createDraft 的模板参数消费方只有旧 RuleTemplateServiceImpl.instantiate —— Task 8 会重写它，这里先让 createDraft 干净。

- [ ] **Step 2: 删 RuleVersion 字段**

删 `RuleVersion.java` 的 `templateId`/`templateVersion` 字段 + 相关 `@TableField`。

- [ ] **Step 3: 改 PublishService.createDraft**

读当前签名，删模板参数。若 createDraft 内部把这俩写入 RuleVersion，一并删。保留其余逻辑不动。

- [ ] **Step 4: 修所有调用点 + 测试**

调用点（含旧 instantiate、测试）适配新签名。若旧 instantiate 暂时编译不过，可临时注释其模板参数传递（Task 8 重写）——或先跑 Task 5-8 再回来，但优先让 config-svc 编译通过。

- [ ] **Step 5: 验证 + Commit**

```bash
$MVN -pl rule-config-svc -am test
git add -A && git commit -m "refactor(config): RuleVersion 删 templateId/templateVersion,PublishService.createDraft 签名去模板参数——核心表零污染"
```

---

## Task 5: RuleTemplate 身份/快照实体拆分 + Mapper

**Files:**
- Modify: `rule-config-svc/.../internal/domain/RuleTemplate.java`（精简为身份）
- Create: `rule-config-svc/.../internal/domain/RuleTemplateVersion.java`
- Create: `rule-config-svc/.../internal/domain/RuleTemplateInstantiation.java`
- Create: `rule-config-svc/.../internal/domain/TemplateStatus.java`（枚举 DRAFT/PUBLISHED/DISABLED）
- Modify: `rule-config-svc/.../internal/repository/RuleTemplateMapper.java`
- Create: `RuleTemplateVersionMapper.java` / `RuleTemplateInstantiationMapper.java`
- Test: mapper `default` 方法测（若需）

**Interfaces:**
- Produces: 三实体 + 三 mapper；`RuleTemplateMapper.findVisibleByTenant(tenantId)`/`findVisibleByCode(tenantId, code)`；`RuleTemplateVersionMapper.findLatestPublished(templateId)`/`findByVersion(templateId, version)`/`findDraft(templateId)`。

- [ ] **Step 1: TemplateStatus 枚举**

```java
public enum TemplateStatus { DRAFT, PUBLISHED, DISABLED }
```

- [ ] **Step 2: RuleTemplate 精简为身份实体**

读现有 RuleTemplate，删 `bodySkeleton`/`slots`/`bindings`/`version` 字段，保留 id/tenantId/code/name/description/kind/status/审计字段。status 用 `TemplateStatus` enum。`@TableName("rule_template")`。

- [ ] **Step 3: RuleTemplateVersion 快照实体**

```java
@TableName(value = "rule_template_version", autoResultMap = true)
public class RuleTemplateVersion {
    @TableId(type = IdType.AUTO) private Long id;
    private Long templateId;
    private Integer version;
    @TableField(typeHandler = Jackson3TypeHandler.class) private RuleBody bodySkeleton;
    @TableField(typeHandler = Jackson3TypeHandler.class) private List<TemplateSlot> slots;
    @TableField(typeHandler = Jackson3TypeHandler.class) private List<SlotBinding> bindings;
    private TemplateStatus status;
    private String createdBy;
    private LocalDateTime createdAt;
    // Lombok @Getter/@Setter；参照 RuleVersion 实体的 typed JSON 列模式
}
```

- [ ] **Step 4: RuleTemplateInstantiation 实体**（typed，字段对齐 §3.3 表；slot_values 用 `Map<String,Object>` typed JSON）

- [ ] **Step 5: 三 Mapper**

`RuleTemplateMapper`：`findVisibleByTenant`/`findVisibleByCode`（`@Select` JOIN tenant.type，见 spec §5.6 完整 SQL）。
`RuleTemplateVersionMapper`：`findLatestPublished`（`@Select ... status='PUBLISHED' ORDER BY version DESC LIMIT 1`）、`findByVersion`、`findDraft`（status='DRAFT'）。
`RuleTemplateInstantiationMapper`：`BaseMapper` + `default` insert 足够。

- [ ] **Step 6: 验证 + Commit**

```bash
$MVN -pl rule-config-svc -am test
git add -A && git commit -m "feat(config): RuleTemplate 身份/快照拆分——RuleTemplate(身份)+RuleTemplateVersion(快照)+RuleTemplateInstantiation(溯源)+TemplateStatus+三 mapper(可见性/版本查询)"
```

---

## Task 6: SlotRefResolver SPI + 三实现

**Files:**
- Create: `rule-config-svc/.../api/service/SlotRefResolver.java`（SPI 接口）
- Create: `rule-config-svc/.../api/dto/SlotResolutionContext.java`
- Create: `rule-config-svc/.../internal/template/ref/MetricRefResolver.java`
- Create: `.../ref/DecisionRefResolver.java`
- Create: `.../ref/RuleRefResolver.java`
- Test: 各 resolver 单测 + SPI 分派测

**Interfaces:**
- Consumes: metric/decision/rule 的存在性查询 mapper（读现有 MetricDefinitionMapper/DecisionMapper/RuleDefinitionMapper）。
- Produces: `SlotRefResolver{ boolean supports(SlotKind); void validate(String value, TemplateSlot slot, SlotResolutionContext ctx) }`；三个 `@Component` 实现。

- [ ] **Step 1: SPI 接口 + Context**（见 spec §5.1，逐字）

- [ ] **Step 2: 三实现（只做存在性校验，Phase 1）**

- `MetricRefResolver`：supports METRIC_REF；validate 查 `metricDefinitionMapper.findActiveByTenant(ctx.tenantId())` 含该 code，不存在抛 IllegalArgumentException。（Phase 1 不做 allowedDataTypes 深度校验）
- `DecisionRefResolver`：supports DECISION_REF；查 decision code 在 `ctx.sceneCode()` 的 scene 内 ACTIVE 存在（读现有 decision 查询）。
- `RuleRefResolver`：supports RULE_REF；查 rule code 在 tenant 内有 PUBLISHED 版本。
- 各实现读现有对应 mapper 的存在性查询方法（先 grep 确认方法签名）。

- [ ] **Step 3: 测试**

各 resolver：存在→通过，不存在→抛异常；supports 只认自己的 kind。SPI 分派：`List<SlotRefResolver>` 注入后按 kind 找到唯一实现。

- [ ] **Step 4: 验证 + Commit**

```bash
$MVN -pl rule-config-svc -am test
git add -A && git commit -m "feat(config): SlotRefResolver SPI + Metric/Decision/RuleRefResolver 三实现(Phase1 存在性校验),零 switch 分派"
```

---

## Task 7: RuleTemplateService 接口调整 + 版本化 ServiceImpl 重写

**Files:**
- Modify: `rule-config-svc/.../api/service/RuleTemplateService.java`
- Modify(重写): `rule-config-svc/.../internal/service/RuleTemplateServiceImpl.java`
- Modify: `rule-config-svc/.../internal/event/RuleTemplateSnapshot.java`（随实体调整）
- Test: `RuleTemplateServiceImplTest.java`

**Interfaces:**
- Consumes: 三 mapper(Task5) + SlotRefResolver(Task6) + TemplateBinder + PublishService.createDraft(Task4)。
- Produces: service 方法——create/update(版本化)/publish/disable/list(可见性)/get/getVersion/instantiate。

- [ ] **Step 1: 调整接口签名**

- `create`：返回模板 id，内部建 rule_template(DRAFT) + rule_template_version(v1,DRAFT)
- `update`：DRAFT 版本直接更新（不 +version）；无 DRAFT 时（已 PUBLISHED）新建 v(n+1) DRAFT
- `publish`：当前 DRAFT version→PUBLISHED，rule_template.status→PUBLISHED；发布前 `binder.validate`
- `disable`：rule_template.status→DISABLED
- `list`：`findVisibleByTenant`
- `get`/`getVersion`：身份 + 指定/最新 version 快照
- `instantiate`：见 spec §5.2 流水线（DISABLED 拦截 + coercedValues 含 REF + SlotRefResolver 验证 + binder.bind + createDraft + 溯源 best-effort）

- [ ] **Step 2: 重写 ServiceImpl**

按 spec §5.2 + §5.4 状态机实现。DRAFT 唯一性：新建 DRAFT 前 `findDraft` 检查，有则复用更新。REF slot 值 pass-through 进 coercedValues。callerTenantId 贯穿。溯源 try-catch best-effort。

- [ ] **Step 3: 测试**

`RuleTemplateServiceImplTest`（Mockito mock 三 mapper + resolver + binder + publishService）：
- create → 建身份 + v1 DRAFT
- update DRAFT → 同 version 更新；update PUBLISHED → 新 v2 DRAFT
- publish → status 流转正确
- instantiate：VALUE+REF 都进 coercedValues；DISABLED 模板抛异常；REF slot 调对应 resolver；溯源写入
- 可选 slot 未填不报错

- [ ] **Step 4: 验证 + Commit**

```bash
$MVN -pl rule-config-svc -am test
git add -A && git commit -m "feat(config): RuleTemplateService 版本化重写——身份/快照分离,SlotRefResolver 接入,实例化流水线(REF pass-through/DISABLED 拦截/溯源 best-effort)"
```

---

## Task 8: Controller + 请求 DTO 调整

**Files:**
- Modify: `rule-api/.../web/admin/RuleTemplateController.java`
- Modify: `rule-api/.../web/admin/dto/CreateTemplateRequest.java` / `UpdateTemplateRequest.java` / `InstantiateRequest.java`
- Create: 新端点若需（版本历史查询 `GET /rule-templates/{code}/versions`）
- Test: `RuleTemplateControllerTest.java`

**Interfaces:**
- Consumes: RuleTemplateService(Task7)。
- Produces: HTTP 端点——create/update/publish/disable/list/get/instantiate/versions；VO 出参 enum `.name()` 转 String。

- [ ] **Step 1: 请求 DTO 调整**

Create/Update Request 的 slots 字段用新 `TemplateSlot`（含 kind）。确认 Jackson 反序列化 SlotKind/ValueDataType 正常（enum by name）。

- [ ] **Step 2: Controller 适配**

list 用可见性查询；get 返回身份 + 最新 version；加 `GET /{code}/versions` 列历史（若前端需要，见前端计划，可延后）。VO 边界 enum→String。

- [ ] **Step 3: 测试**

`RuleTemplateControllerTest`（standaloneSetup + mock service）：create/instantiate 200；slots 带 kind 正确反序列化。

- [ ] **Step 4: 验证 + Commit**

```bash
$MVN -pl rule-api -am test
git add -A && git commit -m "feat(api): RuleTemplateController 适配 V2——slots 带 kind,可见性列表,版本查询,enum 边界转 String"
```

---

## Task 9: 全量兜底 + e2e 功能验证

**Files:** 无（验证 task）

- [ ] **Step 1: 全量 clean test**

```bash
$MVN clean test
```
预期：全绿。clean 强制重编译所有 test 类，防增量漏过期 test（CLAUDE.md 测试纪律）。

- [ ] **Step 2: e2e 真实链路**（起打包产物，非 reactor run）

按 CLAUDE.md 功能测试纪律：flyway clean 重建 → 起服务 → 建 SYSTEM tenant 下模板(create)→publish→STANDARD tenant 实例化(instantiate,填 VALUE slot)→查 DB 核对：
- `rule_template`/`rule_template_version` 身份/快照分离正确
- `rule_version` 无 template 列、body 正确 bound
- `rule_template_instantiation` 溯源 template_version_id/rule_version_id 正确
- 可见性：STANDARD tenant 列表能看到 SYSTEM 模板
- DISABLED 模板拒绝实例化

- [ ] **Step 3: 清理测试数据 + Commit（若有文档/脚本产出）**

```bash
git add -A && git commit -m "test(e2e): 模板系统 V2 端到端验证——SYSTEM 模板/可见性/版本化/溯源/核心表零污染" 2>/dev/null || echo "无代码产出"
```

---

## 依赖顺序

```
Task1(迁移) ─┐
Task2(TenantType) ─┤
Task3(SlotKind/枚举) ─┼─→ Task5(实体拆分/mapper) ─→ Task7(Service 重写) ─→ Task8(Controller) ─→ Task9(全量+e2e)
Task4(核心清理) ─────┘         ↑
Task6(SlotRefResolver SPI) ────┘(Task7 消费)
```

Task1-4 相对独立可先行；Task5 依赖 Task2/3；Task6 独立（依赖 Task3 的 SlotKind）；Task7 依赖 Task4/5/6；Task8 依赖 Task7；Task9 收口。

## 自检（spec 覆盖）

- §3.1 TenantType(T2)✓ §3.2 身份/快照拆分(T5)✓ §3.3 溯源表(T1/T5)✓ §3.4 核心零污染(T4)✓
- §4 SlotKind/ValueDataType/TemplateSlot/SlotConstraint(T3)✓
- §5.1 SlotRefResolver SPI(T6)✓ §5.2 实例化流水线(T7)✓ §5.4 状态机(T7)✓ §5.6 可见性查询(T5)✓
- §8 迁移(T1)✓
- 前端(§6)→ 独立前端计划，不在本计划
- Phase 2 项(METRIC_REF 深度校验等)→ 不做
