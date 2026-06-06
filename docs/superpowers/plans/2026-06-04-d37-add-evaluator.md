# D37 Client 级 addEvaluator() 实现计划

> **Goal：** `RuleEngineClient.Builder` 新增 `addEvaluator()` 方法，让调用方注册自定义 `ConditionEvaluator`，叠加在 `KernelEvaluators.defaults()` 之上（同名自定义可覆盖内置）。
>
> **决策依据：** D37（`00-decisions.md`）

---

## 影响范围

| 模块 | 文件 | 变更类型 |
|------|------|---------|
| `rule-sdk` | `RuleEngineClient.java` | Builder 新增 `addEvaluator()` 字段和方法，构造时合并进 evaluator map |
| `rule-sdk` | `RuleEngineClientTest.java` | 补自定义 evaluator 测试 |

**不改的**：`KernelEvaluators`、`InterpretedExecutor`、`ConditionEvaluator` SPI、服务端任何模块。

---

## Maven 环境

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-25.jdk/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH
MVN=/Users/sunke/.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9/bin/mvn
```

---

## Task 1：先补测试

在 `RuleEngineClientTest.java` 追加：

```java
@Test
void addEvaluator_customOperator_evaluatedCorrectly() {
    // 自定义算子：BLACKLIST_HIT，检查 device_id 是否在黑名单
    RuleVersionSnapshot snap = RuleVersionSnapshot.builder()
            .ruleVersionId(99L).tenantId("t1").sceneCode("device")
            .conditionAst(Condition.of("BLACKLIST_HIT", "device_id",
                    Map.of("list", List.of("dev-001", "dev-002"))).toAst())
            .addTriggerEventType("LOGIN")
            .addDecisionBinding("BLOCK", 100)
            .build();

    try (RuleEngineClient client = RuleEngineClient.builder()
            .localSnapshot(snap)
            .addEvaluator("BLACKLIST_HIT", (node, ctx) -> {
                @SuppressWarnings("unchecked")
                List<Object> list = (List<Object>) node.params().get("list");
                Object val = ctx.providedMetrics().get(node.metricCode());
                return val != null && list.contains(val);
            })
            .build()) {

        // device_id 在黑名单 → 命中
        RuleEvent hit = new RuleEvent("t1", "device", "LOGIN",
                "sub1", UUID.randomUUID().toString(),
                Instant.now(), Map.of(), Map.of("device_id", "dev-001"));
        assertThat(client.evaluate(hit).ruleHit()).isTrue();

        // device_id 不在黑名单 → 未命中
        RuleEvent miss = new RuleEvent("t1", "device", "LOGIN",
                "sub1", UUID.randomUUID().toString(),
                Instant.now(), Map.of(), Map.of("device_id", "dev-999"));
        assertThat(client.evaluate(miss).ruleHit()).isFalse();
    }
}

@Test
void addEvaluator_customOverridesBuiltin() {
    // 覆盖内置 GT 算子，验证自定义优先
    RuleVersionSnapshot snap = RuleVersionSnapshot.builder()
            .ruleVersionId(98L).tenantId("t1").sceneCode("override")
            .conditionAst(Condition.gt("amount", 1000).toAst())
            .addTriggerEventType("ORDER")
            .addDecisionBinding("PASS", 10)
            .build();

    try (RuleEngineClient client = RuleEngineClient.builder()
            .localSnapshot(snap)
            // 自定义 GT：永远返回 true，无论 amount 值
            .addEvaluator("GT", (node, ctx) -> true)
            .build()) {

        RuleEvent event = new RuleEvent("t1", "override", "ORDER",
                "sub1", UUID.randomUUID().toString(),
                Instant.now(), Map.of(), Map.of("amount", 1));  // amount=1 < 1000
        assertThat(client.evaluate(event).ruleHit()).isTrue();  // 自定义覆盖，应命中
    }
}
```

> **依赖**：测试中用到了 `Condition.of()`，需要 D36 先完成。

---

## Task 2：先确认测试编译失败（addEvaluator 不存在）

```bash
$MVN -pl rule-sdk -am test -Dtest='RuleEngineClientTest' -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | grep "addEvaluator\|BUILD"
```

---

## Task 3：修改 `RuleEngineClient`

### Builder 新增字段和方法

```java
private final Map<String, ConditionEvaluator> extraEvaluators = new HashMap<>();
```

```java
/**
 * 注册自定义条件算子，key 为 conditionType 字符串。
 * 与 KernelEvaluators.defaults() 合并，同名自定义覆盖内置。
 *
 * @param conditionType 算子类型标识，与 ConditionNode.conditionType 对应
 * @param evaluator     算子实现
 */
public Builder addEvaluator(String conditionType, ConditionEvaluator evaluator) {
    extraEvaluators.put(conditionType, evaluator); return this;
}
```

### 构造器中合并 evaluator map

```java
// 以 KernelEvaluators.defaults() 为底，用户自定义叠加（可覆盖同名内置）
Map<String, ConditionEvaluator> evaluators = new HashMap<>(KernelEvaluators.defaults());
evaluators.putAll(b.extraEvaluators);
RuleVersionExecutor executor = b.executor != null
        ? b.executor
        : new InterpretedExecutor(evaluators);
```

### 需要补充的 import

```java
import com.sstlfsj.rule.kernel.api.spi.evaluator.ConditionEvaluator;
import java.util.HashMap;
```

---

## Task 4：运行测试，确认通过

```bash
$MVN -pl rule-sdk -am test -Dtest='RuleEngineClientTest' -Dsurefire.failIfNoSpecifiedTests=false
```

---

## Task 5：全模块验证

```bash
$MVN test
```

---

## Task 6：Commit

```bash
git add rule-sdk/src/main/java/com/sstlfsj/rule/sdk/RuleEngineClient.java \
        rule-sdk/src/test/java/com/sstlfsj/rule/sdk/RuleEngineClientTest.java
git commit -m "feat(sdk): D37 Client 级 addEvaluator()，自定义算子叠加内置"
```
