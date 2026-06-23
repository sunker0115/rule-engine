# 规则版本管理 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 rule 历史版本可查看完整内容、与当前版本 diff、两步式回滚；并把 rule-write 三方法的长位置参数收敛成 `RuleContent` 对象。

**Architecture:** 后端只加 1 个读端点（按 versionId 取完整版本内容，view+diff 共用）；回滚复用既有 `newVersion(fromVersionId)`（前端编排）；rule-write 三方法（createDraft/editDraft/newVersion）的内容字段抽 `RuleContent` record。前端把版本时间线条目变可交互（查看/对比/恢复）。

**Tech Stack:** Java 25 / Spring Boot 4 / MyBatis-Plus / 前端 React+TS+antd+i18next。

**Spec:** `docs/superpowers/specs/2026-06-16-rule-version-management-design.md`

**环境：** 后端先 `mvn-env` skill 设环境用 `$MVN`，跨模块带 `-am`，结束 `$MVN clean test`。前端在 `frontend/` 跑 `npm run build`。测试方法名英文、注释中文。提交后 `git status --short` 确认干净。

---

## 文件结构

**后端（rule-config-svc）：**
- Create `api/dto/RuleContent.java` — 三方法共有内容字段 record
- Create `api/dto/RuleVersionContentVO.java` — 单版本完整内容 VO
- Modify `api/service/ConfigService.java` — 三方法签名收敛 + 加 getRuleVersion
- Modify `internal/service/ConfigServiceImpl.java` — 解包 RuleContent + 实现 getRuleVersion
- Modify `internal/publish/PublishService.java` — createDraft/editDraft/newVersion 接 RuleContent（内容在底层解包）

**后端（rule-api）：**
- Modify `web/admin/RuleController.java` — DTO→RuleContent 映射 + 加 GET 版本端点

**前端：**
- Modify `frontend/src/api/rule.ts` — getRuleVersion
- Modify `frontend/src/types/rule.ts` — RuleVersionContent 类型
- Modify `frontend/src/pages/rule-editor/LeftPanel.tsx` + `RuleDetailDrawer.tsx` — 版本时间线动作
- Create `frontend/src/pages/rule-editor/VersionContentDrawer.tsx` — 只读版本内容查看
- Create `frontend/src/pages/rule-editor/VersionDiffDrawer.tsx` — 与当前版本 JSON diff（复用审计 diff）

**测试影响面（RuleContent 重构必须全部更新且不回归）：** RuleControllerTest / RuleControllerScriptTest / LoadTestSeeder / PublishServicePayloadTest / PublishServiceTest / ConfigServiceImplTest / ConfigServiceTest。

---

## Task 1: RuleContent 参数对象化（既有方法重构，行为不变）

**Files:**
- Create: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/dto/RuleContent.java`
- Modify: `rule-config-svc/.../api/service/ConfigService.java`、`internal/service/ConfigServiceImpl.java`、`internal/publish/PublishService.java`
- Modify: `rule-api/.../web/admin/RuleController.java`
- Test: 上述 7 个测试文件全部改走 RuleContent

> 这是 behavior-preserving 重构。**先打开 ConfigService / ConfigServiceImpl / PublishService 的 createDraft/editDraft/newVersion 摸清现签名与委托链**（ConfigServiceImpl 委托 PublishService），再动。验收靠"所有既有 rule 写测试不回归"。

- [ ] **Step 1: 建 RuleContent record**

```java
package com.sstlfsj.rule.config.api.dto;

import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot.DecisionBinding;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot.PreGateConfig;
import com.sstlfsj.rule.kernel.api.model.ScriptSource;
import com.sstlfsj.rule.kernel.api.model.ast.AstNode;

import java.util.List;

/**
 * 规则写内容载体：createDraft/editDraft/newVersion 共有的内容字段。
 * 新增规则内容字段只改本 record + controller 的 DTO 映射，不动三方法签名与调用点。
 *
 * @param name              规则名称
 * @param kind              规则类型（AST_BOOLEAN/SCORECARD/DECISION_TREE/DECISION_TABLE/EXPRESSION_SCRIPT）
 * @param conditionAst      条件 AST
 * @param decisionBindings  决策绑定列表（草稿期 priority 占位，发布时回填）
 * @param preGates          前置门控列表
 * @param triggerEventTypes 触发事件类型列表
 * @param script            EXPRESSION_SCRIPT 脚本载体，其它 kind 传 null
 */
