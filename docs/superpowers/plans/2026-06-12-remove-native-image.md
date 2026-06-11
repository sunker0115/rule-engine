# 去 GraalVM Native Image 化 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 拆除从未真正部署过的 GraalVM Native Image 脚手架(`rule-mybatis-native` 模块、Groovy 4 锁、kernel 禁反射约束、相关文档表述),保留 AOT 与模块化约束,部署形态(JVM)不变。

**Architecture:** 纯删除/清理型重构。删 `rule-mybatis-native` 模块及三处引用;删 Groovy 版本锁让其浮动到 xxl-job 传递的 5.x 并验证 xxl 兼容;解除 rule-kernel 禁反射约束(解锁 D61 Easy Rules 反射注入);清理 CLAUDE.md / `09-skeleton.md` / kernel pom 的 native 表述;追加决策 D62。

**Tech Stack:** Maven 多模块、Spring Boot 4、MyBatis-Plus、xxl-job-core 3.4.0、Java 25(JVM 部署)。

**前置:** 跑 Maven 前先用 `mvn-env` skill 设环境,命令形如 `$MVN -pl <module> -am test`;跨模块改动必带 `-am`,整轮收尾用全量 `$MVN clean test`。设计依据见 `docs/superpowers/specs/2026-06-12-remove-native-image-design.md` 与决策 D62。**本计划是删除型,验证 = 编译/测试通过 + 残留检索为空**(无新单测)。

---

## 文件结构

- 删除:`rule-mybatis-native/`(整个模块目录)
- 修改:`pom.xml`(parent — 删 module 声明、depMgmt 条目、Groovy 锁)
- 修改:`rule-app/pom.xml`(删 rule-mybatis-native 依赖)
- 修改:`rule-kernel/pom.xml`(Lombok 注释去 native 措辞)
- 修改:`CLAUDE.md`(第 10 行去 kernel native 硬约束 + 辅助模块列表删 rule-mybatis-native)
- 修改:`docs/09-skeleton.md`(§47 删 Native Image 说明、§49 kernel Lombok 措辞、§260 AOT 段去 native 参照)
- 修改:`docs/00-decisions.md`(追加 D62)
- 修改:`docs/superpowers/specs/2026-06-12-remove-native-image-design.md`(状态行)

---

## Task 1: 删除 rule-mybatis-native 模块及三处引用

**Files:**
- Delete: `rule-mybatis-native/`(整个目录)
- Modify: `pom.xml:23`(parent modules)
- Modify: `pom.xml:235-239`(parent dependencyManagement)
- Modify: `rule-app/pom.xml:27-31`(dependency)

- [ ] **Step 1: 删模块目录**

```bash
git rm -r rule-mybatis-native
```

- [ ] **Step 2: parent pom 删 module 声明**

`pom.xml` 删除这一行(约 `:23`):
```xml
        <module>rule-mybatis-native</module>
```

- [ ] **Step 3: parent pom 删 dependencyManagement 条目**

`pom.xml` 删除这一段(约 `:235-239`):
```xml
            <dependency>
                <groupId>com.sstlfsj.rule</groupId>
                <artifactId>rule-mybatis-native</artifactId>
                <version>${project.version}</version>
            </dependency>
```

- [ ] **Step 4: rule-app pom 删依赖**

`rule-app/pom.xml` 删除这一段(约 `:27-31`,连同注释):
```xml
        <!-- MyBatis-Plus 的 GraalVM native-image 支持（vendored PR #7017，仅 native 构建生效） -->
        <dependency>
            <groupId>com.sstlfsj.rule</groupId>
            <artifactId>rule-mybatis-native</artifactId>
        </dependency>
```

- [ ] **Step 5: 验证编译 + 无残留引用**

Run: `$MVN -q clean compile`
Expected: BUILD SUCCESS(无 `rule-mybatis-native` 找不到的报错)

Run(残留检索,用 Grep 工具或 rg):搜索全仓 `rule-mybatis-native`
Expected: 仅 `docs/superpowers/`(本计划/spec,历史记录)命中;源码、`pom.xml`、`rule-app/pom.xml`、`CLAUDE.md` 无命中(CLAUDE.md 在 Task 3 处理)

