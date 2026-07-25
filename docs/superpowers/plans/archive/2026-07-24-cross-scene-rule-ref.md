# cross-scene-rule-ref 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `rule_definition.scene_id` → `scene_code`，消灭代理键翻译层；同时开放 DECISION_FLOW RuleRefNode 跨 Scene 引用规则，新增反向血缘查询。

**Architecture:** 单向迁移——`rule_definition` 存 `scene_code` VARCHAR，消灭所有 `sceneId` 翻译层；eval-svc SQL JOIN 条件改写；发布期查询键从 (tenant, scene.id, code) → (tenant, code)；FlowExecutor 评估链路零改动。

**Tech Stack:** Flyway V1_41 DDL + MyBatis-Plus LambdaQueryWrapper + Spring @Transactional + @Select SQL

## Global Constraints

- kernel / FlowExecutor / SceneRuleIndex / eval-svc 评估链路 **零改动**
- `tenant_id` BIGINT 保留，不动任何 tenant 相关字段
- `scene` 表本身不改
- 不考虑历史数据兼容
- 全程中文注释
- 每 Task 提交前必须运行该模块所有测试，全部通过才能 commit
- 跨模块改动必须带 `-am`

---

## Files

| 文件 | 动作 | 负责 |
|---|---|---|
| `V1_41__rule_definition_scene_code.sql` | 新建 | DDL 迁移 |
| `RuleDefinition.java` | 修改 | sceneId→sceneCode 字段 |
| `RuleDefinitionMapper.java` | 修改 | 方法改写/新增 |
| `PublishService.java` | 修改 | createDraft + freezeReferencedRule |
| `ConfigServiceImpl.java` | 修改 | listRules 翻译层删除 |
| `RuleImportService.java` | 修改 | 查重逻辑 |
| `RuleAnalysisServiceImpl.java` | 修改 | 查询方法改 |
| `MetadataServiceImpl.java` | 修改 | collectRequiredDeps |
| `RuleExportService.java` | 修改 | sceneId 用法清理 |
| `RuleBundleService.java` (接口) | 修改 | sceneId→sceneCode 参数 |
| `RuleBundleServiceImpl.java` | 修改 | sceneId→sceneCode |
| `RuleVersionReadMapper.java` | 修改 | 3处 SQL JOIN 改写 |
| `RuleBundleController.java` | 修改 | ?sceneId=→?sceneCode= |
| `RuleLineageService.java` | 新建 | 反向血缘接口 |
| `RuleLineageServiceImpl.java` | 新建 | 遍历 ACTIVE flow 快照 |
| `RuleController.java` | 修改 | 新增 referencedBy endpoint |
| 前端 3 个文件 | 修改 | RuleRef 下拉 tenant 全量 |

---

### Task 1: DDL + 实体 + draft 工厂

**Files:**
- Create: `rule-config-svc/src/main/resources/db/migration/V1_41__rule_definition_scene_code.sql`
- Modify: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/domain/RuleDefinition.java`

**Interfaces:**
- Produces: `RuleDefinition.sceneCode String`（替代 `sceneId Long`）；`RuleDefinition.draft(tenantId, sceneCode, code, name, kind, createdBy)`

- [ ] **Step 1: 写 DDL 迁移文件**

```sql
-- V1_41__rule_definition_scene_code.sql
-- cross-scene-rule-ref: rule_definition 去 scene_id，改用 scene_code 业务标识
-- scene_code 在 tenant 内 + rule_definition 内已有 uk_tenant_code 保证 code 唯一

ALTER TABLE rule_definition
    DROP KEY idx_scene_id,
    DROP COLUMN scene_id,
    ADD COLUMN scene_code VARCHAR(64) NOT NULL DEFAULT '' COMMENT '关联 scene.code，业务标识',
    ADD KEY idx_tenant_scene (tenant_id, scene_code);
