# 规则↔指标血缘（B33）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 补齐 Decision→规则反向血缘 + 批量计数端点（后端），并以 B31 治理范式统一 Decision/Metric 血缘呈现、补 Decision 详情页、列表徽标、编辑器反向提示、停用前影响拦截（前端）。

**Architecture:** 后端沿用 `MetricWriteService.findReferencingRules` 的按需扫 DB 房规（强一致、零索引/事件），镜像补反向；前端复用 AntD 5 + B31「抽屉/卡片 + 可点定位」范式。详见 `docs/superpowers/specs/2026-06-18-rule-metric-lineage-design.md`。

**Tech Stack:** Java 25 / Spring Boot 4 / MyBatis-Plus / JUnit5 + Mockito；React 18 + TS + AntD 5 + Zustand + react-router 6 + i18next。

**测试环境（每次跑后端测试前 export）：**
```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-25.jdk/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH
MVN=/Users/sunke/.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9/bin/mvn
```

---

# 后端

## Task 1: `DecisionService` 接口 + 共享 `UsageCount` record

**Files:**
- Create: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/service/UsageCount.java`
- Modify: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/service/DecisionService.java`

- [ ] **Step 1: 新建共享 `UsageCount` record**

```java
package com.sstlfsj.rule.config.api.service;

/** 资源（decision/metric）code → 被 ACTIVE 规则引用计数。供列表徽标批量计数复用。 */
public record UsageCount(String code, int count) {}
```

- [ ] **Step 2: `DecisionService` 接口追加 3 方法 + 内嵌 RuleRef（在 `list` 后、接口右括号前）**

```java
    /**
     * 产出某 decision 的所有 ACTIVE 规则（下线前影响面 / Decision 覆盖来源）。
     * 口径同 MetricWriteService#findReferencingRules：按 rv.status=ACTIVE 收集，不按 rule_definition.status 过滤。
     *
     * @param tenantId     租户 id
     * @param decisionCode decision 编码
     * @return 产出该 decision 的规则引用项；无引用返回空列表
     */
    List<RuleRef> findRulesProducingDecision(Long tenantId, String decisionCode);

    /**
     * 按 tenantId+code 取单个 decision（详情页加载）。
     *
     * @param tenantId 租户 id
     * @param code     decision 编码
     * @return decision 定义
     * @throws IllegalArgumentException decision 不存在时抛出
     */
    DecisionDefinition get(Long tenantId, String code);

    /**
     * 一次扫聚合 tenant 下每个 decisionCode 的 ACTIVE 规则产出计数（列表徽标）。
     *
     * @param tenantId 租户 id
     * @return 每个 decisionCode 的引用计数（无引用的 decision 不出现在结果里）
     */
    List<UsageCount> countRuleUsages(Long tenantId);

    /** 产出某 decision 的规则引用项。sceneCode 由 rule_definition.scene_id 关联；status 为 rule_definition.status。 */
    record RuleRef(Long ruleDefinitionId, String ruleCode, String ruleName,
                   String sceneCode, String status) {}
```

> `java.util.List` 已 import；`DecisionDefinition` 已 import。

- [ ] **Step 3: Commit**

```bash
git add rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/service/UsageCount.java \
        rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/service/DecisionService.java
git commit -m "feat(config): DecisionService 声明 反向血缘/get/批量计数 + 共享 UsageCount"
```

---

## Task 2: `DecisionServiceImpl` 实现（含 Mockito 单测）

**Files:**
- Modify: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/service/DecisionServiceImpl.java`
- Create: `rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/service/DecisionServiceImplTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.sstlfsj.rule.config.internal.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sstlfsj.rule.config.api.service.DecisionService.RuleRef;
import com.sstlfsj.rule.config.api.service.UsageCount;
import com.sstlfsj.rule.config.internal.domain.DecisionDefinition;
import com.sstlfsj.rule.config.internal.domain.RuleDefinition;
import com.sstlfsj.rule.config.internal.domain.RuleDefinitionStatus;
import com.sstlfsj.rule.config.internal.domain.RuleVersion;
import com.sstlfsj.rule.config.internal.domain.RuleVersionStatus;
import com.sstlfsj.rule.config.internal.domain.SceneDef;
import com.sstlfsj.rule.config.internal.repository.DecisionDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.RuleDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.RuleVersionMapper;
import com.sstlfsj.rule.config.internal.repository.SceneMapper;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot.DecisionBinding;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** DecisionServiceImpl 反向血缘/get/批量计数单元测试（mock mapper，不依赖 Spring 容器）。 */
@ExtendWith(MockitoExtension.class)
class DecisionServiceImplTest {

    @BeforeAll
    static void initMybatisPlusTableInfo() {
        MybatisConfiguration cfg = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(cfg, "");
        TableInfoHelper.initTableInfo(assistant, DecisionDefinition.class);
        TableInfoHelper.initTableInfo(assistant, RuleDefinition.class);
        TableInfoHelper.initTableInfo(assistant, RuleVersion.class);
        TableInfoHelper.initTableInfo(assistant, SceneDef.class);
    }

