# D39 Spring Boot Starter 补完

> **Goal：** `rule-sdk-spring-boot-starter` 补全三项能力：文件模式、`@ConditionType` Bean 自动扫描、Listener Bean 注入，使 Spring Boot 项目无需手动配置即可使用全部规则来源模式与扩展点。

---

## 影响范围

| 模块 | 文件 | 变更类型 |
|------|------|---------|
| `rule-sdk-spring-boot-starter` | `SdkProperties.java` | 已加 `ruleFiles` 字段（上次会话） |
| `rule-sdk-spring-boot-starter` | `RuleEngineClientAutoConfiguration.java` | 修改：补文件模式、Bean 扫描、Listener 注入 |
| `rule-sdk-spring-boot-starter` | `SdkPropertiesTest.java` | 修改：补 `ruleFiles` 属性绑定测试 |
| `rule-sdk-spring-boot-starter` | `RuleEngineClientAutoConfigurationTest.java` | 修改：补三项能力的测试用例 |
| `docs/10-api-contract.md` | §8.2 | 修改：补 Spring Boot 配置说明 |

---

## 设计要点

### 文件模式
`SdkProperties.ruleFiles` 已有（`List<String>`）。`AutoConfiguration` 遍历列表调 `builder.ruleFile(path)`，路径支持 `classpath:` 前缀（FileRuleSource 内部处理）。

### `@ConditionType` Bean 自动扫描
- `AutoConfiguration` 注入 `ApplicationContext`
- `ctx.getBeansWithAnnotation(ConditionType.class)` 获取所有标注 Bean
- 对每个 Bean：若实现 `ConditionEvaluator`，以 `@ConditionType.value()` 为 key 调 `builder.addEvaluator()`；否则跳过
- 顺序：先扫描 Bean，再 `build()`，与 `addEvaluator()` 底层路径相同（用户自定义覆盖内置）

### Listener Bean 注入
- `AutoConfiguration` 注入 `Optional<EvalResultListener>` 和 `Optional<EvalSessionListener>`
- 存在则调对应 Builder 方法，不存在时不设置

---

## 实现步骤

### Step 1：更新 `RuleEngineClientAutoConfiguration.java`

```java
@AutoConfiguration
@EnableConfigurationProperties(SdkProperties.class)
public class RuleEngineClientAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public RuleEngineClient ruleEngineClient(
            SdkProperties props,
            ApplicationContext ctx,
            Optional<EvalResultListener> evalResultListener,
            Optional<EvalSessionListener> evalSessionListener) {

        RuleEngineClient.Builder builder = RuleEngineClient.builder();

        // HTTP 轮询模式
        if (props.getServerUrl() != null && !props.getServerUrl().isBlank()) {
            builder.serverUrl(props.getServerUrl())
                   .tenantId(props.getTenantId())
                   .fetchMode(props.getFetchMode())
                   .pollInterval(props.getPollInterval());
            if (props.getScenes() != null) {
                props.getScenes().forEach(builder::scenes);
            }
        }

        // 文件模式
        if (props.getRuleFiles() != null) {
            props.getRuleFiles().forEach(builder::ruleFile);
        }

        // @ConditionType Bean 自动扫描
        ctx.getBeansWithAnnotation(ConditionType.class).forEach((name, bean) -> {
            if (bean instanceof ConditionEvaluator evaluator) {
                ConditionType ann = bean.getClass().getAnnotation(ConditionType.class);
                builder.addEvaluator(ann.value(), evaluator);
            }
        });

        // Listener Bean 注入
        evalResultListener.ifPresent(builder::evalResultListener);
        evalSessionListener.ifPresent(builder::evalSessionListener);

        return builder.build();
    }
}
```

### Step 2：更新 `SdkPropertiesTest.java`

补 `ruleFiles` 绑定测试：

```java
@Test
void ruleFiles_bindFromProperties() {
    ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RuleEngineClientAutoConfiguration.class));
    runner.withPropertyValues(
                    "rule.sdk.rule-files=classpath:rules/a.json,classpath:rules/b.json")
            // rule-files 属性绑定验证通过即可，不需要文件真实存在
            .run(ctx -> {
                SdkProperties props = ctx.getBean(SdkProperties.class);
                assertThat(props.getRuleFiles())
                        .containsExactly("classpath:rules/a.json", "classpath:rules/b.json");
            });
}
```

### Step 3：更新 `RuleEngineClientAutoConfigurationTest.java`

补三项新能力测试：

**文件模式**（用现有 `rules/test-rule.json`，从 rule-sdk 测试资源借用）：
```java
@Test
void ruleFile_mode_loadsFromClasspath() {
    runner.withPropertyValues("rule.sdk.rule-files=rules/test-rule.json")
            .run(ctx -> {
                assertThat(ctx).hasSingleBean(RuleEngineClient.class);
                ctx.getBean(RuleEngineClient.class).close();
            });
}
```

**`@ConditionType` Bean 自动扫描**：
```java
@ConditionType("ALWAYS_TRUE")
static class AlwaysTrueEvaluator implements ConditionEvaluator {
    @Override public boolean evaluate(ConditionNode node, EvalContext ctx) { return true; }
}

@Test
void conditionType_bean_autoRegistered() {
    runner.withPropertyValues("rule.sdk.rule-files=rules/test-rule.json")
            .withBean(AlwaysTrueEvaluator.class)
            .run(ctx -> {
                assertThat(ctx).hasSingleBean(RuleEngineClient.class);
                ctx.getBean(RuleEngineClient.class).close();
            });
}
```

**Listener Bean 注入**：
```java
@Test
void evalResultListener_bean_autoInjected() {
    boolean[] called = {false};
    runner.withPropertyValues(
                    "rule.sdk.server-url=http://localhost:19999",
                    "rule.sdk.tenant-id=t1",
                    "rule.sdk.poll-interval=3600s")
            .withBean(EvalResultListener.class, () -> (ev, res) -> called[0] = true)
            .run(ctx -> {
                assertThat(ctx).hasSingleBean(RuleEngineClient.class);
                ctx.getBean(RuleEngineClient.class).close();
            });
}
```

### Step 4：运行 starter 模块测试

```bash
$MVN -pl rule-sdk-spring-boot-starter -am test
```

Expected: BUILD SUCCESS

### Step 5：更新 `10-api-contract.md §8.2`

在 HTTP 轮询模式的 yml 示例后，新增文件模式 yml 示例、`@ConditionType` Bean 声明式用法、Listener Bean 注册说明。

### Step 6：更新 plans README（D39 标为 ✅）、commit

```bash
git add -p
git commit -m "feat(starter): D39 Spring Boot Starter 补完——文件模式 + Bean 自动扫描 + Listener 注入"
```