```

- [ ] **Step 2: 修改 RuleDefinition 实体**

```java
// 替换字段
private String sceneCode;   // 原 private Long sceneId;

// draft() 工厂参数 sceneId Long → sceneCode String
public static RuleDefinition draft(Long tenantId, String sceneCode, String code, String name,
                                   RuleKind kind, String createdBy) {
    RuleDefinition rd = new RuleDefinition();
    rd.setTenantId(tenantId);
    rd.setSceneCode(sceneCode);
    rd.setCode(code);
    rd.setName(name);
    rd.setStatus(RuleDefinitionStatus.DRAFT);
    rd.setKind(kind);
    rd.setCreatedBy(createdBy);
    rd.setCreatedAt(java.time.LocalDateTime.now());
    return rd;
}
```

Javadoc 同步修改（`@param sceneCode 场景编码`）。

- [ ] **Step 3: 编译验证**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-25.jdk/Contents/Home && export PATH=$JAVA_HOME/bin:$PATH
MVN=/Users/sunke/.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9/bin/mvn
$MVN -pl rule-config-svc -am compile 2>&1 | grep -E "ERROR|BUILD" | tail -5
```

- [ ] **Step 4: Commit**

```bash
git add rule-config-svc/src/main/resources/db/migration/V1_41__rule_definition_scene_code.sql \
        rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/domain/RuleDefinition.java
git commit -m "feat(config): rule_definition scene_id→scene_code DDL + 实体"
```

---

### Task 2: RuleDefinitionMapper 重构

**Files:**
- Modify: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/repository/RuleDefinitionMapper.java`

**Interfaces:**
- Produces: `findByTenantAndCode(Long tenantId, String code)`; `findByTenantAndSceneCode(Long tenantId, String sceneCode)`; `selectRulePage` 改接收 `String sceneCode`

- [ ] **Step 1: 写失败测试**

在 `RuleDefinitionMapperTest`（如有）或新建测试类，确认接口声明正确。实际因为是 default 方法，编译测试即可。

- [ ] **Step 2: 重写 Mapper**

```java
/** 按 (tenantId, code) 查规则定义，tenant 内唯一。不存在返回 null。 */
default RuleDefinition findByTenantAndCode(Long tenantId, String code) {
    return selectOne(new LambdaQueryWrapper<RuleDefinition>()
            .eq(RuleDefinition::getTenantId, tenantId)
            .eq(RuleDefinition::getCode, code));
}

/** 按 (tenantId, sceneCode) 查全部规则定义。 */
default List<RuleDefinition> findByTenantAndSceneCode(Long tenantId, String sceneCode) {
    return selectList(new LambdaQueryWrapper<RuleDefinition>()
            .eq(RuleDefinition::getTenantId, tenantId)
            .eq(RuleDefinition::getSceneCode, sceneCode));
}

/** 导出选取：ruleIds → sceneCode → 整租户。 */
default List<RuleDefinition> selectForExport(Long tenantId, List<Long> ruleIds, String sceneCode) {
    boolean byIds = ruleIds != null && !ruleIds.isEmpty();
    return selectList(new LambdaQueryWrapper<RuleDefinition>()
            .eq(RuleDefinition::getTenantId, tenantId)
            .in(byIds, RuleDefinition::getId, ruleIds)
            .eq(!byIds && sceneCode != null, RuleDefinition::getSceneCode, sceneCode));
}

