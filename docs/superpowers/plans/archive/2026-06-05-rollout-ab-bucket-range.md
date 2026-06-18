# ROLLOUT 灰度增强：桶区间互斥 + 发布期校验 + 清除 rollout 死列

> **For agentic workers:** 用 superpowers:subagent-driven-development 或 executing-plans 逐 task 执行。步骤用 `- [ ]` 跟踪。

**Goal:** ① 给 ROLLOUT pre-gate 加可选桶区间 `bucketStart`/`bucketEnd`，支持 A/B 真互斥（同 experimentId 共享种子 + 不相交区间 → 每用户恰好命中其一），percentage 退化为 `[0,percentage)` 语法糖；② 发布期校验 ROLLOUT params；③ 彻底删除只写不读的 `rollout` 死列。

**Architecture:** experimentId/percentage/区间继续放 `pre_gates` JSON 的 ROLLOUT 项 params（不动 CreateRuleRequest 顶层签名）。evaluate 改动纯在 `RolloutPreGate`（eval-svc）。发布期校验内联进 `PublishService.publish()`，follow 现有 `validateTriggerEventTypes` / DECISION_TREE 校验模式，抛 `IllegalArgumentException`（GlobalExceptionHandler 统一映射 `INVALID_ARGUMENT`，无 errorCode 枚举）。`rollout` 列通过新增 Flyway `V1_4__drop_rollout.sql` 删除（不改已应用的 V1_0，保持迁移不可变）。

**Tech Stack:** Java 25 / Spring Boot 4 / Spring Modulith / MyBatis-Plus / Flyway / Jackson 3.x（`tools.jackson`）/ guava Hashing（murmur3）/ JUnit5 + Mockito + AssertJ。

**已敲定决策（来自用户）：** Flyway 走 V1_4 drop 迁移；互斥用 bucketStart/bucketEnd 显式区间；发布期仅单规则校验（不查兄弟规则）；保持 pre_gates JSON 承载 + 新增类型化 `RolloutParams` 仅供发布期校验。

**环境：** 跑测试前先按 `mvn-env` skill 设 JDK 25 + `$MVN`。本项目编译需 JDK 25。

---

## Phase 1：ROLLOUT 桶区间（互斥）—— RolloutPreGate evaluate

**Files:**
- Modify: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/pregate/RolloutPreGate.java`
- Test: `rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/pregate/RolloutPreGateTest.java`

### Task 1.1：先写失败测试（band 互斥）

- [ ] **Step 1: 在 RolloutPreGateTest 追加 band 用例**

在文件末尾 `}` 前追加。辅助方法 `ctx(...)` 已有（构造裸 gateParams Map），新用例直接 new PreGateContext 塞 bucketStart/bucketEnd。

```java
    @Test
    void bucketRange_hit_passes_and_miss_blocked() {
        // 全区间 [0,100) 必命中；空区间 [0,0) 必拦
        RuleEvent event = new RuleEvent("1", "scene", "E", "uHit",
                "eid", Instant.now(), Map.of(), Map.of());
        PreGateContext full = new PreGateContext("1", "scene", "uHit", event, 1L,
                Map.of("bucketStart", 0, "bucketEnd", 100));
        PreGateContext empty = new PreGateContext("1", "scene", "uHit", event, 1L,
                Map.of("bucketStart", 0, "bucketEnd", 0));
        assertTrue(full.gateParams() != null && gate.evaluate(full).passed(), "[0,100) 必命中");
        assertFalse(gate.evaluate(empty).passed(), "[0,0) 必拦");
    }

    @Test
    void bucketRange_disjoint_sameExperimentId_isMutuallyExclusive() {
        // 真互斥：同 experimentId 共享种子，A 占 [0,50)、B 占 [50,100)，每个 subject 恰好命中其一
        for (int i = 0; i < 100; i++) {
            String sid = "user" + i;
            RuleEvent event = new RuleEvent("1", "scene", "E", sid,
                    "eid", Instant.now(), Map.of(), Map.of());
            PreGateContext ruleA = new PreGateContext("1", "scene", sid, event, 1L,
                    Map.of("experimentId", "exp-mx", "bucketStart", 0, "bucketEnd", 50));
            PreGateContext ruleB = new PreGateContext("1", "scene", sid, event, 2L,
                    Map.of("experimentId", "exp-mx", "bucketStart", 50, "bucketEnd", 100));
            boolean a = gate.evaluate(ruleA).passed();
            boolean b = gate.evaluate(ruleB).passed();
            assertTrue(a ^ b, "subject " + sid + " 必须恰好命中 A/B 其一（互斥）");
        }
    }

    @Test
    void bucketRange_deterministic_sameInput() {
        RuleEvent event = new RuleEvent("1", "scene", "E", "uDet",
                "eid", Instant.now(), Map.of(), Map.of());
        PreGateContext c = new PreGateContext("1", "scene", "uDet", event, 7L,
                Map.of("experimentId", "exp-1", "bucketStart", 10, "bucketEnd", 60));
        assertEquals(gate.evaluate(c).passed(), gate.evaluate(c).passed());
    }

    @Test
    void bothPercentageAndRangeAbsent_failOpen() {
        RuleEvent event = new RuleEvent("1", "scene", "E", "u1",
                "eid", Instant.now(), Map.of(), Map.of());
        PreGateContext ctx = new PreGateContext("1", "scene", "u1", event, 1L, Map.of());
        assertTrue(gate.evaluate(ctx).passed(), "无 percentage 也无区间时 fail-open");
    }
