# 场景输入参数清单 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给"对场景发事件"的调用方一个精确可校验的输入契约——公开评估接口只收 `payload` 事实,`providedMetrics` 从公开接口彻底拿掉;发布期把规则引用的 payload 字段冻结成场景级输入清单(`rule_version.payload_dependencies`),随快照下发;评估期据清单校验入参,并新增发现接口让调用方查"该场景要传哪些字段"。

**Architecture:** 完全镜像现有 `metric_dependencies` 机制做 `payload_dependencies`。发布期 `PayloadFieldCollector`(feature A 已建)收集 `valueRef=PAYLOAD` 字段名 → 从 `scene.payloadSchema` 富集成 `List<PayloadDependency>{name,dataType,required}`(无额外查询,payloadSchema 发布期已在手)→ 落 `rule_version.payload_dependencies`(typed JSON 列)→ 随 `RuleVersionSnapshot.payloadDependencies` 下发。评估入口取候选快照 `payloadDependencies` 并集校验请求 payload(必填全到 + 基础类型匹配,多塞的忽略)。`providedMetrics` 从 `EvalEventRequest` 删除(内部 `RuleEvent` 保留供 SDK/Job 非公开注入);`MetadataService.getProvidedMetrics` 及其端点删除;新增 `GET /api/v1/rule/scenes/{sceneCode}/input-manifest`。

**Tech Stack:** Java 25, Spring Boot 4, MyBatis-Plus(`Jackson3TypeHandler` typed JSON 列), Flyway, JUnit 5 + AssertJ + Mockito。设计见 `docs/superpowers/specs/2026-06-10-scene-input-manifest-design.md`;依赖 A(payload 直接引用)已落地(`ValueRef`/`ConditionNode.valueRef`/`PayloadFieldCollector`/`PayloadDataTypeMapper`/`PublishService` payload 校验均已在 `rule-config-svc/.../internal/publish/`)。

**测试环境:** 每次跑 mvn 前用 `mvn-env` skill 设 `JAVA_HOME`(JDK 25)+ `$MVN`。命令形如 `$MVN -pl <module> -am test -Dtest='Xxx' -Dsurefire.failIfNoSpecifiedTests=false`。跨模块改动带 `-am`;整轮收尾用全量 `$MVN clean test`。

**实现决策(spec 已批准大方向内的落地选择,评审可推翻):**
1. **errorCode 粒度 = 消息前缀法**:`throw new IllegalArgumentException("MISSING_REQUIRED_INPUT: 缺少必填字段 amount")`,经现有 `GlobalExceptionHandler.handleIllegalArgument` → HTTP 400,wire `errorCode` 仍是 `INVALID_ARGUMENT`,语义码在 message 前缀(与既有 `DECISION_CODE_NOT_FOUND:` 前缀约定一致,spec §4.4"走现有约定")。不新增异常类型/handler。
2. **新发现端点落点 = 新建公开 `SceneManifestController`** @ `/api/v1/rule/scenes/{sceneCode}/input-manifest`(spec 路由在公开 `/api/v1/rule` 命名空间、用 `tenantCode`),不塞进 admin 的 `MetadataController`(`/admin/v1/scenes`、用 `tenantId`)。
3. **示例重做 = high-risk-login 纯 payload 驱动**:rule 改为 `AND(GT(payload.amount,1000), IN(payload.country, [...]))`(`country` 已在 payloadSchema),彻底不依赖 providedMetrics,正好演示 manifest 返回 `[amount, country]`;移除 `user.risk.score`(需真实数据源,公开接口已无 providedMetrics 喂值路径)。
4. **dry-run 也校验**:主 evaluate/event 路径按候选快照并集校验;dry-run 按其单一加载快照的 `payloadDependencies` 校验(一致)。
5. **决策表列不支持 payload**(沿用 A 的 YAGNI;`PayloadFieldCollector` 对 DecisionTableNode 不收集)。

---

## Task 1: PayloadDependency record(rule-kernel)

**Files:**
- Create: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/PayloadDependency.java`
- Test: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/api/model/PayloadDependencyTest.java`

镜像同包 `MetricDependency`(`record MetricDependency(String metricCode, int metricVersion)`)。

- [ ] **Step 1: 写失败测试**

```java
package com.sstlfsj.rule.kernel.api.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PayloadDependencyTest {
    @Test
    void holdsNameDataTypeRequired() {
        PayloadDependency d = new PayloadDependency("amount", "DECIMAL", true);
        assertEquals("amount", d.name());
        assertEquals("DECIMAL", d.dataType());
        assertTrue(d.required());
    }
}
```

- [ ] **Step 2: 跑测试验证失败** — `$MVN -pl rule-kernel test -Dtest='PayloadDependencyTest'`,预期编译失败(record 不存在)。

- [ ] **Step 3: 实现**

```java
package com.sstlfsj.rule.kernel.api.model;

/**
 * 规则引用的 payload 字段依赖(发布期从 scene.payloadSchema 冻结,随 RuleVersionSnapshot 下发)。
 * 与 MetricDependency 对称:metric 是受治理指标依赖,payload 是事件事实输入契约。
 *
 * @param name     payload 字段名(== ConditionNode.metricCode,valueRef=PAYLOAD 时复用为字段名)
 * @param dataType 字段类型标签(DataType.tag(),由 payloadSchema type 经 PayloadDataTypeMapper 映射冻结)
 * @param required 是否必填(取自 payloadSchema 字段声明)
 */
public record PayloadDependency(String name, String dataType, boolean required) {
}
```

- [ ] **Step 4: 跑测试验证通过** — `$MVN -pl rule-kernel test -Dtest='PayloadDependencyTest'`,预期 PASS。

