# D38 注解精简实现计划

> **Goal：** 删除 `@ConditionType.requiresMetric`、`@MetricSourceType.defaultTimeoutMs`、`@MetricSourceType.defaultCacheTtlSeconds` 三个无消费方的字段，对齐 D20 后的架构现状。
>
> **决策依据：** D38（`00-decisions.md`）

---

## 影响范围

| 模块 | 文件 | 变更类型 |
|------|------|---------|
| `rule-kernel` | `ConditionType.java` | 删除 `requiresMetric` 字段 |
| `rule-kernel` | `MetricSourceType.java` | 删除 `defaultTimeoutMs` / `defaultCacheTtlSeconds` 字段 |

**不改的**：`@ActionType`（有运行时消费方，保持现状）、所有服务端模块、`KernelEvaluators`。

---

## 前置检查

执行改动前，先确认被删字段在全项目中无任何使用（赋值或读取）：

```bash
# 确认 requiresMetric 无消费方
grep -r "requiresMetric" --include="*.java" /Users/sunke/dev/ai-project/rule-engine/

# 确认 defaultTimeoutMs / defaultCacheTtlSeconds 无消费方
grep -r "defaultTimeoutMs\|defaultCacheTtlSeconds" --include="*.java" \
  /Users/sunke/dev/ai-project/rule-engine/
```

期望：仅出现在注解定义文件本身，无其他引用。

---

## Maven 环境

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-25.jdk/Contents/Home
export PATH=$JAVA_HOME/bin:$PATH
MVN=/Users/sunke/.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9/bin/mvn
```

---

## Task 1：精简 `@ConditionType`

将 `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/annotation/ConditionType.java` 从：

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ConditionType {
    String value();
    String displayName() default "";
    String paramsSchema() default "{}";
    boolean requiresMetric() default false;  // 删除
}
```

改为：

```java
/** 标注 ConditionEvaluator 实现类的条件类型标识与元数据。 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ConditionType {
    /** 条件类型唯一标识，与 ConditionNode.conditionType 对应。 */
    String value();
    /** 前端展示名称。 */
    String displayName() default "";
    /** 条件参数的 JSON Schema，供前端表单渲染校验。 */
    String paramsSchema() default "{}";
}
```

---

## Task 2：精简 `@MetricSourceType`

将 `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/annotation/MetricSourceType.java` 从：

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface MetricSourceType {
    String value();
    String paramsSchema() default "{}";
    int defaultTimeoutMs() default 1000;          // 删除
    int defaultCacheTtlSeconds() default 60;      // 删除
}
```

改为：

```java
/** 标注 MetricSourceHandler 实现类的指标来源类型标识与元数据。 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface MetricSourceType {
    /** 指标来源类型唯一标识。 */
    String value();
    /** 来源参数的 JSON Schema，供前端渲染校验。 */
    String paramsSchema() default "{}";
}
```

---

## Task 3：全量测试确认无编译错误

```bash
$MVN test
```

期望：全模块 BUILD SUCCESS（删除字段不会引入编译错误，因为无任何消费方使用这些字段）

---

## Task 4：Commit

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/annotation/ConditionType.java \
        rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/annotation/MetricSourceType.java
git commit -m "refactor(kernel): D38 注解精简——删除无消费方字段 requiresMetric/defaultTimeoutMs/defaultCacheTtlSeconds"
```