- [ ] **Step 6: 提交**

```bash
git add -A
git commit -m "build: remove rule-mybatis-native module and its references"
```

---

## Task 2: 删 Groovy 版本锁 → Groovy 5 + 验证 xxl 兼容

**Files:**
- Modify: `pom.xml:54-57`(parent — Groovy 锁注释 + 属性)

- [ ] **Step 1: 删 Groovy 锁(注释 + 属性)**

`pom.xml` 删除这一段(约 `:54-57`):
```xml
        <!-- 把 xxl-job-core 传递的 Groovy 5 锁回 4.x：GraalVM 内置 GroovySubstitutions 仅适配 Groovy 4，
             对 Groovy 5 因 IndyInterface.invalidateSwitchPoints() 缺失导致 native 编译失败（GR-60622）。
             适配器走 glueType=BEAN 不跑脚本，仅需 GroovyClassLoader 可加载，4.x 运行期等价。 -->
        <groovy.version>4.0.24</groovy.version>
```

- [ ] **Step 2: 查解析后的 Groovy 版本(确认浮动到 5.x)**

Run: `$MVN -pl rule-job-xxl -am dependency:tree -Dincludes=org.apache.groovy:*,org.codehaus.groovy:*`
Expected: 输出 Groovy 依赖,版本为 5.x(由 xxl-job-core 传递);确认锁已解除

- [ ] **Step 3: 跑 xxl 适配器单测**

Run: `$MVN -pl rule-job-xxl -am test`
Expected: PASS(`XxlJobSchedulerAdapter` 等单测在 Groovy 5 下通过)

- [ ] **Step 4: -Pxxl 装配冒烟(确认 GroovyClassLoader 加载 + handler 注册)**

Run: `$MVN -pl rule-app -am -Pxxl clean package -DskipTests`
Expected: BUILD SUCCESS(把 rule-job-xxl 纳入 rule-app 装配并打包通过;`process-aot` 不报 Groovy 相关错)

> **不兼容回退**:若 Step 3/4 因 Groovy 5 失败(`GroovyClassLoader` 加载异常 / 适配器注册失败),恢复 Step 1 删除的四行(把注释改为非 native 理由 `<!-- 锁定 Groovy 4.x:避免 xxl-job-core 传递的 Groovy 5 在 JVM 下未验证升级 -->` + `<groovy.version>4.0.24</groovy.version>`),并在提交信息注明回退原因。

- [ ] **Step 5: 提交**

```bash
git add pom.xml
git commit -m "build: drop native-only Groovy 4 lock, float to Groovy 5 (xxl verified)"
```

---

## Task 3: 解除 kernel 反射约束 + 清理 native 文档表述

**Files:**
- Modify: `rule-kernel/pom.xml:34`(Lombok 注释)
- Modify: `CLAUDE.md:10`(kernel native 硬约束 + 辅助模块列表)
- Modify: `docs/09-skeleton.md:47,49,260`(native 表述)

- [ ] **Step 1: rule-kernel pom — Lombok 注释去 native 措辞**

`rule-kernel/pom.xml:34` 把:
```xml
        <!-- Lombok：编译期注解处理器（@Builder），不进运行时，不破坏零运行时依赖 / Native Image -->
```
改为:
```xml
        <!-- Lombok：编译期注解处理器（@Builder），编译后无运行时依赖 -->
```

- [ ] **Step 2: CLAUDE.md — 去 kernel native 硬约束 + 删辅助模块 rule-mybatis-native**

`CLAUDE.md:10` 把表格行中:
- `rule-kernel`(纯 Java SPI+模型+求值内核,无 Spring,GraalVM native 硬约束) → 改为 `rule-kernel`(纯 Java SPI+模型+求值内核,无 Spring)
- 辅助模块列表里的 `/rule-mybatis-native` → 删除(连同其前的分隔)

