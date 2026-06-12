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
pom 仿 `rule-expression-cel/pom.xml`:parent = rule-engine + `${revision}`;依赖 `rule-kernel`(不写 version)+ 引擎库 + 可选 Caffeine + test(junit-jupiter/assertj)。**模块 pom 一律不写第三方库 version**——所有版本集中在根 pom(`<properties>` 加 `<xxx.version>` + `<dependencyManagement>` 列该库 GAV,见步骤 4),模块只写 groupId/artifactId。

实现 `com.sstlfsj.rule.kernel.api.spi.expression.ExpressionEngine` 四方法 + `CompiledExpression`:

| 方法 | 职责 | 备注 |
|---|---|---|
| `String lang()` | 返回 `ExpressionLang.<X>.tag()` | 路由 key |
| `CompiledExpression compile(String source)` | 编译(含语法检查),失败抛 `ExpressionCompileException` | **内部按 source 内容哈希缓存**(Caffeine,对标 CEL);`CompiledExpression.referencedVariables()` 返回点路径集合 `{metrics.x, payload.y, subject.z}` 供发布期冻依赖 |
| `Object evaluate(CompiledExpression, Map<String,Object> bindings)` | 求值,返回 **Boolean/String/Number/null** 之一,失败抛 `ExpressionEvaluateException` | bindings 顶层键 `metrics`/`payload`/`subject`(均 `Map<String,Object>`)+ `now`(Instant)。**绑定面规整**:把数据源值对齐引擎期望类型(CEL 的 `adaptBindings`/`normalizeNumber` 是范例——JSON 来的 Integer/BigDecimal 要按引擎数值模型规整) |
| `default void typeCheck(String, ScriptTypeEnv)` | 发布期类型检查,只 check 不 eval | **弱引擎不实现**(default no-op);强类型引擎才 override。`ScriptTypeEnv(Map<String,DataType> metrics, payload)` 给被引用变量声明类型。**DataType→引擎类型映射必须与运行期数值规整对齐**(见 CEL:LONG→INT、DOUBLE→DOUBLE、DECIMAL→DYN 因运行期按值浮动、STRING/BOOL/TIMESTAMP 精确、LIST/UNKNOWN→DYN) |

**safe-by-design(动手前先过这道可行性闸,决定能不能收这个引擎)**:引擎必须无法访问文件/反射/类加载/命令执行/网络。脚本是不可信输入,这是硬前提,不是可选项。按"能否沙箱化"把候选引擎分三档:

| 引擎 | 类型 | 沙箱机制(已落地的真相) |
|---|---|---|
| CEL | 受限表达式语言 | 天生安全:无 IO/反射/类加载内建 |
| JsonLogic | JSON 数据驱动 | 天生安全:无代码执行能力 |
| Aviator | 弱类型表达式 | 默认即不解析任意 Java 类(默认安全,**实测验证** `java.lang.Runtime` 不可达) |
| QLExpress | 弱类型/动态 | `QLExpressRunStrategy.setSandBoxMode(true)`(进程级全局静态,放引擎静态块设一次)。默认黑名单只挡 `exec`/`exit` 等终端调用,**挡不住类解析本身**,必须开沙箱 |
| JEXL | 弱类型 | `new JexlBuilder().permissions(JexlPermissions.RESTRICTED)`(显式声明,不靠库默认)。禁 `Runtime/System/ProcessBuilder/Class/反射/net/File` |
| Groovy | **完整 JVM 语言** | 本体无运行期沙箱 → 用 groovy-sandbox(**`org.craftercms:groovy-sandbox` 维护分支,别用停更于 2018 的 `org.kohsuke:groovy-sandbox:1.19`**;包名同为 `org.kohsuke.groovy.sandbox`,drop-in):`SandboxTransformer`(编译期把每次调用改写为转发)+ deny-by-default `GroovyValueFilter`(运行期按接收者类型白名单逐调用过滤,静态调用/构造器一律禁,`class`/`metaClass` 禁)。绑定变量经 `Script.getProperty` 解析,须把 `metrics/payload/subject/now` 显式加白 |
| ~~Janino~~ | Java 编译器 | ✗ **无可用沙箱**:唯一机制是 `SecurityManager`,JDK18+ 已废、JDK25 彻底禁用。直接执行任意 Java → **拒绝接收**(白名单 ClassLoader 也挡不住 `getClass()` 反射链) |