```

- [ ] **Step 2: 运行确认失败**

```bash
$MVN -pl rule-eval-svc -am test -Dtest='RolloutPreGateTest' -Dsurefire.failIfNoSpecifiedTests=false
```
预期：`bucketRange_*` 相关用例 FAIL（当前 evaluate 不认 bucketStart/bucketEnd，且 percentage 缺失时直接 fail-open，区间被忽略）。

### Task 1.2：实现 band 逻辑

- [ ] **Step 3: 重构 RolloutPreGate.evaluate**

bucket 计算要前移到 percentage 短路之前（band 模式不依赖 percentage）。band 存在时优先用 band，否则用 percentage，两者皆无则 fail-open。整体替换 `evaluate` 方法体：

```java
    @Override
    public PreGateResult evaluate(PreGateContext ctx) {
        Object percentageParam = ctx.gateParams().get("percentage");
        Object startParam = ctx.gateParams().get("bucketStart");
        Object endParam = ctx.gateParams().get("bucketEnd");

        // percentage 与桶区间都未配置时 fail-open（视为无灰度限制，全量放行）
        if (percentageParam == null && startParam == null && endParam == null) {
            return PreGateResult.pass();
        }

        // experimentId 存在时共享种子，保证同实验 A/B 互斥；否则退回 ruleVersionId 独立分桶
        Object experimentId = ctx.gateParams().get("experimentId");
        String hashInput = experimentId != null
                ? ctx.subjectId() + ":" + experimentId
                : ctx.subjectId() + ":" + ctx.ruleVersionId();
        // & 0x7fffffff 屏蔽符号位，避免 Integer.MIN_VALUE 取绝对值仍为负数的 JVM 陷阱
        int bucket = (Hashing.murmur3_32_fixed()
                .hashString(hashInput, StandardCharsets.UTF_8)
                .asInt() & 0x7fffffff) % 100;

        // 桶区间模式（A/B 互斥）：bucketStart <= bucket < bucketEnd；优先于 percentage
        if (startParam != null && endParam != null) {
            int start = Integer.parseInt(startParam.toString());
            int end = Integer.parseInt(endParam.toString());
            return (bucket >= start && bucket < end)
                    ? PreGateResult.pass() : PreGateResult.blocked("ROLLOUT");
        }

        // percentage 模式（语义等价于区间 [0, percentage)）
        int percentage = Integer.parseInt(percentageParam.toString());
        if (percentage >= 100) return PreGateResult.pass();
        if (percentage <= 0)   return PreGateResult.blocked("ROLLOUT");
        return bucket < percentage ? PreGateResult.pass() : PreGateResult.blocked("ROLLOUT");
    }