- [ ] **Step 5: 提交**

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/PayloadDependency.java rule-kernel/src/test/java/com/sstlfsj/rule/kernel/api/model/PayloadDependencyTest.java
git commit -m "feat(kernel): 新增 PayloadDependency record(场景输入清单元素)"
```

---

## Task 2: RuleVersionSnapshot 加 payloadDependencies 字段 + Builder

**Files:**
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/RuleVersionSnapshot.java`
- Test: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/api/model/RuleVersionSnapshotTest.java`(已存在,追加用例)

**先读 `RuleVersionSnapshot.java` 全文**:`metricDependencies` 是最后一个 record component(`List<MetricDependency>`),compact ctor 里归一化(null→`List.of()` / `List.copyOf`);有一个省略 metricDependencies 的便利 ctor;Builder 有 `addMetricDependency(String,int)`。本任务:在 `metricDependencies` **之后**追加最终 component `List<PayloadDependency> payloadDependencies`,compact ctor 归一化,Builder 加 `addPayloadDependency(String,String,boolean)` + `build()` 透传,并在省略型便利 ctor 里默认 `List.of()`。

> 注:greenfield(memory `project_greenfield_dev_phase`,不写向后兼容);但便利 ctor 是减少调用点 churn 的"便利重载"非"兼容垫片",保留并默认空表即可。

- [ ] **Step 1: 追加失败测试**(到 `RuleVersionSnapshotTest`)

```java
    @Test
    void builder_carriesPayloadDependencies() {
        RuleVersionSnapshot snap = RuleVersionSnapshot.builder()
                .ruleVersionId(1L).ruleDefinitionId(1L).sceneId(1L).tenantId(1L)
                .ruleCode("r1").kind("AST_BOOLEAN").status("ACTIVE")
                .addPayloadDependency("amount", "DECIMAL", true)
                .build();
        assertThat(snap.payloadDependencies())
                .containsExactly(new com.sstlfsj.rule.kernel.api.model.PayloadDependency("amount", "DECIMAL", true));
    }
```

> 注:`RuleVersionSnapshot.builder()` 的必填项以实际 Builder 为准(读现有测试 `RuleVersionSnapshotTest` 已有的 builder 用例,照其最小必填集补全;若 builder 链方法名/必填项与上例不符,按真实 API 调整,保持断言意图:`addPayloadDependency` 后 `payloadDependencies()` 含该项)。

- [ ] **Step 2: 跑测试验证失败** — `$MVN -pl rule-kernel test -Dtest='RuleVersionSnapshotTest'`,预期编译失败(无 `addPayloadDependency`/`payloadDependencies()`)。

- [ ] **Step 3: 实现**
  - record 头部在 `List<MetricDependency> metricDependencies` 后加 `, List<PayloadDependency> payloadDependencies`。
  - compact ctor 内加:`payloadDependencies = payloadDependencies == null ? List.of() : List.copyOf(payloadDependencies);`
  - 省略 metricDependencies 的便利 ctor:其 `this(...)` 调用末尾补 `List.of()` 作为 payloadDependencies(与 metricDependencies 同样默认空表)。
  - Builder:加字段 `private final List<PayloadDependency> payloadDependencies = new ArrayList<>();`,加方法
    ```java
    /** 追加一条 payload 字段依赖(发布期从 payloadSchema 冻结)。 */
    public Builder addPayloadDependency(String name, String dataType, boolean required) {
        this.payloadDependencies.add(new PayloadDependency(name, dataType, required));
        return this;
    }
    ```
    `build()` 的 `new RuleVersionSnapshot(...)` 末参补 `payloadDependencies`。
  - import `java.util.ArrayList`(若未引)。

- [ ] **Step 4: 跑测试验证通过** — `$MVN -pl rule-kernel test -Dtest='RuleVersionSnapshotTest'`,预期 PASS。本步会改 record canonical ctor 元数;调用点(`SnapshotAssembler`、`PublishService`)在 Task 3/5 同步,本任务只保证 kernel 模块自身测试编译通过——若 kernel 内有其它直接调 canonical full ctor 的点,补 `List.of()` 末参。

- [ ] **Step 5: 提交**

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/RuleVersionSnapshot.java rule-kernel/src/test/java/com/sstlfsj/rule/kernel/api/model/RuleVersionSnapshotTest.java
git commit -m "feat(kernel): RuleVersionSnapshot 加 payloadDependencies 字段 + Builder.addPayloadDependency"
```

---

## Task 3: RuleVersionRow + AstJsonCodec + SnapshotAssembler 串联 payloadDependencies(eval 读路径)

**Files:**
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/codec/RuleVersionRow.java`
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/codec/AstJsonCodec.java`
- Modify: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/codec/SnapshotAssembler.java`
- Test: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/codec/SnapshotAssemblerTest.java`(追加用例)

**先读三文件**:`RuleVersionRow` 有 component `String metricDependenciesJson`(+ 省略它的便利 ctor);`AstJsonCodec` 有 `deserializeMetricDependencies(String)`;`SnapshotAssembler.assemble(RuleVersionRow)` 反序列化 metricDependenciesJson(null→`"[]"` 兜底)并传入快照 ctor 末参。

- [ ] **Step 1: 追加失败测试**(`SnapshotAssemblerTest`,镜像 `assemble_populatesMetricDependencies`)

```java
    @Test
    void assemble_populatesPayloadDependencies() {
        RuleVersionRow row = row("[]", "[{\"name\":\"amount\",\"dataType\":\"DECIMAL\",\"required\":true}]");
        RuleVersionSnapshot snap = assembler.assemble(row);
        assertThat(snap.payloadDependencies())
                .containsExactly(new com.sstlfsj.rule.kernel.api.model.PayloadDependency("amount", "DECIMAL", true));
    }

    @Test
    void assemble_nullPayloadDependenciesJson_yieldsEmptyList() {
        RuleVersionRow row = row("[]", null);
        RuleVersionSnapshot snap = assembler.assemble(row);
        assertThat(snap.payloadDependencies()).isEmpty();
    }
```

> 注:测试里的 `row(...)` helper 当前是 metric-only 签名。新增一个重载 `row(String metricDepsJson, String payloadDepsJson)`(或扩展现有 helper)构造带两段 JSON 的 `RuleVersionRow`。读现有 `row(...)` helper 的字段顺序,按 `RuleVersionRow` 真实 component 顺序构造,`payloadDependenciesJson` 放到你在 Step 3 加的 component 位置。

- [ ] **Step 2: 跑测试验证失败** — `$MVN -pl rule-kernel test -Dtest='SnapshotAssemblerTest'`,预期编译失败(无 `payloadDependenciesJson` / `deserializePayloadDependencies`)。

- [ ] **Step 3: 实现**
  - `RuleVersionRow`:在 `metricDependenciesJson` 之后加 component `String payloadDependenciesJson`。若有省略它的便利 ctor,补默认 `null`。**注意 component 顺序**:`RuleVersionReadMapper` 的 `@Select` 别名顺序(Task 6)必须与此 record component 顺序一致(MyBatis 按构造器位置映射)。
  - `AstJsonCodec`:加
    ```java
    /** 反序列化 payload 依赖快照 JSON(随 rule_version.payload_dependencies 下发)。 */
    public List<PayloadDependency> deserializePayloadDependencies(String json) {
        return mapper.readValue(json, new TypeReference<List<PayloadDependency>>() {});
    }
    ```
    (镜像 `deserializeMetricDependencies` 的 body / 异常处理;import `PayloadDependency`。)
  - `SnapshotAssembler.assemble`:加
    ```java
    List<PayloadDependency> payloadDependencies =
            codec.deserializePayloadDependencies(row.payloadDependenciesJson() == null ? "[]" : row.payloadDependenciesJson());
    ```
    并把 `payloadDependencies` 作为快照 ctor 的新末参传入(metricDependencies 之后)。