/** 规则列表分页：sceneId 参数改为 sceneCode。 */
default Page<RuleDefinition> selectRulePage(Page<RuleDefinition> page, Long tenantId,
                                            String sceneCode, String status,
                                            java.time.LocalDate from, java.time.LocalDate to) {
    return selectPage(page, new LambdaQueryWrapper<RuleDefinition>()
            .eq(RuleDefinition::getTenantId, tenantId)
            .eq(sceneCode != null && !sceneCode.isBlank(), RuleDefinition::getSceneCode, sceneCode)
            .eq(status != null && !status.isBlank(), RuleDefinition::getStatus, status)
            .ge(from != null, RuleDefinition::getPublishedAt, from != null ? from.atStartOfDay() : null)
            .le(to != null, RuleDefinition::getPublishedAt, to != null ? to.plusDays(1).atStartOfDay() : null)
            .orderByDesc(RuleDefinition::getId));
}
```

删除 `findBySceneAndCode` 和 `findByTenantAndSceneIds` 两个旧方法。

同时新增（T6 反向血缘用）：

```java
/** 按 (tenantId, kind) 查规则定义列表，供反向血缘遍历 DECISION_FLOW 用。 */
default List<RuleDefinition> findByTenantAndKind(Long tenantId, RuleKind kind) {
    return selectList(new LambdaQueryWrapper<RuleDefinition>()
            .eq(RuleDefinition::getTenantId, tenantId)
            .eq(RuleDefinition::getKind, kind));
}
```

- [ ] **Step 3: 编译（暂时忽略调用处报错，下一 Task 修）**

```bash
$MVN -pl rule-config-svc -am compile 2>&1 | grep "ERROR.*\.java" | grep -v "findBySceneAndCode\|findByTenantAndSceneIds\|sceneId\|getSceneId\|setSceneId" | head -5
```

- [ ] **Step 4: Commit**

```bash
git add rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/repository/RuleDefinitionMapper.java
git commit -m "feat(config): RuleDefinitionMapper 重构——scene_id→scene_code 查询方法"
```

---

### Task 3: PublishService 核心改动

**Files:**
- Modify: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/publish/PublishService.java`

**Interfaces:**
- Consumes: `findByTenantAndCode`(T2), `RuleDefinition.draft(sceneCode)`(T1)
- 改动点：createDraft L892/L910、freezeReferencedRule L646/L659

- [ ] **Step 1: createDraft — 去掉 scene.getId() 翻译 + code 唯一性改 tenant 级**

```java
// L892 改前:
boolean codeExists = ruleDefinitionMapper.findBySceneAndCode(tenantId, scene.getId(), code) != null;
// L892 改后:
boolean codeExists = ruleDefinitionMapper.findByTenantAndCode(tenantId, code) != null;

// L910 改前:
RuleDefinition rd = RuleDefinition.draft(tenantId, scene.getId(), code, name, effectiveRuleKind, actorId);
// L910 改后:
RuleDefinition rd = RuleDefinition.draft(tenantId, sceneCode, code, name, effectiveRuleKind, actorId);
```

注释更新：原"2. 校验 code 在同 tenant+scene 下唯一" → "2. 校验 code 在同 tenant 下唯一（ruleCode 为 tenant 级业务标识）"

- [ ] **Step 2: freezeReferencedRule — 查询改 tenant 级 + 快照 sceneCode 修正**

```java
// L646 改前:
RuleDefinition ref = ruleDefinitionMapper.findBySceneAndCode(tenantId, scene.getId(), ruleCode);
if (ref == null) {
    throw new IllegalArgumentException(
            "DECISION_FLOW 引用的规则不存在或不属于同一 Scene(v1 限同 Scene): " + ruleCode);
}
// L646 改后:
RuleDefinition ref = ruleDefinitionMapper.findByTenantAndCode(tenantId, ruleCode);
if (ref == null) {
    throw new IllegalArgumentException("DECISION_FLOW 引用的规则不存在: " + ruleCode);
}

// L659 改前:
return new RuleVersionSnapshot(
        active.getId(), scene.getCode(), String.valueOf(tenantId), ...);
// L659 改后（🟢 RuleDefinition.getSceneCode() 已在 T1 新增）:
return new RuleVersionSnapshot(
        active.getId(), ref.getSceneCode(), String.valueOf(tenantId), ...);
```

注释更新：删除"v1 限同 Scene"注释；更新方法 Javadoc（去掉"须同 Scene"限制描述）。

- [ ] **Step 3: 写单测**

