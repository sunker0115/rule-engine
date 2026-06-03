# v1.5：ActionHandler dryRun 实装 + 灰度 A/B 互斥 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 补全两个 v1 遗留项：① `BlockTransactionHandler` / `SendAlertHandler` 补齐 `dryRun()` 方法（消除 `DRY_RUN_NOT_IMPLEMENTED` errorCode）；② `RolloutPreGate` 支持 `experimentId` 字段，同实验内多规则共享 hash 种子（保证 A/B 互斥）。

**Architecture:** `dryRun()` 实装仅修改两个 handler 类，`ActionHandler` SPI `default` 实现继续兜底；灰度 A/B 只改 `RolloutPreGate` 的 hash 输入——`rollout` JSON 里出现可选 `experimentId` 键时用 `hash(subjectId:experimentId)` 替换 `hash(subjectId:ruleVersionId)`，向后兼容，无 DDL 变更，无 `PreGateContext` 结构变更（`experimentId` 已存在于 `gateParams` Map 中）。

**Tech Stack:** Java 25 / Spring Boot 4 / JUnit 5 / AssertJ / Mockito / rule-kernel SPI / rule-eval-svc

**Maven 环境（每次运行测试前设置）：**
```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-25.jdk/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH
MVN=/Users/sunke/.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9/bin/mvn
```

---

## 文件变更清单

| 操作 | 文件 |
|------|------|
| 修改 | `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/action/BlockTransactionHandler.java` |
| 修改（测试）| `rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/action/BlockTransactionHandlerTest.java` |
| 修改 | `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/action/SendAlertHandler.java` |
| 修改（测试）| `rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/action/SendAlertHandlerTest.java` |
| 修改 | `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/pregate/RolloutPreGate.java` |
| 修改（测试）| `rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/pregate/RolloutPreGateTest.java` |

---

## Task 1: BlockTransactionHandler.dryRun() 实装

**背景：** `ActionHandler.dryRun()` 已有 `default` 兜底，v1 handler 未 override。补齐后 dry-run 试算时该 handler 返回 `SUCCESS` 而非 `SKIPPED/DRY_RUN_NOT_IMPLEMENTED`。

**Files:**
- Modify: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/action/BlockTransactionHandler.java`
- Modify test: `rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/action/BlockTransactionHandlerTest.java`

- [x] **Step 1: 写失败测试**

在 `BlockTransactionHandlerTest.java` 追加：

```java
@Test
void dryRun_returnsSuccess_withCorrectActionIdAndType() {
    ActionContext ctx = new ActionContext(
            "action-1", "BLOCK_TRANSACTION", Map.of(), null, null, null);

    ActionResult result = handler.dryRun(ctx);

    assertThat(result.status()).isEqualTo(ActionResult.ActionStatus.SUCCESS);
    assertThat(result.actionId()).isEqualTo("action-1");
    assertThat(result.actionType()).isEqualTo("BLOCK_TRANSACTION");
}

@Test
void dryRun_doesNotReturnSkipped() {
    ActionContext ctx = new ActionContext(
            "action-1", "BLOCK_TRANSACTION", Map.of(), null, null, null);

    ActionResult result = handler.dryRun(ctx);

    assertThat(result.status()).isNotEqualTo(ActionResult.ActionStatus.SKIPPED);
    assertThat(result.errorCode()).isNull();
}
```

- [x] **Step 2: 运行测试确认失败**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-25.jdk/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH
MVN=/Users/sunke/.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9/bin/mvn
$MVN -pl rule-eval-svc -am test -Dtest='BlockTransactionHandlerTest' -Dsurefire.failIfNoSpecifiedTests=false
```

期望：FAIL — `dryRun_returnsSuccess_withCorrectActionIdAndType` 返回 SKIPPED（`default` 实现）而非 SUCCESS

- [x] **Step 3: 实现 `dryRun()` 方法**

完整替换 `BlockTransactionHandler.java`：

