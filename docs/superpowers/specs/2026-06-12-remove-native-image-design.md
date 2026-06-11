# 去 GraalVM Native Image 化 — 设计

> 状态:设计待评审 · 日期:2026-06-12 · 关联决策:D62(草案,见文末)
> 背景关联:D49(kernel 引 Lombok 称"不破坏 Native")、D32(ArchUnit/release21,**与 native 无关,不动**)、archive/2026-06-01-modulith(约束 5 Native 部分支持)、2026-06-07 xxl native go/no-go(已判 NO-GO)

## 1. 背景与动机

`rule-engine` 一直背着"主服务向 GraalVM Native Image 兼容"的隐性约束,但**从未真正按 native 部署过**:

- **生产部署已是 JVM**:`Dockerfile` 用 `eclipse-temurin:25-jre` + `java -jar`,无 native 二进制。
- **没有 native 构建链路**:全仓 pom 无 `native-maven-plugin` / GraalVM buildtools,无 `-Pnative` profile,即没人 `native:compile`。
- **主服务本就不支持 native**:`09-skeleton.md` §47 明载"主服务因 MyBatis-Plus 动态代理,v1 不支持 Native Image 编译";xxl 接入的 native go/no-go 实测 **NO-GO**(`XxlJobSpringExecutor` 回调在 AOT/native 下不触发)。
- **约束在挡路**:rule-kernel 的"禁反射/AOT 友好"硬约束阻止了反射类特性(如 `2026-06-12-annotation-rule-easyrules-style-design.md` 的 `@Condition` 反射注入)。

结论:native 是**只有维护成本、没有部署收益**的潜伏脚手架。去掉它 = 解锁反射类特性 + 减负。

### 非目标

- **不动 AOT**:`process-aot` 保留(它也加速 JVM 启动,与 native 解耦,见 §3)。
- **不动模块化约束**:`KernelArchTest` 的"内核禁止依赖 Spring"是纯 Java 库的模块边界约束,**与 native 无关,保留**。
- **不改部署形态**:Dockerfile 已是 JVM,不变。
- **不在本次重新接 OTel Java Agent**(见 §6 下游)。Groovy 升 5 **纳入本次**(见 §4)。

## 2. 范围:删 / 留 / 缓

| 处理 | 项 | 说明 |
|---|---|---|
| **删** | `rule-mybatis-native` 整个模块 | vendored MyBatis-Plus PR #7017,仅 native 构建期生效,JVM 下惰性 |
| **删** | parent `pom.xml` `<module>rule-mybatis-native</module>` | 模块摘除 |
| **删** | parent `pom.xml` `<dependencyManagement>` 中 rule-mybatis-native 条目 | 依赖声明摘除 |
| **删** | `rule-app/pom.xml` 对 rule-mybatis-native 的 dependency | 主服务不再引 |
| **改** | rule-kernel"GraalVM native 硬约束"(CLAUDE.md / 09-skeleton) | 解除禁反射约束;kernel/sdk 现可用反射(解锁 Easy Rules) |
| **改** | `rule-kernel/pom.xml` Lombok 注释("不破坏 Native") | 去掉 native 理由,保留 Lombok 编译期处理器本身(本就是好实践) |
| **改** | `09-skeleton.md` §47/§49/§263 native 表述 | 删 Native Image 章节;AOT 段去掉"与 native 无关/主服务不支持 native"措辞,改述为纯 JVM 启动优化 |
| **改** | `CLAUDE.md` 第 10 行 | 去掉 rule-kernel"GraalVM native 硬约束"、辅助模块列表删 `rule-mybatis-native` |
| **留** | AOT `process-aot`(rule-app)+ 各 svc `spring-boot-autoconfigure-processor` | 用户决定保留;JVM 启动加速 |
| **留** | `KernelArchTest`、Lombok 编译期处理器、Dockerfile | 与 native 无关 |
| **删**(§4) | parent `pom.xml` Groovy `4.0.24` 锁 | 理由纯为 native;删锁让 Groovy 浮动到 xxl-job 传递的 5.x,**本次纳入并验证 xxl-job-core on Groovy 5 (JVM)** |

## 3. AOT 为什么保留

`process-aot` 与 `spring-boot-autoconfigure-processor` 是 **Spring AOT**,native 与 JVM 双用途:

- native 构建**需要**它;
- JVM 模式它预生成 BeanDefinition / condition metadata,**加速启动**(`09-skeleton.md` §263 原话"加速 JVM 启动")。

去 native 后,AOT 的 JVM 收益仍在,故保留。仅需把文档里"与 GraalVM Native Image 无关(主服务 v1 不支持 native)"这类**以 native 为参照系**的措辞,改写为直接陈述"JVM 启动优化",不再提 native。

## 4. Groovy 版本锁(已定:删锁升 5)