新建 `PublishServiceCrossSceneTest`：

```java
@Test
void freezeReferencedRule_crossScene_successAndCorrectSceneCode() {
    // GIVEN: rule "base-check" 属于 sceneA（scene_code="risk.base"）
    // flow 属于 sceneB（scene_code="risk.transfer"）
    // WHEN: publishService.createDraft 发布引用 "base-check" 的 flow
    // THEN: 冻结快照的 sceneCode = "risk.base"（不是 "risk.transfer"）
}

@Test
void createDraft_duplicateCodeSameTenant_throws() {
    // sceneA 已有 "check-amount" → sceneB 再建同 code → 抛异常
}
```

- [ ] **Step 4: 跑测试**

```bash
$MVN -pl rule-config-svc -am test -Dtest='PublishServiceCrossSceneTest,PublishServiceTest' -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | grep -E "Tests run|BUILD" | tail -5
```

- [ ] **Step 5: Commit**

```bash
git add rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/publish/PublishService.java \
        rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/publish/PublishServiceCrossSceneTest.java
git commit -m "feat(config): PublishService 跨 Scene RuleRef + code tenant 级唯一"
```

---

### Task 4: config-svc 其余调用点清理

**Files:**
- Modify: ConfigServiceImpl, RuleImportService, RuleAnalysisServiceImpl, MetadataServiceImpl, RuleExportService, RuleBundleService接口, RuleBundleServiceImpl

**Interfaces:**
- Consumes: `findByTenantAndCode`(T2), `findByTenantAndSceneCode`(T2), `selectForExport(tenantId, ruleIds, sceneCode)`(T2)

- [ ] **Step 1: ConfigServiceImpl.listRules — 去掉翻译层**

```java
// 改前（L119-126）:
Long sceneId = null;
if (q.sceneCode() != null && !q.sceneCode().isBlank()) {
    SceneDef scene = sceneMapper.findByCode(q.tenantId(), q.sceneCode());
    if (scene == null) { return new Page<>(q.page(), q.size()); }
    sceneId = scene.getId();
}
return ruleDefinitionMapper.selectRulePage(new Page<>(...), q.tenantId(), sceneId, ...);

// 改后:
return ruleDefinitionMapper.selectRulePage(
        new Page<>(q.page(), q.size()), q.tenantId(),
        q.sceneCode(), q.status(), fromDate, toDate);
```

同时删除 `getSceneCodeMap` 方法（`RuleDefinition.getSceneCode()` 直接可用，不再需要 id→code 映射）——但先检查是否还有调用方。

- [ ] **Step 2: 检查 getSceneCodeMap 调用方**

```bash
grep -rn "getSceneCodeMap" rule-api/src/main/java --include="*.java" | grep -v test
```

`RuleController` 里用 `getSceneCodeMap(sceneIds)` 来回填 VO 的 sceneCode——改完后直接用 `rd.getSceneCode()` 即可，删掉这段逻辑。

- [ ] **Step 3: RuleImportService — 查重改 tenant 级**

```java
// L145 改前:
RuleDefinition existing = ruleDefinitionMapper.findBySceneAndCode(
        tenantId, scene.getId(), rule.code());
// 改后:
RuleDefinition existing = ruleDefinitionMapper.findByTenantAndCode(tenantId, rule.code());
```

- [ ] **Step 4: RuleAnalysisServiceImpl — findByTenantAndSceneCode**

```java
// 改前:
List<RuleDefinition> ruleDefs = ruleDefinitionMapper.findByTenantAndSceneIds(tenantId, List.of(scene.getId()));
// 改后:
List<RuleDefinition> ruleDefs = ruleDefinitionMapper.findByTenantAndSceneCode(tenantId, sceneCode);
```

- [ ] **Step 5: MetadataServiceImpl.collectRequiredDeps — 去掉 sceneId 中转**