    @Mock DecisionDefinitionMapper mapper;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock RuleDefinitionMapper ruleDefinitionMapper;
    @Mock RuleVersionMapper ruleVersionMapper;
    @Mock SceneMapper sceneMapper;
    @InjectMocks DecisionServiceImpl sut;

    private static final Long TENANT = 1L;

    @Test
    void findRulesProducingDecision_returnsOnlyProducingRules() {
        RuleDefinition rd1 = ruleDefinition(101L, "risk.transfer", "转账风控", 10L, "PUBLISHED");
        RuleDefinition rd2 = ruleDefinition(102L, "risk.login", "登录风控", 11L, "DISABLED");
        RuleVersion rv1 = ruleVersion(1001L, 101L, List.of(new DecisionBinding("REJECT", 10)));
        RuleVersion rv2 = ruleVersion(1002L, 101L, List.of(new DecisionBinding("PASS", 5)));
        RuleVersion rv3 = ruleVersion(1003L, 102L, List.of(new DecisionBinding("REJECT", 8)));
        RuleVersion rv4 = ruleVersion(1004L, 102L, List.of());

        when(ruleDefinitionMapper.findByTenant(any())).thenReturn(List.of(rd1, rd2));
        when(ruleVersionMapper.findActiveByRuleDefIds(any())).thenReturn(List.of(rv1, rv2, rv3, rv4));
        when(sceneMapper.findByIds(any())).thenReturn(List.of(scene(10L, "risk.transfer"), scene(11L, "risk.login")));

        List<RuleRef> result = sut.findRulesProducingDecision(TENANT, "REJECT");

        assertThat(result).hasSize(2);
        assertThat(result).extracting(RuleRef::ruleDefinitionId).containsExactlyInAnyOrder(101L, 102L);
        assertThat(result).anySatisfy(r -> {
            assertThat(r.ruleCode()).isEqualTo("risk.transfer");
            assertThat(r.sceneCode()).isEqualTo("risk.transfer");
            assertThat(r.status()).isEqualTo("PUBLISHED");
        });
        assertThat(result).anySatisfy(r -> assertThat(r.status()).isEqualTo("DISABLED")); // rv.status=ACTIVE 口径
    }

    @Test
    void findRulesProducingDecision_noRules_returnsEmpty() {
        when(ruleDefinitionMapper.findByTenant(any())).thenReturn(List.of());
        assertThat(sut.findRulesProducingDecision(TENANT, "REJECT")).isEmpty();
        verifyNoInteractions(ruleVersionMapper);
    }

    @Test
    void countRuleUsages_aggregatesPerDecisionCode_dedupPerRule() {
        RuleDefinition rd = ruleDefinition(101L, "risk.transfer", "转账", 10L, "PUBLISHED");
        // rv1 产出 REJECT；rv2 产出 REJECT+REVIEW；rv3 同一规则版本里 REJECT 重复（应只计一次）
        RuleVersion rv1 = ruleVersion(1001L, 101L, List.of(new DecisionBinding("REJECT", 10)));
        RuleVersion rv2 = ruleVersion(1002L, 101L, List.of(new DecisionBinding("REJECT", 9), new DecisionBinding("REVIEW", 3)));
        RuleVersion rv3 = ruleVersion(1003L, 101L, List.of(new DecisionBinding("REJECT", 8), new DecisionBinding("REJECT", 7)));

        when(ruleDefinitionMapper.findByTenant(any())).thenReturn(List.of(rd));
        when(ruleVersionMapper.findActiveByRuleDefIds(any())).thenReturn(List.of(rv1, rv2, rv3));

        List<UsageCount> counts = sut.countRuleUsages(TENANT);

        assertThat(counts).anySatisfy(c -> { assertThat(c.code()).isEqualTo("REJECT"); assertThat(c.count()).isEqualTo(3); });
        assertThat(counts).anySatisfy(c -> { assertThat(c.code()).isEqualTo("REVIEW"); assertThat(c.count()).isEqualTo(1); });
    }

    @Test
    void get_existing_returnsIt_missing_throws() {
        DecisionDefinition d = new DecisionDefinition();
        d.setCode("REJECT");
        when(mapper.findByCode(TENANT, "REJECT")).thenReturn(d);
        when(mapper.findByCode(TENANT, "NOPE")).thenReturn(null);

        assertThat(sut.get(TENANT, "REJECT").getCode()).isEqualTo("REJECT");
        assertThatThrownBy(() -> sut.get(TENANT, "NOPE")).isInstanceOf(IllegalArgumentException.class);
    }

    private RuleDefinition ruleDefinition(Long id, String code, String name, Long sceneId, String status) {
        RuleDefinition rd = new RuleDefinition();
        rd.setId(id); rd.setTenantId(TENANT); rd.setCode(code); rd.setName(name);
        rd.setSceneId(sceneId); rd.setStatus(RuleDefinitionStatus.valueOf(status));
        return rd;
    }
    private SceneDef scene(Long id, String code) { SceneDef s = new SceneDef(); s.setId(id); s.setCode(code); return s; }
    private RuleVersion ruleVersion(Long id, Long ruleDefinitionId, List<DecisionBinding> bindings) {
        RuleVersion rv = new RuleVersion();
        rv.setId(id); rv.setRuleDefinitionId(ruleDefinitionId);
        rv.setStatus(RuleVersionStatus.ACTIVE); rv.setDecisionBindings(bindings);
        return rv;
    }
}
```

- [ ] **Step 2: 跑确认失败**

```bash
$MVN -pl rule-config-svc -am test -Dtest='DecisionServiceImplTest' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 编译/测试失败（方法未实现）。