- [ ] **Step 4: 跑测试验证通过** — `$MVN -pl rule-kernel test -Dtest='SnapshotAssemblerTest,RuleVersionRowTest,AstJsonCodecTest'`,预期全 PASS(`RuleVersionRowTest` 若因 component 增加需补一参,同步改)。

- [ ] **Step 5: 提交**

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/codec/RuleVersionRow.java rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/codec/AstJsonCodec.java rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/codec/SnapshotAssembler.java rule-kernel/src/test/java/com/sstlfsj/rule/kernel/internal/codec/SnapshotAssemblerTest.java
git commit -m "feat(kernel): RuleVersionRow/AstJsonCodec/SnapshotAssembler 串联 payloadDependencies"
```

---

## Task 4: DB 迁移 V1_24 加 payload_dependencies 列 + RuleVersion 实体字段

**Files:**
- Create: `rule-config-svc/src/main/resources/db/migration/V1_24__rule_version_payload_dependencies.sql`
- Modify: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/domain/RuleVersion.java`
- Test: `rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/domain/RuleVersionTest.java`(追加字段断言;若不存在则在 Task 5 的发布测试覆盖)

**先确认最新迁移版本**:`rule-config-svc/src/main/resources/db/migration/` 下最新是 `V1_23`(本计划下一个用 `V1_24`)。`metric_dependencies JSON NOT NULL` 在 `V1_0` 的 `rule_version` 表定义。

- [ ] **Step 1: 写迁移脚本**

```sql
-- 场景输入参数清单:规则引用的 payload 字段依赖(发布期从 scene.payloadSchema 冻结)
-- 与 metric_dependencies 对称;typed JSON 列,随 RuleVersionSnapshot 下发,评估期据此校验入参
ALTER TABLE rule_version
    ADD COLUMN payload_dependencies JSON NOT NULL
        COMMENT 'AST 引用的 payload 字段依赖 [{name,dataType,required}]'
        AFTER metric_dependencies;
```

> greenfield 无生产数据(memory),`NOT NULL` 无默认即可——若库里已有历史 `rule_version` 行导致 ALTER 失败,改为 `NOT NULL DEFAULT (JSON_ARRAY())` 或先 `UPDATE rule_version SET payload_dependencies='[]'`。MySQL 8 的 `JSON` 列不支持非表达式默认,故用 `DEFAULT (JSON_ARRAY())` 表达式形式(若选默认)。

- [ ] **Step 2: 改实体 RuleVersion**
  - 在 `metricDependencies` 字段后加(`@TableName(value="rule_version", autoResultMap=true)` 已具备):
    ```java
    @TableField(typeHandler = Jackson3TypeHandler.class)
    private List<PayloadDependency> payloadDependencies;
    ```
    import `com.sstlfsj.rule.kernel.api.model.PayloadDependency`。
  - `draftV1(...)` 工厂里 `rv.setMetricDependencies(List.of())` 之后加 `rv.setPayloadDependencies(List.of());`(草稿不冻结依赖)。

- [ ] **Step 3: 跑测试** — `$MVN -pl rule-config-svc -am test -Dtest='RuleVersionTest'`(若有);并 `$MVN -q -pl rule-config-svc -am compile` 确认实体编译。预期通过。迁移脚本会在后续集成测试(Task 5/13)随 Flyway 跑起来验证。

- [ ] **Step 4: 提交**

```bash
git add rule-config-svc/src/main/resources/db/migration/V1_24__rule_version_payload_dependencies.sql rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/domain/RuleVersion.java rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/domain/RuleVersionTest.java
git commit -m "feat(config): rule_version 加 payload_dependencies 列(V1_24)+ 实体 typed 字段"
```

---

## Task 5: PublishService 收集并冻结 payloadDependencies

**Files:**
- Modify: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/publish/PublishService.java`
- Test: `rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/publish/PublishServiceTest.java`(追加用例,镜像 metricDependencies 断言)

**先读 `PublishService.publish(...)`**(feature A 已加:`List<String> payloadFields = PayloadFieldCollector.collect(ast);` + 构造 `payloadTypeMap` 并校验每个字段在 `scene.getPayloadSchema()`)。`scene.getPayloadSchema()` 返回 `List<PayloadFieldSpec>`(`PayloadFieldSpec{name,type,required}`,见 `rule-config-svc/.../api/dto/PayloadFieldSpec.java`)。entity 在 `newRv.setMetricDependencies(metricDeps)` 处 populate;`RuleVersionSnapshot` ctor 在其下游构造。

- [ ] **Step 1: 追加失败测试**(`PublishServiceTest`,镜像断 `metricDependencies` 的那个用例)

```java
    @Test
    void publish_freezesPayloadDependencies_fromPayloadSchema() {
        // 规则含一个 valueRef=PAYLOAD 的 amount(GT 1000)节点;scene.payloadSchema 声明 amount: number, required=true
        // 装配同现有 PublishServiceTest 的 mock(参照 PublishServicePayloadTest 的 SceneDef/RuleDefinition/RuleVersion 桩)
        // 断言落库实体 与 下发快照 都含冻结的 payload 依赖:
        ArgumentCaptor<RuleVersion> captor = ArgumentCaptor.forClass(RuleVersion.class);
        // ... 触发 publish ...
        verify(ruleVersionMapper).insert(captor.capture()); // 以实际持久化方法为准
        assertThat(captor.getValue().getPayloadDependencies())
                .containsExactly(new PayloadDependency("amount", "DECIMAL", true));
    }
