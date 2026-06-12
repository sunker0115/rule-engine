---
name: add-expression-engine
description: "在 rule-engine 项目新增一个 EXPRESSION_SCRIPT 表达式引擎实现(如 Aviator / Lua / SpEL)时用这个 skill。固化既有装配定式:纯引擎模块 + -spring-boot-starter + List<ExpressionEngine> 自动收集,评估/发布/SDK 三处零改动。检测信号:用户说'加 Aviator 引擎''新增 Lua 表达式引擎''再支持一种脚本语言'。"
---

# 新增 rule-expression 表达式引擎

EXPRESSION_SCRIPT 脚本规则的引擎是 SPI 可插拔的。盒内默认 CEL(`rule-expression-cel`),新增引擎(Aviator/Lua/SpEL…)照本定式走,**评估侧(eval-svc)、发布侧(config-svc)、SDK 三处装配零改动**——它们都按 `List<ExpressionEngine>` / `ObjectProvider<ExpressionEngine>` 自动收集,新引擎只要 starter 注册一个 bean 就被纳入,按 `lang()` 路由(同 lang 重复声明 fail-fast)。

**唯一可参照的完整样板**:`rule-expression-cel` + `rule-expression-cel-spring-boot-starter`。动手前通读这两个模块。

## 装配定式(不可破)

```
纯引擎模块 rule-expression-<lang>(无 Spring)
  └─ -spring-boot-starter 模块(持 @AutoConfiguration,注册引擎 bean)
        └─ 消费方(eval-svc / config-svc / rule-sdk-spring-boot-starter)自动收集,零改动
```

- **引擎模块保持纯 Java、无 Spring**:`KernelArchTest` 禁 kernel 包碰 Spring;且引擎模块本身也不放 `@AutoConfiguration`(集中到 starter)。包根 `com.sstlfsj.rule.expression.<lang>`。
- **starter 持 Spring 装配**:包 `com.sstlfsj.rule.expression.<lang>.starter`,`@AutoConfiguration` + `@Bean @ConditionalOnMissingBean` 注册引擎,`META-INF/spring/...AutoConfiguration.imports` 列出该类。
- **lang() 唯一**:不同引擎 `lang()` 返回不同值,互不冲突;消费方按 lang 建路由表,重复 lang 装配期 `IllegalStateException` fail-fast。

## 步骤(按序)

### 1. kernel 加 ExpressionLang 枚举值
`rule-kernel/.../api/model/ExpressionLang.java` 是开放枚举,加一个值(如 `AVIATOR`)。引擎 `lang()` 返回 `ExpressionLang.AVIATOR.tag()`(== 枚举名)。`ScriptSource.lang` 用同一字符串路由。

### 2. 新建纯引擎模块 `rule-expression-<lang>`
pom 仿 `rule-expression-cel/pom.xml`:parent = rule-engine + `${revision}`;依赖 `rule-kernel`(parent 已管版本,不写 version)+ 引擎库(parent 未管的显式钉 version)+ 可选 Caffeine + test(junit-jupiter/assertj)。

实现 `com.sstlfsj.rule.kernel.api.spi.expression.ExpressionEngine` 四方法 + `CompiledExpression`:

| 方法 | 职责 | 备注 |
|---|---|---|
| `String lang()` | 返回 `ExpressionLang.<X>.tag()` | 路由 key |
| `CompiledExpression compile(String source)` | 编译(含语法检查),失败抛 `ExpressionCompileException` | **内部按 source 内容哈希缓存**(Caffeine,对标 CEL);`CompiledExpression.referencedVariables()` 返回点路径集合 `{metrics.x, payload.y, subject.z}` 供发布期冻依赖 |
| `Object evaluate(CompiledExpression, Map<String,Object> bindings)` | 求值,返回 **Boolean/String/Number/null** 之一,失败抛 `ExpressionEvaluateException` | bindings 顶层键 `metrics`/`payload`/`subject`(均 `Map<String,Object>`)+ `now`(Instant)。**绑定面规整**:把数据源值对齐引擎期望类型(CEL 的 `adaptBindings`/`normalizeNumber` 是范例——JSON 来的 Integer/BigDecimal 要按引擎数值模型规整) |
| `default void typeCheck(String, ScriptTypeEnv)` | 发布期类型检查,只 check 不 eval | **弱引擎不实现**(default no-op);强类型引擎才 override。`ScriptTypeEnv(Map<String,DataType> metrics, payload)` 给被引用变量声明类型。**DataType→引擎类型映射必须与运行期数值规整对齐**(见 CEL:LONG→INT、DOUBLE→DOUBLE、DECIMAL→DYN 因运行期按值浮动、STRING/BOOL/TIMESTAMP 精确、LIST/UNKNOWN→DYN) |

**safe-by-design**:引擎必须无文件/反射/类加载内建(对标 CEL)。若所选库默认开放这些能力,必须关掉/不注册——这是 EXPRESSION_SCRIPT 安全前提,不是可选项。

### 3. 新建 starter 模块 `rule-expression-<lang>-spring-boot-starter`
pom 仿 `rule-expression-cel-spring-boot-starter/pom.xml`:依赖纯引擎模块 + `spring-boot-autoconfigure` + test `spring-boot-starter-test`。
- `<lang>ExpressionEngineAutoConfiguration`(包 `...expression.<lang>.starter`):`@AutoConfiguration` + `@Bean @ConditionalOnMissingBean` 返回引擎单例。
- `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 写入该类全限定名(一行)。

### 4. 根 pom 两处登记
- `<modules>` 加两个新模块(放在 `rule-expression-cel-spring-boot-starter` 之后)。
- `<dependencyManagement>` 加两个新模块的 GAV(`version=${project.version}`)。
- **传递依赖版本陷阱**:若引擎库的传递依赖与 Spring Boot BOM 钉的版本冲突(CEL 的 `protobuf-java`:dev.cel 需 ≥4.33,Boot BOM 钉 4.26.1 → 运行期崩),在根 pom `<dependencyManagement>` 钉死正确版本(单一真相源)。引入新引擎务必核对其传递依赖是否被 Boot BOM 降级。

### 5. 验证装配零改动
**不要改** eval-svc `EvalAutoConfiguration.scriptExecutor(List<ExpressionEngine>)`、config-svc `PublishService(List<ExpressionEngine>)`、`rule-sdk-spring-boot-starter` 的 `ObjectProvider<ExpressionEngine>` 收集——它们已就绪。新引擎 starter 被引入哪个服务,该服务就自动收集到。SDK 本地模式经 `RuleEngineClient.Builder.expressionEngine(...)` opt-in。

## 测试约定
- 引擎模块测试仿 `CelExpressionEngineTest`:`lang()` / 各返回类型 `evaluate`(Boolean/String/Number/null)/ 数值规整(JSON 来源 Integer/BigDecimal/Float)/ 编译缓存命中(同源同实例)/ `typeCheck`(well-typed 过、类型不符拒)/ **safe-by-design**(IO/反射表达式编译即拒)。
- 跑测试先用 `mvn-env` skill 设环境。跨模块改动(动了 kernel ExpressionLang)必须带 `-am`;最后用全量 `$MVN clean test`(无 -pl)兜底。

## 红线
- 引擎模块出现 Spring 依赖 = 错(装配进 starter)。
- 改 eval-svc/config-svc/sdk 的引擎收集逻辑去"特判"新引擎 = 错(定式就是零改动自动收集)。
- typeCheck 的类型映射与运行期数值规整不一致 = 会误拒合法脚本或漏掉真错(见 CEL 的 DECIMAL→DYN 取舍)。
- 跳过 safe-by-design 校验 = 安全红线。