- [ ] **Step 3: 实现**

3a. 字段区扩展：

```java
    private final DecisionDefinitionMapper mapper;
    private final ApplicationEventPublisher eventPublisher;
    private final RuleDefinitionMapper ruleDefinitionMapper;
    private final RuleVersionMapper ruleVersionMapper;
    private final SceneMapper sceneMapper;
```

3b. 在 `list` 方法后新增三方法 + 私有谓词；`requireDecision` 私有方法若已存在则复用，否则 `get` 直接用 `mapper.findByCode` + 校验：

```java
    @Override
    @Transactional(readOnly = true)
    public DecisionDefinition get(Long tenantId, String code) {
        DecisionDefinition d = mapper.findByCode(tenantId, code);
        if (d == null) {
            throw new IllegalArgumentException("decision 不存在: code=" + code);
        }
        return d;
    }

    @Override
    @Transactional(readOnly = true)
    public List<RuleRef> findRulesProducingDecision(Long tenantId, String decisionCode) {
        List<RuleDefinition> defs = ruleDefinitionMapper.findByTenant(tenantId);
        if (defs.isEmpty()) {
            return List.of();
        }
        Map<Long, RuleDefinition> defMap = defs.stream()
                .collect(Collectors.toMap(RuleDefinition::getId, d -> d));
        Map<Long, String> sceneCodeMap = sceneCodeMap(defs);
        List<RuleVersion> activeVersions = ruleVersionMapper.findActiveByRuleDefIds(defMap.keySet());

        List<RuleRef> result = new ArrayList<>();
        for (RuleVersion rv : activeVersions) {
            if (containsDecision(rv.getDecisionBindings(), decisionCode)) {
                RuleDefinition def = defMap.get(rv.getRuleDefinitionId());
                result.add(new RuleRef(def.getId(), def.getCode(), def.getName(),
                        sceneCodeMap.getOrDefault(def.getSceneId(), ""), def.getStatus().name()));
            }
        }
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public List<UsageCount> countRuleUsages(Long tenantId) {
        List<RuleDefinition> defs = ruleDefinitionMapper.findByTenant(tenantId);
        if (defs.isEmpty()) {
            return List.of();
        }
        Set<Long> defIds = defs.stream().map(RuleDefinition::getId).collect(Collectors.toSet());
        List<RuleVersion> activeVersions = ruleVersionMapper.findActiveByRuleDefIds(defIds);
        Map<String, Integer> counts = new HashMap<>();
        for (RuleVersion rv : activeVersions) {
            List<DecisionBinding> bindings = rv.getDecisionBindings();
            if (bindings == null) continue;
            // 同一规则版本对同一 decisionCode 多次绑定只计一次
            bindings.stream().map(DecisionBinding::decisionCode).distinct()
                    .forEach(code -> counts.merge(code, 1, Integer::sum));
        }
        return counts.entrySet().stream().map(e -> new UsageCount(e.getKey(), e.getValue())).toList();
    }

    /** 批量查 scene，建 sceneId → sceneCode 索引。 */
    private Map<Long, String> sceneCodeMap(List<RuleDefinition> defs) {
        Set<Long> sceneIds = defs.stream().map(RuleDefinition::getSceneId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        return sceneIds.isEmpty() ? Map.of() :
                sceneMapper.findByIds(sceneIds).stream()
                        .collect(Collectors.toMap(SceneDef::getId, SceneDef::getCode));
    }

    /** 判断 typed decisionBindings 是否含指定 decisionCode；null/空视为不含。 */
    private boolean containsDecision(List<DecisionBinding> bindings, String decisionCode) {
        if (bindings == null || bindings.isEmpty()) {
            return false;
        }
        return bindings.stream().anyMatch(b -> decisionCode.equals(b.decisionCode()));
    }
```

3c. 补 import：

```java
import com.sstlfsj.rule.config.api.service.DecisionService.RuleRef;
import com.sstlfsj.rule.config.api.service.UsageCount;
import com.sstlfsj.rule.config.internal.domain.RuleDefinition;
import com.sstlfsj.rule.config.internal.domain.RuleVersion;
import com.sstlfsj.rule.config.internal.domain.SceneDef;
import com.sstlfsj.rule.config.internal.repository.RuleDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.RuleVersionMapper;
import com.sstlfsj.rule.config.internal.repository.SceneMapper;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot.DecisionBinding;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
```

- [ ] **Step 4: 跑确认通过 + config-svc 全量**

```bash
$MVN -pl rule-config-svc -am test -Dtest='DecisionServiceImplTest' -Dsurefire.failIfNoSpecifiedTests=false
$MVN -pl rule-config-svc -am test
```
Expected: PASS / BUILD SUCCESS。

- [ ] **Step 5: Commit**

