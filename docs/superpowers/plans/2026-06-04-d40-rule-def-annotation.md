# D40 SDK 注解模式 — `@RuleDef` + `AnnotationRuleSource`

> **更新 (2026-06-11)**：本计划描述的 `@RuleDef` 用 `long id()` 直接指定代理键，已被规则身份 `(code, version)` 改造（D59，阶段甲）取代——`id()` 移除，改为 `String code()`（逻辑身份）+ `long version() default 1`，并新增 `String tenantId() default ""`（空 = 继承 client 租户）；代理键 `ruleVersionId` 不再手填，由 `AnnotationRuleSource` 按 `(tenant, scene, code)` 稳定哈希派生。下文 `id()` 相关签名与示例保留为历史记录，最新口径见 [`docs/04-extension.md`](../../04-extension.md) §九 + [`docs/00-decisions.md`](../../00-decisions.md) D59。

> **Goal：** 新增第四种规则来源模式：`@RuleDef` 标注规则类，实现 `InlineRuleSpec.condition()` 返回条件 DSL，`AnnotationRuleSource` 扫描后装载到评估索引。Spring Starter 自动收集容器内的 `InlineRuleSpec` Bean，非 Spring 场景手动传入列表。

---

## 影响范围

| 模块 | 文件 | 变更类型 |
|------|------|---------|
| `rule-kernel` | `api/annotation/RuleDef.java` | 新建 |
| `rule-kernel` | `api/annotation/DecisionBinding.java` | 新建（`@RuleDef.decisions` 嵌套注解） |
| `rule-sdk` | `InlineRuleSpec.java` | 新建 |
| `rule-sdk` | `source/AnnotationRuleSource.java` | 新建 |
| `rule-sdk-spring-boot-starter` | `RuleEngineClientAutoConfiguration.java` | 修改：收集 `InlineRuleSpec` Bean |
| `rule-sdk` 测试 | `source/AnnotationRuleSourceTest.java` | 新建 |
| `rule-sdk-spring-boot-starter` 测试 | `RuleEngineClientAutoConfigurationTest.java` | 修改：补注解模式测试 |

---

## 接口设计

### `@DecisionBinding`（嵌套注解）

```java
// rule-kernel api/annotation/DecisionBinding.java
@Target({})
@Retention(RetentionPolicy.RUNTIME)
public @interface DecisionBinding {
    String code();
    int priority() default 0;
}
```

### `@RuleDef`

```java
// rule-kernel api/annotation/RuleDef.java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RuleDef {
    /** 规则版本 ID，调用方负责唯一且稳定（幂等 loadInto 依赖此值）。 */
    long id();
    /** 租户 ID。 */
    String tenantId();
    /** 场景编码。 */
    String sceneCode();
    /** 触发事件类型；空数组表示通配（等价于 loadInto 时写入 "*"）。 */
    String[] trigger() default {};
    /** Decision 绑定列表。 */
    DecisionBinding[] decisions() default {};
}
```

### `InlineRuleSpec`

```java
// rule-sdk InlineRuleSpec.java
public interface InlineRuleSpec {
    /** 返回规则条件，由 AnnotationRuleSource 调用 toAst() 转为 AST。 */
    Condition condition();
}
```

### `AnnotationRuleSource`

```java
// rule-sdk source/AnnotationRuleSource.java
public class AnnotationRuleSource implements RuleSource {
    private final List<InlineRuleSpec> specs;

    public AnnotationRuleSource(List<InlineRuleSpec> specs) {
        this.specs = List.copyOf(specs);
    }

    @Override
    public void loadInto(SceneRuleIndex index) {
        for (InlineRuleSpec spec : specs) {
            RuleDef ann = spec.getClass().getAnnotation(RuleDef.class);
            if (ann == null) continue; // 跳过未标注的 spec

            RuleVersionSnapshot.Builder builder = RuleVersionSnapshot.builder()
                    .ruleVersionId(ann.id())
                    .tenantId(ann.tenantId())
                    .sceneCode(ann.sceneCode())
                    .conditionAst(spec.condition().toAst());

            for (String trigger : ann.trigger()) {
                builder.addTriggerEventType(trigger);
            }
            for (DecisionBinding d : ann.decisions()) {
                builder.addDecisionBinding(d.code(), d.priority());
            }

            new DslRuleSource(List.of(builder.build())).loadInto(index);
        }
    }
}
```

---

## 用法示例

**非 Spring（直接使用）**：

```java
@RuleDef(id = 1L, tenantId = "t1", sceneCode = "fraud",
         trigger = "TRANSACTION",
         decisions = @DecisionBinding(code = "BLOCK", priority = 100))
class AmountFraudRule implements InlineRuleSpec {
    @Override
    public Condition condition() {
        return Condition.gt("amount", 1000)
                        .and(Condition.in("country", "CN", "HK"));
    }
}

try (RuleEngineClient client = RuleEngineClient.builder()
        .ruleSource(new AnnotationRuleSource(List.of(new AmountFraudRule())))
        .build()) {
    EvalResult result = client.evaluate(event);
}
```

**Spring Boot（自动装配）**：

```java
@Component
@RuleDef(id = 1L, tenantId = "t1", sceneCode = "fraud",
         trigger = "TRANSACTION",
         decisions = @DecisionBinding(code = "BLOCK", priority = 100))
public class AmountFraudRule implements InlineRuleSpec {
    @Override
    public Condition condition() {
        return Condition.gt("amount", 1000);
    }
}
// Starter AutoConfiguration 自动发现并装载，@Autowired RuleEngineClient 即可使用
```

---

## 实现步骤

### Step 1：新建 `@DecisionBinding` 注解（rule-kernel）

文件：`rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/annotation/DecisionBinding.java`

### Step 2：新建 `@RuleDef` 注解（rule-kernel）

文件：`rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/annotation/RuleDef.java`

同步：`rule-kernel` 注解测试 `RuleDefTest.java`

### Step 3：新建 `InlineRuleSpec` 接口（rule-sdk）

文件：`rule-sdk/src/main/java/com/sstlfsj/rule/sdk/InlineRuleSpec.java`

### Step 4：新建 `AnnotationRuleSource`（rule-sdk）

文件：`rule-sdk/src/main/java/com/sstlfsj/rule/sdk/source/AnnotationRuleSource.java`

同步：`AnnotationRuleSourceTest.java` — 覆盖：loadInto 写入索引、无 @RuleDef 的 spec 跳过、空 trigger 通配、多 spec 多规则

### Step 5：更新 Starter AutoConfiguration

`RuleEngineClientAutoConfiguration` 收集容器内所有 `InlineRuleSpec` Bean：

```java
List<InlineRuleSpec> inlineSpecs = new ArrayList<>(
        ctx.getBeansOfType(InlineRuleSpec.class).values());
if (!inlineSpecs.isEmpty()) {
    builder.ruleSource(new AnnotationRuleSource(inlineSpecs));
}
```

同步：`RuleEngineClientAutoConfigurationTest` 补 `inlineRuleSpec_bean_autoLoaded` 测试

### Step 6：运行测试

```bash
$MVN -pl rule-kernel,rule-sdk,rule-sdk-spring-boot-starter -am test
```

Expected: BUILD SUCCESS

### Step 7：更新 `10-api-contract.md`

`§8.1` 模式总览表补注解模式行；新增 `§8.x 注解模式`（@RuleDef 用法 + Spring 自动装配）。

### Step 8：commit

```bash
git commit -m "feat(sdk): D40 @RuleDef 注解模式——InlineRuleSpec + AnnotationRuleSource + Starter 自动装配"
```