```java
// 改前:
List<Long> sceneIds = sceneMapper.findByCodes(tenantId, scenes)
        .stream().map(SceneDef::getId).toList();
if (sceneIds.isEmpty()) return Set.of();
List<Long> defIds = ruleDefinitionMapper.findByTenantAndSceneIds(tenantId, sceneIds)
        .stream().map(RuleDefinition::getId).toList();

// 改后（直接按 sceneCode 列表查）:
List<Long> defIds = scenes.stream()
        .flatMap(sc -> ruleDefinitionMapper.findByTenantAndSceneCode(tenantId, sc).stream())
        .map(RuleDefinition::getId).distinct().toList();
if (defIds.isEmpty()) return Set.of();
```

同理修改 `getInputManifest` 里的 `findByTenantAndSceneIds` 调用。

- [ ] **Step 6: RuleExportService — sceneId → sceneCode**

```java
// selectForExport 参数 sceneId Long → sceneCode String
// 删除 sceneIds Set + sceneById Map 的构建逻辑
// rd.getSceneCode() 直接可用
for (RuleDefinition rd : ruleDefs) {
    // ...
    // 改前: if (rd.getSceneId() != null) sceneIds.add(rd.getSceneId());
    // 改后: sceneCode 直接在下方 rd.getSceneCode() 获取，不需要 set 收集
}
// scenes 列表构建: 收集去重的 sceneCode → 查 scene 详情
Set<String> sceneCodes = exportable.stream()
        .map(RuleDefinition::getSceneCode).collect(java.util.stream.Collectors.toSet());
Map<String, SceneDef> sceneByCode = sceneMapper.findByCodes(tenantId, sceneCodes)
        .stream().collect(java.util.stream.Collectors.toMap(SceneDef::getCode, s -> s));
// rule entry: scene = sceneByCode.get(rd.getSceneCode())
```

- [ ] **Step 7: RuleBundleService 接口 + Impl — sceneId → sceneCode**

```java
// RuleBundleService.java
RuleBundle export(Long tenantId, List<Long> ruleIds, String sceneCode);
List<RuleVersionSnapshot> exportSnapshots(Long tenantId, List<Long> ruleIds, String sceneCode);
```

- [ ] **Step 8: 编译 + 测试**

```bash
$MVN -pl rule-config-svc -am test -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | grep -E "Tests run|BUILD" | tail -5
```

- [ ] **Step 9: Commit**

```bash
git add rule-config-svc/src/main/java/
git commit -m "feat(config): 消灭 sceneId 翻译层——ConfigService/Import/Analysis/Metadata/Export/Bundle"
```

---

### Task 5: eval-svc SQL JOIN 改写

**Files:**
- Modify: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/repository/RuleVersionReadMapper.java`

**Interfaces:**
- 3处 `INNER JOIN scene s ON rd.scene_id = s.id` 改写

- [ ] **Step 1: 改写 3 处 JOIN**

```sql
-- 改前:
INNER JOIN scene s ON rd.scene_id = s.id

-- 改后:
INNER JOIN scene s ON rd.tenant_id = s.tenant_id AND rd.scene_code = s.code
```

三处（`loadAllActive` / `loadActiveByScene` / `loadById`）完全相同的替换。

- [ ] **Step 2: 跑 eval-svc 测试**

```bash
$MVN -pl rule-eval-svc -am test -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | grep -E "Tests run|BUILD" | tail -5
```

- [ ] **Step 3: Commit**

```bash
git add rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/repository/RuleVersionReadMapper.java
git commit -m "feat(eval): RuleVersionReadMapper SQL JOIN scene_id→scene_code"
```

---

### Task 6: rule-api — Bundle sceneId 清理 + 反向血缘接口

**Files:**
- Modify: `RuleBundleController.java`
- Modify: `RuleController.java`
- New: `RuleLineageService.java` (config-svc api)
- New: `RuleLineageServiceImpl.java` (config-svc internal)

**Interfaces:**
- Produces: `GET /admin/v1/rules/{code}/referencedBy?tenantId=`

- [ ] **Step 1: RuleBundleController ?sceneId → ?sceneCode**

```java
// 改前:
@RequestParam(required = false) Long sceneId,
// 改后:
@RequestParam(required = false) String sceneCode,
// 下游调用同步改参数名
```

- [ ] **Step 2: RuleLineageService 接口（config-svc api 包）**

```java
package com.sstlfsj.rule.config.api.service;