```bash
git add rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/service/DecisionServiceImpl.java \
        rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/service/DecisionServiceImplTest.java
git commit -m "feat(config): DecisionServiceImpl 实现 反向血缘/get/批量计数"
```

---

## Task 3: `MetricWriteService.countRuleUsages`（含单测）

**Files:**
- Modify: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/service/MetricWriteService.java`
- Modify: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/service/MetricWriteServiceImpl.java`
- Modify: `rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/service/MetricWriteServiceImplTest.java`

- [ ] **Step 1: 接口加方法**（在 `findReferencingRules` 后）

```java
    /**
     * 一次扫聚合 tenant 下每个 metricCode 的 ACTIVE 规则引用计数（版本无关，列表徽标用）。
     *
     * @param tenantId 租户 id
     * @return 每个 metricCode 的引用计数
     */
    List<UsageCount> countRuleUsages(Long tenantId);
```
（`UsageCount` import：`import com.sstlfsj.rule.config.api.service.UsageCount;`）

- [ ] **Step 2: 测试加用例**（`MetricWriteServiceImplTest`，import `UsageCount`）

```java
    @Test
    void countRuleUsages_aggregatesPerMetricCode_dedupPerRule() {
        RuleDefinition rd = ruleDefinition(101L, "risk.transfer", "转账", 10L, "PUBLISHED");
        // rv1 引用 account.age v1；rv2 引用 account.age v2 + user.level v1；
        // 同一规则跨版本引用同 metric → 计数按规则去重（account.age 计 2，不是 3）
        RuleVersion rv1 = ruleVersion(1001L, 101L, List.of(new MetricDependency("account.age", 1)));
        RuleVersion rv2 = ruleVersion(1002L, 101L,
                List.of(new MetricDependency("account.age", 2), new MetricDependency("user.level", 1)));
        when(ruleDefinitionMapper.findByTenant(any())).thenReturn(List.of(rd));
        when(ruleVersionMapper.findActiveByRuleDefIds(any())).thenReturn(List.of(rv1, rv2));

        List<UsageCount> counts = sut.countRuleUsages(1L);

        assertThat(counts).anySatisfy(c -> { assertThat(c.code()).isEqualTo("account.age"); assertThat(c.count()).isEqualTo(2); });
        assertThat(counts).anySatisfy(c -> { assertThat(c.code()).isEqualTo("user.level"); assertThat(c.count()).isEqualTo(1); });
    }
```

- [ ] **Step 3: 跑确认失败**

```bash
$MVN -pl rule-config-svc -am test -Dtest='MetricWriteServiceImplTest#countRuleUsages_aggregatesPerMetricCode_dedupPerRule' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 编译失败（方法未实现）。

- [ ] **Step 4: 实现**（`MetricWriteServiceImpl`，在 `findReferencingRules` 后；复用既有 import，补 `UsageCount`/`HashMap`）

```java
    @Override
    @Transactional(readOnly = true)
    public List<UsageCount> countRuleUsages(Long tenantId) {
        List<RuleDefinition> defs = ruleDefinitionMapper.findByTenant(tenantId);
        if (defs.isEmpty()) {
            return List.of();
        }
        Set<Long> defIds = defs.stream().map(RuleDefinition::getId).collect(Collectors.toSet());
        List<RuleVersion> activeVersions = ruleVersionMapper.findActiveByRuleDefIds(defIds);
        Map<String, Integer> counts = new java.util.HashMap<>();
        for (RuleVersion rv : activeVersions) {
            List<MetricDependency> deps = rv.getMetricDependencies();
            if (deps == null) continue;
            // 版本无关 + 同一规则对同一 metricCode 去重
            deps.stream().map(MetricDependency::metricCode).distinct()
                    .forEach(code -> counts.merge(code, 1, Integer::sum));
        }
        return counts.entrySet().stream().map(e -> new UsageCount(e.getKey(), e.getValue())).toList();
    }
```

- [ ] **Step 5: 跑确认通过 + 全量**

```bash
$MVN -pl rule-config-svc -am test
```
Expected: BUILD SUCCESS。

- [ ] **Step 6: Commit**

```bash
git add rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/service/MetricWriteService.java \
        rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/service/MetricWriteServiceImpl.java \
        rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/service/MetricWriteServiceImplTest.java
git commit -m "feat(config): MetricWriteService.countRuleUsages 批量计数(版本无关)"
```

---

## Task 4: `DecisionController` 三端点（含委托测试）

**Files:**
- Modify: `rule-api/src/main/java/com/sstlfsj/rule/web/admin/DecisionController.java`
- Modify: `rule-api/src/test/java/com/sstlfsj/rule/web/admin/DecisionControllerTest.java`

- [ ] **Step 1: 测试加用例**

```java
    @Test
    void sources_delegatesToService() {
        var refs = List.of(new com.sstlfsj.rule.config.api.service.DecisionService.RuleRef(
                101L, "risk.transfer", "转账", "risk.transfer", "PUBLISHED"));
        when(service.findRulesProducingDecision(9001L, "REJECT")).thenReturn(refs);
        var resp = controller.sources("REJECT", 9001L);
        assertThat(resp.data().decisionCode()).isEqualTo("REJECT");
        assertThat(resp.data().sourceCount()).isEqualTo(1);
        verify(service).findRulesProducingDecision(9001L, "REJECT");
    }

    @Test
    void get_delegatesToService() {
        var d = new DecisionDefinition(); d.setCode("REJECT");
        when(service.get(9001L, "REJECT")).thenReturn(d);
        assertThat(controller.get("REJECT", 9001L).data().getCode()).isEqualTo("REJECT");
    }

    @Test
    void usageCounts_delegatesToService() {
        when(service.countRuleUsages(9001L)).thenReturn(
                List.of(new com.sstlfsj.rule.config.api.service.UsageCount("REJECT", 3)));
        var resp = controller.usageCounts(9001L);
        assertThat(resp.data()).hasSize(1);
        assertThat(resp.data().get(0).count()).isEqualTo(3);
    }
