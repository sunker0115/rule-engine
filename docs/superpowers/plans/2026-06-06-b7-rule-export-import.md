# B7 规则导出 / 导入 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为规则提供跨环境 / 跨租户迁移的自包含 JSON Bundle **批量**导出 + 幂等导入（导入落为 DRAFT），全程 HTTP 端点，无 DDL、无核心引擎改动。

**Architecture:** 导出端 `RuleExportService` 按条件（ruleId 列表 / sceneId / 整租户）查出一批规则，对每条取当前 ACTIVE rule_version，组装为**多规则** Bundle（`rules[]` + 跨规则去重的 `scenes[]` / `metricDefinitions[]` / `decisionDefinitions[]` / `actionTypeManifest[]`）。导入端 `RuleImportService` 在单事务内先整体 upsert 依赖（Scene / metric / decision 缺失则建、已存在跳过），再逐条把规则落为 DRAFT rule_version；规则已存在则追加 DRAFT 版本，不覆盖已发布版本。两者经 `RuleBundleService` 接口由 `RuleBundleController` 暴露 HTTP——**导出做成 Bundle JSON 文件下载（`Content-Disposition: attachment`），导入做成 multipart 文件上传**；Service 仍进出 `RuleBundle` 对象，Controller 负责对象 ↔ 文件转换。所有 JSON 列（conditionAst / decisionBindings / preGates / triggerEventTypes / metricDependencies / payloadSchema / eventTypes / defaultParams / actions）按**原始 JSON 字符串**无损搬运。

**Tech Stack:** Java 25 / Spring Boot 4 / Spring Modulith / MyBatis-Plus / Jackson 3（`tools.jackson`）/ JUnit5 + Mockito + AssertJ / Testcontainers MySQL + Flyway。包根 `com.sstlfsj.rule`。

**关键设计决策（已与用户确认，2026-06-06）：**
1. **权限**：沿用 `X-Actor-Id` header，不引入新权限框架；§2.9 的 EXPORT / PUBLISH 权限校验留 TODO（后续 B16 合规批次）。
2. **Bundle 含 `decisionDefinitions[]`**：扩展 §2.9 原始字段集，使 `rule_version.decisionBindings` 引用的 tenant 级 decision 行随包搬运（D27 后 Action 落在 decision_definition）。
3. **metric 导入**：按 `(tenantId, metricCode)` upsert——缺失则建（version 取包内值，默认 1，status=ACTIVE）；已存在跳过不覆盖；`SQL_AGGREGATE` 类型若缺失**不自动创建**，列入 `metricsRequiringReview`（发布期 PublishService 的"被引用 metric 无 ACTIVE 版本"校验是安全网）。
4. **载体**：HTTP 端点（`rule-api` controller + `rule-config-svc` service），复用现有分层。**导出 = Bundle JSON 文件下载，导入 = multipart 文件上传**（Service 仍进出 `RuleBundle` 对象，转换在 Controller）。
5. **批量 + 统一多规则结构**：Bundle 始终为 `rules[]` / `scenes[]` 多规则形态（单规则 = `rules` 长度 1 的特例）；导出端点 `GET /api/v1/rules/export` 用查询条件选取规则（ruleIds / sceneId / 整租户）。不另设单规则端点。

**范围边界（v1）：**
- 导出选取：`ruleIds` 列表（精确）→ 否则 `sceneId`（整场景）→ 否则该租户全部；对每条只导**当前 ACTIVE 版本**，无 ACTIVE 版本的规则跳过；最终 `rules` 为空则报错。**导出入参用 `sceneId`（前端列表已有，省一次 scene 表解析）；Bundle 内 `RuleEntry.sceneCode` 仍用 code（跨环境 id 不同，按 code 关联）。**
- 导入幂等：规则 code 已存在 → 追加一条 DRAFT rule_version（不动 rule_definition 状态 / currentVersion）；不存在 → 新建 rule_definition(DRAFT) + rule_version(DRAFT v1)。把已发布规则的导入草稿提升为 ACTIVE 需 D19 回滚流程（尚未实现），不在 B7 范围。
- 无 DDL：复用 scene / metric_definition / decision_definition / rule_definition / rule_version / audit_log 既有表。
- 无分页：开发阶段无生产数据，整租户导出不分页（YAGNI）；量大时另行演进。

---

## File Structure

| 文件 | 模块 | 职责 |
|------|------|------|
| `config/api/dto/RuleBundle.java` | rule-config-svc | Bundle 顶层（`rules[]`/`scenes[]`/依赖去重）+ 4 个嵌套 record（RuleEntry / SceneSnapshot / MetricEntry / DecisionEntry）。public，供 rule-api 序列化 |
| `config/api/dto/RuleImportResult.java` | rule-config-svc | 导入结果 record：`rules[]`（逐条 ImportedRule）+ Bundle 级依赖处置清单 |
| `config/api/service/RuleBundleService.java` | rule-config-svc | 接口：`export(tenantId, ruleIds, sceneId)` + `importBundle` |
| `config/internal/bundle/RuleExportService.java` | rule-config-svc | 按条件查多规则、组装多规则 Bundle（@Service） |
| `config/internal/bundle/RuleImportService.java` | rule-config-svc | 事务内整体 upsert 依赖 + 逐条落 DRAFT 版本（@Service） |
| `config/internal/service/RuleBundleServiceImpl.java` | rule-config-svc | 实现接口，委托 export/import 两个内部 @Service |
| `web/config/RuleBundleController.java` | rule-api | `GET .../export`（Bundle JSON 文件下载）、`POST .../import`（multipart 文件上传）端点；注入全局 `ObjectMapper` 做对象↔文件转换 |

测试镜像：`RuleBundleTest`（DTO round-trip）/ `RuleExportServiceTest` / `RuleImportServiceTest`（mock mapper）/ `RuleBundleControllerTest`（MockMvc）/ `RuleBundleIntegrationTest`（Testcontainers 端到端）。

**Bean 注册**：`ConfigAutoConfiguration` 已 `@ComponentScan("com.sstlfsj.rule.config.internal")`，新 `@Service` 自动扫描，无需显式注册。

**Jackson 注意**：全项目用 `tools.jackson.*`（Jackson 3），不是 `com.fasterxml.jackson`。所有 import 与测试 `JsonMapper.builder().build()` 沿用现有写法。

**运行测试前置**：先用 `mvn-env` skill 设环境拿到 `$MVN`，再执行模块测试。

---

### Task 1: Bundle DTO + 导入结果 + Service 接口

**Files:**
- Create: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/dto/RuleBundle.java`
- Create: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/dto/RuleImportResult.java`
- Create: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/service/RuleBundleService.java`
- Test: `rule-config-svc/src/test/java/com/sstlfsj/rule/config/api/dto/RuleBundleTest.java`

- [ ] **Step 1: 写失败测试（Bundle Jackson round-trip）**

`rule-config-svc/src/test/java/com/sstlfsj/rule/config/api/dto/RuleBundleTest.java`：

```java
package com.sstlfsj.rule.config.api.dto;