```

> 注:`PublishServiceTest` 的 mock 装配较重;**直接复制 feature A 的 `PublishServicePayloadTest` 的桩搭建**(它已构造引用 payload 字段的草稿 + payloadSchema)。断言两处:(a)落库 `RuleVersion.getPayloadDependencies()`,(b)下发 `RuleVersionSnapshot.payloadDependencies()`(若该测试能拿到构造出的 snapshot;拿不到就只断实体,snapshot 串联由 Task 3 的 SnapshotAssemblerTest 覆盖)。`amount` 为 number+required ⇒ `PayloadDependency("amount","DECIMAL",true)`。再加一条:`valueRef=METRIC` 字段不混入 payloadDependencies。

- [ ] **Step 2: 跑测试验证失败** — `$MVN -pl rule-config-svc -am test -Dtest='PublishServiceTest'`,预期新用例 FAIL(payloadDependencies 为空/null)。

- [ ] **Step 3: 实现** — 在 `publish` 的 payload 校验段(feature A 已建 `payloadFields` + `schemaTypeByName`)后,富集成 `List<PayloadDependency>` 并 set 到实体 + 传入快照 ctor:

```java
        // 把 payload 字段富集成依赖三元组(name + dataType + required),从已在手的 payloadSchema 取,无额外查询
        List<PayloadDependency> payloadDeps = new ArrayList<>();
        if (!payloadFields.isEmpty()) {
            Map<String, PayloadFieldSpec> specByName = new HashMap<>();
            for (PayloadFieldSpec f : (scene.getPayloadSchema() != null ? scene.getPayloadSchema() : List.<PayloadFieldSpec>of())) {
                specByName.put(f.name(), f);
            }
            for (String field : payloadFields) {
                PayloadFieldSpec spec = specByName.get(field); // A 的校验已保证存在,这里必非 null
                payloadDeps.add(new PayloadDependency(field, PayloadDataTypeMapper.toDataTypeTag(spec.type()), spec.required()));
            }
        }
```
然后:
  - 实体 populate:在 `newRv.setMetricDependencies(metricDeps);` 旁加 `newRv.setPayloadDependencies(payloadDeps);`。
  - 快照 ctor:`new RuleVersionSnapshot(... , metricDeps, payloadDeps)` —— 末参补 `payloadDeps`(Task 2 已让 ctor 收该参)。
  - import:`com.sstlfsj.rule.kernel.api.model.PayloadDependency`、`java.util.ArrayList`(若未引)。

> `PayloadFieldSpec` 的访问器以实际为准(feature A 用的是 `.name()` / `.type()` / `.required()`)。

- [ ] **Step 4: 跑测试验证通过** — `$MVN -pl rule-config-svc -am test -Dtest='PublishServiceTest,PublishServicePayloadTest'`,预期全 PASS。

- [ ] **Step 5: 提交**

```bash
git add rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/publish/PublishService.java rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/publish/PublishServiceTest.java
git commit -m "feat(config): 发布期冻结 payloadDependencies(name/dataType/required)到 rule_version + 快照"
```

---

## Task 6: RuleVersionReadMapper 三查询补 payload_dependencies(eval 读路径)

**Files:**
- Modify: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/repository/RuleVersionReadMapper.java`
- Test: `rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/integration/EvalIntegrationTest.java`(已有端到端;本任务靠 Task 13 全量 + 下一步集成验证,不单写新测试,除非现有 mapper 有专测)

**先读 `RuleVersionReadMapper`**:三个 `@Select`(`loadAllActive` / `loadActiveByScene` / `loadById`),每个把 `rv.metric_dependencies AS metricDependenciesJson` 选进 `RuleVersionRow`。

- [ ] **Step 1: 实现** — 在三个 `@Select` 的列清单里,`rv.metric_dependencies AS metricDependenciesJson` 之后各加一行 `rv.payload_dependencies AS payloadDependenciesJson`。**确认别名顺序与 `RuleVersionRow` component 顺序一致**(Task 3 把 `payloadDependenciesJson` 放在 `metricDependenciesJson` 之后,这里也紧随其后)。

- [ ] **Step 2: 编译 + 跑现有 eval 读路径测试** — `$MVN -pl rule-eval-svc -am test -Dtest='EvalIntegrationTest'`(及该模块涉及快照加载的测试),预期 PASS(payload 依赖随快照加载,空表也正常)。

- [ ] **Step 3: 提交**

```bash
git add rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/repository/RuleVersionReadMapper.java
git commit -m "feat(eval): RuleVersionReadMapper 三查询补选 payload_dependencies"
```

---

## Task 7: EvalEventRequest 删 providedMetrics + EvalController 不再填

**Files:**
- Modify: `rule-api/src/main/java/com/sstlfsj/rule/web/api/dto/EvalEventRequest.java`
- Modify: `rule-api/src/main/java/com/sstlfsj/rule/web/api/EvalController.java`
- Test: `rule-api/src/test/java/com/sstlfsj/rule/web/api/EvalControllerTest.java`(若存在;否则在 Task 13 端到端覆盖)+ 编译契约

**先读两文件**:`EvalEventRequest` 是 record,末字段 `Map<String,Object> providedMetrics`;`EvalController.toEvent(EvalEventRequest)` 里 `.providedMetrics(r.providedMetrics())` 把它填进 `RuleEvent`(`RuleEvent` 的 compact ctor 已把 providedMetrics null 默认为 `Map.of()`)。

- [ ] **Step 1: 写/改失败测试**(契约层:断 `EvalEventRequest` 无 providedMetrics)

新建/追加 `rule-api/src/test/java/com/sstlfsj/rule/web/api/EvalEventRequestContractTest.java`:
```java
package com.sstlfsj.rule.web.api;

import com.sstlfsj.rule.web.api.dto.EvalEventRequest;
import org.junit.jupiter.api.Test;
import java.lang.reflect.RecordComponent;
import static org.assertj.core.api.Assertions.assertThat;

class EvalEventRequestContractTest {
    @Test
    void hasNoProvidedMetricsComponent() {
        assertThat(java.util.Arrays.stream(EvalEventRequest.class.getRecordComponents())
                .map(RecordComponent::getName))
                .doesNotContain("providedMetrics");
    }
}
```

- [ ] **Step 2: 跑测试验证失败** — `$MVN -pl rule-api -am test -Dtest='EvalEventRequestContractTest'`,预期 FAIL(仍含 providedMetrics)。

- [ ] **Step 3: 实现**
  - `EvalEventRequest`:删除 `providedMetrics` component(及其 Javadoc 行)。
  - `EvalController.toEvent`:删除 `.providedMetrics(r.providedMetrics())` 这一行(RuleEvent 自动默认 `Map.of()`,HTTP 路径 providedMetrics 恒空,符合 spec §4.3)。

- [ ] **Step 4: 跑测试验证通过** — `$MVN -pl rule-api -am test -Dtest='EvalEventRequestContractTest'`;并 `$MVN -q -pl rule-api -am test-compile` 确认无其它引用 `r.providedMetrics()` 的点遗留(若有 mapper/转换器引用,一并清掉)。预期 PASS。

- [ ] **Step 5: 提交**