```java
package com.sstlfsj.rule.eval.internal.action;

import com.sstlfsj.rule.kernel.api.annotation.ActionType;
import com.sstlfsj.rule.kernel.api.model.ActionContext;
import com.sstlfsj.rule.kernel.api.model.ActionResult;
import com.sstlfsj.rule.kernel.api.spi.action.ActionHandler;
import org.springframework.stereotype.Component;

/** 阻断交易 ActionHandler，v1 stub 实现，execute 和 dryRun 均直接返回 success。 */
@Component
@ActionType("BLOCK_TRANSACTION")
public class BlockTransactionHandler implements ActionHandler {

    /**
     * 执行阻断交易动作（v1 stub，直接返回成功）。
     *
     * @param ctx 动作执行上下文
     * @return 执行结果
     */
    @Override
    public ActionResult execute(ActionContext ctx) {
        return ActionResult.success(ctx.actionId(), ctx.actionType());
    }

    /**
     * dry-run 预览阻断交易动作（v1 stub，返回与 execute 相同的成功结果）。
     *
     * @param ctx 动作执行上下文
     * @return 预览结果，status=SUCCESS
     */
    @Override
    public ActionResult dryRun(ActionContext ctx) {
        return ActionResult.success(ctx.actionId(), ctx.actionType());
    }
}
```

- [x] **Step 4: 运行测试确认通过**

```bash
$MVN -pl rule-eval-svc -am test -Dtest='BlockTransactionHandlerTest' -Dsurefire.failIfNoSpecifiedTests=false
```

期望：BUILD SUCCESS，3 tests passed

- [x] **Step 5: 提交**

```bash
git add rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/action/BlockTransactionHandler.java \
        rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/action/BlockTransactionHandlerTest.java
git commit -m "feat(eval): BlockTransactionHandler 实装 dryRun() 方法"
```

---

## Task 2: SendAlertHandler.dryRun() 实装

**背景：** 与 Task 1 对称，`SendAlertHandler` 同样补齐 `dryRun()`。

**Files:**
- Modify: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/action/SendAlertHandler.java`
- Modify test: `rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/action/SendAlertHandlerTest.java`

- [x] **Step 1: 写失败测试**

在 `SendAlertHandlerTest.java` 追加：

```java
@Test
void dryRun_returnsSuccess_withCorrectActionIdAndType() {
    ActionContext ctx = new ActionContext(
            "action-2", "SEND_ALERT", Map.of(), null, null, null);

    ActionResult result = handler.dryRun(ctx);

    assertThat(result.status()).isEqualTo(ActionResult.ActionStatus.SUCCESS);
    assertThat(result.actionId()).isEqualTo("action-2");
    assertThat(result.actionType()).isEqualTo("SEND_ALERT");
}

@Test
void dryRun_doesNotReturnSkipped() {
    ActionContext ctx = new ActionContext(
            "action-2", "SEND_ALERT", Map.of(), null, null, null);

    ActionResult result = handler.dryRun(ctx);

    assertThat(result.status()).isNotEqualTo(ActionResult.ActionStatus.SKIPPED);
    assertThat(result.errorCode()).isNull();
}
```

- [x] **Step 2: 运行测试确认失败**

```bash
$MVN -pl rule-eval-svc -am test -Dtest='SendAlertHandlerTest' -Dsurefire.failIfNoSpecifiedTests=false
```

期望：FAIL — `dryRun_returnsSuccess_withCorrectActionIdAndType` 返回 SKIPPED（`default` 实现）

- [x] **Step 3: 实现 `dryRun()` 方法**

完整替换 `SendAlertHandler.java`：

```java
package com.sstlfsj.rule.eval.internal.action;

import com.sstlfsj.rule.kernel.api.annotation.ActionType;
import com.sstlfsj.rule.kernel.api.model.ActionContext;
import com.sstlfsj.rule.kernel.api.model.ActionResult;
import com.sstlfsj.rule.kernel.api.spi.action.ActionHandler;
import org.springframework.stereotype.Component;

/** 发送告警 ActionHandler，v1 stub 实现，execute 和 dryRun 均直接返回 success。 */
@Component
@ActionType("SEND_ALERT")
public class SendAlertHandler implements ActionHandler {

    /**
     * 执行发送告警动作（v1 stub，直接返回成功）。
     *
     * @param ctx 动作执行上下文
     * @return 执行结果
     */
    @Override
    public ActionResult execute(ActionContext ctx) {
        return ActionResult.success(ctx.actionId(), ctx.actionType());
    }

    /**
     * dry-run 预览发送告警动作（v1 stub，返回与 execute 相同的成功结果）。
     *
     * @param ctx 动作执行上下文
     * @return 预览结果，status=SUCCESS
     */
    @Override
    public ActionResult dryRun(ActionContext ctx) {
        return ActionResult.success(ctx.actionId(), ctx.actionType());
    }
}
```

- [x] **Step 4: 运行测试确认通过**

```bash
$MVN -pl rule-eval-svc -am test -Dtest='SendAlertHandlerTest' -Dsurefire.failIfNoSpecifiedTests=false
```

期望：BUILD SUCCESS，3 tests passed

- [x] **Step 5: 提交**

```bash
git add rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/action/SendAlertHandler.java \
        rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/action/SendAlertHandlerTest.java