判定规则:
- **天生安全 / 有内建沙箱开关**(CEL/JsonLogic/Aviator/QLExpress/JEXL)→ 收,按上表把开关打开。
- **完整 JVM 语言但有运行期拦截件**(Groovy + groovy-sandbox)→ 可收,但要自建 deny-by-default 白名单 + 逐条逃逸测试兜底,成本高。
- **完整语言且无可用运行期沙箱**(Janino;以及任何只能靠 SecurityManager 的库)→ **不收**。别用编译期 customizer(SecureASTCustomizer)凑数:实测它要么严到误伤合法方法调用、要么漏 `metaClass`/`execute` 等运行期动态分发,挡不住。
- **结论必须实测,不靠臆断**:是否安全、用什么开关、拦截发生在编译期还是运行期——写 10 行 probe 直接跑(本会话曾据 groovy-sandbox 声明的依赖误判它跑不了 Groovy 5,一跑就推翻)。

### 3. 新建 starter 模块 `rule-expression-<lang>-spring-boot-starter`
pom 仿 `rule-expression-cel-spring-boot-starter/pom.xml`:依赖纯引擎模块 + `spring-boot-autoconfigure` + test `spring-boot-starter-test`。
- `<lang>ExpressionEngineAutoConfiguration`(包 `...expression.<lang>.starter`):`@AutoConfiguration` + `@Bean @ConditionalOnMissingBean` 返回引擎单例。
- `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 写入该类全限定名(一行)。

### 4. 根 pom 登记(版本单一真相源)
- `<modules>` 加两个新模块(放在已有 `rule-expression-*` 之后)。
- `<dependencyManagement>` 加两个新模块的 GAV(`version=${project.version}`)。
- **第三方引擎库版本集中在根 pom**:`<properties>` 加 `<xxx.version>`,`<dependencyManagement>` 加该库 GAV + `version=${xxx.version}`;模块 pom 只引 groupId/artifactId 不写 version。所有引擎库(cel/aviator/qlexpress/json-logic/commons-jexl/groovy/groovy-sandbox)都按此集中——版本散落到模块 pom = 错。
- **传递依赖版本陷阱**:若引擎库的传递依赖与 Spring Boot BOM 钉的版本冲突(CEL 的 `protobuf-java`:dev.cel 需 ≥4.33,Boot BOM 钉 4.26.1 → 运行期崩;groovy-sandbox 传递的 `org.apache.groovy:groovy` 也靠根 pom 钉到 `${groovy.version}` 收口),在根 pom `<dependencyManagement>` 钉死正确版本即可,通常无需 `<exclusion>`。引入新引擎务必核对其传递依赖是否被 Boot BOM 降级。

### 5. 验证装配零改动
**不要改** eval-svc `EvalAutoConfiguration.scriptExecutor(List<ExpressionEngine>)`、config-svc `PublishService(List<ExpressionEngine>)`、`rule-sdk-spring-boot-starter` 的 `ObjectProvider<ExpressionEngine>` 收集——它们已就绪。新引擎 starter 被引入哪个服务,该服务就自动收集到。SDK 本地模式经 `RuleEngineClient.Builder.expressionEngine(...)` opt-in。

## 测试约定
- 引擎模块测试仿 `CelExpressionEngineTest`:`lang()` / 各返回类型 `evaluate`(Boolean/String/Number/null)/ 数值规整(JSON 来源 Integer/BigDecimal/Float)/ 编译缓存命中(同源同实例)/ `typeCheck`(well-typed 过、类型不符拒)/ **safe-by-design 逃逸用例逐条拦**(Runtime.exec / String.execute / System.exit / Class.forName / getClass / metaClass / new File / Eval / 闭包藏命令)。
- **safe-by-design 断言要匹配引擎的"挡法",不能假设**:同样是拦截,有的抛异常(QLExpress 沙箱、Groovy 拦截器、JEXL 的禁构造器)、有的把禁用类/方法求值为 **null**(JEXL RESTRICTED 下 `Runtime.getRuntime()` → null,`exec` 不执行)。先用 probe 看真实返回是 throw 还是 null,再写断言;`exec` 类用例最强的断言是"命令没被执行"(结果为 null 或抛异常),而不是纠结具体异常类型。
- 跑测试先用 `mvn-env` skill 设环境。跨模块改动(动了 kernel ExpressionLang)必须带 `-am`;最后用全量 `$MVN clean test`(无 -pl)兜底。

## 红线
- 引擎模块出现 Spring 依赖 = 错(装配进 starter)。
- 改 eval-svc/config-svc/sdk 的引擎收集逻辑去"特判"新引擎 = 错(定式就是零改动自动收集)。
- typeCheck 的类型映射与运行期数值规整不一致 = 会误拒合法脚本或漏掉真错(见 CEL 的 DECIMAL→DYN 取舍)。
- 跳过 safe-by-design 校验 = 安全红线。
- **收一个无可用沙箱的完整语言引擎 = 安全红线**:JDK25 上只能靠 `SecurityManager` 的库(如 Janino)直接拒;别拿编译期 customizer(SecureASTCustomizer)假装安全。
- 第三方库 version 写在模块 pom 而非根 pom = 错(版本单一真相源在根 pom)。