```

- [ ] **Step 2: 跑确认失败**

```bash
$MVN -pl rule-api -am test -Dtest='DecisionControllerTest' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 编译失败。

- [ ] **Step 3: Controller 加三端点 + DTO**（在 `list` 后）

```java
    /** GET /admin/v1/decisions/{code} — 单个 decision（详情页加载）。 */
    @GetMapping("/{code}")
    public ApiResponse<DecisionDefinition> get(@PathVariable String code, @RequestParam Long tenantId) {
        return ApiResponse.ok(decisionService.get(tenantId, code));
    }

    /** GET /admin/v1/decisions/{code}/sources — 产出该 decision 的 ACTIVE 规则（兼作下线影响预检）。 */
    @GetMapping("/{code}/sources")
    public ApiResponse<DecisionSourcesResponse> sources(@PathVariable String code, @RequestParam Long tenantId) {
        List<DecisionService.RuleRef> rules = decisionService.findRulesProducingDecision(tenantId, code);
        return ApiResponse.ok(new DecisionSourcesResponse(code, rules, rules.size()));
    }

    /** GET /admin/v1/decisions/usage-counts — tenant 下每个 decision 的被引用计数（列表徽标）。 */
    @GetMapping("/usage-counts")
    public ApiResponse<List<UsageCount>> usageCounts(@RequestParam Long tenantId) {
        return ApiResponse.ok(decisionService.countRuleUsages(tenantId));
    }

    /** Decision 产出来源响应（兼作下线影响面）。 */
    public record DecisionSourcesResponse(String decisionCode,
                                          List<DecisionService.RuleRef> sources, int sourceCount) {}
```

import 补：`import com.sstlfsj.rule.config.api.service.DecisionService;`（若当前只 import 了实现/方法）、`import com.sstlfsj.rule.config.api.service.UsageCount;`、`import java.util.List;`，注解 `GetMapping/PathVariable/RequestParam`（若非通配 import）。

> **路由顺序注意**：`/{code}` 与 `/usage-counts` 都匹配 `/decisions/xxx`。Spring 默认精确路径优先于变量路径，`usage-counts` 不会被 `/{code}` 吞（精确段优先）；保留两者即可，无需调整顺序。

- [ ] **Step 4: 跑确认通过 + rule-api 全量**

```bash
$MVN -pl rule-api -am test
```
Expected: BUILD SUCCESS。

- [ ] **Step 5: Commit**

```bash
git add rule-api/src/main/java/com/sstlfsj/rule/web/admin/DecisionController.java \
        rule-api/src/test/java/com/sstlfsj/rule/web/admin/DecisionControllerTest.java
git commit -m "feat(api): DecisionController +/sources +/{code} +/usage-counts"
```

---

## Task 5: `MetricController` `/usage-counts`（含测试）

**Files:**
- Modify: `rule-api/src/main/java/com/sstlfsj/rule/web/admin/MetricController.java`
- Modify: `rule-api/src/test/java/com/sstlfsj/rule/web/admin/MetricControllerTest.java`

- [ ] **Step 1: 测试加用例**（MockMvc 风格，对齐既有 impact 用例）

```java
    @Test
    void usageCounts_returns200() throws Exception {
        when(service.countRuleUsages(1L)).thenReturn(
                List.of(new com.sstlfsj.rule.config.api.service.UsageCount("account.age", 5)));
        mockMvc.perform(get("/admin/v1/metrics/usage-counts").param("tenantId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].code").value("account.age"))
                .andExpect(jsonPath("$.data[0].count").value(5));
    }
```

- [ ] **Step 2: 跑确认失败**

```bash
$MVN -pl rule-api -am test -Dtest='MetricControllerTest#usageCounts_returns200' -Dsurefire.failIfNoSpecifiedTests=false
```
Expected: 失败（端点不存在）。

- [ ] **Step 3: Controller 加端点**

```java
    /** GET /admin/v1/metrics/usage-counts — tenant 下每个 metric 的被引用计数（列表徽标，版本无关）。 */
    @GetMapping("/usage-counts")
    public ApiResponse<List<com.sstlfsj.rule.config.api.service.UsageCount>> usageCounts(@RequestParam Long tenantId) {
        return ApiResponse.ok(service.countRuleUsages(tenantId));
    }
```
> `/usage-counts` 与既有 `/{metricCode}` 精确段优先，不冲突。

- [ ] **Step 4: 跑确认通过 + 全量**