```

- [ ] **Step 4: 更新类级 Javadoc**

在现有 Javadoc 的 hash 种子说明后补一段，说明两种命中模式：

```java
 * <p>命中模式（二选一，桶区间优先）：
 * <ul>
 *   <li>桶区间：配置 {@code bucketStart}/{@code bucketEnd} 时，{@code bucketStart <= bucket < bucketEnd} 命中。
 *       配合同一 {@code experimentId} 给多条规则不相交区间，即实现 A/B 互斥（每 subject 恰好命中其一）。</li>
 *   <li>百分比：仅配置 {@code percentage} 时，{@code bucket < percentage} 命中，等价于区间 {@code [0, percentage)}。</li>
 * </ul>
 * <p>percentage 与桶区间均未配置时 fail-open（全量放行）。
```

- [ ] **Step 5: 运行测试（全绿）**

```bash
$MVN -pl rule-eval-svc -am test -Dtest='RolloutPreGateTest' -Dsurefire.failIfNoSpecifiedTests=false
```
预期：PASS（含原有 11 个 + 新增 4 个）。

- [ ] **Step 6: Commit**

```bash
git add rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/pregate/RolloutPreGate.java \
        rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/pregate/RolloutPreGateTest.java
git commit -m "feat(eval): RolloutPreGate 支持 bucketStart/bucketEnd 桶区间，实现 A/B 真互斥"
```

---

## Phase 2：发布期校验 ROLLOUT params

**Files:**
- Create: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/publish/RolloutParams.java`
- Modify: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/publish/PublishService.java`
- Test: `rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/publish/PublishServiceTest.java`

### Task 2.1：新建 RolloutParams record

- [ ] **Step 1: 创建 RolloutParams.java**

package-private 内部解析载体，仅供 PublishService 校验用。静态工厂从裸 Map 解析（容忍 String/Number 混入，与 RolloutPreGate 的 `toString()` 解析风格一致）。

```java
package com.sstlfsj.rule.config.internal.publish;

import java.util.Map;

/**
 * ROLLOUT pre-gate 的 params 类型化视图，仅用于发布期校验。
 * 字段均可选：percentage 为百分比模式；bucketStart/bucketEnd 为桶区间（互斥）模式；experimentId 为共享分桶种子。
 */
record RolloutParams(Integer percentage, Integer bucketStart, Integer bucketEnd, String experimentId) {

    /** 从裸 gateParams Map 解析，缺失键为 null；值可为 Number 或可解析的 String。 */
    static RolloutParams from(Map<String, Object> params) {
        return new RolloutParams(
                toInt(params.get("percentage")),
                toInt(params.get("bucketStart")),
                toInt(params.get("bucketEnd")),
                params.get("experimentId") == null ? null : params.get("experimentId").toString());
    }