public record RuleContent(
        String name, String kind,
        AstNode conditionAst,
        List<DecisionBinding> decisionBindings,
        List<PreGateConfig> preGates,
        List<String> triggerEventTypes,
        ScriptSource script) {}
```

- [ ] **Step 2: 收敛 ConfigService 三方法签名**

`ConfigService.java`：三方法的内容参数换成 `RuleContent content`，保留身份/控制参数：
```java
DraftCreatedResult createDraft(String tenantId, String sceneCode, String code, RuleContent content, String actorId);
DraftCreatedResult editDraft(String tenantId, Long ruleId, RuleContent content, String actorId);
DraftCreatedResult newVersion(String tenantId, Long ruleId, RuleContent content, Long fromVersionId, String actorId);
```
更新 Javadoc（内容字段说明移到 RuleContent，方法 Javadoc 引用它）。**保留 createDraft 的 sceneCode/code、newVersion 的 fromVersionId 语义不变**（fromVersionId 非空时 content 被忽略、改用克隆值——既有行为）。

- [ ] **Step 3: PublishService 三方法接 RuleContent**

`PublishService` 的 createDraft/editDraft/newVersion 同样把内容参数换成 `RuleContent`，在方法体底层（构建 RuleVersion 实体处，如 `buildDraftVersion`）解包 `content.name()/kind()/conditionAst()/...`。**只改参数承载方式，解析/校验/落库逻辑一字不动。**

- [ ] **Step 4: ConfigServiceImpl 透传**

`ConfigServiceImpl` 三方法直接把 `RuleContent` 透传给 PublishService（不再逐字段拆，因 PublishService 也接 RuleContent 了）。

- [ ] **Step 5: RuleController 映射 DTO→RuleContent**

三端点把请求 DTO 映射成 `RuleContent`（含既有 `DecisionBindingInput→DecisionBinding(decisionCode, 0)` 占位映射，集中到一处或抽个私有 `toContent(req)`）：
```java
// 例（createDraft）：
RuleContent content = new RuleContent(req.name(), req.kind(), req.conditionAst(),
        toBindings(req.decisionBindings()), req.preGates(), req.triggerEventTypes(), req.script());