```bash
$MVN -pl rule-api -am test
```
Expected: BUILD SUCCESS。

- [ ] **Step 5: Commit**

```bash
git add rule-api/src/main/java/com/sstlfsj/rule/web/admin/MetricController.java \
        rule-api/src/test/java/com/sstlfsj/rule/web/admin/MetricControllerTest.java
git commit -m "feat(api): MetricController +/usage-counts 批量计数"
```

---

# 前端

> 范式参照（实现前先读）：`rule-editor/RuleAnalysisDrawer.tsx`（抽屉+卡片）、`pages/metric-detail/index.tsx`（详情页 Descriptions+Tabs+impact）、`config/columns/{decision,metric}.tsx`（列）、`api/{decision,metric}.ts` + `constants/api-endpoints.ts`（API 范式）、`i18n/types.ts`（强类型 key）。

## Task 6: 前端 API client + 端点常量 + i18n 类型/文案

**Files:**
- Modify: `src/constants/api-endpoints.ts`、`src/api/decision.ts`、`src/api/metric.ts`
- Create: `src/i18n/locales/zh-CN/lineage.ts`、`src/i18n/locales/en/lineage.ts`；Modify `src/i18n/types.ts`、`src/i18n/locales/{zh-CN,en}/index.ts`

- [ ] **Step 1: 端点常量**（`api-endpoints.ts` 的 `ENDPOINTS` 内，对齐既有函数式写法）

```ts
  DECISION_GET: (code: string) => `${ADMIN}/decisions/${code}`,
  DECISION_SOURCES: (code: string) => `${ADMIN}/decisions/${code}/sources`,
  DECISION_USAGE_COUNTS: `${ADMIN}/decisions/usage-counts`,
  METRIC_USAGE_COUNTS: `${ADMIN}/metrics/usage-counts`,
```

- [ ] **Step 2: 类型 + API 函数**（`api/decision.ts`）

```ts
export interface LineageRuleRef {
  ruleDefinitionId: number; ruleCode: string; ruleName: string; sceneCode: string; status: string;
}
export interface DecisionSources { decisionCode: string; sources: LineageRuleRef[]; sourceCount: number; }
export interface UsageCount { code: string; count: number; }

export const getDecision = (tenantId: number, code: string) =>
  client.get(ENDPOINTS.DECISION_GET(code), { params: { tenantId } }).then(r => r.data.data);
export const getDecisionSources = (tenantId: number, code: string): Promise<DecisionSources> =>
  client.get(ENDPOINTS.DECISION_SOURCES(code), { params: { tenantId } }).then(r => r.data.data);
export const getDecisionUsageCounts = (tenantId: number): Promise<UsageCount[]> =>
  client.get(ENDPOINTS.DECISION_USAGE_COUNTS, { params: { tenantId } }).then(r => r.data.data);
```
（`api/metric.ts` 加 `getMetricUsageCounts(tenantId)` 同形，命中 `METRIC_USAGE_COUNTS`；复用 `UsageCount`/`LineageRuleRef` 从 decision.ts import 或抽到 `api/types.ts`，实现期定。）

- [ ] **Step 3: i18n**

新建 `lineage.ts`（zh-CN）：

```ts
const lineage = {
  drawerTitle: '产出 {{code}} 的规则',
  metricDrawerTitle: '引用 {{code}} 的规则',
  count: '共 {{n}} 条',
  empty: '暂无规则引用，可安全停用 / 下线',
  toEditor: '进编辑器',
  badge: '{{n}} 条',
  badgeZero: '0 条',
  col: { ruleCode: '规则', ruleName: '名称', scene: '场景', status: '状态' },
  editorChip: '还被 {{n}} 条引用',
  disableGuardTitle: '该 Decision 仍被以下规则产出',
  disableGuardConfirm: '仍要停用？',
};
export default lineage;
```
en 版同 key 英文；在 `i18n/types.ts` 加 `lineage` 命名空间类型；`locales/{zh-CN,en}/index.ts` 注册 `lineage`。

- [ ] **Step 4: 构建验证 + Commit**

```bash
cd frontend && npm run build
```
Expected: 类型通过。
```bash
git add frontend/src && git commit -m "feat(frontend): 血缘 API client + 端点 + i18n"
```

---

## Task 7: 共享血缘组件（抽屉 + 表格 + hook）

**Files:**
- Create: `src/components/lineage/useLineage.ts`、`LineageTable.tsx`、`LineageDrawer.tsx`

- [ ] **Step 1: 数据 hook `useLineage.ts`**

```tsx
import { useState, useCallback } from 'react';
import { getDecisionSources, LineageRuleRef } from '../../api/decision';
// metric 复用同 hook：传不同 fetcher
export type LineageFetcher = (tenantId: number, code: string) => Promise<{ sources: LineageRuleRef[] }>;

export function useLineage(fetcher: LineageFetcher) {
  const [loading, setLoading] = useState(false);
  const [rows, setRows] = useState<LineageRuleRef[]>([]);
  const load = useCallback(async (tenantId: number, code: string) => {
    setLoading(true);
    try { setRows((await fetcher(tenantId, code)).sources ?? []); }
    finally { setLoading(false); }
  }, [fetcher]);
  return { loading, rows, load, reset: () => setRows([]) };
}
```