改后该行(完整替换):
```
| `rule-*/`（Maven 多模块） | 代码实现。核心:`rule-kernel`(纯 Java SPI+模型+求值内核,无 Spring)、`rule-config-svc`(配置写:scene/rule/metric/binding CRUD + 发布)、`rule-eval-svc`(评估+取数+action 派发+异步落库)、`rule-api`(HTTP `/admin·api·sdk/v1`)、`rule-app`(装配 + Modulith 边界 + 启动);辅助:`rule-observability`/`rule-audit-svc`/`rule-job-svc`/`rule-job-xxl`/`rule-sdk(-spring-boot-starter)`/`rule-benchmark` | 改动审查派 `rule-engine-reviewer` agent |
```

> 注:CLAUDE.md 描述的"action 派发"是历史措辞(D60 后已纯决策化),本计划不顺手改它(范围外,见外科式修改原则)。

- [ ] **Step 3: 09-skeleton §47 — 删 Native Image 说明整段**

`docs/09-skeleton.md:47` 删除整行:
```
**`rule-kernel` / `rule-sdk` Native Image 说明**：两者均零 Spring 零 DB，完全兼容 GraalVM Native Image。主服务（`rule-app`）因 MyBatis-Plus 动态代理机制，v1 不支持 Native Image 编译（详见架构设计文档约束 5）。
```
(连同其上下空行收敛为一个空行)

- [ ] **Step 4: 09-skeleton §49 — kernel Lombok 措辞去 native**

`docs/09-skeleton.md:49` 把:
```
> **kernel 引入 Lombok（D49）**：`rule-kernel` 自 D49 起引 Lombok（如 `RuleEvent` 的 `@Builder(toBuilder)`）。Lombok 是**编译期注解处理器**，编译后无运行时依赖，不破坏 kernel「运行时零依赖 / GraalVM Native 兼容」承诺。
```
改为:
```
> **kernel 引入 Lombok（D49）**：`rule-kernel` 自 D49 起引 Lombok（如 `RuleEvent` 的 `@Builder(toBuilder)`）。Lombok 是**编译期注解处理器**，编译后无运行时依赖，保持 kernel「运行时零依赖」。
```

- [ ] **Step 5: 09-skeleton §260 — AOT 段去 native 参照**

`docs/09-skeleton.md:260` 把句尾:
```
预生成 BeanDefinition，加速 JVM 启动。此为 JVM 模式加速，与 GraalVM Native Image 无关（主服务 v1 不支持 Native Image，见 §二说明）。
```
改为:
```
预生成 BeanDefinition，加速 JVM 启动。此为纯 JVM 启动优化。
```

- [ ] **Step 6: 验证文档无失效 native 表述**

残留检索(Grep 工具):在 `CLAUDE.md`、`docs/09-skeleton.md` 搜 `(?i)graalvm|native image|native 硬约束`
Expected: 无命中(`docs/00-decisions.md` 历史条目 D49 的 native 措辞保留,append-only 不改;`docs/archive`/`docs/superpowers` 历史不动)

- [ ] **Step 7: 提交**

```bash
git add rule-kernel/pom.xml CLAUDE.md docs/09-skeleton.md
git commit -m "docs: relax kernel reflection constraint and drop native-image wording"
```

---

## Task 4: 追加决策 D62 + 更新 spec 状态

**Files:**
- Modify: `docs/00-decisions.md`(汇总表末尾追加 D62)
- Modify: `docs/superpowers/specs/2026-06-12-remove-native-image-design.md`(状态行)

- [ ] **Step 1: 追加 D62 到 00-decisions.md 汇总表**