```bash
git add rule-api/src/main/java/com/sstlfsj/rule/web/api/dto/EvalEventRequest.java rule-api/src/main/java/com/sstlfsj/rule/web/api/EvalController.java rule-api/src/test/java/com/sstlfsj/rule/web/api/EvalEventRequestContractTest.java
git commit -m "feat(api): EvalEventRequest 删 providedMetrics,EvalController 不再从请求体填"
```

---

## Task 8: 评估期 payload 入参校验(PayloadInputValidator + 接入 EvalServiceImpl)

**Files:**
- Create: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/validate/PayloadInputValidator.java`
- Modify: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/service/EvalServiceImpl.java`
- Test: `rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/validate/PayloadInputValidatorTest.java`
- Test: `rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/service/EvalServiceImplTest.java`(追加接入用例)

**先读 `EvalServiceImpl.doEvaluate(...)`**:`List<RuleVersionSnapshot> candidates = evalEngine.match(event);`(L~86),`candidates` 为空时 `return EvalResult.miss()`;dry-run 路径用 `snapshotLoader.loadById` 拿单一 `snap`。校验落点:取 candidates(或 dry-run 的单一 snap)的 `payloadDependencies` 并集校验 `event.payload()`。

设计:无状态校验器,纯函数。类型匹配按 dataType 标签对 Java 值类型:`DECIMAL`/`LONG`→`Number`、`STRING`→`CharSequence`、`BOOLEAN`→`Boolean`、`LIST`→`java.util.Collection`、`UNKNOWN`→跳过。必填缺失 → `MISSING_REQUIRED_INPUT`;类型不符 → `INPUT_TYPE_MISMATCH`;多塞字段忽略。`required=false` 缺失 = 正常缺失,不违约。

- [ ] **Step 1: 写失败测试**(validator 单元)

```java
package com.sstlfsj.rule.eval.internal.validate;

import com.sstlfsj.rule.kernel.api.model.PayloadDependency;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class PayloadInputValidatorTest {

    @Test
    void missingRequired_throwsMissingRequiredInput() {
        var deps = List.of(new PayloadDependency("amount", "DECIMAL", true));
        assertThatThrownBy(() -> PayloadInputValidator.validate(deps, Map.of("country", "CN")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MISSING_REQUIRED_INPUT")
                .hasMessageContaining("amount");
    }

    @Test
    void typeMismatch_throwsInputTypeMismatch() {
        var deps = List.of(new PayloadDependency("amount", "DECIMAL", true));
        assertThatThrownBy(() -> PayloadInputValidator.validate(deps, Map.of("amount", "not-a-number")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("INPUT_TYPE_MISMATCH")
                .hasMessageContaining("amount");
    }

    @Test
    void allPresentCorrectType_passes_andIgnoresExtra() {
        var deps = List.of(new PayloadDependency("amount", "DECIMAL", true));
        assertThatCode(() -> PayloadInputValidator.validate(deps, Map.of("amount", 5000, "extra", "x")))
                .doesNotThrowAnyException();
    }

    @Test
    void optionalMissing_passes() {
        var deps = List.of(new PayloadDependency("note", "STRING", false));
        assertThatCode(() -> PayloadInputValidator.validate(deps, Map.of())).doesNotThrowAnyException();
    }
}
```

- [ ] **Step 2: 跑测试验证失败** — `$MVN -pl rule-eval-svc -am test -Dtest='PayloadInputValidatorTest'`,预期编译失败。

- [ ] **Step 3: 实现 PayloadInputValidator**

```java
package com.sstlfsj.rule.eval.internal.validate;

import com.sstlfsj.rule.kernel.api.model.PayloadDependency;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 评估期 payload 入参校验:据候选快照的 payload 依赖清单,校验请求 payload 必填全到 + 基础类型匹配。
 * 多塞的、无规则引用的字段忽略(当额外上下文)。违约抛 IllegalArgumentException
 * (message 前缀 MISSING_REQUIRED_INPUT / INPUT_TYPE_MISMATCH),经 GlobalExceptionHandler → HTTP 400。
 */
public final class PayloadInputValidator {
    private PayloadInputValidator() {}

    /**
     * @param deps    候选快照的 payload 依赖并集(同名去重后)
     * @param payload 请求事件 payload(可能为 null)
     * @throws IllegalArgumentException 必填缺失或类型不符
     */
    public static void validate(List<PayloadDependency> deps, Map<String, Object> payload) {
        Map<String, Object> p = payload == null ? Map.of() : payload;
        for (PayloadDependency d : deps) {
            boolean present = p.containsKey(d.name());
            if (!present) {
                if (d.required()) {
                    throw new IllegalArgumentException("MISSING_REQUIRED_INPUT: 缺少必填字段 " + d.name());
                }
                continue; // 可选字段缺失:正常缺失,不违约
            }
            Object value = p.get(d.name());
            if (value != null && !typeMatches(d.dataType(), value)) {
                throw new IllegalArgumentException(
                        "INPUT_TYPE_MISMATCH: 字段 " + d.name() + " 类型不符,期望 " + d.dataType()
                        + ",实际 " + value.getClass().getSimpleName());
            }
        }
    }

    /** 基础类型匹配;UNKNOWN 不校验(放行)。 */
    private static boolean typeMatches(String dataTypeTag, Object value) {
        return switch (dataTypeTag) {
            case "DECIMAL", "LONG", "DOUBLE" -> value instanceof Number;
            case "STRING" -> value instanceof CharSequence;
            case "BOOLEAN" -> value instanceof Boolean;
            case "LIST" -> value instanceof Collection<?>;
            default -> true; // UNKNOWN / DATE / DATETIME 等不在基础类型校验范围,放行
        };
    }
}
```

> 备选:`rule-eval-svc/.../internal/metric/DataTypeCoercion.java` 已有类型转换工具;如要与 eval 其余路径完全一致可复用其判定。本计划用自包含 `typeMatches` 以零外部依赖、判据明确为先;实现者若发现复用 `DataTypeCoercion` 更一致,可改用并同步测试。

- [ ] **Step 4: 跑 validator 测试验证通过** — `$MVN -pl rule-eval-svc -am test -Dtest='PayloadInputValidatorTest'`,预期 PASS。

- [ ] **Step 5: 接入 EvalServiceImpl.doEvaluate** — 在 `evalEngine.match(event)` 拿到 `candidates`、非空之后、`evaluateWithContext` 之前插入并集校验:

```java
        // 据候选快照的 payload 依赖并集,校验请求 payload(必填全到 + 类型匹配;多塞字段忽略)
        java.util.Map<String, com.sstlfsj.rule.kernel.api.model.PayloadDependency> unionByName = new java.util.LinkedHashMap<>();
        for (RuleVersionSnapshot c : candidates) {
            for (com.sstlfsj.rule.kernel.api.model.PayloadDependency d : c.payloadDependencies()) {
                unionByName.putIfAbsent(d.name(), d); // 同名同声明(共享 scene.payloadSchema),去重即可
            }
        }
        com.sstlfsj.rule.eval.internal.validate.PayloadInputValidator.validate(
                java.util.List.copyOf(unionByName.values()), event.payload());
```
dry-run 路径(单一 `snap`)同理:在用 `snap` 评估前加 `PayloadInputValidator.validate(snap.payloadDependencies(), event.payload());`。

> 用 import 替代内联全限定名以符合代码风格;此处全限定仅为计划可读。校验放在 `candidates` 非空分支内——无候选规则=该事件不触发任何规则,无输入契约可校,维持 `EvalResult.miss()` 原行为(不对"无规则场景"强加必填)。

- [ ] **Step 6: 追加 EvalServiceImpl 接入测试**(`EvalServiceImplTest`,镜像其 stub `evalEngine.match(...)` 返回快照的现有用例)

```java
    @Test
    void doEvaluate_rejectsMissingRequiredPayload() {
        // stub match 返回一个 payloadDependencies 含 required amount 的快照;event.payload 不含 amount
        // 断言抛 IllegalArgumentException 且 message 含 MISSING_REQUIRED_INPUT
    }
```
(用该测试类已有的快照/event 构造工具补全;再加一条"多塞字段照常命中"的正路用例。)

- [ ] **Step 7: 跑测试 + 提交**

```bash
$MVN -pl rule-eval-svc -am test -Dtest='PayloadInputValidatorTest,EvalServiceImplTest'
git add rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/validate/PayloadInputValidator.java rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/service/EvalServiceImpl.java rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/validate/PayloadInputValidatorTest.java rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/service/EvalServiceImplTest.java
git commit -m "feat(eval): 评估期校验 payload 入参(MISSING_REQUIRED_INPUT/INPUT_TYPE_MISMATCH)"
```

---

## Task 9: 新发现接口 input-manifest(MetadataService 方法 + 公开 SceneManifestController)

**Files:**
- Modify: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/service/MetadataService.java`(加方法 + 返回 record)
- Modify: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/service/MetadataServiceImpl.java`(实现,镜像 `collectRequiredDeps`)
- Create: `rule-api/src/main/java/com/sstlfsj/rule/web/api/SceneManifestController.java`
- Test: `rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/service/MetadataServiceImplTest.java`(追加)
- Test: `rule-config-svc/src/test/java/com/sstlfsj/rule/config/integration/MetadataServiceIntegrationTest.java`(追加场景级并集集成用例)

**先读 `MetadataServiceImpl.collectRequiredDeps`(L~101-115)**:它解析 sceneIds(`sceneMapper.findByCodes`/`findByCode`)→ruleDefIds(`ruleDefinitionMapper.findByTenantAndSceneIds`)→`ruleVersionMapper.findActiveByRuleDefIds(defIds)` 读 `rv.getMetricDependencies()`。input-manifest 完全镜像,改读 `rv.getPayloadDependencies()` 并按 `name` 并集去重。

设计:`getSceneMetadata` 保留不动;新增 `getInputManifest(String tenantId, String sceneCode, String eventType)`(eventType 可空→不收窄)。返回 typed record `InputManifestResponse(List<InputFieldSpec> fields)`,`InputFieldSpec(String name, String dataType, boolean required)`(对外契约边界用 typed record,符合 CLAUDE.md;字段值与 `PayloadDependency` 同形)。eventType 非空时,只并集 `triggerEventTypes` 含该 eventType(或为空=通配)的 ACTIVE rule_version。

- [ ] **Step 1: 写失败测试**(`MetadataServiceImplTest`,Mockito 镜像 `collectRequiredDeps` 的桩)

```java
    @Test
    void getInputManifest_unionsPayloadDeps_dedupByName() {
        // stub sceneMapper/ruleDefinitionMapper/ruleVersionMapper 返回两条 ACTIVE rule_version:
        //   rv1.payloadDependencies = [amount(DECIMAL,true)]
        //   rv2.payloadDependencies = [amount(DECIMAL,true), country(STRING,true)]
        // 断言 manifest.fields() 按 name 去重 == [amount, country],类型/required 来自声明
        var resp = service.getInputManifest("9001", "demo.login", null);
        assertThat(resp.fields()).extracting("name").containsExactlyInAnyOrder("amount", "country");
    }
```

- [ ] **Step 2: 跑测试验证失败** — `$MVN -pl rule-config-svc -am test -Dtest='MetadataServiceImplTest'`,预期编译失败。

- [ ] **Step 3: 实现 service**
  - `MetadataService` 接口加:
    ```java
    /** 查场景输入参数清单:该场景所有 ACTIVE 规则引用的 payload 字段并集(eventType 非空则收窄到会被触发的规则)。 */
    InputManifestResponse getInputManifest(String tenantId, String sceneCode, String eventType);

    /** 输入清单响应(对外契约;字段值与发布期冻结的 PayloadDependency 同形)。 */
    record InputManifestResponse(java.util.List<InputFieldSpec> fields) {}
    record InputFieldSpec(String name, String dataType, boolean required) {}
    ```
  - `MetadataServiceImpl.getInputManifest`:镜像 `collectRequiredDeps` 取 ACTIVE rule_versions;对每条读 `rv.getPayloadDependencies()`;eventType 非空时按 `rv.getTriggerEventTypes()`(空=通配)过滤;按 `name` 用 `LinkedHashMap.putIfAbsent` 去重并集;映射成 `InputFieldSpec`。
  - **删 `getProvidedMetrics` 留到 Task 10**(本任务只加 manifest,保持单一关注)。

- [ ] **Step 4: 跑 service 测试验证通过** — `$MVN -pl rule-config-svc -am test -Dtest='MetadataServiceImplTest'`,预期 PASS。

- [ ] **Step 5: 写公开 controller**