parent pom 把 xxl-job-core 传递的 Groovy 锁回 `4.0.24`,理由**纯为 native 编译**(GraalVM GroovySubstitutions 仅适配 Groovy 4,Groovy 5 触发 GR-60622)。native 去除后该理由消失。

**决定:删除 `groovy.version=4.0.24` 锁,让 Groovy 浮动到 xxl-job-core 传递的 5.x。** 锁注释里同时声明的"适配器走 glueType=BEAN 不跑脚本,仅需 GroovyClassLoader 可加载,4.x 运行期等价"表明运行期**不依赖 Groovy 脚本执行**,Groovy 5 在 JVM 下大概率等价。但"大概率"不等于已验证,故**本次把 xxl-job-core on Groovy 5 (JVM) 的验证纳入实施**(见 §5):删锁后跑 xxl 适配器单测 + `-Pxxl` 装配冒烟,确认 `GroovyClassLoader` 加载与适配器 handler 注册正常。**实测不兼容则回退**保留 `4.0.24` 锁(注释改为非 native 理由)。

## 5. 风险与验证

风险低(删的是 JVM 下惰性的构建期件),但要验证"删 rule-mybatis-native 后 JVM 链路无回归":

1. **编译/单测**:`$MVN clean test` 全绿(`clean` 强制重编,暴露残留引用)。
2. **依赖闭包**:确认删除 rule-mybatis-native 后 `rule-app` 依赖树无悬空引用(`$MVN -pl rule-app dependency:tree` 不报缺失)。
3. **服务启动**:rule-app 打包后 `java -jar` 正常启动、就绪(MyBatis-Plus 在 JVM 下走正常反射/代理,不需 native 适配模块)。
4. **AOT 仍生效**:`process-aot` 构建步骤不因删模块而失败。
5. **全文检索兜底**:确认无源码 / pom / 文档残留 `rule-mybatis-native` 引用与失效的 native 表述。
6. **Groovy 5 / xxl 兼容**:删 Groovy 锁后 `$MVN -pl rule-job-xxl -am test` 通过;`-Pxxl` 装配冒烟确认 `XxlJobSchedulerAdapter` 的 `GroovyClassLoader` 加载与 handler 注册正常(glueType=BEAN,不跑脚本)。不兼容则回退保留 `4.0.24` 锁。

## 6. 下游解锁(本次不做,仅登记)

去 native 后,几处历史上"为 native 让路"的决策前提消失,后续可重新评估(均**不在本 spec 范围**):

- **OTel Java Agent**:`2026-06-05-otel-javaagent-migration` 不切 Agent 的"决定性"理由就是 native;其"重启条件"含"明确放弃 native 路线"。现已满足该前提,可另议是否切回 Agent。
- **MyBatis-Plus 数据访问**:archive modulith 约束 5 曾设想"为 native 迁 MyBatis 原生 / jOOQ",该动机消失。
- **kernel 反射特性**:Easy Rules 注解规则(`2026-06-12-annotation-rule-easyrules-style-design.md`)的反射注入不再受约束阻挡。

## 7. 决策日志条目草案(D62)

> 待评审通过后追加到 `docs/00-decisions.md` 汇总表。

**D62 放弃 GraalVM Native Image 路线,拆除 native 脚手架 | A**

主服务从未按 native 部署(Dockerfile 是 `temurin-jre`+`java -jar`,无 native profile/插件,xxl 的 native 执行器实测 NO-GO,主服务因 MyBatis-Plus 动态代理本就不支持 native)。**拆除**:删 `rule-mybatis-native` 模块 + parent `modules`/`dependencyManagement` 引用 + `rule-app` 依赖;解除 rule-kernel"GraalVM native 硬约束"(禁反射),kernel/sdk 现可用反射(解锁 D61 Easy Rules `@Condition` 反射注入);清理 CLAUDE.md / `09-skeleton.md` §47/§49/§263 的 native 表述与 `rule-kernel/pom.xml` Lombok 的 native 措辞。**保留**:Spring AOT(`process-aot` + autoconfigure-processor,JVM 启动加速,与 native 解耦)、`KernelArchTest`"内核禁止依赖 Spring"(模块化约束,非 native)、Lombok 编译期处理器、Dockerfile(已 JVM)。**Groovy 锁**:删除 `4.0.24` 锁,Groovy 浮动到 xxl-job 传递的 5.x;本次验证 xxl-job-core on Groovy 5 (JVM,glueType=BEAN 不跑脚本),不兼容则回退保留锁。**moot**:D49/archive-modulith 约束 5 的"GraalVM Native 兼容"承诺自此失效(历史条目不改,本决策声明其前提作废)。**下游解锁(本次不做)**:OTel Java Agent 切换、MyBatis 原生迁移动机消失。设计见 `specs/2026-06-12-remove-native-image-design.md`。