git commit -m "feat(eval): SendAlertHandler 实装 dryRun() 方法"
```

---

## Task 3: RolloutPreGate — experimentId A/B 互斥灰度

**背景：** 当前 `RolloutPreGate` 的 hash 种子为 `subjectId:ruleVersionId`，同一实验的两条规则各自独立分桶，导致同一用户可能同时命中或同时不命中（A/B 实验互斥失效）。`rollout` JSON 的 `gateParams` Map 里新增可选键 `experimentId`：存在时用 `subjectId:experimentId` 作为种子，不存在时行为与 v1 完全一致（向后兼容）。`PreGateContext` 结构无需变更，`experimentId` 天然走 `gateParams`。

**Files:**
- Modify: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/pregate/RolloutPreGate.java`
- Modify test: `rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/pregate/RolloutPreGateTest.java`

- [x] **Step 1: 写失败测试**

在 `RolloutPreGateTest.java` 追加以下三个测试方法：

```java
@Test
void experimentId_presentAndSame_sameBucketForBothVersions() {
    // 两条规则 ruleVersionId 不同但 experimentId 相同 → 同一 subject 分桶相同（互斥保证）
    RuleEvent event = new RuleEvent("1", "scene", "E", "userX",
            "eid", Instant.now(), Map.of(), Map.of());
    PreGateContext ctx1 = new PreGateContext("1", "scene", "userX", event,
            1L, Map.of("percentage", 50, "experimentId", "exp-001"));
    PreGateContext ctx2 = new PreGateContext("1", "scene", "userX", event,
            2L, Map.of("percentage", 50, "experimentId", "exp-001"));

    // 两个 ruleVersionId 下，相同 experimentId 使结果一致
    assertEquals(gate.evaluate(ctx1).passed(), gate.evaluate(ctx2).passed(),
            "同 experimentId 下同一 subject 在不同规则版本的分桶应相同");
}

@Test
void experimentId_differentValues_differentBuckets() {
    // 不同 experimentId → 分桶独立（不同实验互不影响）
    // 统计 100 个 subject，至少 1 个在两个 experimentId 下结果不同
    boolean anyDifference = false;
    for (int i = 0; i < 100; i++) {
        RuleEvent event = new RuleEvent("1", "scene", "E", "user" + i,
                "eid", Instant.now(), Map.of(), Map.of());
        PreGateContext ctxA = new PreGateContext("1", "scene", "user" + i, event,
                1L, Map.of("percentage", 50, "experimentId", "exp-A"));
        PreGateContext ctxB = new PreGateContext("1", "scene", "user" + i, event,
                1L, Map.of("percentage", 50, "experimentId", "exp-B"));
        if (gate.evaluate(ctxA).passed() != gate.evaluate(ctxB).passed()) {
            anyDifference = true;
            break;
        }
    }
    assertTrue(anyDifference, "不同 experimentId 应产生不同分桶");
}

@Test
void experimentId_absent_behaviorUnchanged() {
    // 无 experimentId 时与 v1 行为一致（不影响现有规则）
    // 用两个不同 ruleVersionId 应产生独立分桶（已有测试 differentRuleVersions_differentRolloutBuckets 覆盖）
    // 本测试验证"无 experimentId 时 percentage=100 仍全量通过"
    for (int i = 0; i < 20; i++) {
        PreGateResult result = gate.evaluate(ctx("user" + i, (long) i, 100));
        assertTrue(result.passed(), "无 experimentId + percentage=100 应全量放行");
    }
}
```

- [x] **Step 2: 运行测试确认失败**

```bash
$MVN -pl rule-eval-svc -am test -Dtest='RolloutPreGateTest' -Dsurefire.failIfNoSpecifiedTests=false
```

期望：FAIL — `experimentId_presentAndSame_sameBucketForBothVersions` 失败（当前总用 `ruleVersionId` 分桶，两版本结果不同）

- [x] **Step 3: 修改 RolloutPreGate**

完整替换 `RolloutPreGate.java`：