return ApiResponse.ok(configService.createDraft(req.tenantId(), req.sceneCode(), req.code(), content, actorId));
// toBindings：DecisionBindingInput → new DecisionBinding(i.decisionCode(), 0)
```
editDraft / newVersion 同款（newVersion 多传 req.fromVersionId()）。

- [ ] **Step 6: 更新 7 个测试文件的调用点**

把 RuleControllerTest / RuleControllerScriptTest / LoadTestSeeder / PublishServicePayloadTest / PublishServiceTest / ConfigServiceImplTest / ConfigServiceTest 里对三方法的调用，从长位置参数改成构造 `RuleContent` 传入。**纯机械包装，断言/场景不变。** 逐个文件 grep `createDraft(`/`editDraft(`/`newVersion(` 改。

- [ ] **Step 7: 跑全量确认行为不变**

Run: `$MVN -pl rule-config-svc -am test` + `$MVN -pl rule-api -am test`
Expected: BUILD SUCCESS，所有既有 rule 写测试绿（行为不回归是本 task 唯一验收）。

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "refactor(config): rule-write 三方法参数对象化（RuleContent），加字段不再改满地文件"
```

---

## Task 2: 查看版本内容端点（view + diff 共用）

**Files:**
- Create: `rule-config-svc/.../api/dto/RuleVersionContentVO.java`
- Modify: `rule-config-svc/.../api/service/ConfigService.java`、`internal/service/ConfigServiceImpl.java`
- Modify: `rule-api/.../web/admin/RuleController.java`
- Test: `ConfigServiceImplTest`（或新增）、`RuleControllerTest`

参考：`ConfigServiceImpl.getRuleDetail`（版本时间线 + 当前内容的构造）、`RuleVersionMapper`（`findByRuleDefId` / `selectById`）、`RuleVersion` 实体字段。

- [ ] **Step 1: 写 RuleVersionContentVO**

```java
package com.sstlfsj.rule.config.api.dto;

import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot.DecisionBinding;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot.PreGateConfig;
import com.sstlfsj.rule.kernel.api.model.ScriptSource;
import com.sstlfsj.rule.kernel.api.model.ast.AstNode;

import java.util.List;

/** 单个规则版本的完整内容（供历史版本查看 + diff，typed 内容直返）。 */
public record RuleVersionContentVO(
        Long ruleVersionId, Long version, String status, String kind,
        AstNode conditionAst, List<DecisionBinding> decisionBindings,
        List<PreGateConfig> preGates, List<String> triggerEventTypes,
        ScriptSource script,
        String createdAt, String publishedBy, String publishedAt) {}
```

- [ ] **Step 2: ConfigService 加 getRuleVersion + 写失败测试**

接口加：
```java
/** 取某规则的指定版本完整内容（历史版本查看/diff）。版本须归属该 rule + tenant，否则抛 IllegalArgumentException。 */
RuleVersionContentVO getRuleVersion(String tenantId, Long ruleId, Long versionId);
```
`ConfigServiceImplTest` 加失败测试（mock RuleVersionMapper.selectById 返回某版本 → 断言 VO 字段；版本 ruleDefinitionId 不等于 ruleId → 抛；跨租户 → 抛）。**先打开 ConfigServiceImplTest 看现有 mock 风格照写。**

- [ ] **Step 3: 跑确认失败 → 实现 getRuleVersion**

`ConfigServiceImpl`：
```java
@Override
public RuleVersionContentVO getRuleVersion(String tenantId, Long ruleId, Long versionId) {
    RuleDefinition rule = ruleDefinitionMapper.selectById(ruleId);
    if (rule == null || !tenantId.equals(String.valueOf(rule.getTenantId()))) {
        throw new IllegalArgumentException("规则不存在: id=" + ruleId);
    }
    RuleVersion v = ruleVersionMapper.selectById(versionId);
    if (v == null || !ruleId.equals(v.getRuleDefinitionId())) {
        throw new IllegalArgumentException("版本不存在或不属于该规则: versionId=" + versionId);
    }
    return new RuleVersionContentVO(
            v.getId(), v.getVersion(), v.getStatus().name(),
            v.getKind() != null ? v.getKind().name() : null,
            v.getConditionAst(), v.getDecisionBindings(), v.getPreGates(),
            v.getTriggerEventTypes(), v.getScriptSource(),
            v.getCreatedAt() != null ? v.getCreatedAt().toString() : null,
            v.getPublishedBy(), v.getPublishedAt() != null ? v.getPublishedAt().toString() : null);
}
```
> 打开 `RuleVersion` 实体核对 getter 名（getConditionAst/getDecisionBindings/getPreGates/getTriggerEventTypes/getScriptSource/getKind/getStatus/getVersion/getRuleDefinitionId/getCreatedAt/getPublishedBy/getPublishedAt），与上面对齐。

- [ ] **Step 4: RuleController 加 GET 端点 + 测试**

```java
/** GET /admin/v1/rules/{ruleId}/versions/{versionId} — 取指定版本完整内容（查看/diff）。 */
@GetMapping("/{ruleId}/versions/{versionId}")
public ApiResponse<RuleVersionContentVO> getVersion(
        @PathVariable Long ruleId, @PathVariable Long versionId, @RequestParam String tenantId) {
    return ApiResponse.ok(configService.getRuleVersion(tenantId, ruleId, versionId));
}
```
`RuleControllerTest` 加：200 + typed 内容（jsonPath `$.data.conditionAst` 等）、不存在/越权 400。**核对路径与既有 `DELETE {ruleId}/versions/{versionId}`、`POST {ruleId}/versions` 不冲突（GET vs DELETE/POST，方法区分）。**

- [ ] **Step 5: 跑通 → Commit**

Run: `$MVN -pl rule-config-svc -am test` + `$MVN -pl rule-api -am test`
```bash
git add -A
git commit -m "feat: 规则版本内容查看端点 GET /rules/{ruleId}/versions/{versionId}（view+diff 共用）"
```

---

## Task 3: 前端 api + 类型

**Files:**
- Modify: `frontend/src/api/rule.ts`、`frontend/src/types/rule.ts`

参考 `api/rule.ts` 现有方法（如 getRuleDetail）+ `api-endpoints.ts`。

- [ ] **Step 1: 加端点常量 + api 方法 + 类型**

`types/rule.ts` 加 `RuleVersionContent`（镜像后端 VO：ruleVersionId/version/status/kind/conditionAst/decisionBindings/preGates/triggerEventTypes/script/createdAt/publishedBy/publishedAt）。
`constants/api-endpoints.ts` 加 `RULE_VERSION(ruleId, versionId)` = `/admin/v1/rules/${ruleId}/versions/${versionId}`。
`api/rule.ts` 加 `getRuleVersion(ruleId, versionId, tenantId)` → `ApiResponse<RuleVersionContent>`（照现有 axios 封装）。

- [ ] **Step 2: 构建 → Commit**

Run（frontend）：`npm run build`
```bash
git add frontend/src/api/rule.ts frontend/src/types/rule.ts frontend/src/constants/api-endpoints.ts
git commit -m "feat(frontend): 规则版本内容 api + 类型"
```

---

## Task 4: 版本时间线 → 查看历史版本内容

**Files:**
- Create: `frontend/src/pages/rule-editor/VersionContentDrawer.tsx`
- Modify: `frontend/src/pages/rule-editor/LeftPanel.tsx`（版本时间线条目加"查看"动作）

参考：`LeftPanel.tsx` 版本时间线渲染、`DryRunDrawer.tsx`（Drawer 范式）、编辑器只读展示组件（条件树/脚本怎么渲染）。

- [ ] **Step 1: 写 VersionContentDrawer（只读展示一个版本内容）**

Drawer 接 `ruleId/versionId/tenantId`，打开时 `getRuleVersion` → 只读展示该版本的 kind/conditionAst/decisionBindings/preGates/script。**复用编辑器的只读渲染**（若条件树/脚本编辑器组件支持 readonly 模式则复用；否则用结构化/JSON 展示，与审计页 JSON 展示一致）。顶部显 `v{version} · {status}`。

- [ ] **Step 2: LeftPanel 版本条目加"查看"动作**

时间线每条加"查看"按钮/链接 → 打开 VersionContentDrawer 传该条目 ruleVersionId。i18n t()（rule 命名空间补 key，zh/en 对称）。

- [ ] **Step 3: 构建 → Commit**

Run（frontend）：`npm run build`（无类型错误）
```bash
git add frontend/src/pages/rule-editor/VersionContentDrawer.tsx frontend/src/pages/rule-editor/LeftPanel.tsx frontend/src/i18n/
git commit -m "feat(frontend): 版本时间线查看历史版本内容"
```

---

## Task 5: 版本 diff（与当前版本对比）

**Files:**
- Create: `frontend/src/pages/rule-editor/VersionDiffDrawer.tsx`
- Modify: `frontend/src/pages/rule-editor/LeftPanel.tsx`（条目加"与当前对比"动作）

参考：`audit-log/index.tsx` 的 before/after JSON diff 查看器（**先打开它看用了什么 diff 组件/怎么渲染**，复用同款）。

- [ ] **Step 1: 写 VersionDiffDrawer**

接历史版本 versionId + 当前版本 currentVersionId（rule detail 里有）。打开时 `getRuleVersion` 取两版内容 → 把两份内容（conditionAst/decisionBindings/preGates/triggerEventTypes/script，序列化为 JSON）喂给**复用的审计 diff 查看器**，左=历史版本、右=当前版本，高亮差异。顶部标 `v{old} ↔ v{current}`。

- [ ] **Step 2: LeftPanel 条目加"与当前对比"动作**

每条历史版本加"与当前对比"→ 打开 VersionDiffDrawer。当前版本自身条目不显该动作（无可比）。i18n t()。

- [ ] **Step 3: 构建 → Commit**

Run（frontend）：`npm run build`
```bash
git add frontend/src/pages/rule-editor/VersionDiffDrawer.tsx frontend/src/pages/rule-editor/LeftPanel.tsx frontend/src/i18n/
git commit -m "feat(frontend): 版本与当前对比 JSON diff（复用审计 diff）"
```

---

## Task 6: 回滚（恢复此版本，两步式）

**Files:**
- Modify: `frontend/src/pages/rule-editor/LeftPanel.tsx`（条目加"恢复此版本"动作 + 已有草稿拦截）

参考：`api/rule.ts` 的 `newVersion`（带 fromVersionId）、规则编辑器路由（恢复后导航进编辑器）、版本时间线的 status 字段（检测是否已有 DRAFT）。

- [ ] **Step 1: LeftPanel 条目加"恢复此版本"**

每个**非当前**历史版本条目加"恢复此版本"（Popconfirm 确认）：
- **先检查已有草稿**：若版本时间线里存在 status=DRAFT 的条目 → 禁用"恢复"动作 + Tooltip/提示"已有未发布草稿，请先发布或删除再恢复"（设计 §4.4.1）。不调接口。
- 无草稿时 → `newVersion(ruleId, { tenantId, fromVersionId: 该版本.ruleVersionId, ... })`（复用既有 newVersion api；body 按其签名，fromVersionId 非空时内容字段可空/忽略）→ 成功拿到新草稿 → `navigate` 到规则编辑器该规则（编辑器加载会显示新 DRAFT 的重解析内容供 review）→ message 提示"已基于 v{N} 创建草稿，请在编辑器中确认后发布"。
- **重解析失败**：newVersion 调用失败（旧版引用已失效）→ 拦截器透出后端错误 + 不导航（设计 §4.4.2）。

- [ ] **Step 2: 构建 → Commit**

Run（frontend）：`npm run build`
```bash
git add frontend/src/pages/rule-editor/LeftPanel.tsx frontend/src/i18n/
git commit -m "feat(frontend): 版本回滚（恢复此版本两步式 + 已有草稿拦截）"
```

---

## Task 7: 全量兜底 + 功能 e2e

**Files:** 无新增

- [ ] **Step 1: 后端全量 + 前端构建**

Run: `$MVN clean test`（全绿，含 RuleContent 重构后所有 rule 写测试不回归 + 新版本端点测试 + Modulith/kernel 纯净）
Run（frontend）：`npm run build`（通过）

- [ ] **Step 2: 功能 e2e（起真实服务，CLAUDE.md 功能纪律）**

打可执行包起服务（连本地 MySQL）：建规则→发布 v1→改内容发布 v2→`GET /rules/{id}/versions/{v1Id}` 查 v1 完整内容（真取历史快照、typed 内容齐）→前端 v1 与当前 diff→"恢复 v1"（newVersion fromVersionId 克隆草稿→编辑器→发布 v3，验 v3 内容==v1）→验"已有草稿时恢复被拦"。逐步查持久层真落库。清理测试数据，停服务。

- [ ] **Step 3: 收尾**

确认无脏数据；前端"未视觉验证"项在收尾说明列清。

---

## Self-Review 记录

- **Spec 覆盖**：§3.1 查看端点(Task2)、§3.2 RuleContent 重构(Task1)、§3.3 回滚复用(Task6)、§4.1-4.3 前端交互(Task4/5/6)、§4.4 约束(Task6 已有草稿拦截 + 重解析失败透出)、§5 测试(各 task + Task7 e2e)。§6 非目标不实现。
- **类型一致**：`RuleContent(name,kind,conditionAst,decisionBindings,preGates,triggerEventTypes,script)`、`RuleVersionContentVO(...)`、`ConfigService.getRuleVersion(tenantId,ruleId,versionId)`、`getRuleVersion(ruleId,versionId,tenantId)`(前端)、`RULE_VERSION(ruleId,versionId)` 全计划一致。
- **占位符**：RuleVersion getter 名、audit diff 组件、编辑器只读渲染、PublishService 解包点——均指向具体 exemplar 要求执行者打开核对。
- **风险**：Task1 重构触 7 测试文件（机械包装，行为不变靠测试守）；前端无法视觉验证（标注）；回滚重解析失败路径依赖后端 newVersion 既有行为。