```java
package com.sstlfsj.rule.web.api;

import com.sstlfsj.rule.config.api.service.MetadataService;
import com.sstlfsj.rule.config.api.service.MetadataService.InputManifestResponse;
// 复用 EvalController 同款 tenantCode→id 解析与 ApiResponse 包装
import org.springframework.web.bind.annotation.*;

/** 公开发现接口:查场景的输入参数清单(调用方据此精确传 payload)。 */
@RestController
@RequestMapping("/api/v1/rule/scenes")
public class SceneManifestController {

    private final MetadataService metadataService;
    private final TenantQueryService tenantQueryService; // 与 EvalController 同款注入

    public SceneManifestController(MetadataService metadataService, TenantQueryService tenantQueryService) {
        this.metadataService = metadataService;
        this.tenantQueryService = tenantQueryService;
    }

    /** GET /api/v1/rule/scenes/{sceneCode}/input-manifest?tenantCode=xxx[&eventType=xxx] */
    @GetMapping("/{sceneCode}/input-manifest")
    public ApiResponse<InputManifestResponse> inputManifest(
            @PathVariable String sceneCode,
            @RequestParam String tenantCode,
            @RequestParam(required = false) String eventType) {
        String tenantId = String.valueOf(tenantQueryService.resolveIdByCode(tenantCode));
        return ApiResponse.ok(metadataService.getInputManifest(tenantId, sceneCode, eventType));
    }
}
```

> 以 `EvalController` 实际用法为准:`TenantQueryService.resolveIdByCode` 的真实签名/返回类型、`ApiResponse.ok(...)` 工厂、import 路径都照 `EvalController` 抄。`getInputManifest` 的 `tenantId` 形参类型(String vs Long)与 `MetadataService` 既有方法保持一致。

- [ ] **Step 6: 写场景级并集集成测试**(`MetadataServiceIntegrationTest`,真实 DB:建 scene+payloadSchema、发布两条引用 payload 的规则、查 manifest 断言并集去重)。镜像该类现有 metric 相关集成用例的建数据方式。

- [ ] **Step 7: 跑测试 + 提交**

```bash
$MVN -pl rule-config-svc -am test -Dtest='MetadataServiceImplTest,MetadataServiceIntegrationTest'
$MVN -q -pl rule-api -am test-compile
git add rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/service/MetadataService.java rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/service/MetadataServiceImpl.java rule-api/src/main/java/com/sstlfsj/rule/web/api/SceneManifestController.java rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/service/MetadataServiceImplTest.java rule-config-svc/src/test/java/com/sstlfsj/rule/config/integration/MetadataServiceIntegrationTest.java
git commit -m "feat: 新增场景输入清单发现接口 GET /api/v1/rule/scenes/{sceneCode}/input-manifest"
```

---

## Task 10: 删除 getProvidedMetrics(service + impl + 端点 + record)

**Files:**
- Modify: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/service/MetadataService.java`(删方法 + `ProvidedMetricsResponse` record)
- Modify: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/service/MetadataServiceImpl.java`(删实现)
- Modify: `rule-api/src/main/java/com/sstlfsj/rule/web/admin/MetadataController.java`(删端点)
- Test: 删/改引用 `getProvidedMetrics` 的测试(`MetadataServiceImplTest`/集成测试/controller 测试)

**先 grep 所有引用**:`Grep "getProvidedMetrics|ProvidedMetricsResponse|provided-metrics"`,定位 service/impl/controller/test/docs 全部引用点。

- [ ] **Step 1: 删实现与契约**
  - `MetadataService`:删 `getProvidedMetrics(...)` 方法声明 + `ProvidedMetricsResponse` record(`getSceneMetadata` 及 `MetadataResponse`/`MetricMeta` 保留)。
  - `MetadataServiceImpl`:删 `getProvidedMetrics` 实现。
  - `MetadataController`:删 `getProvidedMetrics` 端点方法(`GET /admin/v1/scenes/{sceneCode}/provided-metrics`);`getMetadata`(`/metadata`)保留。

- [ ] **Step 2: 删/改相关测试** — 删掉断 `getProvidedMetrics` 的单测/集成/controller 用例(grep 命中的)。`getSceneMetadata` 的测试保留。

- [ ] **Step 3: 编译 + 跑测试** — `$MVN -pl rule-config-svc -am test -Dtest='MetadataServiceImplTest,MetadataServiceIntegrationTest'` + `$MVN -q -pl rule-api -am test-compile`,预期无 `getProvidedMetrics` 残留引用、PASS。

- [ ] **Step 4: 提交**

```bash
git add rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/service/MetadataService.java rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/service/MetadataServiceImpl.java rule-api/src/main/java/com/sstlfsj/rule/web/admin/MetadataController.java
git add -u rule-config-svc/src/test  # 删/改的测试
git commit -m "feat: 删除 getProvidedMetrics 服务与端点(公开侧无 provided metric 概念)"
```

---

## Task 11: 示例重做 high-risk-login(纯 payload 驱动 + manifest 剧本)

**Files:**
- Modify: `docs/examples/risk-control/high-risk-login/rules/high-risk-login.json`
- Modify: `docs/examples/risk-control/high-risk-login/metrics/metrics.json`
- Modify: `docs/examples/risk-control/high-risk-login/mock-events.json`
- Modify: `docs/examples/risk-control/high-risk-login/expected-results.json`
- Modify: `docs/examples/risk-control/high-risk-login/scene.json`(确认 payloadSchema 含 country)
- Modify: `docs/examples/risk-control/high-risk-login/README.md`

**先读全部 6 个文件**(feature A 已把 amount 改成 payload、删 amount metric、providedMetrics 去掉 amount)。本任务落实 spec §四"providedMetrics 从公开接口删除"的连锁:**user.risk.score 这条 metric 在公开 HTTP 已无喂值路径**(本轮删了 providedMetrics),demo 改为纯 payload。

- [ ] **Step 1: 改 rule** — `high-risk-login.json` 的 `user.risk.score`(GTE,METRIC)节点替换为 `country`(payload)节点:
```json
{
  "type": "ConditionNode",
  "displayLabel": "高风险国家",
  "conditionType": "IN",
  "metricCode": "country",
  "valueRef": "PAYLOAD",
  "params": { "values": ["US", "RU", "IR"] }
}
```
amount 节点(已 valueRef=PAYLOAD)不变。规则即 `AND(GT(payload.amount,1000), IN(payload.country,[...]))`。

- [ ] **Step 2: 删 metrics** — `metrics/metrics.json` 删 `user.risk.score`(amount feature A 已删)。结果:**空数组 `[]`**(demo 无受治理 metric)。

- [ ] **Step 3: 改 mock-events** — 三个事件**删掉整个 `providedMetrics` 字段**(EvalEventRequest 已不收);`payload` 改为含 amount + country(命中事件 country 用 "US";未命中事件 amount=500 或 country="CN")。

- [ ] **Step 4: 改 expected-results** — user.risk.score 节点 trace 替换为 country 节点(valueSource=PAYLOAD);命中/未命中结论按新规则(amount>1000 且 country∈高风险)。