- [ ] **Step 2: `LineageTable.tsx`**（详情页 Tab 用，全宽可筛表格 + 行下钻）

按 AntD `Table` 写：列 ruleCode（mono，可点）/ ruleName / sceneCode / status（Tag，PUBLISHED 绿/DISABLED 灰，复用 `constants/enums.ts` 的 `colorOf`）；`onRow` 点击 `navigate('/rule-editor/' + ruleDefinitionId)`（确认编辑器路由参数是 ruleId；若编辑器按 ruleCode 进则用 ruleCode）；空态 `Empty` 用 `t('lineage.empty')`；`loading` 接 hook。提供 scene/status 列筛选（AntD 列 `filters`）。

- [ ] **Step 3: `LineageDrawer.tsx`**（列表/编辑器徽标用，卡片版）

按 AntD `Drawer`（width 460）写，镜像 `RuleAnalysisDrawer.tsx`：标题 `t('lineage.drawerTitle',{code})` + 计数；body 按 `sceneCode` 分组，每组标题 = scene + 计数，组内每条是带左色条小卡（mono ruleCode + 状态 Tag + ruleName + “进编辑器 ›”），点击 `navigate` 下钻并关抽屉；空态/加载态同 Table。props：`{ open, code, title, fetcher, tenantId, onClose }`，内部用 `useLineage`，`open && code` 时 `load`。

- [ ] **Step 4: 构建验证 + Commit**

```bash
cd frontend && npm run build
git add frontend/src/components/lineage && git commit -m "feat(frontend): 共享血缘组件(抽屉/表格/hook)"
```

---

## Task 8: Decision 列表徽标 + 详情页 + 停用拦截

**Files:**
- Modify: `src/constants/routes.ts`、`src/router.tsx`、`src/config/columns/decision.tsx`、`src/pages/decision-list/index.tsx`
- Create: `src/pages/decision-detail/index.tsx`

- [ ] **Step 1: 新路由** `DECISION_DETAIL = '/decisions/:code'`（`routes.ts`）+ `router.tsx` lazy 装配 `decision-detail`。

- [ ] **Step 2: decision-list 徽标列 + 行为**
  - 进页拉 `getDecisionUsageCounts(tenantId)` 建 `code→count` map（Zustand 或本地 state）。
  - `columns/decision.tsx` 加「被引用」列：渲染徽标 `t('lineage.badge',{n})`（count>0 蓝、=0 灰），`onClick`（`stopPropagation`）打开 `LineageDrawer`（fetcher=getDecisionSources）。
  - 行点击（`onRow`）从「打开编辑 Modal」改为 `navigate(DECISION_DETAIL)`。**创建**仍保留列表 Modal。

- [ ] **Step 3: decision-detail 页**（镜像 metric-detail）
  - `getDecision` 加载头部 + `Descriptions`（name/priority/description 内联编辑，调 `updateDecision`）。
  - `Tabs`：基本信息 / 被引用规则（懒加载 `LineageTable` fetcher=getDecisionSources）。
  - 停用按钮：点击先 `getDecisionSources`，若 `sourceCount>0` 弹 `Modal.confirm`（标题 `t('lineage.disableGuardTitle')`，内容列规则，`okText=t('lineage.disableGuardConfirm')`）确认后才 `updateDecision(status=DISABLED)`；=0 直接停用。

- [ ] **Step 4: 构建 + Commit**

```bash
cd frontend && npm run build
git add frontend/src && git commit -m "feat(frontend): Decision 列表徽标+详情页+停用血缘拦截"
```

---

## Task 9: Metric 列表徽标 + impact Tab 升级

**Files:**
- Modify: `src/config/columns/metric.tsx`、`src/pages/metric-list/index.tsx`、`src/pages/metric-detail/index.tsx`

- [ ] **Step 1: metric-list 徽标**：进页拉 `getMetricUsageCounts`，`columns/metric.tsx` 加「被引用」列（同 decision 范式），点徽标开 `LineageDrawer`（title=`lineage.metricDrawerTitle`，fetcher=按 metricCode 的 sources——注：metric 单 metric 血缘走既有 `getMetricImpact(code, version)`，抽屉用当前 ACTIVE 版本；适配成 `{sources}` 形状传给 hook）。

- [ ] **Step 2: metric-detail impact Tab 升级**：去掉「手动查询」按钮改为进 Tab 自动加载；把平铺表换成 `LineageTable`（行可下钻）；保留版本选择，切版本重查。

- [ ] **Step 3: 构建 + Commit**

```bash
cd frontend && npm run build
git add frontend/src && git commit -m "feat(frontend): Metric 列表徽标+impact Tab 升级(自动加载/下钻)"
```

---

## Task 10: 规则编辑器反向提示徽标

**Files:**
- Modify: `src/pages/rule-editor/ConditionCard.tsx`、`DecisionBindingEditor.tsx`、`src/pages/rule-editor/index.tsx`（或就近 store）

- [ ] **Step 1: 编辑器加载时取计数**：在编辑器编排处拉一次 `getMetricUsageCounts` + `getDecisionUsageCounts`，存 `metricCount`/`decisionCount` map（props 下传或 Zustand）。