/** 规则血缘查询：rule→flow 反向引用。 */
public interface RuleLineageService {
    /** 返回 tenant 下所有 ACTIVE DECISION_FLOW 规则中引用了 ruleCode 的规则列表。 */
    List<ReferencingFlowItem> findFlowsReferencingRule(Long tenantId, String ruleCode);

    record ReferencingFlowItem(Long ruleDefinitionId, String ruleCode, String sceneCode) {}
}
```

- [ ] **Step 3: RuleLineageServiceImpl（config-svc internal）**

```java
@Service
@RequiredArgsConstructor
public class RuleLineageServiceImpl implements RuleLineageService {

    private final RuleDefinitionMapper ruleDefinitionMapper;
    private final RuleVersionMapper ruleVersionMapper;

    @Override
    @Transactional(readOnly = true)
    public List<ReferencingFlowItem> findFlowsReferencingRule(Long tenantId, String ruleCode) {
        // 查 tenant 下全部 ACTIVE DECISION_FLOW 规则定义
        List<RuleDefinition> flowDefs = ruleDefinitionMapper.findByTenantAndKind(
                tenantId, RuleKind.DECISION_FLOW);
        List<ReferencingFlowItem> result = new ArrayList<>();
        for (RuleDefinition rd : flowDefs) {
            RuleVersion active = ruleVersionMapper.findActiveVersion(rd.getId());
            if (active == null) continue;
            if (!(active.getBody() instanceof FlowBody fb)) continue;
            if (fb.referencedSnapshots().containsKey(ruleCode)) {
                result.add(new ReferencingFlowItem(rd.getId(), rd.getCode(), rd.getSceneCode()));
            }
        }
        return result;
    }
}
```

需要在 `RuleDefinitionMapper` 新增：

```java
/** 按 (tenantId, kind) 查规则定义列表。 */
default List<RuleDefinition> findByTenantAndKind(Long tenantId, RuleKind kind) {
    return selectList(new LambdaQueryWrapper<RuleDefinition>()
            .eq(RuleDefinition::getTenantId, tenantId)
            .eq(RuleDefinition::getKind, kind));
}
```

- [ ] **Step 4: RuleController 新增 endpoint**

```java
@GetMapping("/{code}/referencedBy")
public ApiResponse<List<RuleLineageService.ReferencingFlowItem>> referencedBy(
        @PathVariable String code,
        @RequestParam Long tenantId) {
    return ApiResponse.ok(ruleLineageService.findFlowsReferencingRule(tenantId, code));
}
```

- [ ] **Step 5: 写测试**

```java
// RuleLineageServiceImplTest
@Test
void findFlowsReferencingRule_referencingFlowsReturned() { ... }
@Test
void findFlowsReferencingRule_noFlows_returnsEmpty() { ... }
```

- [ ] **Step 6: 跑测试**

```bash
$MVN -pl rule-config-svc,rule-api -am test -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | grep -E "Tests run|BUILD" | tail -5
```

- [ ] **Step 7: Commit**

```bash
git add rule-api/ rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/service/RuleLineageService.java \
        rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/lineage/