- [ ] **Step 5: 改 README** — §二配置概览:Metric 行改"无(纯 payload 驱动)";Rule 行 AST 改 `AND(GT(payload amount,1000), IN(payload country,[US,RU,IR]))`。§三 curl:删 metric 注册步、删 providedMetrics、加一条 `GET /api/v1/rule/scenes/demo.login/input-manifest?tenantCode=loadtest` 演示发现接口(预期返回 `[{amount,DECIMAL,true},{country,STRING,true}]`)。§四注意点:把"payload 也注册 metric"彻底换成"公开评估只收 payload、providedMetrics 已从公开接口移除、metric 全归引擎(本 demo 无 metric)";加"输入清单"说明。

- [ ] **Step 6: 校验 JSON 有效** — 对改动的 4 个 JSON 跑 `python3 -m json.tool <file> >/dev/null && echo OK`。确认无测试程序消费这些文件(feature A 已确认 LoadTestSeeder 不读它们)。

- [ ] **Step 7: 提交**

```bash
git add docs/examples/risk-control/high-risk-login/
git commit -m "docs(examples): high-risk-login 改纯 payload 驱动 + input-manifest 剧本(去 providedMetrics)"
```

---

## Task 12: 文档更新(概念 + 契约 + 决策)+ 跨文档自洽

**Files:**
- Modify: `docs/01-concepts.md`(payload/metric 输入契约:公开只收 payload)
- Modify: `docs/10-api-contract.md`(eval 请求体删 providedMetrics、新 input-manifest 接口、删 provided-metrics 端点、新 errorCode)
- Modify: `docs/00-decisions.md`(追加本特性决策条目)

- [ ] **Step 1:** `10-api-contract.md`:
  - 评估请求体(§三)删 `providedMetrics` 字段说明,注明"公开评估只收 payload 事实;受治理 metric 全归引擎(取数/SDK/Job 注入走非公开路径)"。
  - 新增 `GET /api/v1/rule/scenes/{sceneCode}/input-manifest?tenantCode=xxx[&eventType=xxx]` 接口(返回 `[{name,dataType,required}]`)。
  - 删 `GET /admin/v1/scenes/{sceneCode}/provided-metrics` 端点条目。
  - §七 errorCode:加 `MISSING_REQUIRED_INPUT`(缺必填 payload 字段)、`INPUT_TYPE_MISMATCH`(payload 字段类型不符),注明 wire errorCode 为 `INVALID_ARGUMENT`、语义码在 message 前缀(与现有约定一致),HTTP 400。
- [ ] **Step 2:** `01-concepts.md`:补"输入契约"概念——payload=事件事实(公开接口唯一业务输入)、metric=受治理指标(引擎侧自取/非公开注入);场景输入清单=该场景 active 规则引用的 payload 字段子集。
- [ ] **Step 3:** `00-decisions.md`:**追加**一条决策(不改历史条目),记本特性五点已定决策(范围=A+B、场景级清单、公开评估只收 payload、缺必填整体拒绝 400、清单来源=发布期快照),引用 spec。
- [ ] **Step 4: 跨文档自洽扫描** — 跑 `doc-consistency-review` skill 检查 01/10/00 + examples README 一致(尤其 providedMetrics 是否全仓清除、errorCode 命名、input-manifest 路由)。修自己改动引入的不一致;既有无关漂移只报告。
- [ ] **Step 5: 提交**

```bash
git add docs/01-concepts.md docs/10-api-contract.md docs/00-decisions.md
git commit -m "docs: 场景输入清单契约/概念/决策更新(公开评估只收 payload + 发现接口 + errorCode)"
```

---

## Task 13: 全量回归 + 收尾

- [ ] **Step 1: 全量 clean test** — `mvn-env` 设环境后 `$MVN clean test`,预期 BUILD SUCCESS(只有 `clean` 强制重编全部 test 类)。
- [ ] **Step 2:** 任一模块失败 → 定位修复重跑,不得 `-DskipTests`。
- [ ] **Step 3: 功能测试(真服务端到端)** — 按项目"功能测试纪律"(本轮含 DB schema 改动 V1_24 + 真落库快照):打可执行包起 `rule-app`,确认 Flyway 跑到 V1_24;按 examples high-risk-login 新剧本:建 scene→发布纯 payload 规则→查 `rule_version.payload_dependencies` 真落库(`[{amount,DECIMAL,true},{country,STRING,true}]`)→调 input-manifest 接口验返回→评估命中/未命中→漏必填字段验 400(MISSING_REQUIRED_INPUT)→类型错验 400(INPUT_TYPE_MISMATCH)。逐表查 payload_dependencies 恒空审计。验完清理测试数据,恢复干净基线。
- [ ] **Step 4: 调 `rule-engine-reviewer` agent** 审本轮"代码 ↔ 文档对齐"(范围:本特性提交区间)。
- [ ] **Step 5:** 据 reviewer 结果修文档/代码不一致(代码侧问题必修;既有无关漂移记后续)。

---

## Self-Review(写计划后自查)

- **Spec 覆盖**:数据模型 payload_dependencies(T1 record/T2 snapshot/T3 codec/T4 DB+实体/T5 发布冻结/T6 eval 读)、快照下发(T2/T3/T6)、对外契约(T7 删 providedMetrics、T9 input-manifest、T10 删 getProvidedMetrics)、评估期校验(T8)、影响面 examples(T11)、docs(T12)、回归+功能测试(T13)。spec §四/§五/§六各项均有对应任务。
- **类型一致**:`PayloadDependency(name,dataType,required)` 贯穿 kernel(record/snapshot/codec)、config(实体列/发布冻结)、eval(校验)、api(manifest 经 `InputFieldSpec` 同形 record 出契约边界);`RuleVersionSnapshot` 末参顺序 metricDependencies→payloadDependencies 在 T2/T3/T5 三处构造点一致;`RuleVersionRow` component 顺序与 T6 的 `@Select` 别名顺序对齐。
- **待实现时核对的现状假设**(已在步骤内标注):`RuleVersionSnapshot.builder()` 必填项、`RuleVersionRow` 真实 component/便利 ctor、`PayloadFieldSpec` 访问器(`.name()/.type()/.required()`)、`MetadataServiceImpl.collectRequiredDeps` 解析链、`TenantQueryService.resolveIdByCode` 签名、`ApiResponse.ok` 工厂、`EvalServiceImpl.doEvaluate` 的 candidates/dry-run 分支、Flyway `JSON NOT NULL` 在已有行上的 ALTER 行为。
- **决策落点**:errorCode 消息前缀法、新端点公开 controller、示例纯 payload 化、dry-run 也校验 —— 均在计划头部"实现决策",评审可推翻。