import com.sstlfsj.rule.kernel.api.model.MetricDependency;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** RuleBundle / RuleImportResult 序列化往返测试（无损保留 JSON 列原文，多规则结构）。 */
class RuleBundleTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();

    @Test
    void ruleBundle_roundTrip_preservesMultiRuleStructure() {
        RuleBundle bundle = new RuleBundle(
                1, "2026-06-06T10:00:00Z", "1",
                List.of(
                        new RuleBundle.RuleEntry(
                                "rule.night.transfer", "夜间大额转账", "AST_BOOLEAN", "risk.transfer",
                                "{\"type\":\"AndNode\",\"children\":[]}",
                                "[{\"decisionCode\":\"BLOCK\",\"priority\":100}]",
                                "[]", "[\"transfer\"]",
                                List.of(new MetricDependency("account.age", 1))),
                        new RuleBundle.RuleEntry(
                                "rule.new.account", "新户拦截", "AST_BOOLEAN", "risk.transfer",
                                "{\"type\":\"ConditionNode\"}", "[]", "[]", "[]", List.of())),
                List.of(new RuleBundle.SceneSnapshot(
                        "risk.transfer", "转账风控", "desc", "USER", "PUSH", "HIGHEST_PRIORITY",
                        "[\"transfer\"]", "{\"amount\":\"NUMBER\"}", "{}", 1)),
                List.of(new RuleBundle.MetricEntry(
                        "account.age", 1, "账户年龄", "ATTRIBUTE", "LONG", "{}", 3600, true)),
                List.of(new RuleBundle.DecisionEntry(
                        "BLOCK", "拦截", 100, "拦截交易",
                        "[{\"actionId\":\"a1\",\"actionType\":\"BLOCK_TRANSACTION\",\"sortOrder\":0,\"params\":{}}]")),
                List.of("BLOCK_TRANSACTION"));

        String json = mapper.writeValueAsString(bundle);
        RuleBundle back = mapper.readValue(json, RuleBundle.class);

        assertThat(back).isEqualTo(bundle);
        assertThat(back.rules()).hasSize(2);
        assertThat(back.rules().getFirst().conditionAst()).isEqualTo("{\"type\":\"AndNode\",\"children\":[]}");
        assertThat(back.rules().getFirst().metricDependencies()).containsExactly(new MetricDependency("account.age", 1));
        assertThat(back.scenes()).hasSize(1);
        assertThat(back.metricDefinitions().getFirst().sourceType()).isEqualTo("ATTRIBUTE");
        assertThat(back.decisionDefinitions().getFirst().code()).isEqualTo("BLOCK");
        assertThat(back.actionTypeManifest()).containsExactly("BLOCK_TRANSACTION");
    }

    @Test
    void ruleImportResult_roundTrip() {
        RuleImportResult result = new RuleImportResult(
                List.of(new RuleImportResult.ImportedRule(10L, 20L, 1L, "rule.a", "risk.transfer", false)),
                List.of("risk.transfer"), List.of(),
                List.of("account.age"), List.of(), List.of("balance.sql"),
                List.of("BLOCK"), List.of(), List.of("BLOCK_TRANSACTION"));

        String json = mapper.writeValueAsString(result);
        RuleImportResult back = mapper.readValue(json, RuleImportResult.class);

        assertThat(back).isEqualTo(result);
        assertThat(back.rules().getFirst().code()).isEqualTo("rule.a");
        assertThat(back.metricsRequiringReview()).containsExactly("balance.sql");
        assertThat(back.scenesCreated()).containsExactly("risk.transfer");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

先设环境（mvn-env skill），然后：
Run: `$MVN -pl rule-config-svc -am test -Dtest=RuleBundleTest`
Expected: 编译失败（`RuleBundle` / `RuleImportResult` 不存在）。

- [ ] **Step 3: 写 `RuleBundle`**

`rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/dto/RuleBundle.java`：

```java
package com.sstlfsj.rule.config.api.dto;

import com.sstlfsj.rule.kernel.api.model.MetricDependency;

import java.util.List;

/**
 * 规则导出 / 导入自包含 Bundle（B7 / 08-evolution §2.9）。
 * <p>多规则结构：{@code rules} 为本次导出的规则版本集合，{@code scenes} / {@code metricDefinitions} /
 * {@code decisionDefinitions} 为跨规则去重的依赖定义，{@code actionTypeManifest} 为去重 actionType 清单。
 * 所有 JSON 列（conditionAst / decisionBindings / preGates / triggerEventTypes /
 * payloadSchema / eventTypes / defaultParams / actions）以原始 JSON 字符串无损搬运，
 * 导入端按原文写库，不做 AST 重解析。</p>
 *
 * @param bundleVersion       Bundle schema 版本，当前固定 1
 * @param exportedAt          导出时间 ISO-8601
 * @param sourceTenantId      源租户 id（诊断用，导入不照搬，目标租户由调用参数决定）
 * @param rules               规则集合（每条含标识 + 当前 ACTIVE rule_version 内容）
 * @param scenes              规则引用的 Scene 快照（去重）
 * @param metricDefinitions   规则 metricDependencies 引用的 metric 定义（去重，按精确版本）
 * @param decisionDefinitions decisionBindings 引用的 tenant 级 decision 定义（去重）
 * @param actionTypeManifest  decisions 内出现的 actionType 去重清单（目标环境 SPI 兼容性核对）
 */
public record RuleBundle(
        int bundleVersion,
        String exportedAt,
        String sourceTenantId,
        List<RuleEntry> rules,
        List<SceneSnapshot> scenes,
        List<MetricEntry> metricDefinitions,
        List<DecisionEntry> decisionDefinitions,
        List<String> actionTypeManifest
) {
    /** 规则主体：标识来自 rule_definition，版本内容来自当前 ACTIVE rule_version；sceneCode 关联 scenes 元素。 */
    public record RuleEntry(
            String code,
            String name,
            String kind,
            String sceneCode,
            String conditionAst,
            String decisionBindings,
            String preGates,
            String triggerEventTypes,
            List<MetricDependency> metricDependencies
    ) {}

    /** Scene 快照，对应 scene 表可重建字段。 */
    public record SceneSnapshot(
            String code,
            String name,
            String description,
            String subjectType,
            String dominantMode,
            String decisionStrategy,
            String eventTypes,
            String payloadSchema,
            String defaultParams,
            Integer payloadSchemaVersion
    ) {}

    /** metric 定义快照，对应 metric_definition 表的精确版本行。 */
    public record MetricEntry(
            String metricCode,
            Integer version,
            String name,
            String sourceType,
            String dataType,
            String params,
            Integer cacheTtlSeconds,
            Boolean allowProvided
    ) {}

    /** decision 定义快照，对应 decision_definition 表（actions 为原始 JSON）。 */
    public record DecisionEntry(
            String code,
            String name,
            Integer priority,
            String description,
            String actions
    ) {}
}
```

- [ ] **Step 4: 写 `RuleImportResult`**

`rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/dto/RuleImportResult.java`：

```java
package com.sstlfsj.rule.config.api.dto;

import java.util.List;

/**
 * 规则导入结果（B7，批量）。{@code rules} 逐条记录每个规则的落库信息，其余字段为 Bundle 级依赖 upsert 的处置清单。
 *
 * @param rules                   逐条规则导入结果
 * @param scenesCreated           缺失而自动创建的 sceneCode
 * @param scenesSkippedExisting   已存在跳过的 sceneCode
 * @param metricsCreated          缺失而自动创建的 metricCode（非 SQL 类）
 * @param metricsSkippedExisting  已存在跳过的 metricCode
 * @param metricsRequiringReview  SQL_AGGREGATE 类缺失、未自动创建、需人工审核的 metricCode
 * @param decisionsCreated        缺失而自动创建的 decision code
 * @param decisionsSkippedExisting 已存在跳过的 decision code
 * @param actionTypesReferenced   Bundle 声明引用的 actionType（提醒目标环境核对 SPI handler 注册）
 */
public record RuleImportResult(
        List<ImportedRule> rules,
        List<String> scenesCreated,
        List<String> scenesSkippedExisting,
        List<String> metricsCreated,
        List<String> metricsSkippedExisting,
        List<String> metricsRequiringReview,
        List<String> decisionsCreated,
        List<String> decisionsSkippedExisting,
        List<String> actionTypesReferenced
) {
    /**
     * 单条规则导入落库结果。
     *
     * @param ruleDefinitionId   规则定义 id（新建或既有）
     * @param ruleVersionId      本次写入的 DRAFT rule_version id
     * @param version            本次草稿版本号
     * @param code               规则编码
     * @param sceneCode          所属 Scene 编码
     * @param ruleAlreadyExisted true=同 code 规则已存在，本次为追加草稿版本；false=新建
     */
    public record ImportedRule(
            Long ruleDefinitionId,
            Long ruleVersionId,
            Long version,
            String code,
            String sceneCode,
            boolean ruleAlreadyExisted
    ) {}
}
```

- [ ] **Step 5: 写 `RuleBundleService` 接口**

`rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/service/RuleBundleService.java`：

```java
package com.sstlfsj.rule.config.api.service;

import com.sstlfsj.rule.config.api.dto.RuleBundle;
import com.sstlfsj.rule.config.api.dto.RuleImportResult;

import java.util.List;

/** 规则批量导出 / 导入（B7 / 08-evolution §2.9）。 */
public interface RuleBundleService {

    /**
     * 按条件批量导出规则的当前 ACTIVE 版本为自包含 Bundle。
     * <p>选取优先级：ruleIds 非空 → 按 id 列表；否则 sceneId 非空 → 该场景全部；否则 → 该租户全部。
     * 对每条规则仅导当前 ACTIVE 版本，无 ACTIVE 版本者跳过；最终无可导出规则时报错。</p>
     *
     * @param tenantId 租户 id
     * @param ruleIds  规则定义 id 列表（可为 null / 空）
     * @param sceneId  场景 id（可为 null）
     * @return 多规则自包含 Bundle
     * @throws IllegalArgumentException 无可导出的 ACTIVE 规则
     */
    RuleBundle export(String tenantId, List<Long> ruleIds, Long sceneId);

    /**
     * 幂等导入 Bundle 到目标租户：整体 upsert 依赖（Scene / metric / decision 缺失则建），
     * 逐条把规则落为 DRAFT 版本（已存在则追加草稿版本，不覆盖已发布版本）。
     *
     * @param tenantId 目标租户 id
     * @param bundle   导入 Bundle
     * @param actorId  操作人（来自 X-Actor-Id）
     * @return 导入结果汇总
     * @throws IllegalArgumentException Bundle 结构非法
     */
    RuleImportResult importBundle(String tenantId, RuleBundle bundle, String actorId);
}
```

- [ ] **Step 6: 运行测试确认通过**

Run: `$MVN -pl rule-config-svc -am test -Dtest=RuleBundleTest`
Expected: PASS（2 个测试）。

- [ ] **Step 7: 提交**

```bash
git add rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/dto/RuleBundle.java \
        rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/dto/RuleImportResult.java \
        rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/service/RuleBundleService.java \
        rule-config-svc/src/test/java/com/sstlfsj/rule/config/api/dto/RuleBundleTest.java
git commit -m "feat(config): B7 规则批量导出/导入 Bundle DTO + Service 接口"
```

---

### Task 2: 批量导出服务 `RuleExportService`

**Files:**
- Create: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/bundle/RuleExportService.java`
- Test: `rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/bundle/RuleExportServiceTest.java`

**说明**：按条件查 rule_definition 集合 → 对每条取 ACTIVE rule_version（无则跳过）→ 跨规则去重组装 scenes / metrics / decisions / actionTypeManifest。**查询封装在各 Mapper 的 `default` 方法里（语义化方法，service 不直接拼 `LambdaQueryWrapper`；这些方法导出/导入共用，本 Task 一次加齐）。** metricDependencies / decisionBindings / actions 用与 `MetricWriteServiceImpl` 相同的 Jackson `TypeReference` 解析。metric 按精确 `(metricCode, version)` 取（含 SUPERSEDED 历史版本）。

- [ ] **Step 1: 写失败测试（mock mapper）**

`rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/bundle/RuleExportServiceTest.java`：

```java
package com.sstlfsj.rule.config.internal.bundle;

import com.sstlfsj.rule.config.api.dto.RuleBundle;
import com.sstlfsj.rule.config.internal.domain.DecisionDefinition;
import com.sstlfsj.rule.config.internal.domain.MetricDefinition;
import com.sstlfsj.rule.config.internal.domain.RuleDefinition;
import com.sstlfsj.rule.config.internal.domain.RuleVersion;
import com.sstlfsj.rule.config.internal.domain.SceneDef;
import com.sstlfsj.rule.config.internal.repository.DecisionDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.MetricDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.RuleDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.RuleVersionMapper;
import com.sstlfsj.rule.config.internal.repository.SceneMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * RuleExportService 单元测试：mock 各 Mapper 的语义查询方法。
 * <p>Mapper 的 default 查询方法会被 Mockito stub 掉（方法体不执行），故无需 TableInfoHelper 预热——
 * wrapper 拼装逻辑由 Mapper default 方法承载，交 Testcontainers 集成测试覆盖真库。</p>
 */
@ExtendWith(MockitoExtension.class)
class RuleExportServiceTest {

    @Mock RuleDefinitionMapper ruleDefinitionMapper;
    @Mock RuleVersionMapper ruleVersionMapper;
    @Mock SceneMapper sceneMapper;
    @Mock MetricDefinitionMapper metricDefinitionMapper;
    @Mock DecisionDefinitionMapper decisionDefinitionMapper;
    @Spy ObjectMapper objectMapper = JsonMapper.builder().build();
    @InjectMocks RuleExportService sut;

    private RuleDefinition rule(long id, String code) {
        RuleDefinition r = new RuleDefinition();
        r.setId(id); r.setTenantId(1L); r.setSceneId(5L);
        r.setCode(code); r.setName("规则" + code); r.setKind("AST_BOOLEAN");
        r.setStatus("PUBLISHED");
        return r;
    }

    private RuleVersion activeVersion(long rdId) {
        RuleVersion v = new RuleVersion();
        v.setId(100L + rdId); v.setRuleDefinitionId(rdId); v.setVersion(3L); v.setStatus("ACTIVE");
        v.setKind("AST_BOOLEAN");
        v.setConditionAst("{\"type\":\"AndNode\",\"children\":[]}");
        v.setDecisionBindings("[{\"decisionCode\":\"BLOCK\",\"priority\":100}]");
        v.setPreGates("[]");
        v.setTriggerEventTypes("[\"transfer\"]");
        v.setMetricDependencies("[{\"metricCode\":\"account.age\",\"metricVersion\":1}]");
        return v;
    }

    private SceneDef scene() {
        SceneDef s = new SceneDef();
        s.setId(5L); s.setTenantId(1L); s.setCode("risk.transfer"); s.setName("转账风控");
        s.setSubjectType("USER"); s.setDominantMode("PUSH"); s.setDecisionStrategy("HIGHEST_PRIORITY");
        s.setEventTypes("[\"transfer\"]"); s.setPayloadSchema("{}"); s.setDefaultParams("{}");
        s.setPayloadSchemaVersion(1);
        return s;
    }

    private MetricDefinition metric() {
        MetricDefinition m = new MetricDefinition();
        m.setMetricCode("account.age"); m.setVersion(1); m.setName("账户年龄");
        m.setSourceType("ATTRIBUTE"); m.setDataType("LONG"); m.setParams("{}");
        m.setCacheTtlSeconds(3600); m.setAllowProvided(true);
        return m;
    }

    private DecisionDefinition decision() {
        DecisionDefinition d = new DecisionDefinition();
        d.setCode("BLOCK"); d.setName("拦截"); d.setPriority(100); d.setDescription("拦截交易");
        d.setActions("[{\"actionId\":\"a1\",\"actionType\":\"BLOCK_TRANSACTION\",\"sortOrder\":0,\"params\":{}}]");
        return d;
    }

    @Test
    void export_byRuleIds_assemblesMultiRuleBundleWithDedupedDeps() {
        // 两条规则共享同一 scene / metric / decision，依赖应去重
        when(ruleDefinitionMapper.selectForExport(any(), any(), any()))
                .thenReturn(List.of(rule(10L, "a"), rule(11L, "b")));
        when(ruleVersionMapper.findActiveVersion(10L)).thenReturn(activeVersion(10L));
        when(ruleVersionMapper.findActiveVersion(11L)).thenReturn(activeVersion(11L));
        when(sceneMapper.findByIds(any())).thenReturn(List.of(scene()));
        when(metricDefinitionMapper.findByCodeAndVersion(any(), eq("account.age"), eq(1)))
                .thenReturn(metric());
        when(decisionDefinitionMapper.findByCodes(any(), any())).thenReturn(List.of(decision()));

        RuleBundle b = sut.export("1", List.of(10L, 11L), null);

        assertThat(b.bundleVersion()).isEqualTo(1);
        assertThat(b.rules()).hasSize(2);
        assertThat(b.rules()).extracting(RuleBundle.RuleEntry::code).containsExactlyInAnyOrder("a", "b");
        assertThat(b.rules().getFirst().sceneCode()).isEqualTo("risk.transfer");
        assertThat(b.scenes()).hasSize(1);                       // 去重
        assertThat(b.metricDefinitions()).hasSize(1);            // 去重
        assertThat(b.decisionDefinitions()).hasSize(1);          // 去重
        assertThat(b.actionTypeManifest()).containsExactly("BLOCK_TRANSACTION");
    }

    @Test
    void export_skipsRulesWithoutActiveVersion() {
        when(ruleDefinitionMapper.selectForExport(any(), any(), any()))
                .thenReturn(List.of(rule(10L, "a"), rule(11L, "b")));
        when(ruleVersionMapper.findActiveVersion(10L)).thenReturn(activeVersion(10L));
        when(ruleVersionMapper.findActiveVersion(11L)).thenReturn(null);   // 第二条无 ACTIVE
        when(sceneMapper.findByIds(any())).thenReturn(List.of(scene()));
        when(metricDefinitionMapper.findByCodeAndVersion(any(), any(), any())).thenReturn(metric());
        when(decisionDefinitionMapper.findByCodes(any(), any())).thenReturn(List.of(decision()));

        RuleBundle b = sut.export("1", List.of(10L, 11L), null);

        assertThat(b.rules()).hasSize(1);
        assertThat(b.rules().getFirst().code()).isEqualTo("a");
    }

    @Test
    void export_rejectsWhenNoExportableRule() {
        when(ruleDefinitionMapper.selectForExport(any(), any(), any()))
                .thenReturn(List.of(rule(10L, "a")));
        when(ruleVersionMapper.findActiveVersion(10L)).thenReturn(null);   // 无 ACTIVE

        assertThatThrownBy(() -> sut.export("1", List.of(10L), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("可导出");
    }

    @Test
    void export_bySceneId_filtersBySceneId() {
        // 入参直接是 sceneId，service 透传给 mapper.selectForExport
        when(ruleDefinitionMapper.selectForExport(any(), any(), any()))
                .thenReturn(List.of(rule(10L, "a")));
        when(ruleVersionMapper.findActiveVersion(10L)).thenReturn(activeVersion(10L));
        when(sceneMapper.findByIds(any())).thenReturn(List.of(scene()));
        when(metricDefinitionMapper.findByCodeAndVersion(any(), any(), any())).thenReturn(metric());
        when(decisionDefinitionMapper.findByCodes(any(), any())).thenReturn(List.of(decision()));

        RuleBundle b = sut.export("1", null, 5L);

        assertThat(b.rules()).hasSize(1);
        assertThat(b.rules().getFirst().sceneCode()).isEqualTo("risk.transfer");   // Bundle 内仍是 code
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `$MVN -pl rule-config-svc -am test -Dtest=RuleExportServiceTest`
Expected: 编译失败（`RuleExportService` 不存在）。

- [ ] **Step 3: 给 Mapper 加语义查询方法（导出/导入共用）+ 写 `RuleExportService`**

先在 5 个既有 Mapper 接口补 `default` 查询方法，把 `LambdaQueryWrapper` 收进 Mapper（与 `RuleVersionMapper.maxVersion` 的"Mapper 带逻辑"风格一致），service 只调语义方法。这些方法导出/导入共用，一次加齐；后续 Task 3 直接复用。

`rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/repository/RuleDefinitionMapper.java`（既有，加 2 个 default 方法）：

```java
package com.sstlfsj.rule.config.internal.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sstlfsj.rule.config.internal.domain.RuleDefinition;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/** rule_definition 表 MyBatis-Plus Mapper。 */
@Mapper
public interface RuleDefinitionMapper extends BaseMapper<RuleDefinition> {

    /** 导出选取：ruleIds → sceneId → 整租户（条件重载，单条查询）。 */
    default List<RuleDefinition> selectForExport(Long tenantId, List<Long> ruleIds, Long sceneId) {
        boolean byIds = ruleIds != null && !ruleIds.isEmpty();
        return selectList(new LambdaQueryWrapper<RuleDefinition>()
                .eq(RuleDefinition::getTenantId, tenantId)
                .in(byIds, RuleDefinition::getId, ruleIds)
                .eq(!byIds && sceneId != null, RuleDefinition::getSceneId, sceneId));
    }

    /** 按 (tenantId, sceneId, code) 查规则定义，不存在返回 null。 */
    default RuleDefinition findBySceneAndCode(Long tenantId, Long sceneId, String code) {
        return selectOne(new LambdaQueryWrapper<RuleDefinition>()
                .eq(RuleDefinition::getTenantId, tenantId)
                .eq(RuleDefinition::getSceneId, sceneId)
                .eq(RuleDefinition::getCode, code));
    }
}
```

`RuleVersionMapper.java`（既有，已有 maxVersion，加 findActiveVersion）：

```java
package com.sstlfsj.rule.config.internal.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sstlfsj.rule.config.internal.domain.RuleVersion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/** rule_version 表 MyBatis-Plus Mapper。 */
@Mapper
public interface RuleVersionMapper extends BaseMapper<RuleVersion> {

    /** 查询指定规则下的最大版本号，无记录返回 0。 */
    @Select("SELECT COALESCE(MAX(version), 0) FROM rule_version WHERE rule_definition_id = #{ruleDefinitionId}")
    Long maxVersion(Long ruleDefinitionId);

    /** 查规则当前 ACTIVE 版本（最高版本号的 ACTIVE 行），不存在返回 null。 */
    default RuleVersion findActiveVersion(Long ruleDefinitionId) {
        return selectOne(new LambdaQueryWrapper<RuleVersion>()
                .eq(RuleVersion::getRuleDefinitionId, ruleDefinitionId)
                .eq(RuleVersion::getStatus, "ACTIVE")
                .orderByDesc(RuleVersion::getVersion)
                .last("LIMIT 1"));
    }
}
```

`SceneMapper.java`（既有，加 2 个 default 方法）：

```java
package com.sstlfsj.rule.config.internal.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sstlfsj.rule.config.internal.domain.SceneDef;
import org.apache.ibatis.annotations.Mapper;

import java.util.Collection;
import java.util.List;

/** scene 表 MyBatis-Plus Mapper。 */
@Mapper
public interface SceneMapper extends BaseMapper<SceneDef> {

    /** 按 (tenantId, code) 查 Scene，不存在返回 null。 */
    default SceneDef findByCode(Long tenantId, String code) {
        return selectOne(new LambdaQueryWrapper<SceneDef>()
                .eq(SceneDef::getTenantId, tenantId)
                .eq(SceneDef::getCode, code));
    }

    /** 按 id 集合批量查 Scene；空集合返回空列表。 */
    default List<SceneDef> findByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        return selectList(new LambdaQueryWrapper<SceneDef>().in(SceneDef::getId, ids));
    }
}
```

`MetricDefinitionMapper.java`（既有，加 2 个 default 方法）：

```java
package com.sstlfsj.rule.config.internal.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sstlfsj.rule.config.internal.domain.MetricDefinition;
import org.apache.ibatis.annotations.Mapper;

/** metric_definition 表 MyBatis-Plus Mapper。 */
@Mapper
public interface MetricDefinitionMapper extends BaseMapper<MetricDefinition> {

    /** 按精确 (tenantId, metricCode, version) 查 metric，不存在返回 null。 */
    default MetricDefinition findByCodeAndVersion(Long tenantId, String metricCode, Integer version) {
        return selectOne(new LambdaQueryWrapper<MetricDefinition>()
                .eq(MetricDefinition::getTenantId, tenantId)
                .eq(MetricDefinition::getMetricCode, metricCode)
                .eq(MetricDefinition::getVersion, version));
    }

    /** 按 (tenantId, metricCode) 查任意一行（判断是否已存在），不存在返回 null。 */
    default MetricDefinition findAnyByCode(Long tenantId, String metricCode) {
        return selectOne(new LambdaQueryWrapper<MetricDefinition>()
                .eq(MetricDefinition::getTenantId, tenantId)
                .eq(MetricDefinition::getMetricCode, metricCode)
                .last("LIMIT 1"));
    }
}
```

`DecisionDefinitionMapper.java`（既有，加 2 个 default 方法）：

```java
package com.sstlfsj.rule.config.internal.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sstlfsj.rule.config.internal.domain.DecisionDefinition;
import org.apache.ibatis.annotations.Mapper;

import java.util.Collection;
import java.util.List;

/** decision_definition 表 MyBatis-Plus Mapper。 */
@Mapper
public interface DecisionDefinitionMapper extends BaseMapper<DecisionDefinition> {

    /** 按 (tenantId, code) 查 decision，不存在返回 null。 */
    default DecisionDefinition findByCode(Long tenantId, String code) {
        return selectOne(new LambdaQueryWrapper<DecisionDefinition>()
                .eq(DecisionDefinition::getTenantId, tenantId)
                .eq(DecisionDefinition::getCode, code));
    }

    /** 按 code 集合批量查 decision；空集合返回空列表。 */
    default List<DecisionDefinition> findByCodes(Long tenantId, Collection<String> codes) {
        if (codes == null || codes.isEmpty()) return List.of();
        return selectList(new LambdaQueryWrapper<DecisionDefinition>()
                .eq(DecisionDefinition::getTenantId, tenantId)
                .in(DecisionDefinition::getCode, codes));
    }
}
```

然后写 `RuleExportService`（service 不再直接拼 `LambdaQueryWrapper`，全调 Mapper 语义方法）：

`rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/bundle/RuleExportService.java`：

```java
package com.sstlfsj.rule.config.internal.bundle;

import com.sstlfsj.rule.config.api.dto.RuleBundle;
import com.sstlfsj.rule.config.internal.domain.DecisionDefinition;
import com.sstlfsj.rule.config.internal.domain.MetricDefinition;
import com.sstlfsj.rule.config.internal.domain.RuleDefinition;
import com.sstlfsj.rule.config.internal.domain.RuleVersion;
import com.sstlfsj.rule.config.internal.domain.SceneDef;
import com.sstlfsj.rule.config.internal.repository.DecisionDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.MetricDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.RuleDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.RuleVersionMapper;
import com.sstlfsj.rule.config.internal.repository.SceneMapper;
import com.sstlfsj.rule.kernel.api.model.MetricDependency;
import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 规则批量导出：按条件查规则集合，组装多规则自包含 Bundle（B7）。
 * <p>选取优先级 ruleIds → sceneId → 整租户；每条仅导当前 ACTIVE rule_version（无则跳过）。
 * scenes / metrics / decisions / actionTypeManifest 跨规则去重。</p>
 */
@Service
@RequiredArgsConstructor
public class RuleExportService {

    private static final TypeReference<List<MetricDependency>> METRIC_DEP_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<Map<String, Object>>> OBJ_LIST_TYPE = new TypeReference<>() {};

    private final RuleDefinitionMapper ruleDefinitionMapper;
    private final RuleVersionMapper ruleVersionMapper;
    private final SceneMapper sceneMapper;
    private final MetricDefinitionMapper metricDefinitionMapper;
    private final DecisionDefinitionMapper decisionDefinitionMapper;
    private final ObjectMapper objectMapper;

    /** 按条件批量导出规则当前 ACTIVE 版本为 Bundle。 */
    @Transactional(readOnly = true)
    public RuleBundle export(String tenantIdStr, List<Long> ruleIds, Long sceneId) {
        Long tid = Long.valueOf(tenantIdStr);

        List<RuleDefinition> ruleDefs = ruleDefinitionMapper.selectForExport(tid, ruleIds, sceneId);

        // 1. 逐条取 ACTIVE rule_version，无则跳过；同时收集 sceneId / metricDep / decisionCode
        List<RuleVersion> activeVersions = new ArrayList<>();
        List<RuleDefinition> exportable = new ArrayList<>();
        Set<Long> sceneIds = new LinkedHashSet<>();
        Set<MetricDependency> metricDeps = new LinkedHashSet<>();
        Set<String> decisionCodes = new LinkedHashSet<>();
        for (RuleDefinition rd : ruleDefs) {
            RuleVersion active = ruleVersionMapper.findActiveVersion(rd.getId());
            if (active == null) continue;
            exportable.add(rd);
            activeVersions.add(active);
            if (rd.getSceneId() != null) sceneIds.add(rd.getSceneId());
            metricDeps.addAll(parseDeps(active.getMetricDependencies()));
            decisionCodes.addAll(parseDecisionCodes(active.getDecisionBindings()));
        }
        if (exportable.isEmpty()) {
            throw new IllegalArgumentException("无可导出的 ACTIVE 规则");
        }

        // 2. scenes（去重）+ sceneId → code 映射
        Map<Long, SceneDef> sceneById = new LinkedHashMap<>();
        for (SceneDef s : sceneMapper.findByIds(sceneIds)) {
            sceneById.put(s.getId(), s);
        }
        List<RuleBundle.SceneSnapshot> scenes = sceneById.values().stream()
                .map(s -> new RuleBundle.SceneSnapshot(
                        s.getCode(), s.getName(), s.getDescription(),
                        s.getSubjectType(), s.getDominantMode(), s.getDecisionStrategy(),
                        s.getEventTypes(), s.getPayloadSchema(), s.getDefaultParams(),
                        s.getPayloadSchemaVersion()))
                .toList();

        // 3. metrics（去重，精确版本）
        List<RuleBundle.MetricEntry> metricEntries = new ArrayList<>();
        for (MetricDependency dep : metricDeps) {
            MetricDefinition m = metricDefinitionMapper.findByCodeAndVersion(
                    tid, dep.metricCode(), dep.metricVersion());
            if (m != null) {
                metricEntries.add(new RuleBundle.MetricEntry(
                        m.getMetricCode(), m.getVersion(), m.getName(),
                        m.getSourceType(), m.getDataType(), m.getParams(),
                        m.getCacheTtlSeconds(), m.getAllowProvided()));
            }
        }

        // 4. decisions（去重）+ actionTypeManifest
        List<DecisionDefinition> decisions = decisionDefinitionMapper.findByCodes(tid, decisionCodes);
        List<RuleBundle.DecisionEntry> decisionEntries = decisions.stream()
                .map(d -> new RuleBundle.DecisionEntry(
                        d.getCode(), d.getName(), d.getPriority(), d.getDescription(), d.getActions()))
                .toList();
        List<String> actionTypes = collectActionTypes(decisions);

        // 5. rules
        List<RuleBundle.RuleEntry> rules = new ArrayList<>();
        for (int i = 0; i < exportable.size(); i++) {
            RuleDefinition rd = exportable.get(i);
            RuleVersion rv = activeVersions.get(i);
            SceneDef scene = sceneById.get(rd.getSceneId());
            rules.add(new RuleBundle.RuleEntry(
                    rd.getCode(), rd.getName(),
                    rv.getKind() != null ? rv.getKind() : "AST_BOOLEAN",
                    scene != null ? scene.getCode() : null,
                    rv.getConditionAst(), rv.getDecisionBindings(),
                    rv.getPreGates(), rv.getTriggerEventTypes(),
                    parseDeps(rv.getMetricDependencies())));
        }

        return new RuleBundle(1, Instant.now().toString(), tenantIdStr,
                rules, scenes, metricEntries, decisionEntries, actionTypes);
    }

    private List<MetricDependency> parseDeps(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, METRIC_DEP_TYPE);
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<String> parseDecisionCodes(String decisionBindingsJson) {
        if (decisionBindingsJson == null || decisionBindingsJson.isBlank()) return List.of();
        try {
            List<Map<String, Object>> bindings = objectMapper.readValue(decisionBindingsJson, OBJ_LIST_TYPE);
            Set<String> codes = new LinkedHashSet<>();
            for (Map<String, Object> b : bindings) {
                Object code = b.get("decisionCode");
                if (code != null) codes.add(String.valueOf(code));
            }
            return new ArrayList<>(codes);
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<String> collectActionTypes(List<DecisionDefinition> decisions) {
        Set<String> types = new LinkedHashSet<>();
        for (DecisionDefinition d : decisions) {
            if (d.getActions() == null || d.getActions().isBlank()) continue;
            try {
                List<Map<String, Object>> actions = objectMapper.readValue(d.getActions(), OBJ_LIST_TYPE);
                for (Map<String, Object> a : actions) {
                    Object t = a.get("actionType");
                    if (t != null) types.add(String.valueOf(t));
                }
            } catch (Exception ignored) {
                // actions JSON 异常容错跳过，不阻断导出
            }
        }
        return new ArrayList<>(types);
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `$MVN -pl rule-config-svc -am test -Dtest=RuleExportServiceTest`
Expected: PASS（4 个测试）。

- [ ] **Step 5: 提交**

```bash
git add rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/repository/RuleDefinitionMapper.java \
        rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/repository/RuleVersionMapper.java \
        rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/repository/SceneMapper.java \
        rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/repository/MetricDefinitionMapper.java \
        rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/repository/DecisionDefinitionMapper.java \
        rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/bundle/RuleExportService.java \
        rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/bundle/RuleExportServiceTest.java
git commit -m "feat(config): B7 Mapper 语义查询方法 + 批量导出服务 RuleExportService"
```

---

### Task 3: 批量导入服务 `RuleImportService`

**Files:**
- Create: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/bundle/RuleImportService.java`
- Test: `rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/bundle/RuleImportServiceTest.java`

**导入语义（单事务）：**
1. 校验 `bundle` / `bundle.rules` 非空（空则 `IllegalArgumentException`）。
2. **Scenes upsert**：逐 `SceneSnapshot`，按 `(tenantId, code)` 查；缺失则 INSERT（status=ACTIVE，payloadSchemaVersion 缺省 1，createdBy=actorId），记 `scenesCreated`；已存在复用，记 `scenesSkippedExisting`。建 `sceneCode → sceneId` 映射。
3. **Metrics upsert**：逐 `MetricEntry`，按 `(tenantId, metricCode)` 查任意行；已存在 → `metricsSkippedExisting`；缺失且 `sourceType="SQL_AGGREGATE"` → `metricsRequiringReview`（**不创建**）；缺失且非 SQL → INSERT（version 缺省 1，status=ACTIVE，params 原文，createdBy=actorId），`metricsCreated`。
4. **Decisions upsert**：逐 `DecisionEntry`，按 `(tenantId, code)` 查；已存在 → `decisionsSkippedExisting`；缺失 → INSERT（status=ACTIVE，actions 原文，createdBy=actorId），`decisionsCreated`。
5. **Rules 逐条**：每条按 sceneCode 取 sceneId（映射缺失则按 `(tenantId, code)` 兜底查库，仍无则报错）；按 `(tenantId, sceneId, code)` 查 rule_definition；缺失 → INSERT rule_definition(DRAFT) + rule_version(v1, DRAFT)，`ruleAlreadyExisted=false`；存在 → 仅追加 rule_version(maxVersion+1, DRAFT)，**不动 rule_definition**，`ruleAlreadyExisted=true`。版本内容（conditionAst / decisionBindings / preGates / triggerEventTypes / metricDependencies）取包内原文，kind 取 `rule.kind`（缺省 AST_BOOLEAN）。每条写 audit_log（action="IMPORT"）。
6. 返回批量 `RuleImportResult`。

- [ ] **Step 1: 写失败测试（mock mapper）**

`rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/bundle/RuleImportServiceTest.java`：

```java
package com.sstlfsj.rule.config.internal.bundle;

import com.sstlfsj.rule.config.api.dto.RuleBundle;
import com.sstlfsj.rule.config.api.dto.RuleImportResult;
import com.sstlfsj.rule.config.internal.domain.AuditLog;
import com.sstlfsj.rule.config.internal.domain.DecisionDefinition;
import com.sstlfsj.rule.config.internal.domain.MetricDefinition;
import com.sstlfsj.rule.config.internal.domain.RuleDefinition;
import com.sstlfsj.rule.config.internal.domain.RuleVersion;
import com.sstlfsj.rule.config.internal.domain.SceneDef;
import com.sstlfsj.rule.config.internal.repository.AuditLogMapper;
import com.sstlfsj.rule.config.internal.repository.DecisionDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.MetricDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.RuleDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.RuleVersionMapper;
import com.sstlfsj.rule.config.internal.repository.SceneMapper;
import com.sstlfsj.rule.kernel.api.model.MetricDependency;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RuleImportService 单元测试：mock 各 Mapper 的语义查询方法。
 * <p>Mapper 的 default 查询方法被 Mockito stub 掉（方法体不执行），无需 TableInfoHelper 预热。
 * insert 仍是 BaseMapper 方法，用 doAnswer 回填自增主键。</p>
 */
@ExtendWith(MockitoExtension.class)
class RuleImportServiceTest {

    @Mock RuleDefinitionMapper ruleDefinitionMapper;
    @Mock RuleVersionMapper ruleVersionMapper;
    @Mock SceneMapper sceneMapper;
    @Mock MetricDefinitionMapper metricDefinitionMapper;
    @Mock DecisionDefinitionMapper decisionDefinitionMapper;
    @Mock AuditLogMapper auditLogMapper;
    @InjectMocks RuleImportService sut;

    private RuleBundle.RuleEntry ruleEntry(String code) {
        return new RuleBundle.RuleEntry(code, "规则" + code, "AST_BOOLEAN", "risk.transfer",
                "{\"type\":\"AndNode\",\"children\":[]}",
                "[{\"decisionCode\":\"BLOCK\",\"priority\":100}]",
                "[]", "[\"transfer\"]",
                List.of(new MetricDependency("account.age", 1)));
    }

    private RuleBundle bundle(String metricSourceType, String... ruleCodes) {
        return new RuleBundle(1, "2026-06-06T10:00:00Z", "1",
                java.util.Arrays.stream(ruleCodes).map(this::ruleEntry).toList(),
                List.of(new RuleBundle.SceneSnapshot("risk.transfer", "转账风控", "d", "USER",
                        "PUSH", "HIGHEST_PRIORITY", "[\"transfer\"]", "{}", "{}", 1)),
                List.of(new RuleBundle.MetricEntry("account.age", 1, "账户年龄",
                        metricSourceType, "LONG", "{}", 3600, true)),
                List.of(new RuleBundle.DecisionEntry("BLOCK", "拦截", 100, "拦截交易",
                        "[{\"actionType\":\"BLOCK_TRANSACTION\"}]")),
                List.of("BLOCK_TRANSACTION"));
    }

    /** 模拟 MyBatis insert 回填自增主键（rule_version 用递增序列）。 */
    private void stubInserts(long sceneId, long ruleDefId, AtomicLong rvSeq) {
        doAnswer(inv -> { ((SceneDef) inv.getArgument(0)).setId(sceneId); return 1; })
                .when(sceneMapper).insert(any());
        doAnswer(inv -> { ((RuleDefinition) inv.getArgument(0)).setId(ruleDefId); return 1; })
                .when(ruleDefinitionMapper).insert(any());
        doAnswer(inv -> { ((RuleVersion) inv.getArgument(0)).setId(rvSeq.incrementAndGet()); return 1; })
                .when(ruleVersionMapper).insert(any());
    }

    @Test
    void import_freshTarget_twoRules_createsDepsOnceAndDraftV1Each() {
        when(sceneMapper.findByCode(any(), any())).thenReturn(null);
        when(metricDefinitionMapper.findAnyByCode(any(), any())).thenReturn(null);
        when(decisionDefinitionMapper.findByCode(any(), any())).thenReturn(null);
        when(ruleDefinitionMapper.findBySceneAndCode(any(), any(), any())).thenReturn(null);
        AtomicLong rvSeq = new AtomicLong(100);
        // 两条规则各自新建 rule_definition：用计数器区分返回 id
        AtomicLong rdSeq = new AtomicLong(9);
        doAnswer(inv -> { ((SceneDef) inv.getArgument(0)).setId(5L); return 1; })
                .when(sceneMapper).insert(any());
        doAnswer(inv -> { ((RuleDefinition) inv.getArgument(0)).setId(rdSeq.incrementAndGet()); return 1; })
                .when(ruleDefinitionMapper).insert(any());
        doAnswer(inv -> { ((RuleVersion) inv.getArgument(0)).setId(rvSeq.incrementAndGet()); return 1; })
                .when(ruleVersionMapper).insert(any());

        RuleImportResult r = sut.importBundle("1", bundle("ATTRIBUTE", "rule.a", "rule.b"), "dev");

        assertThat(r.rules()).hasSize(2);
        assertThat(r.rules()).allMatch(ir -> !ir.ruleAlreadyExisted() && ir.version() == 1L
                && "DRAFT".equals("DRAFT") && "risk.transfer".equals(ir.sceneCode()));
        assertThat(r.scenesCreated()).containsExactly("risk.transfer");
        assertThat(r.metricsCreated()).containsExactly("account.age");   // 依赖只创建一次
        assertThat(r.decisionsCreated()).containsExactly("BLOCK");
        verify(metricDefinitionMapper, times(1)).insert(any());
        verify(decisionDefinitionMapper, times(1)).insert(any());
        verify(auditLogMapper, times(2)).insert(any());                  // 每条规则一条审计
    }

    @Test
    void import_sqlMetricMissing_flaggedForReviewNotCreated() {
        when(sceneMapper.findByCode(any(), any())).thenReturn(null);
        when(metricDefinitionMapper.findAnyByCode(any(), any())).thenReturn(null);
        when(decisionDefinitionMapper.findByCode(any(), any())).thenReturn(null);
        when(ruleDefinitionMapper.findBySceneAndCode(any(), any(), any())).thenReturn(null);
        stubInserts(5L, 10L, new AtomicLong(100));

        RuleImportResult r = sut.importBundle("1", bundle("SQL_AGGREGATE", "rule.a"), "dev");

        assertThat(r.metricsRequiringReview()).containsExactly("account.age");
        assertThat(r.metricsCreated()).isEmpty();
        verify(metricDefinitionMapper, never()).insert(any());
    }

    @Test
    void import_existingRule_appendsDraftVersionWithoutTouchingDefinition() {
        SceneDef existingScene = new SceneDef();
        existingScene.setId(5L); existingScene.setTenantId(1L); existingScene.setCode("risk.transfer");
        when(sceneMapper.findByCode(any(), any())).thenReturn(existingScene);

        MetricDefinition existingMetric = new MetricDefinition();
        existingMetric.setMetricCode("account.age");
        when(metricDefinitionMapper.findAnyByCode(any(), any())).thenReturn(existingMetric);

        DecisionDefinition existingDecision = new DecisionDefinition();
        existingDecision.setCode("BLOCK");
        when(decisionDefinitionMapper.findByCode(any(), any())).thenReturn(existingDecision);

        RuleDefinition existingRule = new RuleDefinition();
        existingRule.setId(10L); existingRule.setTenantId(1L); existingRule.setSceneId(5L);
        existingRule.setCode("rule.a"); existingRule.setStatus("PUBLISHED");
        when(ruleDefinitionMapper.findBySceneAndCode(any(), any(), any())).thenReturn(existingRule);
        when(ruleVersionMapper.maxVersion(10L)).thenReturn(3L);
        doAnswer(inv -> { ((RuleVersion) inv.getArgument(0)).setId(101L); return 1; })
                .when(ruleVersionMapper).insert(any());

        RuleImportResult r = sut.importBundle("1", bundle("ATTRIBUTE", "rule.a"), "dev");

        assertThat(r.rules()).hasSize(1);
        RuleImportResult.ImportedRule ir = r.rules().getFirst();
        assertThat(ir.ruleAlreadyExisted()).isTrue();
        assertThat(ir.version()).isEqualTo(4L);          // maxVersion(3)+1
        assertThat(ir.ruleVersionId()).isEqualTo(101L);
        assertThat(r.scenesSkippedExisting()).containsExactly("risk.transfer");
        assertThat(r.metricsSkippedExisting()).containsExactly("account.age");
        assertThat(r.decisionsSkippedExisting()).containsExactly("BLOCK");
        verify(ruleDefinitionMapper, never()).insert(any());
        verify(ruleDefinitionMapper, never()).updateById(any());
    }

    @Test
    void import_rejectsEmptyRules() {
        RuleBundle bad = new RuleBundle(1, "t", "1", List.of(),
                List.of(), List.of(), List.of(), List.of());
        assertThatThrownBy(() -> sut.importBundle("1", bad, "dev"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `$MVN -pl rule-config-svc -am test -Dtest=RuleImportServiceTest`
Expected: 编译失败（`RuleImportService` 不存在）。

- [ ] **Step 3: 写 `RuleImportService`**

`rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/bundle/RuleImportService.java`：

```java
package com.sstlfsj.rule.config.internal.bundle;

import com.sstlfsj.rule.config.api.dto.RuleBundle;
import com.sstlfsj.rule.config.api.dto.RuleImportResult;
import com.sstlfsj.rule.config.internal.domain.AuditLog;
import com.sstlfsj.rule.config.internal.domain.DecisionDefinition;
import com.sstlfsj.rule.config.internal.domain.MetricDefinition;
import com.sstlfsj.rule.config.internal.domain.RuleDefinition;
import com.sstlfsj.rule.config.internal.domain.RuleVersion;
import com.sstlfsj.rule.config.internal.domain.SceneDef;
import com.sstlfsj.rule.config.internal.repository.AuditLogMapper;
import com.sstlfsj.rule.config.internal.repository.DecisionDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.MetricDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.RuleDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.RuleVersionMapper;
import com.sstlfsj.rule.config.internal.repository.SceneMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 规则批量导入：幂等地把 Bundle 写入目标租户（B7）。
 * <p>单事务内先整体 upsert 依赖（Scene / metric / decision 缺失则建，已存在跳过），
 * 再逐条把规则落为 DRAFT 版本——已存在则追加草稿版本，不覆盖已发布版本。
 * SQL_AGGREGATE 类缺失 metric 不自动创建，列入待审清单。</p>
 */
@Service
@RequiredArgsConstructor
public class RuleImportService {

    private final RuleDefinitionMapper ruleDefinitionMapper;
    private final RuleVersionMapper ruleVersionMapper;
    private final SceneMapper sceneMapper;
    private final MetricDefinitionMapper metricDefinitionMapper;
    private final DecisionDefinitionMapper decisionDefinitionMapper;
    private final AuditLogMapper auditLogMapper;

    /** 幂等批量导入 Bundle 到目标租户。 */
    @Transactional
    public RuleImportResult importBundle(String tenantIdStr, RuleBundle bundle, String actorId) {
        if (bundle == null || bundle.rules() == null || bundle.rules().isEmpty()) {
            throw new IllegalArgumentException("Bundle 结构非法：rules 不得为空");
        }
        Long tenantId = Long.valueOf(tenantIdStr);

        // 2. Scenes upsert + sceneCode → sceneId 映射
        List<String> scenesCreated = new ArrayList<>();
        List<String> scenesSkipped = new ArrayList<>();
        Map<String, Long> sceneIdByCode = new LinkedHashMap<>();
        if (bundle.scenes() != null) {
            for (RuleBundle.SceneSnapshot ss : bundle.scenes()) {
                SceneDef existing = sceneMapper.findByCode(tenantId, ss.code());
                if (existing != null) {
                    scenesSkipped.add(ss.code());
                    sceneIdByCode.put(ss.code(), existing.getId());
                    continue;
                }
                SceneDef s = new SceneDef();
                s.setTenantId(tenantId);
                s.setCode(ss.code());
                s.setName(ss.name());
                s.setDescription(ss.description());
                s.setSubjectType(ss.subjectType());
                s.setDominantMode(ss.dominantMode());
                s.setDecisionStrategy(ss.decisionStrategy());
                s.setEventTypes(ss.eventTypes());
                s.setPayloadSchema(ss.payloadSchema());
                s.setDefaultParams(ss.defaultParams());
                s.setPayloadSchemaVersion(ss.payloadSchemaVersion() == null ? 1 : ss.payloadSchemaVersion());
                s.setStatus("ACTIVE");
                s.setCreatedBy(actorId);
                s.setCreatedAt(LocalDateTime.now());
                sceneMapper.insert(s);
                scenesCreated.add(ss.code());
                sceneIdByCode.put(ss.code(), s.getId());
            }
        }

        // 3. Metrics upsert
        List<String> metricsCreated = new ArrayList<>();
        List<String> metricsSkipped = new ArrayList<>();
        List<String> metricsReview = new ArrayList<>();
        if (bundle.metricDefinitions() != null) {
            for (RuleBundle.MetricEntry me : bundle.metricDefinitions()) {
                MetricDefinition existing = metricDefinitionMapper.findAnyByCode(tenantId, me.metricCode());
                if (existing != null) {
                    metricsSkipped.add(me.metricCode());
                    continue;
                }
                if ("SQL_AGGREGATE".equals(me.sourceType())) {
                    // SQL 类参数含查询语句，需人工审核，不自动创建（发布期 metric 校验是安全网）
                    metricsReview.add(me.metricCode());
                    continue;
                }
                MetricDefinition m = new MetricDefinition();
                m.setTenantId(tenantId);
                m.setMetricCode(me.metricCode());
                m.setVersion(me.version() == null ? 1 : me.version());
                m.setName(me.name());
                m.setSourceType(me.sourceType());
                m.setDataType(me.dataType());
                m.setParams(me.params() == null ? "{}" : me.params());
                m.setCacheTtlSeconds(me.cacheTtlSeconds() == null ? 60 : me.cacheTtlSeconds());
                m.setAllowProvided(Boolean.TRUE.equals(me.allowProvided()));
                m.setStatus("ACTIVE");
                m.setCreatedBy(actorId);
                m.setCreatedAt(LocalDateTime.now());
                metricDefinitionMapper.insert(m);
                metricsCreated.add(me.metricCode());
            }
        }

        // 4. Decisions upsert
        List<String> decisionsCreated = new ArrayList<>();
        List<String> decisionsSkipped = new ArrayList<>();
        if (bundle.decisionDefinitions() != null) {
            for (RuleBundle.DecisionEntry de : bundle.decisionDefinitions()) {
                DecisionDefinition existing = decisionDefinitionMapper.findByCode(tenantId, de.code());
                if (existing != null) {
                    decisionsSkipped.add(de.code());
                    continue;
                }
                DecisionDefinition d = new DecisionDefinition();
                d.setTenantId(tenantId);
                d.setCode(de.code());
                d.setName(de.name());
                d.setPriority(de.priority());
                d.setDescription(de.description());
                d.setActions(de.actions() == null ? "[]" : de.actions());
                d.setStatus("ACTIVE");
                d.setCreatedBy(actorId);
                d.setCreatedAt(LocalDateTime.now());
                decisionDefinitionMapper.insert(d);
                decisionsCreated.add(de.code());
            }
        }

        // 5. Rules 逐条
        List<RuleImportResult.ImportedRule> importedRules = new ArrayList<>();
        for (RuleBundle.RuleEntry rule : bundle.rules()) {
            Long sceneId = resolveSceneId(tenantId, rule.sceneCode(), sceneIdByCode);
            String kind = (rule.kind() == null || rule.kind().isBlank()) ? "AST_BOOLEAN" : rule.kind();

            RuleDefinition rd = ruleDefinitionMapper.findBySceneAndCode(tenantId, sceneId, rule.code());
            boolean ruleExisted = rd != null;
            long newVersion;
            if (rd == null) {
                rd = new RuleDefinition();
                rd.setTenantId(tenantId);
                rd.setSceneId(sceneId);
                rd.setCode(rule.code());
                rd.setName(rule.name());
                rd.setStatus("DRAFT");
                rd.setKind(kind);
                rd.setCreatedBy(actorId);
                rd.setCreatedAt(LocalDateTime.now());
                ruleDefinitionMapper.insert(rd);
                newVersion = 1L;
            } else {
                // 已存在：追加草稿版本，不动 rule_definition 状态/currentVersion（不覆盖已发布版本）
                newVersion = ruleVersionMapper.maxVersion(rd.getId()) + 1;
            }

            RuleVersion rv = new RuleVersion();
            rv.setRuleDefinitionId(rd.getId());
            rv.setVersion(newVersion);
            rv.setConditionAst(blankTo(rule.conditionAst(), "{}"));
            rv.setDecisionBindings(blankTo(rule.decisionBindings(), "[]"));
            rv.setPreGates(blankTo(rule.preGates(), "[]"));
            rv.setKind(kind);
            rv.setTriggerEventTypes(blankTo(rule.triggerEventTypes(), "[]"));
            rv.setMetricDependencies(metricDepsJson(rule));
            rv.setStatus("DRAFT");
            rv.setCreatedAt(LocalDateTime.now());
            ruleVersionMapper.insert(rv);

            AuditLog log = new AuditLog();
            log.setTenantId(tenantId);
            log.setActor(actorId);
            log.setActorType("USER");
            log.setAction("IMPORT");
            log.setTargetType("rule_definition");
            log.setTargetId(rd.getId().toString());
            log.setAfterSnapshot("{\"ruleVersionId\":" + rv.getId() + ",\"version\":" + newVersion
                    + ",\"ruleExisted\":" + ruleExisted + "}");
            log.setOperatedAt(LocalDateTime.now());
            auditLogMapper.insert(log);

            importedRules.add(new RuleImportResult.ImportedRule(
                    rd.getId(), rv.getId(), newVersion, rule.code(), rule.sceneCode(), ruleExisted));
        }

        return new RuleImportResult(importedRules,
                scenesCreated, scenesSkipped,
                metricsCreated, metricsSkipped, metricsReview,
                decisionsCreated, decisionsSkipped,
                bundle.actionTypeManifest() == null ? List.of() : bundle.actionTypeManifest());
    }

    /** 按 sceneCode 解析 sceneId：优先本次 upsert 映射，缺失则兜底查库，仍无则报错。 */
    private Long resolveSceneId(Long tenantId, String sceneCode, Map<String, Long> sceneIdByCode) {
        Long id = sceneIdByCode.get(sceneCode);
        if (id != null) return id;
        SceneDef scene = sceneMapper.findByCode(tenantId, sceneCode);
        if (scene == null) {
            throw new IllegalArgumentException("规则引用的 Scene 不在 Bundle 也不在目标环境: code=" + sceneCode);
        }
        sceneIdByCode.put(sceneCode, scene.getId());
        return scene.getId();
    }

    private static String blankTo(String s, String def) {
        return (s == null || s.isBlank()) ? def : s;
    }

    /** metricDependencies 原文：rule_version 列存 [{metricCode,metricVersion}] 数组。 */
    private static String metricDepsJson(RuleBundle.RuleEntry rule) {
        if (rule.metricDependencies() == null || rule.metricDependencies().isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < rule.metricDependencies().size(); i++) {
            var d = rule.metricDependencies().get(i);
            if (i > 0) sb.append(',');
            sb.append("{\"metricCode\":\"").append(d.metricCode())
              .append("\",\"metricVersion\":").append(d.metricVersion()).append('}');
        }
        return sb.append(']').toString();
    }
}
```

> **注**：`metricDepsJson` 手拼 JSON 是因为本服务未注入 `ObjectMapper`（避免为单一序列化点引依赖），且 `MetricDependency` 字段固定（metricCode 受发布期字符集约束，无需转义）。若后续 metric code 允许特殊字符，改注入 `ObjectMapper` 序列化。

- [ ] **Step 4: 运行测试确认通过**

Run: `$MVN -pl rule-config-svc -am test -Dtest=RuleImportServiceTest`
Expected: PASS（4 个测试）。

- [ ] **Step 5: 提交**

```bash
git add rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/bundle/RuleImportService.java \
        rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/bundle/RuleImportServiceTest.java
git commit -m "feat(config): B7 批量导入服务 RuleImportService 幂等 upsert + 逐条 DRAFT 落版本"
```

---

### Task 4: Service 实现 + HTTP 端点

**Files:**
- Create: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/service/RuleBundleServiceImpl.java`
- Create: `rule-api/src/main/java/com/sstlfsj/rule/web/config/RuleBundleController.java`
- Test: `rule-api/src/test/java/com/sstlfsj/rule/web/config/RuleBundleControllerTest.java`

- [ ] **Step 1: 写 `RuleBundleServiceImpl`（委托）**

`rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/service/RuleBundleServiceImpl.java`：

```java
package com.sstlfsj.rule.config.internal.service;

import com.sstlfsj.rule.config.api.dto.RuleBundle;
import com.sstlfsj.rule.config.api.dto.RuleImportResult;
import com.sstlfsj.rule.config.api.service.RuleBundleService;
import com.sstlfsj.rule.config.internal.bundle.RuleExportService;
import com.sstlfsj.rule.config.internal.bundle.RuleImportService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/** RuleBundleService 实现，委托 RuleExportService / RuleImportService。 */
@Service
@RequiredArgsConstructor
class RuleBundleServiceImpl implements RuleBundleService {

    private final RuleExportService ruleExportService;
    private final RuleImportService ruleImportService;

    @Override
    public RuleBundle export(String tenantId, List<Long> ruleIds, Long sceneId) {
        return ruleExportService.export(tenantId, ruleIds, sceneId);
    }

    @Override
    public RuleImportResult importBundle(String tenantId, RuleBundle bundle, String actorId) {
        return ruleImportService.importBundle(tenantId, bundle, actorId);
    }
}
```

- [ ] **Step 2: 写 `RuleBundleController`**

`rule-api/src/main/java/com/sstlfsj/rule/web/config/RuleBundleController.java`：

```java
package com.sstlfsj.rule.web.config;

import com.sstlfsj.rule.config.api.dto.RuleBundle;
import com.sstlfsj.rule.config.api.dto.RuleImportResult;
import com.sstlfsj.rule.config.api.service.RuleBundleService;
import com.sstlfsj.rule.web.common.ApiResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 规则批量导出 / 导入入口（B7 / 08-evolution §2.9）。
 * <p>导出为 Bundle JSON 文件下载，导入为 multipart 文件上传；Service 进出 {@link RuleBundle} 对象，
 * 本 Controller 负责对象 ↔ 文件的转换。权限 v1 沿用 X-Actor-Id（EXPORT / PUBLISH 校验留 TODO）。</p>
 */
@RestController
@RequestMapping("/api/v1/rules")
public class RuleBundleController {

    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final RuleBundleService ruleBundleService;
    private final ObjectMapper objectMapper;

    public RuleBundleController(RuleBundleService ruleBundleService, ObjectMapper objectMapper) {
        this.ruleBundleService = ruleBundleService;
        this.objectMapper = objectMapper;
    }

    /**
     * GET /api/v1/rules/export — 按条件导出规则当前 ACTIVE 版本为 Bundle JSON 文件下载。
     * <p>选取优先级：ruleIds 非空 → 按 id 列表；否则 sceneId 非空 → 该场景全部；否则 → 该租户全部。
     * 成功返回 attachment 文件；无可导出规则等错误由 GlobalExceptionHandler 转 JSON 错误体。</p>
     *
     * @param tenantId 租户 id
     * @param ruleIds  规则定义 id 列表（逗号分隔，可选）
     * @param sceneId  场景 id（可选）
     * @return Bundle JSON 文件（Content-Disposition: attachment）
     */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam String tenantId,
                                         @RequestParam(required = false) List<Long> ruleIds,
                                         @RequestParam(required = false) Long sceneId) {
        RuleBundle bundle = ruleBundleService.export(tenantId, ruleIds, sceneId);
        byte[] body = objectMapper.writeValueAsString(bundle).getBytes(StandardCharsets.UTF_8);
        String filename = "rule-bundle-" + tenantId + "-" + LocalDateTime.now().format(FILE_TS) + ".json";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(body);
    }

    /**
     * POST /api/v1/rules/import — 上传 Bundle JSON 文件，幂等批量导入，规则逐条落为 DRAFT 版本。
     *
     * @param tenantId 目标租户 id
     * @param actorId  操作人
     * @param file     Bundle JSON 文件（multipart 字段名 file）
     * @return 导入结果汇总
     */
    @PostMapping("/import")
    public ApiResponse<RuleImportResult> importBundle(@RequestParam String tenantId,
                                                      @RequestHeader("X-Actor-Id") String actorId,
                                                      @RequestParam("file") MultipartFile file) {
        RuleBundle bundle;
        try {
            bundle = objectMapper.readValue(file.getBytes(), RuleBundle.class);
        } catch (Exception e) {
            // 文件读取失败（IOException）或 JSON 反序列化失败 → 400
            throw new IllegalArgumentException("Bundle 文件解析失败: " + e.getMessage());
        }
        return ApiResponse.ok(ruleBundleService.importBundle(tenantId, bundle, actorId));
    }
}
```

> **注**：导出入参 `@RequestParam(required = false) List<Long> ruleIds` 接受 `?ruleIds=1,2,3` 或 `?ruleIds=1&ruleIds=2`。导入用 multipart 字段名 `file`，Spring Boot 默认装配 MultipartResolver 即可。`objectMapper` 注入全局 Bean（不自行 new）。

- [ ] **Step 3: 写失败测试（MockMvc）**

`rule-api/src/test/java/com/sstlfsj/rule/web/config/RuleBundleControllerTest.java`：

```java
package com.sstlfsj.rule.web.config;

import com.sstlfsj.rule.config.api.dto.RuleBundle;
import com.sstlfsj.rule.config.api.dto.RuleImportResult;
import com.sstlfsj.rule.config.api.service.RuleBundleService;
import com.sstlfsj.rule.kernel.api.model.MetricDependency;
import com.sstlfsj.rule.web.common.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** RuleBundleController 单元测试：export（文件下载）/ import（文件上传）端点。 */
class RuleBundleControllerTest {

    private MockMvc mockMvc;
    private RuleBundleService service;

    @BeforeEach
    void setUp() {
        service = mock(RuleBundleService.class);
        JsonMapper mapper = JsonMapper.builder().build();
        // 导出返回 byte[]，需 ByteArrayHttpMessageConverter；导入结果与错误体走 Jackson 转换器
        mockMvc = MockMvcBuilders
                .standaloneSetup(new RuleBundleController(service, mapper))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new ByteArrayHttpMessageConverter(),
                        new JacksonJsonHttpMessageConverter(mapper))
                .build();
    }

    private RuleBundle sampleBundle() {
        return new RuleBundle(1, "2026-06-06T10:00:00Z", "1",
                List.of(new RuleBundle.RuleEntry("rule.a", "规则A", "AST_BOOLEAN", "risk.transfer",
                        "{}", "[]", "[]", "[]", List.of(new MetricDependency("account.age", 1)))),
                List.of(new RuleBundle.SceneSnapshot("risk.transfer", "转账风控", null, "USER",
                        "PUSH", "HIGHEST_PRIORITY", "[]", "{}", "{}", 1)),
                List.of(), List.of(), List.of());
    }

    @Test
    void export_byRuleIds_returnsAttachmentFile() throws Exception {
        when(service.export(eq("t1"), eq(List.of(1L, 2L)), eq(null))).thenReturn(sampleBundle());

        // 响应体是 Bundle JSON 文件原文（非 ApiResponse 包裹），故 jsonPath 直接落在 Bundle 字段上
        mockMvc.perform(get("/api/v1/rules/export")
                        .param("tenantId", "t1")
                        .param("ruleIds", "1,2"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        org.hamcrest.Matchers.containsString("attachment; filename=\"rule-bundle-t1-")))
                .andExpect(jsonPath("$.bundleVersion").value(1))
                .andExpect(jsonPath("$.rules[0].code").value("rule.a"))
                .andExpect(jsonPath("$.scenes[0].code").value("risk.transfer"));

        verify(service).export("t1", List.of(1L, 2L), null);
    }

    @Test
    void export_bySceneId_returnsAttachmentFile() throws Exception {
        when(service.export(eq("t1"), eq(null), eq(5L))).thenReturn(sampleBundle());

        mockMvc.perform(get("/api/v1/rules/export")
                        .param("tenantId", "t1")
                        .param("sceneId", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rules[0].code").value("rule.a"));

        verify(service).export("t1", null, 5L);
    }

    @Test
    void export_returns400_whenTenantIdMissing() throws Exception {
        mockMvc.perform(get("/api/v1/rules/export"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void import_uploadFile_returns200_withResult() throws Exception {
        RuleImportResult result = new RuleImportResult(
                List.of(new RuleImportResult.ImportedRule(10L, 100L, 1L, "rule.a", "risk.transfer", false)),
                List.of("risk.transfer"), List.of(),
                List.of("account.age"), List.of(), List.of(),
                List.of("BLOCK"), List.of(), List.of("BLOCK_TRANSACTION"));
        when(service.importBundle(eq("t1"), any(), eq("user1"))).thenReturn(result);

        String bundleJson = """
                {"bundleVersion":1,"exportedAt":"t","sourceTenantId":"9",
                 "rules":[{"code":"rule.a","name":"规则A","kind":"AST_BOOLEAN",
                         "sceneCode":"risk.transfer","conditionAst":"{}",
                         "decisionBindings":"[]","preGates":"[]","triggerEventTypes":"[]",
                         "metricDependencies":[]}],
                 "scenes":[{"code":"risk.transfer","name":"转账风控","description":null,
                          "subjectType":"USER","dominantMode":"PUSH",
                          "decisionStrategy":"HIGHEST_PRIORITY","eventTypes":"[]",
                          "payloadSchema":"{}","defaultParams":"{}","payloadSchemaVersion":1}],
                 "metricDefinitions":[],"decisionDefinitions":[],"actionTypeManifest":[]}
                """;
        MockMultipartFile file = new MockMultipartFile(
                "file", "rule-bundle.json", "application/json",
                bundleJson.getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/v1/rules/import")
                        .file(file)
                        .param("tenantId", "t1")
                        .header("X-Actor-Id", "user1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.rules[0].ruleDefinitionId").value(10))
                .andExpect(jsonPath("$.data.rules[0].version").value(1))
                .andExpect(jsonPath("$.data.scenesCreated[0]").value("risk.transfer"));

        verify(service).importBundle(eq("t1"), any(), eq("user1"));
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `$MVN -pl rule-api -am test -Dtest=RuleBundleControllerTest`
Expected: PASS（4 个测试）。

- [ ] **Step 5: 提交**

```bash
git add rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/service/RuleBundleServiceImpl.java \
        rule-api/src/main/java/com/sstlfsj/rule/web/config/RuleBundleController.java \
        rule-api/src/test/java/com/sstlfsj/rule/web/config/RuleBundleControllerTest.java
git commit -m "feat(api): B7 规则批量导出/导入 HTTP 端点 + Service 实现"
```

---

### Task 5: 端到端集成测试（Testcontainers MySQL）

**Files:**
- Test: `rule-config-svc/src/test/java/com/sstlfsj/rule/config/integration/RuleBundleIntegrationTest.java`

**说明**：真 MySQL + Flyway 建表，验证批量链路——seed 同一 Scene 下两条已发布规则（含 metric / decision / ACTIVE rule_version）→ 按 sceneCode 批量 `export` → 改目标租户 `importBundle` → 校验目标租户下 Scene / metric / decision 被创建、两条规则各落 DRAFT v1；再次导入 → 各追加 DRAFT v2。无 DDL。

- [ ] **Step 1: 写集成测试**

`rule-config-svc/src/test/java/com/sstlfsj/rule/config/integration/RuleBundleIntegrationTest.java`：

```java
package com.sstlfsj.rule.config.integration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sstlfsj.rule.config.api.dto.RuleBundle;
import com.sstlfsj.rule.config.api.dto.RuleImportResult;
import com.sstlfsj.rule.config.api.service.RuleBundleService;
import com.sstlfsj.rule.config.internal.domain.DecisionDefinition;
import com.sstlfsj.rule.config.internal.domain.MetricDefinition;
import com.sstlfsj.rule.config.internal.domain.RuleDefinition;
import com.sstlfsj.rule.config.internal.domain.RuleVersion;
import com.sstlfsj.rule.config.internal.domain.SceneDef;
import com.sstlfsj.rule.config.internal.repository.DecisionDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.MetricDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.RuleDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.RuleVersionMapper;
import com.sstlfsj.rule.config.internal.repository.SceneMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** B7 端到端集成测试：seed 两条已发布规则 → 按 scene 批量导出 → 跨租户导入 → 校验落库 + 重复导入。 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class RuleBundleIntegrationTest {

    @SpringBootApplication(scanBasePackages = "com.sstlfsj.rule.config.internal")
    @MapperScan("com.sstlfsj.rule.config.internal.repository")
    static class TestApp {
    }

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("rule_engine_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    private static final long SRC_TENANT = 1L;
    private static final long DST_TENANT = 2L;

    @Autowired RuleBundleService ruleBundleService;
    @Autowired SceneMapper sceneMapper;
    @Autowired MetricDefinitionMapper metricDefinitionMapper;
    @Autowired DecisionDefinitionMapper decisionDefinitionMapper;
    @Autowired RuleDefinitionMapper ruleDefinitionMapper;
    @Autowired RuleVersionMapper ruleVersionMapper;

    @BeforeEach
    void clean() {
        ruleVersionMapper.delete(new LambdaQueryWrapper<RuleVersion>().isNotNull(RuleVersion::getId));
        ruleDefinitionMapper.delete(new LambdaQueryWrapper<RuleDefinition>().isNotNull(RuleDefinition::getId));
        metricDefinitionMapper.delete(new LambdaQueryWrapper<MetricDefinition>().isNotNull(MetricDefinition::getId));
        decisionDefinitionMapper.delete(new LambdaQueryWrapper<DecisionDefinition>().isNotNull(DecisionDefinition::getId));
        sceneMapper.delete(new LambdaQueryWrapper<SceneDef>().isNotNull(SceneDef::getId));
    }

    /** seed 一个 scene + metric + decision + 两条已发布规则，返回 sceneId。 */
    private Long seedTwoPublishedRules() {
        SceneDef scene = new SceneDef();
        scene.setTenantId(SRC_TENANT); scene.setCode("risk.transfer"); scene.setName("转账风控");
        scene.setSubjectType("USER"); scene.setDominantMode("PUSH"); scene.setDecisionStrategy("HIGHEST_PRIORITY");
        scene.setEventTypes("[\"transfer\"]"); scene.setPayloadSchema("{}"); scene.setDefaultParams("{}");
        scene.setPayloadSchemaVersion(1); scene.setStatus("ACTIVE"); scene.setCreatedBy("seed");
        scene.setCreatedAt(LocalDateTime.now());
        sceneMapper.insert(scene);

        MetricDefinition metric = new MetricDefinition();
        metric.setTenantId(SRC_TENANT); metric.setMetricCode("account.age"); metric.setVersion(1);
        metric.setName("账户年龄"); metric.setSourceType("ATTRIBUTE"); metric.setDataType("LONG");
        metric.setParams("{}"); metric.setCacheTtlSeconds(3600); metric.setAllowProvided(true);
        metric.setStatus("ACTIVE"); metric.setCreatedBy("seed"); metric.setCreatedAt(LocalDateTime.now());
        metricDefinitionMapper.insert(metric);

        DecisionDefinition decision = new DecisionDefinition();
        decision.setTenantId(SRC_TENANT); decision.setCode("BLOCK"); decision.setName("拦截");
        decision.setPriority(100); decision.setDescription("拦截交易");
        decision.setActions("[{\"actionId\":\"a1\",\"actionType\":\"BLOCK_TRANSACTION\",\"sortOrder\":0,\"params\":{}}]");
        decision.setStatus("ACTIVE"); decision.setCreatedBy("seed"); decision.setCreatedAt(LocalDateTime.now());
        decisionDefinitionMapper.insert(decision);

        seedRule(scene.getId(), "rule.night.transfer", "夜间大额转账");
        seedRule(scene.getId(), "rule.new.account", "新户拦截");
        return scene.getId();
    }

    private void seedRule(Long sceneId, String code, String name) {
        RuleDefinition rd = new RuleDefinition();
        rd.setTenantId(SRC_TENANT); rd.setSceneId(sceneId); rd.setCode(code);
        rd.setName(name); rd.setStatus("PUBLISHED"); rd.setKind("AST_BOOLEAN");
        rd.setCreatedBy("seed"); rd.setCreatedAt(LocalDateTime.now());
        ruleDefinitionMapper.insert(rd);

        RuleVersion rv = new RuleVersion();
        rv.setRuleDefinitionId(rd.getId()); rv.setVersion(1L);
        rv.setConditionAst("{\"type\":\"AndNode\",\"children\":[]}");
        rv.setDecisionBindings("[{\"decisionCode\":\"BLOCK\",\"priority\":100}]");
        rv.setPreGates("[]"); rv.setKind("AST_BOOLEAN"); rv.setTriggerEventTypes("[\"transfer\"]");
        rv.setMetricDependencies("[{\"metricCode\":\"account.age\",\"metricVersion\":1}]");
        rv.setStatus("ACTIVE"); rv.setPublishedBy("seed"); rv.setPublishedAt(LocalDateTime.now());
        rv.setCreatedAt(LocalDateTime.now());
        ruleVersionMapper.insert(rv);

        rd.setCurrentVersion(rv.getId());
        ruleDefinitionMapper.updateById(rd);
    }

    @Test
    void exportSceneThenImportToAnotherTenant_reconstructsBothRulesAsDraft() {
        Long sceneId = seedTwoPublishedRules();

        RuleBundle bundle = ruleBundleService.export(String.valueOf(SRC_TENANT), null, sceneId);
        assertThat(bundle.rules()).hasSize(2);
        assertThat(bundle.scenes()).hasSize(1);
        assertThat(bundle.metricDefinitions()).hasSize(1);    // 去重
        assertThat(bundle.decisionDefinitions()).hasSize(1);  // 去重
        assertThat(bundle.actionTypeManifest()).containsExactly("BLOCK_TRANSACTION");

        RuleImportResult result = ruleBundleService.importBundle(String.valueOf(DST_TENANT), bundle, "dev");

        assertThat(result.rules()).hasSize(2);
        assertThat(result.rules()).allMatch(ir -> !ir.ruleAlreadyExisted() && ir.version() == 1L);
        assertThat(result.scenesCreated()).containsExactly("risk.transfer");
        assertThat(result.metricsCreated()).containsExactly("account.age");
        assertThat(result.decisionsCreated()).containsExactly("BLOCK");

        // 目标租户下依赖与规则均落库，AST 原文无损、状态 DRAFT
        SceneDef dstScene = sceneMapper.selectOne(new LambdaQueryWrapper<SceneDef>()
                .eq(SceneDef::getTenantId, DST_TENANT).eq(SceneDef::getCode, "risk.transfer"));
        assertThat(dstScene).isNotNull();
        long draftCount = ruleVersionMapper.selectCount(new LambdaQueryWrapper<RuleVersion>()
                .eq(RuleVersion::getStatus, "DRAFT")
                .in(RuleVersion::getRuleDefinitionId,
                        result.rules().stream().map(RuleImportResult.ImportedRule::ruleDefinitionId).toList()));
        assertThat(draftCount).isEqualTo(2);
        RuleVersion anyDraft = ruleVersionMapper.selectById(result.rules().getFirst().ruleVersionId());
        assertThat(anyDraft.getConditionAst()).isEqualTo("{\"type\":\"AndNode\",\"children\":[]}");
    }

    @Test
    void reimportSameBundle_appendsSecondDraftVersionPerRule() {
        Long sceneId = seedTwoPublishedRules();
        RuleBundle bundle = ruleBundleService.export(String.valueOf(SRC_TENANT), null, sceneId);

        RuleImportResult first = ruleBundleService.importBundle(String.valueOf(DST_TENANT), bundle, "dev");
        RuleImportResult second = ruleBundleService.importBundle(String.valueOf(DST_TENANT), bundle, "dev");

        assertThat(first.rules()).allMatch(ir -> !ir.ruleAlreadyExisted() && ir.version() == 1L);
        assertThat(second.rules()).allMatch(ir -> ir.ruleAlreadyExisted() && ir.version() == 2L);
        assertThat(second.scenesSkippedExisting()).containsExactly("risk.transfer");
        assertThat(second.metricsSkippedExisting()).containsExactly("account.age");
        assertThat(second.decisionsSkippedExisting()).containsExactly("BLOCK");
    }
}
```

- [ ] **Step 2: 运行测试确认通过（需 Docker）**

Run: `$MVN -pl rule-config-svc -am test -Dtest=RuleBundleIntegrationTest`
Expected: PASS（2 个测试）。若本机无 Docker，`@Testcontainers(disabledWithoutDocker = true)` 会跳过——此时必须在有 Docker 的环境补跑确认，不能当作通过。

- [ ] **Step 3: 跑模块全量测试，确认无回归**

Run: `$MVN -pl rule-config-svc -am test`
Run: `$MVN -pl rule-api -am test`
Expected: BUILD SUCCESS，无失败。

- [ ] **Step 4: 提交**

```bash
git add rule-config-svc/src/test/java/com/sstlfsj/rule/config/integration/RuleBundleIntegrationTest.java
git commit -m "test(config): B7 批量导出/导入端到端集成测试（Testcontainers）"
```

---

### Task 6: 文档回写

**Files:**
- Modify: `docs/08-evolution.md`（§2.9 加"已实装"块）
- Modify: `docs/10-api-contract.md`（新增批量导出/导入端点契约）
- Modify: `docs/superpowers/plans/backlog.md`（从主动推进序列移除 B7）

**前置**：跨文档改动，按 CLAUDE.md 文档纪律——动手前先跑 `doc-consistency-review` skill 扫这三个文件的自洽性；改完用 `rule-engine-reviewer` agent 审"代码 ↔ 文档对齐"。

- [ ] **Step 1: 跑文档自洽性检查**

调用 `doc-consistency-review` skill，范围：`docs/08-evolution.md` §2.9、`docs/10-api-contract.md`、`docs/superpowers/plans/backlog.md`。记录其指出的任何措辞/字段不一致，在后续 Step 修正。

- [ ] **Step 2: 在 `08-evolution.md` §2.9 末尾追加"已实装"块**

在 §2.9 的 `- **迁移成本**：中（独立工具链，不动核心引擎）。` 行之后追加（与 §2.2 B6 的"已实装"块风格一致）：

```markdown

**已实装（B7 / 2026-06-06）：**

- **载体**：HTTP 端点——`GET /api/v1/rules/export?tenantId=&ruleIds=&sceneId=`（按条件批量导出 ACTIVE 版本为 **Bundle JSON 文件下载**，`Content-Disposition: attachment`）、`POST /api/v1/rules/import`（**multipart 文件上传**，幂等批量导入）；Service 落 `rule-config-svc`（`RuleBundleService` → `RuleExportService` / `RuleImportService`，进出 `RuleBundle` 对象），Controller 落 `rule-api`（`RuleBundleController`，做对象↔文件转换）。无 DDL。
- **批量 + 多规则 Bundle**：导出选取优先级 ruleIds → sceneId → 整租户（入参用 sceneId，前端列表已有；Bundle 内 `RuleEntry.sceneCode` 仍用 code，跨环境按 code 关联）；Bundle 统一为多规则结构 `{bundleVersion, exportedAt, sourceTenantId, rules[], scenes[], metricDefinitions[], decisionDefinitions[], actionTypeManifest[]}`，单规则 = rules 长度 1 特例。所有 JSON 列（conditionAst / decisionBindings / preGates / triggerEventTypes / metricDependencies / payloadSchema / eventTypes / defaultParams / actions）按原始 JSON 字符串无损搬运。**实装较原始字段集多 `decisionDefinitions[]`**：D27 后 Action 落在 tenant 级 decision_definition，随包搬运才能真正自包含重建。
- **导入幂等**：Scene / metric / decision 按业务键整体 upsert（缺失则建、已存在跳过不覆盖）；规则逐条——code 不存在 → 新建 rule_definition(DRAFT) + rule_version(DRAFT v1)，已存在 → 仅追加 rule_version(DRAFT, maxVersion+1)，不动 rule_definition 状态/currentVersion（不覆盖已发布版本）。把导入草稿提升为 ACTIVE 走发布/回滚流程（D19，尚未实现）。
- **metric 安全**：`SQL_AGGREGATE` 类缺失 metric **不自动创建**，列入导入结果 `metricsRequiringReview`，由运营人工审核后建；发布期 PublishService 的"被引用 metric 无 ACTIVE 版本"校验是安全网。
- **权限**：v1 沿用 `X-Actor-Id` header；§2.9 设想的 EXPORT / PUBLISH 权限校验留 TODO（后续合规批次 §2.8）。
- **与本地调试格式正交**：B7 Bundle 专做跨环境 DB 迁移；本地 / 离线调试用 `GET /api/v1/sdk/snapshots`（输出 RuleVersionSnapshot 数组、可直接 SDK 评估，见 §8.3 / 10-api-contract），两者不打通。
- **不做**：跨租户实时同步（由 §2.10 规则模板市场解决）。
```

并把 §五跨文档 TODO 表对应行状态（若有）更新为已实装；§2.10 提到的"依赖 2.9"无需改（仍成立）。

- [ ] **Step 3: 在 `10-api-contract.md` 规则章节补端点契约**

在规则相关章节（§4.x 规则生命周期）追加两节，风格对齐既有端点描述（请求/响应示例）：

```markdown
### 4.8 批量导出规则 Bundle

`GET /api/v1/rules/export?tenantId={tenantId}&ruleIds={id,id}&sceneId={sceneId}`

按条件批量导出规则的当前 ACTIVE 版本为自包含 JSON Bundle，**以文件下载形式返回**（`Content-Type: application/json` + `Content-Disposition: attachment; filename="rule-bundle-{tenantId}-{ts}.json"`），供跨环境 / 跨租户迁移、Incident 复现。选取优先级：`ruleIds` 非空 → 按 id 列表；否则 `sceneId` 非空 → 该场景全部；否则 → 该租户全部。对每条仅导当前 ACTIVE 版本，无 ACTIVE 版本者跳过；最终无可导出规则时返回 `INVALID_ARGUMENT`（JSON 错误体）。（导出入参用 sceneId；Bundle 内 `rules[].sceneCode` 用 code，跨环境按 code 关联。）

**Response 200**：Bundle JSON 文件（attachment），内容为多规则 Bundle（`{bundleVersion, exportedAt, sourceTenantId, rules[], scenes[], metricDefinitions[], decisionDefinitions[], actionTypeManifest[]}`）。

### 4.9 批量导入规则 Bundle

`POST /api/v1/rules/import?tenantId={tenantId}`，header `X-Actor-Id`，**`multipart/form-data` 上传 Bundle JSON 文件（字段名 `file`）**。

幂等批量导入到目标租户：Scene / metric / decision 缺失则建、已存在跳过；规则逐条落为 DRAFT 版本（已存在则追加草稿版本）。`SQL_AGGREGATE` 类缺失 metric 不自动创建，列入 `metricsRequiringReview`。文件解析失败返回 `INVALID_ARGUMENT`。

**Response 200**：`ApiResponse<RuleImportResult>`，含 `rules[]`（每条 `{ruleDefinitionId, ruleVersionId, version, code, sceneCode, ruleAlreadyExisted}`）+ `scenesCreated[] / scenesSkippedExisting[] / metricsCreated[] / metricsSkippedExisting[] / metricsRequiringReview[] / decisionsCreated[] / decisionsSkippedExisting[] / actionTypesReferenced[]`。
```

（若 §4 现有编号已用到 4.8，顺延到下一可用编号；与 §4.4 规则列表查询、§4.7 metric 影响面保持编号连续。）

- [ ] **Step 4: 从 backlog 主动推进序列移除 B7**

`docs/superpowers/plans/backlog.md`：删除"一、主动推进序列"表中 B7 行（序 1），把 B1 / B5 的序号上提为 1 / 2；并在文件合适处记录 B7 已落地（含批量能力）。第 6 行引导语"（B7 靠前）"按情况更新。

- [ ] **Step 5: 跑代码↔文档对齐审查**

调用 `rule-engine-reviewer` agent（只读），范围：本批次 `src/**` 改动 + `docs/08-evolution.md` / `docs/10-api-contract.md`。按其反馈修正文档措辞或补漏，不改代码行为。

- [ ] **Step 6: 提交**

```bash
git add docs/08-evolution.md docs/10-api-contract.md docs/superpowers/plans/backlog.md
git commit -m "docs: B7 规则批量导出/导入已实装，回写 §2.9 + api-contract + backlog"
```

---

## Self-Review（计划自检，已执行）

**1. Spec 覆盖（§2.9 逐条）：**
- 导出格式 JSON Bundle 自包含 → Task 1（DTO）+ Task 2（组装）。✅（实装多 `decisionDefinitions[]` + 批量 `rules[]`，已记录原因）
- 导入校验 Scene 兼容 / metric 安全 / 版本号重映射（按 code upsert）→ Task 3（Scene/metric/decision upsert + SQL 待审 + 按 code 而非 id 落库）。✅
- 幂等（重复导入=新建草稿，不覆盖已发布）→ Task 3 + Task 5 第二个测试。✅
- 权限 EXPORT / PUBLISH → 用户确认沿用 X-Actor-Id，留 TODO，已在 §2.9 已实装块与 controller Javadoc 注明。✅（显式 out-of-scope）
- 批量（用户追加需求）→ Bundle 多规则化 + 导出条件查询，贯穿 Task 1–5。✅
- 不做跨租户实时同步 / 与本地调试格式不打通 → 文档保留。✅

**2. Placeholder 扫描**：无 TBD / “add error handling” 类占位；每个 code step 含完整代码与可运行命令。✅

**3. 类型一致性**：
- `RuleBundle` 顶层 `rules()/scenes()/metricDefinitions()/decisionDefinitions()/actionTypeManifest()` + 4 个嵌套 record 字段，在 Task 1 定义，Task 2/3/4/5 引用一致。✅
- `RuleImportResult` 9 字段 + 嵌套 `ImportedRule`（`version()` 为 `Long`，断言用 `1L/2L/4L`）在 Task 1 定义，Task 3 返回与 Task 4/5 断言一致。✅
- Service 方法签名 `export(String, List<Long>, Long)`（导出选取入参用 sceneId）/ `importBundle(String, RuleBundle, String)` 在接口（Task 1）、impl（Task 4）、controller（Task 4）、测试一致。✅
- mapper 查询封装为各 Mapper 的 `default` 语义方法（Task 2 一次加齐）：`RuleDefinitionMapper.selectForExport/findBySceneAndCode`、`RuleVersionMapper.findActiveVersion`(+既有 maxVersion)、`SceneMapper.findByCode/findByIds`、`MetricDefinitionMapper.findByCodeAndVersion/findAnyByCode`、`DecisionDefinitionMapper.findByCode/findByCodes`；service 不直接拼 wrapper。`insert` 仍用 BaseMapper。Task 2/3 调用与 Task 5 一致。✅
- Jackson 一律 `tools.jackson.*`。单测 mock Mapper 语义方法（default 方法被 Mockito stub，方法体不执行），**不再需要 `TableInfoHelper.initTableInfo` 预热**；wrapper 拼装逻辑由 Task 5 Testcontainers 集成测试覆盖真库。✅

---

## 注意事项与已知边界

- **无 DDL**：复用 scene / metric_definition / decision_definition / rule_definition / rule_version / audit_log 既有表（B6 后 metric_dependencies 已是对象数组，Bundle 直接对接）。
- **导出仅 ACTIVE 版本**：每条规则只导当前 ACTIVE 版本；无 ACTIVE 版本者跳过。整租户导出不分页（开发阶段无生产数据，量大时另演进）。
- **导入草稿不可直接发布到已发布规则之上**：受现有 `PublishService.publish` 要求 rule_definition.status=DRAFT 限制；这是 D19 回滚流程的范畴，B7 不补。Bundle 主用例（迁移到 fresh 目标环境）规则不存在 → 干净新建，不触发此限制。
- **actionTypeManifest 不做硬校验**：v1 actionType 来自 SPI Bean 无 DB 白名单表，导入只在结果回显 `actionTypesReferenced` 供人工核对目标环境 SPI handler 是否注册。
- **JSON 列以转义字符串嵌入 Bundle**：无损、实现简单；若将来要更可读的内嵌结构，可改为 `JsonNode` 字段（非 v1 必要）。
- **不兼容 SDK 本地调试格式（已决策，2026-06-06）**：B7 Bundle 专做跨环境 DB 迁移（多规则对象 + 自包含依赖定义 + 转义 JSON 列，导入后落 DRAFT 待 publish）；本地 / 离线调试是另一职责，已由 `GET /api/v1/sdk/snapshots`（输出 `RuleVersionSnapshot` 数组、AST 为嵌套对象、可直接 `client.ruleFile(...)` 评估，见 10-api-contract §8.3）覆盖。两者格式与用途正交，**不打通**——合并会让单端点返回类型分叉、语义冲突（自包含 vs 运行时可评估）。需要本地调试格式时用 sdk/snapshots，不要扩 B7。

---

## Execution Handoff

计划已保存到 `docs/superpowers/plans/2026-06-06-b7-rule-export-import.md`。两种执行方式：

1. **Subagent-Driven（推荐）** — 每个 Task 派新 subagent 实现，Task 间两段式 review，迭代快。
2. **Inline Execution** — 本会话内按 executing-plans 批量执行 + 检查点。

选哪种？