- [ ] **Step 2: 徽标**：`ConditionCard.tsx` 在 metric `Select` 旁、`DecisionBindingEditor.tsx` 在 decision `Select` 旁，渲染 `t('lineage.editorChip',{n})` 小徽标（count 来自 map），点击打开 `LineageDrawer`（metric 用 metricDrawerTitle+impact fetcher、decision 用 drawerTitle+getDecisionSources）。

- [ ] **Step 3: 构建 + Commit**

```bash
cd frontend && npm run build
git add frontend/src && git commit -m "feat(frontend): 规则编辑器 metric/decision 反向血缘徽标"
```

---

# 文档 + 端到端

## Task 11: `08-evolution.md` §2.28 重写为已实装

**Files:** Modify `docs/08-evolution.md`（§2.28，约 371–381 行）

- [ ] **Step 1: 整段重写**为「已实装」块（删原「演进方向：LineageIndex」「接口衔接面」等废弃段，不留残渣）。要点：
  - metric→规则早随 B6 实装（`findReferencingRules` + `/metrics/{code}/versions/{version}/impact`）；2026-06-18 补批量计数 `/metrics/usage-counts`。
  - Decision→规则反向（2026-06-18）：`findRulesProducingDecision` + `/decisions/{code}/sources`、`/decisions/{code}`、`/decisions/usage-counts`；停用前影响拦截。
  - 前端：B31 治理范式统一 Decision/Metric 血缘呈现（抽屉/卡片 + 详情页表格 + 列表徽标 + 编辑器徽标），补 Decision 详情页。
  - **与原计划偏差**：放弃常驻 `LineageIndex` + D17 热更——核实发现 metric 侧按需扫已存在，常驻索引属 over-engineering 且双轨；改为沿用按需扫、零索引、零 DDL。
  - 已知缺口：仅认 `decision_bindings` / `metric_dependencies`（发布期冻结的结构化引用）。

- [ ] **Step 2: Commit**

```bash
git add docs/08-evolution.md
git commit -m "docs(evo): §2.28 改写为已实装(metric既有+Decision补齐+前端统一),弃 LineageIndex"
```

---

## Task 12: 功能端到端验证（真实服务）

**Files:** 无（验证 + 清理）

- [ ] **Step 1: 全量回归 + 打包**

```bash
$MVN clean test
$MVN package -DskipTests
```
Expected: BUILD SUCCESS。

- [ ] **Step 2: 起服务**：用 `rule-app` 打包产物运行（别用 reactor run），确认迁移/初始化完成。

- [ ] **Step 3: 按依赖顺序补配置**：Scene → Decision → 绑定该 Decision 的规则 → 发布（rule_version 进 ACTIVE、decision_bindings 冻结）。

- [ ] **Step 4: 验后端**：
```bash
curl -s "http://localhost:8080/admin/v1/decisions/REJECT/sources?tenantId=1" | jq
curl -s "http://localhost:8080/admin/v1/decisions/usage-counts?tenantId=1" | jq
curl -s "http://localhost:8080/admin/v1/metrics/usage-counts?tenantId=1" | jq
```
Expected: sources 列出绑定规则、counts 计数正确。

- [ ] **Step 5: 真落库核对**：查 `rule_version.decision_bindings` 真冻结，与 `/sources` 一致。

- [ ] **Step 6: 前端手动核对**（端到端无法自动验证项，明确记录「手动验证」）：decision/metric 列表徽标显示、点徽标弹抽屉、行进详情页、详情页被引用 Tab、停用拦截弹确认、编辑器徽标。

- [ ] **Step 7: 清理测试数据**，恢复干净基线。

---

## 自审记录

- **Spec 覆盖**：§2.1→T1/T2；§2.2→T3；§2.3→T4/T5；§3.2→T7；§3.3→T8；§3.4→T9；§3.5→T10；§3.6→T6/T7/T8/T9/T10；docs→T11；§5 测试→T2/T3/T4/T5/T12。无遗漏。
- **类型一致**：`UsageCount(String code,int count)` 共享 record（T1 定义，T2/T3/T4/T5 用）；`DecisionService.RuleRef(Long,String,String,String,String)` T1 定义、T2/T4 用；`findRulesProducingDecision`/`get`/`countRuleUsages` 签名贯穿；前端 `LineageRuleRef`/`UsageCount`/`getDecisionSources`/`useLineage`/`LineageDrawer`/`LineageTable` 命名贯穿 T6–T10。
- **无占位符**：后端 T1–T5 全代码；前端 T6–T10 给出 API/hook/组件关键代码 + 精确文件 + 镜像范式文件（页面级 UI 按既有 AntD 组件套用，spec 已声明视觉形态随上下文）。
- **风险点**：① 编辑器下钻路由参数（ruleId vs ruleCode）需 T7 Step2 实测确认；② metric 抽屉 fetcher 需把版本感知 `getMetricImpact` 适配成 `{sources}` 形状（T9 Step1）；③ 路由 `/usage-counts` 与 `/{code}` 段优先级（T4/T5 已注明精确段优先，实测确认）。