在 `docs/00-decisions.md` 汇总表最后一行(D61)之后、结尾的 `> README §二决策表...` 之前,插入:
```
| D62 | 放弃 GraalVM Native Image 路线,拆除 native 脚手架 | A | 主服务从未按 native 部署(Dockerfile=temurin-jre+java -jar,无 native profile/插件,xxl native 执行器实测 NO-GO,主服务因 MyBatis-Plus 动态代理本就不支持 native)。**拆除**:删 `rule-mybatis-native` 模块 + parent `modules`/`dependencyManagement` 引用 + `rule-app` 依赖;删 native-only 的 Groovy 4 锁(浮动到 xxl-job 传递的 Groovy 5,验证 xxl 兼容,不兼容回退);解除 rule-kernel"GraalVM native 硬约束"(禁反射),kernel/sdk 现可用反射(解锁 D61 Easy Rules `@Condition` 反射注入);清理 CLAUDE.md / `09-skeleton.md` §47/§49/§260 native 表述 + `rule-kernel/pom.xml` Lombok 措辞。**保留**:Spring AOT(`process-aot`+autoconfigure-processor,JVM 启动加速,与 native 解耦)、`KernelArchTest`"内核禁止依赖 Spring"(模块化约束,非 native)、Lombok 编译期处理器、Dockerfile(已 JVM)。**moot**:D49/archive-modulith 约束 5 的"GraalVM Native 兼容"承诺自此失效(历史条目不改,本决策声明前提作废)。**下游解锁(本次不做)**:OTel Java Agent 切换、MyBatis 原生迁移动机消失。设计见 `specs/2026-06-12-remove-native-image-design.md` |
```

- [ ] **Step 2: 更新 spec 状态行**

把 `docs/superpowers/specs/2026-06-12-remove-native-image-design.md` 首部 `> 状态:设计待评审` 改为 `> 状态:已实现`。

- [ ] **Step 3: 提交**

```bash
git add docs/00-decisions.md docs/superpowers/specs/2026-06-12-remove-native-image-design.md
git commit -m "docs: record D62 (drop native image) and mark design implemented"
```

---

## Task 5: 全量回归 + 启动/装配冒烟

**Files:** 无(纯验证)

- [ ] **Step 1: 全量编译测试**

Run: `$MVN clean test`
Expected: 全绿(`clean` 强制重编所有模块,暴露删模块后的残留引用 / 过期增量编译)

- [ ] **Step 2: rule-app 打包冒烟(确认 process-aot 仍生效)**

Run: `$MVN -pl rule-app -am clean package -DskipTests`
Expected: BUILD SUCCESS(删 rule-mybatis-native 后 `process-aot` 不报缺类;产物 `rule-app/target/rule-app-*.jar` 生成)

- [ ] **Step 3: 最终残留检索**

残留检索(Grep 工具)全仓搜 `rule-mybatis-native`
Expected: 仅 `docs/superpowers/`(本计划 + spec,历史记录)与 `docs/00-decisions.md` D62(记述删除动作)命中;源码 / 所有 `pom.xml` / `CLAUDE.md` / `09-skeleton.md` 无命中

- [ ] **Step 4: 收尾(无新改动则跳过提交)**

若前述步骤未引入新改动,本任务无提交;若打包冒烟暴露需修的残留,修复后:
```bash
git add -A
git commit -m "build: fix residual native-image references found in regression"
```

---

## 自查清单(已核)

- **spec 覆盖**:删 rule-mybatis-native(T1,spec §2)/删 Groovy 锁升 5 + xxl 验证(T2,spec §4)/解除 kernel 反射约束 + 文档清理(T3,spec §2)/D62(T4,spec §7)/全量回归 + 启动冒烟(T5,spec §5)。spec §2 删留表每项均有对应 task。
- **保留项未误删**:AOT(`process-aot`/autoconfigure-processor)、`KernelArchTest`、Lombok 处理器本体、Dockerfile —— 各 task 仅改注释/文档措辞,不动其构建配置。
- **锚点真实性**:`pom.xml:23/235-239/54-57`、`rule-app/pom.xml:27-31`、`rule-kernel/pom.xml:34`、`CLAUDE.md:10`、`09-skeleton.md:47/49/260` 均经源码逐行核对。
- **回退路径**:Groovy 5 不兼容时 T2 有精确回退片段(恢复 4.0.24 锁 + 非 native 注释)。
- **append-only 守纪**:`00-decisions.md` D49 历史 native 措辞不改,仅由 D62 声明前提作废;`docs/archive` 不动。

---

## 已知不在范围(spec §6 下游,本计划不做)

- OTel Java Agent 切换(native 不再是阻碍,另起决策)。
- MyBatis 原生 / jOOQ 迁移(native 动机消失)。
- CLAUDE.md "action 派发"等 D60 前历史措辞的顺手订正(范围外)。