    private static Integer toInt(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        return Integer.parseInt(v.toString().trim());
    }
}
```

### Task 2.2：先写失败测试（发布期校验）

- [ ] **Step 2: 在 PublishServiceTest 追加校验用例**

复用现有 `@BeforeEach` fixture（draftRule/scene/draftVersion）。校验在 step 3.5 后触发，所以异常用例只需 mock 到能进入校验即可（selectById + sceneMapper + selectOne 返回草稿）。合法用例走完整发布流程，参照现有 `publish_draftRule_createsVersionAndUpdatesDefinition` 的 mock 集。

```java
    @Test
    void publish_rolloutPercentageOutOfRange_throws() {
        when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);
        when(sceneMapper.selectById(5L)).thenReturn(scene);
        when(ruleVersionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(draftVersion);
        draftVersion.setTriggerEventTypes("[]");
        draftVersion.setPreGates("[{\"gateType\":\"ROLLOUT\",\"params\":{\"percentage\":101}}]");
        assertThatThrownBy(() -> publishService.publish(1L, 10L, "actor"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("percentage");
    }

    @Test
    void publish_rolloutInvalidRange_throws() {
        when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);
        when(sceneMapper.selectById(5L)).thenReturn(scene);
        when(ruleVersionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(draftVersion);
        draftVersion.setTriggerEventTypes("[]");
        // bucketStart >= bucketEnd 非法
        draftVersion.setPreGates("[{\"gateType\":\"ROLLOUT\",\"params\":{\"bucketStart\":60,\"bucketEnd\":50}}]");
        assertThatThrownBy(() -> publishService.publish(1L, 10L, "actor"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bucket");
    }

    @Test
    void publish_rolloutBlankExperimentId_throws() {
        when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);
        when(sceneMapper.selectById(5L)).thenReturn(scene);
        when(ruleVersionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(draftVersion);
        draftVersion.setTriggerEventTypes("[]");
        draftVersion.setPreGates("[{\"gateType\":\"ROLLOUT\",\"params\":{\"percentage\":50,\"experimentId\":\"  \"}}]");
        assertThatThrownBy(() -> publishService.publish(1L, 10L, "actor"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("experimentId");
    }
```

注：合法发布的回归已由现有 `publish_draftRule_createsVersionAndUpdatesDefinition`（preGates="[]"）覆盖——空 pre_gates 不触发 ROLLOUT 校验。另加一个合法 ROLLOUT 区间的正向用例：

```java
    @Test
    void publish_rolloutValidRange_publishes() {
        when(ruleDefinitionMapper.selectById(10L)).thenReturn(draftRule);
        when(sceneMapper.selectById(5L)).thenReturn(scene);
        when(ruleVersionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(draftVersion);
        when(ruleVersionMapper.maxVersion(10L)).thenReturn(0L);
        when(ruleVersionMapper.insert((RuleVersion) any())).thenReturn(1);
        when(ruleDefinitionMapper.updateById((RuleDefinition) any())).thenReturn(1);
        when(auditLogMapper.insert((AuditLog) any())).thenReturn(1);
        when(astSerializer.fromJson(anyString()))
                .thenReturn(new ConditionNode("c.type", "m.code", null, Map.of(), 0.0));
        draftVersion.setTriggerEventTypes("[]");
        draftVersion.setPreGates("[{\"gateType\":\"ROLLOUT\",\"params\":{\"experimentId\":\"exp-1\",\"bucketStart\":0,\"bucketEnd\":50}}]");
        assertThat(publishService.publish(1L, 10L, "actor")).isNotNull();
    }
```

- [ ] **Step 3: 运行确认失败**

```bash
$MVN -pl rule-config-svc -am test -Dtest='PublishServiceTest' -Dsurefire.failIfNoSpecifiedTests=false
```
预期：3 个异常用例 FAIL（当前发布不校验 pre_gates，非法值不抛异常）。

### Task 2.3：实现校验

- [ ] **Step 4: PublishService 新增 validatePreGateParams 私有方法**

放在 `validateTriggerEventTypes` 方法附近（约 324 行后）。JSON 解析失败容错跳过（与 validateTriggerEventTypes 一致），仅语义越界抛 `IllegalArgumentException`。

```java
    /**
     * 校验 pre_gates 中 ROLLOUT 项的 params 合法性（仅单规则校验，不查兄弟规则）。
     * percentage∈[0,100]；若给桶区间则 0<=bucketStart<bucketEnd<=100；experimentId 非空白。
     * pre_gates JSON 格式异常时容错跳过（不阻断发布），仅参数语义越界抛 IllegalArgumentException。
     */
    private void validatePreGateParams(String preGatesJson) {
        if (preGatesJson == null || preGatesJson.isBlank()) return;
        java.util.List<java.util.Map<String, Object>> gates;
        try {
            gates = objectMapper.readValue(preGatesJson, new tools.jackson.core.type.TypeReference<>() {});
        } catch (Exception e) {
            return;   // 格式异常容错跳过
        }
        for (java.util.Map<String, Object> gate : gates) {
            if (!"ROLLOUT".equals(String.valueOf(gate.get("gateType")))) continue;
            Object p = gate.get("params");
            if (!(p instanceof java.util.Map<?, ?> raw)) continue;
            @SuppressWarnings("unchecked")
            RolloutParams params = RolloutParams.from((java.util.Map<String, Object>) raw);

            if (params.percentage() != null
                    && (params.percentage() < 0 || params.percentage() > 100)) {
                throw new IllegalArgumentException(
                        "ROLLOUT percentage 必须在 [0,100]，实际值: " + params.percentage());
            }
            boolean hasStart = params.bucketStart() != null;
            boolean hasEnd = params.bucketEnd() != null;
            if (hasStart != hasEnd) {
                throw new IllegalArgumentException(
                        "ROLLOUT bucketStart/bucketEnd 必须成对出现");
            }
            if (hasStart) {
                int s = params.bucketStart(), en = params.bucketEnd();
                if (s < 0 || en > 100 || s >= en) {
                    throw new IllegalArgumentException(
                            "ROLLOUT 桶区间非法，要求 0<=bucketStart<bucketEnd<=100，实际: ["
                                    + s + "," + en + ")");
                }
            }
            if (params.experimentId() != null && params.experimentId().isBlank()) {
                throw new IllegalArgumentException(
                        "ROLLOUT experimentId 不得为空白字符串");
            }
        }
    }
```

- [ ] **Step 5: 在 publish() 调用校验**

在 `validateTriggerEventTypes(...)` 调用（约 98 行）之后插入一行：

```java
        // 3.6. 校验 pre_gates 中 ROLLOUT 项参数合法性
        validatePreGateParams(draftVersion.getPreGates());
```

- [ ] **Step 6: 运行测试（全绿）**

```bash
$MVN -pl rule-config-svc -am test -Dtest='PublishServiceTest' -Dsurefire.failIfNoSpecifiedTests=false
```
预期：PASS（含新增 4 个）。

- [ ] **Step 7: Commit**

```bash
git add rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/publish/RolloutParams.java \
        rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/publish/PublishService.java \
        rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/publish/PublishServiceTest.java
git commit -m "feat(config): 发布期校验 ROLLOUT params（percentage/桶区间/experimentId）"
```

---

## Phase 3：清除 rollout 死列（DDL + 代码 + 测试）

> 顺序：先建迁移与改 init DDL 注释无关——只加 V1_4 drop。再删代码引用。删列后 `RuleVersion` 不再有 rollout 字段，PublishService 两处 setRollout 与测试引用必须同步删，否则编译失败。

**Files:**
- Create: `rule-config-svc/src/main/resources/db/migration/V1_4__drop_rollout.sql`
- Create: `rule-eval-svc/src/test/resources/db/migration/V1_4__drop_rollout.sql`
- Modify: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/domain/RuleVersion.java`
- Modify: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/publish/PublishService.java`
- Modify: `rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/domain/RuleVersionTest.java`
- Modify: `rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/publish/PublishServiceTest.java`
- Modify: `rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/integration/EvalIntegrationTest.java`

### Task 3.1：Flyway drop 迁移

- [ ] **Step 1: 建 prod 迁移**

`rule-config-svc/src/main/resources/db/migration/V1_4__drop_rollout.sql`：
```sql
-- rollout 列为 D6 初版灰度快照遗留，ROLLOUT 改由 pre_gates 承载后该列只写不读，删除。
ALTER TABLE rule_version DROP COLUMN rollout;
```

- [ ] **Step 2: 建 test 迁移（内容相同）**

`rule-eval-svc/src/test/resources/db/migration/V1_4__drop_rollout.sql`：内容同上。

### Task 3.2：删代码引用

- [ ] **Step 3: RuleVersion 删 rollout 字段**

删 `rule-config-svc/.../domain/RuleVersion.java:21` 的 `private String rollout;`（Lombok 自动移除 getter/setter）。

- [ ] **Step 4: PublishService 删两处 setRollout**

- 删 publish() 中 `newRv.setRollout(...)`（约 170-171 行整段）。
- 删 createDraft helper 中 `rv.setRollout("{}");`（约 296 行）。

- [ ] **Step 5: 删测试引用**

- `RuleVersionTest.java`：删第 19 行 `ver.setRollout("{}");` 与第 32 行 `assertEquals("{}", ver.getRollout());`。
- `PublishServiceTest.java`：删第 78 行 `draftVersion.setRollout("{}");`。
- `EvalIntegrationTest.java`：INSERT 语句删 `rollout` 列名与对应值。列名行改为 `condition_ast, decision_bindings, pre_gates,`（去掉 `rollout,`），并删除值列表里的 `'{"strategy":"ALL"}',` 这一行。

- [ ] **Step 6: 全量编译 + 测试两个模块**

```bash
$MVN -pl rule-config-svc -am test
$MVN -pl rule-eval-svc -am test
```
预期：编译通过（无 getRollout/setRollout 残留），全绿。EvalIntegrationTest（Testcontainers）跑 V1_0→V1_2→V1_3→V1_4，drop 后 INSERT 不含 rollout，通过。

- [ ] **Step 7: Commit**

```bash
git add rule-config-svc/src/main/resources/db/migration/V1_4__drop_rollout.sql \
        rule-eval-svc/src/test/resources/db/migration/V1_4__drop_rollout.sql \
        rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/domain/RuleVersion.java \
        rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/publish/PublishService.java \
        rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/domain/RuleVersionTest.java \
        rule-config-svc/src/test/java/com/sstlfsj/rule/config/internal/publish/PublishServiceTest.java \
        rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/integration/EvalIntegrationTest.java
git commit -m "refactor(storage): 删除只写不读的 rule_version.rollout 死列（V1_4 迁移）"
```

---

## Phase 4：文档对齐

**Files:**
- Modify: `docs/08-evolution.md`（§2.16）
- Modify: `docs/05-storage.md`（DDL §，删 rollout 列定义）
- Modify: `docs/10-api-contract.md`（pre_gates ROLLOUT params schema）

### Task 4.1：08-evolution §2.16

- [ ] **Step 1: 补桶区间互斥两种模式说明**

在 §2.16 "已实装" 段后补一条，说明 bucketStart/bucketEnd 区间模式实现真互斥、percentage 为 `[0,percentage)` 语法糖、发布期已加单规则校验。标题"实验互斥"现已名实相符（区间不相交 = 真互斥；同区间 = 一致分桶）。

### Task 4.2：05-storage.md

- [ ] **Step 2: 删 rollout 列定义 + 加说明**

删 `rule_version` DDL 里的 `rollout JSON NOT NULL` 行（约 160 行）。在该表附近注明：灰度配置（含 ROLLOUT percentage/桶区间/experimentId）由 `pre_gates` 列承载，原 `rollout` 列已于 V1_4 废弃删除。

### Task 4.3：10-api-contract.md

- [ ] **Step 3: 补 pre_gates ROLLOUT params schema**

在 pre_gates 契约处补 ROLLOUT 项的 params 字段说明：`percentage`（0-100，百分比模式）、`bucketStart`/`bucketEnd`（0-100 桶区间，互斥模式，成对出现）、`experimentId`（可选，同实验共享分桶种子）。给一个互斥示例（A `[0,50)` + B `[50,100)` 同 experimentId）。发布期校验规则一并写明。

- [ ] **Step 4: 文档自洽性扫描 + Commit**

跨多文档改动，按 CLAUDE.md 先跑 `doc-consistency-review` skill（或 `rule-engine-reviewer` agent 审代码↔文档对齐），无误后：

```bash
git add docs/08-evolution.md docs/05-storage.md docs/10-api-contract.md
git commit -m "docs: ROLLOUT 桶区间互斥 + 发布期校验 + rollout 列废弃对齐"
```

---

## 执行顺序

1. Phase 1（RolloutPreGate band + 测试）
2. Phase 2（RolloutParams + 发布期校验 + 测试）
3. Phase 3（drop rollout 列：迁移 + 删码 + 改测试，全量验证）
4. Phase 4（文档对齐 + 一致性扫描）

## 全量验证

```bash
$MVN -pl rule-eval-svc,rule-config-svc -am test
```
预期：两模块全绿，含新增 RolloutPreGateTest band 用例、PublishServiceTest 校验用例、EvalIntegrationTest（无 rollout 列）通过。

## 范围边界（本次不做）

- 跨规则桶区间不重叠校验（需查兄弟 rule_version，用户选"仅单规则校验"）——留待后续。
- experimentId/区间提升为 CreateRuleRequest 顶层类型化字段——用户选保持 pre_gates JSON 承载。
- experimentId 命名字符集强约束（当前仅校验非空白）——如需正则规范后续再加。