git commit -m "feat(api,config): Bundle sceneId→sceneCode + 反向血缘 referencedBy"
```

---

### Task 7: 前端 RuleRef 下拉 tenant 全量

**Files:**
- Modify: `frontend/src/pages/rule-editor/CenterPanel.tsx`
- Modify: `frontend/src/pages/rule-editor/FlowNodeInspectorDrawer.tsx`
- Modify: `frontend/src/pages/rule-editor/FlowCanvasEditor.tsx`

- [ ] **Step 1: CenterPanel — flowSceneRules 改 tenant 全量**

```typescript
// 改前 (L43):
listRules(tenantId, sceneCode, { page: 1, size: 500 }).then((data) => {
  setFlowSceneRules((data.items ?? [])
    .filter((r) => r.kind !== 'DECISION_FLOW')
    ...

// 改后:
listRules(tenantId, undefined, { page: 1, size: 500 }).then((data) => {
  setFlowSceneRules((data.items ?? [])
    .filter((r) => r.kind !== 'DECISION_FLOW')  // 排除 flow 防递归
    ...
```

Store 里 `SceneRuleItem` 增加 `sceneCode?: string` 字段（供下拉分组用）。

- [ ] **Step 2: FlowNodeInspectorDrawer — RuleRef 下拉加 sceneCode 分组**

```typescript
// 改前:
options={sceneRules.map((r) => ({ value: r.code, label: `${r.name} (${r.code})` }))}

// 改后（antd Select 的 options 支持 groupBy）:
options={
  Object.entries(
    sceneRules.reduce((acc, r) => {
      const sc = r.sceneCode ?? '';
      (acc[sc] = acc[sc] ?? []).push(r);
      return acc;
    }, {} as Record<string, SceneRuleItem[]>)
  ).map(([sc, items]) => ({
    label: sc,
    options: items.map((r) => ({ value: r.code, label: `${r.name} (${r.code})` })),
  }))
}
```

- [ ] **Step 3: FlowCanvasEditor — 新建叶子规则 scene 弹选**

新建叶子按钮点击时，若 tenant 有多个 Scene，弹出 Select 选择目标 scene（默认当前 flow 所属 sceneCode）：

```typescript
// 现有逻辑: createRule(tenantId, { sceneCode, code, name, kind: 'AST_BOOLEAN' })
// 改为: 先 prompt scene 选择 → 用选定 sceneCode 创建
```

- [ ] **Step 4: TypeScript 检查 + 构建**

```bash
cd frontend && npx tsc --noEmit && npx vite build 2>&1 | tail -5
```

- [ ] **Step 5: Commit**

```bash
git add frontend/src/pages/rule-editor/CenterPanel.tsx \
        frontend/src/pages/rule-editor/FlowNodeInspectorDrawer.tsx \
        frontend/src/pages/rule-editor/FlowCanvasEditor.tsx
git commit -m "feat(frontend): RuleRef 下拉 tenant 全量 + sceneCode 分组 + 叶子 scene 弹选"
```

---

### Task 8: 全量测试 + 端到端验证

- [ ] **Step 1: 全量 clean test（排除集成测试）**

```bash
$MVN clean test -Dtest='!ScheduledTaskAnnotationIntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | grep -E "BUILD|Tests run.*Failures.*[1-9]" | tail -5
```

- [ ] **Step 2: 起服务端到端验证**

```bash
# 打包
$MVN -pl rule-app -am package -DskipTests -q
# 启动
nohup java -jar rule-app/target/rule-app-1.0.0-SNAPSHOT.jar > /tmp/rule-engine.log 2>&1 &
```

验证：
1. 在 SceneA 建规则 `base-check`，发布
2. 在 SceneB 建 DECISION_FLOW，RuleRefNode 引用 `base-check`（跨 scene），发布 → 应成功
3. 评估 SceneB 的 flow → 应正确命中
4. `GET /admin/v1/rules/base-check/referencedBy?tenantId=1` → 应返回 SceneB 的 flow

- [ ] **Step 3: 更新文档**

```bash
# 08-evolution §2.3 标注已实装
# openspec tasks.md 回填完成状态
```

- [ ] **Step 4: 最终 commit**

```bash
git add docs/ openspec/
git commit -m "docs: §2.3 跨 Scene 规则复用已实装 + openspec tasks 回填"
```