```java
package com.sstlfsj.rule.eval.internal.pregate;

import com.google.common.hash.Hashing;
import com.sstlfsj.rule.kernel.api.model.PreGateContext;
import com.sstlfsj.rule.kernel.api.model.PreGateResult;
import com.sstlfsj.rule.kernel.api.spi.pregate.PreGate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * ROLLOUT Pre-Gate：按百分比灰度放行。
 *
 * <p>hash 种子规则：
 * <ul>
 *   <li>gateParams 含 {@code experimentId} 时：{@code hash(subjectId:experimentId)} —— 同实验内
 *       多规则共享分桶，保证 A/B 互斥（同一 subject 在同实验的不同规则版本结果一致）。</li>
 *   <li>不含 {@code experimentId} 时：{@code hash(subjectId:ruleVersionId)} —— 各规则版本独立分桶，
 *       与 v1 行为完全一致。</li>
 * </ul>
 *
 * <p>缺少 percentage 配置时 fail-open（视为全量放行）。
 */
@Component
public class RolloutPreGate implements PreGate {

    @Override
    public String gateType() {
        return "ROLLOUT";
    }

    @Override
    public PreGateResult evaluate(PreGateContext ctx) {
        Object percentageParam = ctx.gateParams().get("percentage");
        if (percentageParam == null) {
            // 无配置时 fail-open
            return PreGateResult.pass();
        }
        int percentage = Integer.parseInt(percentageParam.toString());
        if (percentage >= 100) return PreGateResult.pass();
        if (percentage <= 0)   return PreGateResult.blocked("ROLLOUT");

        // experimentId 存在时共享种子，保证同实验 A/B 互斥；否则退回 ruleVersionId 独立分桶
        Object experimentId = ctx.gateParams().get("experimentId");
        String hashInput = experimentId != null
                ? ctx.subjectId() + ":" + experimentId
                : ctx.subjectId() + ":" + ctx.ruleVersionId();

        // & 0x7fffffff 屏蔽符号位，避免 Integer.MIN_VALUE 取绝对值仍为负数的 JVM 陷阱
        int bucket = (Hashing.murmur3_32_fixed()
                .hashString(hashInput, StandardCharsets.UTF_8)
                .asInt() & 0x7fffffff) % 100;

        return bucket < percentage ? PreGateResult.pass() : PreGateResult.blocked("ROLLOUT");
    }
}
```

- [x] **Step 4: 运行测试确认通过**

```bash
$MVN -pl rule-eval-svc -am test -Dtest='RolloutPreGateTest' -Dsurefire.failIfNoSpecifiedTests=false
```

期望：BUILD SUCCESS，10 tests passed（原 7 + 新增 3）

- [x] **Step 5: 运行 rule-eval-svc 全量测试**

```bash
$MVN -pl rule-eval-svc -am test
```

期望：BUILD SUCCESS，全部通过

- [x] **Step 6: 提交**

```bash
git add rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/pregate/RolloutPreGate.java \
        rule-eval-svc/src/test/java/com/sstlfsj/rule/eval/internal/pregate/RolloutPreGateTest.java
git commit -m "feat(eval): RolloutPreGate 支持 experimentId A/B 互斥灰度"
```

---

## Task 4: 全量测试验证 + 文档更新

**Files:**
- Modify: `docs/00-decisions.md`（D7 / D6 实装状态更新）

- [x] **Step 1: 运行受影响模块全量测试**

```bash
$MVN -pl rule-eval-svc -am test
```

期望：BUILD SUCCESS，全部通过，无跳过

- [x] **Step 2: 更新 docs/00-decisions.md D7 v1.5 状态**

在 `docs/00-decisions.md` 的 D7 节"v1 实装状态"段末尾追加一句：

```
v1.5 已实装：`BlockTransactionHandler.dryRun()` + `SendAlertHandler.dryRun()` 均 override 返回 `ActionResult.success()`；`DRY_RUN_NOT_IMPLEMENTED` errorCode 不再产生。
```

- [x] **Step 3: 更新 docs/00-decisions.md D6 关于 experimentId**

在 `docs/00-decisions.md` D6 节末尾（**v1 已知缺陷** 段落）将原有文字：

```
**v1 已知缺陷（待 v1.5 修）**：当前 hash 种子为 `(subjectId, ruleVersionId)`，A/B 实验场景下无法保证互斥。演进方向：`rollout` 新增可选字段 `experimentId`，同实验规则共享 hash 种子。详见 [`08-evolution.md §2.16`](./08-evolution.md)。
```

替换为：

```
**v1.5 已修**：`RolloutPreGate` 支持 `gateParams.experimentId` 可选字段。存在时以 `hash(subjectId:experimentId)` 作种子，同实验内多规则版本共享分桶（A/B 互斥）；不存在时行为与 v1 完全一致（向后兼容，无 DDL 变更）。
```

- [x] **Step 4: 提交文档**

```bash
git add docs/00-decisions.md
git commit -m "docs: 同步 v1.5 handler dryRun 实装 + experimentId A/B 灰度完成状态"
```
