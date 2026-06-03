# Backend Skeleton Implementation Plan

> **Status: 已完成**

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 搭建 rule-engine Maven 多模块骨架——8 个模块完整的 pom.xml 体系 + 包结构 + SPI 接口定义 + 核心模型类 + InterpretedExecutor 实现 + AutoConfiguration + 启动类 + 边界测试；骨架通过编译、ArchUnit 约束检测和 Modulith 边界验证。

**Architecture:** rule-kernel 是零 Spring 零 DB 纯 Java 库（SPI 接口 + AST 模型 + InterpretedExecutor）；rule-config/eval/audit-svc 是 Spring Modulith 模块，提供 Service 接口 + 空桩实现（无业务逻辑）；rule-observability 提供 TraceWriter 实现；rule-api 提供 HTTP 控制器骨架；rule-app 是 Spring Boot 启动装配层。所有 Modulith 边界测试和 ArchUnit 约束测试集中在 rule-app 测试目录。

**Tech Stack:** Java 25 / Spring Boot 4.0.6 / Spring Modulith 2.0.6 / MyBatis-Plus 3.5.16 / Maven / JUnit 6 / ArchUnit 1.3.x

> **注意事项：**
> - 运行 `mvn` 命令前必须先用 `mvn-env` skill 设置环境（本机 mvn 不在 PATH）
> - 使用 Lombok 1.18.36（支持 Java 25）；model/AST 类用 record，Service/Component 类可用 `@Slf4j` / `@RequiredArgsConstructor`；**不要把 Lombok 注解加在 record 上**
> - 启用 Spring Boot AOT（JVM 模式）：有 AutoConfiguration 的模块加 `spring-boot-autoconfigure-processor`（optional），rule-app 的 maven plugin 加 `process-aot` execution
> - Spring Boot 4.0.6 GA + Spring Modulith 2.0.6 GA，版本已确认可用，无需在 Task 1 执行时再查

---

## 文件结构总览

```
rule-engine/
├── pom.xml                            # 根 pom（parent，packaging=pom）
├── rule-kernel/
│   ├── pom.xml
│   └── src/
│       ├── main/java/com/sstlfsj/rule/kernel/
│       │   ├── api/annotation/        # @ConditionType @ActionType @MetricSourceType
│       │   ├── api/model/ast/         # AstNode(sealed) AndNode OrNode NotNode ConditionNode
│       │   ├── api/model/             # EvalContext EvalResult RuleEvent RuleVersionSnapshot
│       │   │                          # MetricQuery MetricValue Subject SubjectType
│       │   │                          # Decision ActionResult ActionContext NodeTrace
│       │   │                          # PreGateContext PreGateResult
│       │   └── api/spi/               # ConditionEvaluator ActionHandler MetricSourceHandler
│       │       condition/ action/     # SubjectLoader RuleVersionWatcher SceneWatcher
│       │       metric/ subject/       # RuleVersionExecutor Scheduler TraceWriter PreGate
│       │       watcher/ executor/
│       │       scheduler/ trace/
│       │       pregate/
│       │   └── internal/evaluator/    # InterpretedExecutor
│       └── test/java/com/sstlfsj/rule/kernel/
│           ├── evaluator/             # InterpretedExecutorTest
│           └── arch/                  # KernelZeroSpringTest (ArchUnit)
│
├── rule-kernel-polling/
│   ├── pom.xml
│   └── src/main/java/com/sstlfsj/rule/kernel/polling/
│       ├── DbPollingRuleWatcher.java
│       └── DbPollingSceneWatcher.java
│
├── rule-config-svc/
│   ├── pom.xml
│   └── src/main/java/com/sstlfsj/rule/config/
│       ├── api/service/               # ConfigService SceneService MetadataService (接口)
│       ├── internal/domain/           # Scene RuleDefinition RuleVersion MetricDefinition
│       ├── internal/repository/       # SceneMapper RuleDefinitionMapper (MyBatis-Plus)
│       ├── internal/publish/          # PublishService stub
│       ├── internal/event/            # RulePublishedEvent SceneChangedEvent
│       └── ConfigAutoConfiguration.java
│       resources/META-INF/spring/...AutoConfiguration.imports
│
├── rule-eval-svc/
│   ├── pom.xml
│   └── src/main/java/com/sstlfsj/rule/eval/
│       ├── api/service/               # EvalService (接口)
│       ├── internal/index/            # SceneRuleIndex placeholder
│       ├── internal/service/          # EvalServiceImpl stub
│       └── EvalAutoConfiguration.java
│
├── rule-audit-svc/
│   ├── pom.xml
│   └── src/main/java/com/sstlfsj/rule/audit/
│       ├── api/service/               # AuditService (接口)
│       ├── internal/service/          # AuditServiceImpl stub
│       └── AuditAutoConfiguration.java
│
├── rule-observability/
│   ├── pom.xml
│   └── src/main/java/com/sstlfsj/rule/observability/
│       ├── api/metrics/               # RuleMetrics (Prometheus 名常量)
│       ├── internal/trace/            # NoopTraceWriter TraceWriterDbImpl(stub)
│       └── ObservabilityAutoConfiguration.java
│
├── rule-api/
│   ├── pom.xml
│   └── src/main/java/com/sstlfsj/rule/web/
│       ├── eval/                      # EvalController
│       ├── config/                    # RuleController SceneController MetadataController
│       ├── audit/                     # AuditController
│       └── filter/                    # ActorIdFilter (X-Actor-Id header)
│
└── rule-app/
    ├── pom.xml
    ├── src/main/java/com/sstlfsj/rule/app/
    │   └── RuleEngineApplication.java
    ├── src/main/resources/
    │   └── application.yml
    └── src/test/java/com/sstlfsj/rule/app/
        ├── module/                    # ConfigModuleTest EvalModuleTest AuditModuleTest
        └── arch/                      # KernelArchTest (ArchUnit，在 rule-app 环境跑)
```

---

## Task 1: 根 pom.xml + 8 个子模块 pom.xml + 空目录结构

**Files:**
- Create: `pom.xml`
- Create: `rule-kernel/pom.xml`
- Create: `rule-kernel-polling/pom.xml`
- Create: `rule-config-svc/pom.xml`
- Create: `rule-eval-svc/pom.xml`
- Create: `rule-audit-svc/pom.xml`
- Create: `rule-observability/pom.xml`
- Create: `rule-api/pom.xml`
- Create: `rule-app/pom.xml`

- [ ] **Step 1: 创建根 pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                             https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>4.0.6</version>
        <relativePath/>
    </parent>

    <groupId>com.sstlfsj.rule</groupId>
    <artifactId>rule-engine</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <packaging>pom</packaging>

    <modules>
        <module>rule-kernel</module>
        <module>rule-kernel-polling</module>
        <module>rule-config-svc</module>
        <module>rule-eval-svc</module>
        <module>rule-audit-svc</module>
        <module>rule-observability</module>
        <module>rule-api</module>
        <module>rule-app</module>
    </modules>

    <properties>
        <java.version>25</java.version>
        <spring-modulith.version>2.0.6</spring-modulith.version>
        <mybatis-plus.version>3.5.16</mybatis-plus.version>
        <archunit.version>1.3.0</archunit.version>
        <guava.version>33.2.1-jre</guava.version>
        <lombok.version>1.18.36</lombok.version>
    </properties>

    <dependencyManagement>
        <dependencies>
            <!-- Spring Modulith BOM -->
            <dependency>
                <groupId>org.springframework.modulith</groupId>
                <artifactId>spring-modulith-bom</artifactId>
                <version>${spring-modulith.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>

            <!-- MyBatis-Plus (Spring Boot 3/4 compatible artifact) -->
            <dependency>
                <groupId>com.baomidou</groupId>
                <artifactId>mybatis-plus-spring-boot4-starter</artifactId>
                <version>${mybatis-plus.version}</version>
            </dependency>

            <!-- MySQL Connector -->
            <dependency>
                <groupId>com.mysql</groupId>
                <artifactId>mysql-connector-j</artifactId>
                <version>9.0.0</version>
            </dependency>

            <!-- Guava (only for rule-kernel: murmur3 hash for ROLLOUT gate) -->
            <dependency>
                <groupId>com.google.guava</groupId>
                <artifactId>guava</artifactId>
                <version>${guava.version}</version>
            </dependency>

            <!-- Lombok 1.18.36+：支持 Java 25，可与 record 共存；不加在 record 类上 -->
            <dependency>
                <groupId>org.projectlombok</groupId>
                <artifactId>lombok</artifactId>
                <version>${lombok.version}</version>
                <optional>true</optional>
            </dependency>

            <!-- ArchUnit -->
            <dependency>
                <groupId>com.tngtech.archunit</groupId>
                <artifactId>archunit-junit5</artifactId>
                <version>${archunit.version}</version>
                <scope>test</scope>
            </dependency>

            <!-- 内部模块版本管理 -->
            <dependency>
                <groupId>com.sstlfsj.rule</groupId>
                <artifactId>rule-kernel</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>com.sstlfsj.rule</groupId>
                <artifactId>rule-kernel-polling</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>com.sstlfsj.rule</groupId>
                <artifactId>rule-config-svc</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>com.sstlfsj.rule</groupId>
                <artifactId>rule-eval-svc</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>com.sstlfsj.rule</groupId>
                <artifactId>rule-audit-svc</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>com.sstlfsj.rule</groupId>
                <artifactId>rule-observability</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>com.sstlfsj.rule</groupId>
                <artifactId>rule-api</artifactId>
                <version>${project.version}</version>
            </dependency>
        </dependencies>
    </dependencyManagement>
</project>
```

- [ ] **Step 2: 创建 rule-kernel/pom.xml（零 Spring 零 DB）**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                             https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.sstlfsj.rule</groupId>
        <artifactId>rule-engine</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>
    <artifactId>rule-kernel</artifactId>

    <dependencies>
        <!-- 唯一的非 JDK 依赖：murmur3 hash，用于 ROLLOUT Pre-Gate -->
        <dependency>
            <groupId>com.google.guava</groupId>
            <artifactId>guava</artifactId>
        </dependency>

        <!-- 测试 -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>com.tngtech.archunit</groupId>
            <artifactId>archunit-junit5</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

- [ ] **Step 3: 创建 rule-kernel-polling/pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                             https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.sstlfsj.rule</groupId>
        <artifactId>rule-engine</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>
    <artifactId>rule-kernel-polling</artifactId>

    <dependencies>
        <dependency>
            <groupId>com.sstlfsj.rule</groupId>
            <artifactId>rule-kernel</artifactId>
        </dependency>
        <dependency>
            <groupId>com.baomidou</groupId>
            <artifactId>mybatis-plus-spring-boot4-starter</artifactId>
        </dependency>
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

- [ ] **Step 4: 创建 rule-config-svc/pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                             https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.sstlfsj.rule</groupId>
        <artifactId>rule-engine</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>
    <artifactId>rule-config-svc</artifactId>

    <dependencies>
        <dependency>
            <groupId>com.sstlfsj.rule</groupId>
            <artifactId>rule-kernel</artifactId>
        </dependency>
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.modulith</groupId>
            <artifactId>spring-modulith-starter-core</artifactId>
        </dependency>
        <dependency>
            <groupId>com.baomidou</groupId>
            <artifactId>mybatis-plus-spring-boot4-starter</artifactId>
        </dependency>
        <dependency>
            <groupId>com.mysql</groupId>
            <artifactId>mysql-connector-j</artifactId>
            <scope>runtime</scope>
        </dependency>
        <!-- AOT: 编译期生成 AutoConfiguration condition metadata -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-autoconfigure-processor</artifactId>
            <optional>true</optional>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

- [ ] **Step 5: 创建 rule-eval-svc/pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                             https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.sstlfsj.rule</groupId>
        <artifactId>rule-engine</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>
    <artifactId>rule-eval-svc</artifactId>

    <dependencies>
        <dependency>
            <groupId>com.sstlfsj.rule</groupId>
            <artifactId>rule-kernel</artifactId>
        </dependency>
        <dependency>
            <groupId>com.sstlfsj.rule</groupId>
            <artifactId>rule-observability</artifactId>
        </dependency>
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.modulith</groupId>
            <artifactId>spring-modulith-starter-core</artifactId>
        </dependency>
        <dependency>
            <groupId>com.baomidou</groupId>
            <artifactId>mybatis-plus-spring-boot4-starter</artifactId>
        </dependency>
        <dependency>
            <groupId>com.mysql</groupId>
            <artifactId>mysql-connector-j</artifactId>
            <scope>runtime</scope>
        </dependency>
        <!-- AOT: 编译期生成 AutoConfiguration condition metadata -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-autoconfigure-processor</artifactId>
            <optional>true</optional>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

- [ ] **Step 6: 创建 rule-audit-svc/pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                             https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.sstlfsj.rule</groupId>
        <artifactId>rule-engine</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>
    <artifactId>rule-audit-svc</artifactId>

    <dependencies>
        <dependency>
            <groupId>com.sstlfsj.rule</groupId>
            <artifactId>rule-kernel</artifactId>
        </dependency>
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.modulith</groupId>
            <artifactId>spring-modulith-starter-core</artifactId>
        </dependency>
        <dependency>
            <groupId>com.baomidou</groupId>
            <artifactId>mybatis-plus-spring-boot4-starter</artifactId>
        </dependency>
        <dependency>
            <groupId>com.mysql</groupId>
            <artifactId>mysql-connector-j</artifactId>
            <scope>runtime</scope>
        </dependency>
        <!-- AOT: 编译期生成 AutoConfiguration condition metadata -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-autoconfigure-processor</artifactId>
            <optional>true</optional>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

- [ ] **Step 7: 创建 rule-observability/pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                             https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.sstlfsj.rule</groupId>
        <artifactId>rule-engine</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>
    <artifactId>rule-observability</artifactId>

    <dependencies>
        <dependency>
            <groupId>com.sstlfsj.rule</groupId>
            <artifactId>rule-kernel</artifactId>
        </dependency>
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
        <dependency>
            <groupId>io.micrometer</groupId>
            <artifactId>micrometer-registry-prometheus</artifactId>
        </dependency>
        <dependency>
            <groupId>com.baomidou</groupId>
            <artifactId>mybatis-plus-spring-boot4-starter</artifactId>
        </dependency>
        <!-- AOT: 编译期生成 AutoConfiguration condition metadata -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-autoconfigure-processor</artifactId>
            <optional>true</optional>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

- [ ] **Step 8: 创建 rule-api/pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                             https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.sstlfsj.rule</groupId>
        <artifactId>rule-engine</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>
    <artifactId>rule-api</artifactId>

    <dependencies>
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
        </dependency>
        <dependency>
            <groupId>com.sstlfsj.rule</groupId>
            <artifactId>rule-config-svc</artifactId>
        </dependency>
        <dependency>
            <groupId>com.sstlfsj.rule</groupId>
            <artifactId>rule-eval-svc</artifactId>
        </dependency>
        <dependency>
            <groupId>com.sstlfsj.rule</groupId>
            <artifactId>rule-audit-svc</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

- [ ] **Step 9: 创建 rule-app/pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                             https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.sstlfsj.rule</groupId>
        <artifactId>rule-engine</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>
    <artifactId>rule-app</artifactId>

    <dependencies>
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
        </dependency>
        <dependency>
            <groupId>com.sstlfsj.rule</groupId>
            <artifactId>rule-kernel</artifactId>
        </dependency>
        <dependency>
            <groupId>com.sstlfsj.rule</groupId>
            <artifactId>rule-config-svc</artifactId>
        </dependency>
        <dependency>
            <groupId>com.sstlfsj.rule</groupId>
            <artifactId>rule-eval-svc</artifactId>
        </dependency>
        <dependency>
            <groupId>com.sstlfsj.rule</groupId>
            <artifactId>rule-audit-svc</artifactId>
        </dependency>
        <dependency>
            <groupId>com.sstlfsj.rule</groupId>
            <artifactId>rule-observability</artifactId>
        </dependency>
        <dependency>
            <groupId>com.sstlfsj.rule</groupId>
            <artifactId>rule-api</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.modulith</groupId>
            <artifactId>spring-modulith-starter-core</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.modulith</groupId>
            <artifactId>spring-modulith-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>com.tngtech.archunit</groupId>
            <artifactId>archunit-junit5</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <executions>
                    <!-- AOT: 预生成 BeanDefinition，加速 JVM 启动 -->
                    <execution>
                        <id>process-aot</id>
                        <goals>
                            <goal>process-aot</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 10: 验证根模块结构能解析（仅 pom，不编译）**

先用 `mvn-env` skill 设置 Maven 环境，然后运行：
```bash
mvn validate
```
Expected: BUILD SUCCESS（所有 8 个模块被识别为子模块，依赖版本无冲突）

> 若版本报错，版本已确认为 Spring Boot 4.0.6 / Spring Modulith 2.0.6 GA，检查网络或本地 Maven 仓库缓存。

- [ ] **Step 11: Commit**

```bash
git add pom.xml rule-kernel/pom.xml rule-kernel-polling/pom.xml \
        rule-config-svc/pom.xml rule-eval-svc/pom.xml \
        rule-audit-svc/pom.xml rule-observability/pom.xml \
        rule-api/pom.xml rule-app/pom.xml
git commit -m "chore: init Maven multi-module skeleton (8 modules)"
```

---

## Task 2: rule-kernel — AST 节点 + 核心模型类

**Files:**
- Create: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/ast/AstNode.java`
- Create: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/ast/AndNode.java`
- Create: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/ast/OrNode.java`
- Create: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/ast/NotNode.java`
- Create: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/ast/ConditionNode.java`
- Create: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/EvalContext.java`
- Create: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/EvalResult.java`
- Create: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/RuleEvent.java`
- Create: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/RuleVersionSnapshot.java`
- Create: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/MetricQuery.java`
- Create: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/MetricValue.java`
- Create: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/Subject.java`
- Create: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/SubjectType.java`
- Create: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/Decision.java`
- Create: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/ActionResult.java`
- Create: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/ActionContext.java`
- Create: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/NodeTrace.java`
- Create: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/PreGateContext.java`
- Create: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/model/PreGateResult.java`

- [ ] **Step 1: 创建 AST 节点（sealed interface + 4 种记录类）**

`AstNode.java`:
```java
package com.sstlfsj.rule.kernel.api.model.ast;

public sealed interface AstNode
        permits AndNode, OrNode, NotNode, ConditionNode {}
```

`AndNode.java`:
```java
package com.sstlfsj.rule.kernel.api.model.ast;

import java.util.List;

public record AndNode(
        List<AstNode> children,
        String displayLabel,
        Double weight
) implements AstNode {
    public AndNode {
        children = List.copyOf(children);
    }
}
```

`OrNode.java`:
```java
package com.sstlfsj.rule.kernel.api.model.ast;

import java.util.List;

public record OrNode(
        List<AstNode> children,
        String displayLabel,
        Double weight
) implements AstNode {
    public OrNode {
        children = List.copyOf(children);
    }
}
```

`NotNode.java`:
```java
package com.sstlfsj.rule.kernel.api.model.ast;

public record NotNode(
        AstNode child
) implements AstNode {}
```

`ConditionNode.java`:
```java
package com.sstlfsj.rule.kernel.api.model.ast;

import java.util.Map;

public record ConditionNode(
        String conditionType,
        String metricCode,
        String displayLabel,
        Map<String, Object> params
) implements AstNode {
    public ConditionNode {
        params = Map.copyOf(params);
    }
}
```

- [ ] **Step 2: 创建 EvalContext（不可变，持有指标快照）**

`EvalContext.java`:
```java
package com.sstlfsj.rule.kernel.api.model;

import java.util.Map;

public final class EvalContext {
    private final String tenantId;
    private final RuleEvent event;
    private final Subject subject;
    private final Map<String, MetricValue> metrics;

    public EvalContext(String tenantId, RuleEvent event,
                       Subject subject, Map<String, MetricValue> metrics) {
        this.tenantId = tenantId;
        this.event = event;
        this.subject = subject;
        this.metrics = Map.copyOf(metrics);
    }

    public String getTenantId()  { return tenantId; }
    public RuleEvent getEvent()  { return event; }
    public Subject getSubject()  { return subject; }

    public MetricValue getMetric(String metricCode) {
        return metrics.get(metricCode);
    }

    public boolean hasMetric(String metricCode) {
        return metrics.containsKey(metricCode);
    }
}
```

- [ ] **Step 3: 创建 RuleEvent（触发事件 POJO）**

`RuleEvent.java`:
```java
package com.sstlfsj.rule.kernel.api.model;

import java.time.Instant;
import java.util.Map;

public record RuleEvent(
        String tenantId,
        String sceneCode,
        String eventType,
        String subjectId,
        String eventId,
        Instant occurredAt,
        Map<String, Object> payload,
        Map<String, Object> providedMetrics
) {
    public RuleEvent {
        payload = Map.copyOf(payload);
        providedMetrics = providedMetrics == null ? Map.of() : Map.copyOf(providedMetrics);
    }
}
```

- [ ] **Step 4: 创建 MetricValue、MetricQuery、Subject、SubjectType**

`MetricValue.java`:
```java
package com.sstlfsj.rule.kernel.api.model;

public record MetricValue(
        Object value,
        String dataType,
        String valueSource
) {}
```

`MetricQuery.java`:
```java
package com.sstlfsj.rule.kernel.api.model;

import java.util.Map;

public record MetricQuery(
        String metricCode,
        String tenantId,
        String subjectId,
        Map<String, Object> params,
        Map<String, Object> eventPayload
) {}
```

`SubjectType.java`:
```java
package com.sstlfsj.rule.kernel.api.model;

public enum SubjectType {
    USER, ACCOUNT, DEVICE, ORDER
}
```

`Subject.java`:
```java
package com.sstlfsj.rule.kernel.api.model;

import java.util.Map;

public record Subject(
        String subjectId,
        SubjectType subjectType,
        Map<String, Object> attributes
) {
    public Subject {
        attributes = Map.copyOf(attributes);
    }

    public Object getAttribute(String key) {
        return attributes.get(key);
    }
}
```

- [ ] **Step 5: 创建 Decision、ActionResult、ActionContext**

`Decision.java`:
```java
package com.sstlfsj.rule.kernel.api.model;

public record Decision(
        String code,
        String name,
        int priority,
        Long fromRuleVersionId
) {}
```

`ActionResult.java`:
```java
package com.sstlfsj.rule.kernel.api.model;

public record ActionResult(
        String actionId,
        String actionType,
        ActionStatus status,
        String errorCode,
        String errorMessage,
        boolean retryable
) {
    public enum ActionStatus { SUCCESS, FAILED, SKIPPED }

    public static ActionResult success(String actionId, String actionType) {
        return new ActionResult(actionId, actionType, ActionStatus.SUCCESS, null, null, false);
    }

    public static ActionResult skipped(String actionId, String actionType, String reason) {
        return new ActionResult(actionId, actionType, ActionStatus.SKIPPED, reason, null, false);
    }

    public static ActionResult failed(String actionId, String actionType,
                                      String errorCode, boolean retryable) {
        return new ActionResult(actionId, actionType, ActionStatus.FAILED,
                errorCode, null, retryable);
    }

    public static ActionResult notSupported() {
        return new ActionResult(null, null, ActionStatus.SKIPPED,
                "COMPENSATE_NOT_SUPPORTED", null, false);
    }
}
```

`ActionContext.java`:
```java
package com.sstlfsj.rule.kernel.api.model;

import java.util.Map;

public record ActionContext(
        String actionId,
        String actionType,
        Map<String, Object> params,
        EvalContext evalContext,
        Long actionExecutionId,
        String decisionCode
) {
    public ActionContext {
        params = Map.copyOf(params);
    }
}
```

- [ ] **Step 6: 创建 NodeTrace、EvalResult、RuleVersionSnapshot、PreGateContext、PreGateResult**

`NodeTrace.java`:
```java
package com.sstlfsj.rule.kernel.api.model;

import com.sstlfsj.rule.kernel.api.model.ast.AstNode;
import java.util.List;

public record NodeTrace(
        String nodeType,
        String conditionType,
        String metricCode,
        Boolean result,
        Object actualValue,
        String valueSource,
        String errorCode,
        List<NodeTrace> children
) {
    public NodeTrace {
        children = children == null ? List.of() : List.copyOf(children);
    }
}
```

`EvalResult.java`:
```java
package com.sstlfsj.rule.kernel.api.model;

import java.util.List;

public record EvalResult(
        boolean ruleHit,
        Decision finalDecision,
        List<Decision> hitDecisions,
        List<NodeTrace> nodeTrace,
        String errorCode,
        List<ActionResult> actionResults
) {
    public EvalResult {
        hitDecisions = hitDecisions == null ? List.of() : List.copyOf(hitDecisions);
        nodeTrace = nodeTrace == null ? List.of() : List.copyOf(nodeTrace);
        actionResults = actionResults == null ? List.of() : List.copyOf(actionResults);
    }

    public static EvalResult miss() {
        return new EvalResult(false, null, List.of(), List.of(), null, List.of());
    }
}
```

`RuleVersionSnapshot.java`:
```java
package com.sstlfsj.rule.kernel.api.model;

import com.sstlfsj.rule.kernel.api.model.ast.AstNode;
import java.util.List;

public record RuleVersionSnapshot(
        Long ruleVersionId,
        String sceneCode,
        String tenantId,
        AstNode conditionAst,
        List<PreGateConfig> preGates,
        List<DecisionBinding> decisionBindings
) {
    public record PreGateConfig(String gateType, java.util.Map<String, Object> params) {}
    public record DecisionBinding(String decisionCode, int priority) {}
}
```

`PreGateContext.java`:
```java
package com.sstlfsj.rule.kernel.api.model;

public record PreGateContext(
        String tenantId,
        String sceneCode,
        String subjectId,
        RuleEvent event
) {}
```

`PreGateResult.java`:
```java
package com.sstlfsj.rule.kernel.api.model;

public record PreGateResult(
        boolean passed,
        String blockedBy
) {
    public static PreGateResult pass() {
        return new PreGateResult(true, null);
    }

    public static PreGateResult blocked(String gateType) {
        return new PreGateResult(false, gateType);
    }
}
```

- [ ] **Step 7: 验证 rule-kernel 模型类编译通过**

```bash
mvn compile -pl rule-kernel
```
Expected: BUILD SUCCESS，无编译错误

- [ ] **Step 8: Commit**

```bash
git add rule-kernel/src/main/java/
git commit -m "feat(kernel): AST nodes, EvalContext, EvalResult, and core model records"
```

---

## Task 3: rule-kernel — SPI 接口 + 注解

**Files:**
- Create: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/annotation/ConditionType.java`
- Create: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/annotation/ActionType.java`
- Create: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/annotation/MetricSourceType.java`
- Create: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/spi/condition/ConditionEvaluator.java`
- Create: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/spi/action/ActionHandler.java`
- Create: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/spi/metric/MetricSourceHandler.java`
- Create: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/spi/subject/SubjectLoader.java`
- Create: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/spi/watcher/RuleVersionWatcher.java`
- Create: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/spi/watcher/SceneWatcher.java`
- Create: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/spi/executor/RuleVersionExecutor.java`
- Create: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/spi/scheduler/Scheduler.java`
- Create: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/spi/trace/TraceWriter.java`
- Create: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/spi/pregate/PreGate.java`

- [ ] **Step 1: 创建注解（@ConditionType、@ActionType、@MetricSourceType）**

`ConditionType.java`:
```java
package com.sstlfsj.rule.kernel.api.annotation;

import java.lang.annotation.*;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ConditionType {
    String value();
    String displayName() default "";
    String paramsSchema() default "{}";
    boolean requiresMetric() default false;
}
```

`ActionType.java`:
```java
package com.sstlfsj.rule.kernel.api.annotation;

import java.lang.annotation.*;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ActionType {
    String value();
    String displayName() default "";
    String paramsSchema() default "{}";
    int timeoutMs() default 3000;
    boolean compensatable() default false;
}
```

`MetricSourceType.java`:
```java
package com.sstlfsj.rule.kernel.api.annotation;

import java.lang.annotation.*;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface MetricSourceType {
    String value();
    String paramsSchema() default "{}";
    int defaultTimeoutMs() default 1000;
    int defaultCacheTtlSeconds() default 60;
}
```

- [ ] **Step 2: 创建核心 SPI 接口**

`ConditionEvaluator.java`:
```java
package com.sstlfsj.rule.kernel.api.spi.condition;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;

public interface ConditionEvaluator {
    boolean evaluate(ConditionNode node, EvalContext ctx);
}
```

`ActionHandler.java`:
```java
package com.sstlfsj.rule.kernel.api.spi.action;

import com.sstlfsj.rule.kernel.api.model.ActionContext;
import com.sstlfsj.rule.kernel.api.model.ActionResult;

public interface ActionHandler {
    ActionResult execute(ActionContext ctx);

    default ActionResult compensate(ActionContext ctx) {
        return ActionResult.notSupported();
    }

    default ActionResult dryRun(ActionContext ctx) {
        return ActionResult.skipped(ctx.actionId(), ctx.actionType(), "DRY_RUN_NOT_IMPLEMENTED");
    }
}
```

`MetricSourceHandler.java`:
```java
package com.sstlfsj.rule.kernel.api.spi.metric;

import com.sstlfsj.rule.kernel.api.model.MetricQuery;
import com.sstlfsj.rule.kernel.api.model.MetricValue;

public interface MetricSourceHandler {
    MetricValue fetch(MetricQuery query);
}
```

`SubjectLoader.java`:
```java
package com.sstlfsj.rule.kernel.api.spi.subject;

import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import com.sstlfsj.rule.kernel.api.model.Subject;
import com.sstlfsj.rule.kernel.api.model.SubjectType;

import java.util.List;

public interface SubjectLoader {
    Subject load(String subjectId, SubjectType subjectType, RuleEvent event);
    List<SubjectType> supportedTypes();
}
```

- [ ] **Step 3: 创建 Watcher、Executor、Scheduler、TraceWriter、PreGate SPI**

`RuleVersionWatcher.java`:
```java
package com.sstlfsj.rule.kernel.api.spi.watcher;

import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import java.util.function.Consumer;

public interface RuleVersionWatcher {
    void watch(Consumer<RuleVersionSnapshot> onUpdate);
    void stop();
}
```

`SceneWatcher.java`:
```java
package com.sstlfsj.rule.kernel.api.spi.watcher;

import java.util.function.Consumer;

public interface SceneWatcher {
    record SceneChangeEvent(String tenantId, String sceneCode, boolean active) {}
    void watch(Consumer<SceneChangeEvent> onUpdate);
    void stop();
}
```

`RuleVersionExecutor.java`:
```java
package com.sstlfsj.rule.kernel.api.spi.executor;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;

public interface RuleVersionExecutor {
    EvalResult execute(RuleVersionSnapshot snapshot, EvalContext ctx);
}
```

`Scheduler.java`:
```java
package com.sstlfsj.rule.kernel.api.spi.scheduler;

public interface Scheduler {
    void schedule(String jobCode, String cronExpression, Runnable task);
    void unschedule(String jobCode);
}
```

`TraceWriter.java`:
```java
package com.sstlfsj.rule.kernel.api.spi.trace;

import com.sstlfsj.rule.kernel.api.model.NodeTrace;
import java.util.List;

public interface TraceWriter {
    void write(String tenantId, String sessionId, List<NodeTrace> traces);
}
```

`PreGate.java`:
```java
package com.sstlfsj.rule.kernel.api.spi.pregate;

import com.sstlfsj.rule.kernel.api.model.PreGateContext;
import com.sstlfsj.rule.kernel.api.model.PreGateResult;

public interface PreGate {
    String gateType();
    PreGateResult evaluate(PreGateContext ctx);
}
```

- [ ] **Step 4: 验证编译**

```bash
mvn compile -pl rule-kernel
```
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/annotation/ \
        rule-kernel/src/main/java/com/sstlfsj/rule/kernel/api/spi/
git commit -m "feat(kernel): SPI interfaces and annotation declarations"
```

---

## Task 4: rule-kernel — InterpretedExecutor + 单测 + ArchUnit 约束

**Files:**
- Create: `rule-kernel/src/main/java/com/sstlfsj/rule/kernel/internal/evaluator/InterpretedExecutor.java`
- Create: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/evaluator/InterpretedExecutorTest.java`
- Create: `rule-kernel/src/test/java/com/sstlfsj/rule/kernel/arch/KernelZeroSpringTest.java`

- [ ] **Step 1: 写失败测试（AND/OR/NOT 求值语义）**

`InterpretedExecutorTest.java`:
```java
package com.sstlfsj.rule.kernel.evaluator;

import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.kernel.api.model.ast.*;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;
import com.sstlfsj.rule.kernel.internal.evaluator.InterpretedExecutor;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InterpretedExecutorTest {

    // ConditionEvaluator stubs
    static final ConditionEvaluator ALWAYS_TRUE  = (node, ctx) -> true;
    static final ConditionEvaluator ALWAYS_FALSE = (node, ctx) -> false;

    static InterpretedExecutor executor(Map<String, ConditionEvaluator> evaluators) {
        return new InterpretedExecutor(evaluators);
    }

    static EvalContext emptyCtx() {
        RuleEvent event = new RuleEvent("t1", "scene1", "evt", "u1",
                "evt-1", Instant.now(), Map.of(), Map.of());
        return new EvalContext("t1", event, null, Map.of());
    }

    static RuleVersionSnapshot snapshot(AstNode ast) {
        return new RuleVersionSnapshot(1L, "scene1", "t1", ast, List.of(), List.of());
    }

    @Test
    void andNode_allChildren_true_returns_ruleHit() {
        var ast = new AndNode(List.of(
                new ConditionNode("t.true", null, null, Map.of()),
                new ConditionNode("t.true", null, null, Map.of())
        ), null, null);

        EvalResult result = executor(Map.of("t.true", ALWAYS_TRUE))
                .execute(snapshot(ast), emptyCtx());

        assertThat(result.ruleHit()).isTrue();
    }

    @Test
    void andNode_oneChild_false_returns_miss() {
        var ast = new AndNode(List.of(
                new ConditionNode("t.true",  null, null, Map.of()),
                new ConditionNode("t.false", null, null, Map.of())
        ), null, null);

        EvalResult result = executor(Map.of("t.true", ALWAYS_TRUE, "t.false", ALWAYS_FALSE))
                .execute(snapshot(ast), emptyCtx());

        assertThat(result.ruleHit()).isFalse();
    }

    @Test
    void andNode_shortCircuits_after_first_false() {
        int[] callCount = {0};
        ConditionEvaluator counter = (node, ctx) -> { callCount[0]++; return true; };

        var ast = new AndNode(List.of(
                new ConditionNode("t.false",   null, null, Map.of()),
                new ConditionNode("t.counter", null, null, Map.of())
        ), null, null);

        executor(Map.of("t.false", ALWAYS_FALSE, "t.counter", counter))
                .execute(snapshot(ast), emptyCtx());

        assertThat(callCount[0]).isZero(); // counter not called due to short-circuit
    }

    @Test
    void orNode_oneChild_true_returns_ruleHit() {
        var ast = new OrNode(List.of(
                new ConditionNode("t.false", null, null, Map.of()),
                new ConditionNode("t.true",  null, null, Map.of())
        ), null, null);

        EvalResult result = executor(Map.of("t.true", ALWAYS_TRUE, "t.false", ALWAYS_FALSE))
                .execute(snapshot(ast), emptyCtx());

        assertThat(result.ruleHit()).isTrue();
    }

    @Test
    void orNode_allFalse_returns_miss() {
        var ast = new OrNode(List.of(
                new ConditionNode("t.false", null, null, Map.of()),
                new ConditionNode("t.false", null, null, Map.of())
        ), null, null);

        EvalResult result = executor(Map.of("t.false", ALWAYS_FALSE))
                .execute(snapshot(ast), emptyCtx());

        assertThat(result.ruleHit()).isFalse();
    }

    @Test
    void notNode_inverts_true_to_false() {
        var ast = new NotNode(new ConditionNode("t.true", null, null, Map.of()));

        EvalResult result = executor(Map.of("t.true", ALWAYS_TRUE))
                .execute(snapshot(ast), emptyCtx());

        assertThat(result.ruleHit()).isFalse();
    }

    @Test
    void notNode_inverts_false_to_true() {
        var ast = new NotNode(new ConditionNode("t.false", null, null, Map.of()));

        EvalResult result = executor(Map.of("t.false", ALWAYS_FALSE))
                .execute(snapshot(ast), emptyCtx());

        assertThat(result.ruleHit()).isTrue();
    }

    @Test
    void nested_and_or_evaluated_correctly() {
        // AND(true, OR(false, true)) = AND(true, true) = true
        var ast = new AndNode(List.of(
                new ConditionNode("t.true", null, null, Map.of()),
                new OrNode(List.of(
                        new ConditionNode("t.false", null, null, Map.of()),
                        new ConditionNode("t.true",  null, null, Map.of())
                ), null, null)
        ), null, null);

        EvalResult result = executor(Map.of("t.true", ALWAYS_TRUE, "t.false", ALWAYS_FALSE))
                .execute(snapshot(ast), emptyCtx());

        assertThat(result.ruleHit()).isTrue();
    }
}
```

- [ ] **Step 2: 运行测试，确认失败（InterpretedExecutor 尚未创建）**

```bash
mvn test -pl rule-kernel -Dtest=InterpretedExecutorTest
```
Expected: FAIL with `ClassNotFoundException: InterpretedExecutor`

- [ ] **Step 3: 实现 InterpretedExecutor**

`InterpretedExecutor.java`:
```java
package com.sstlfsj.rule.kernel.internal.evaluator;

import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.kernel.api.model.ast.*;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;
import com.sstlfsj.rule.kernel.api.spi.executor.RuleVersionExecutor;

import java.util.Map;

public class InterpretedExecutor implements RuleVersionExecutor {

    private final Map<String, ConditionEvaluator> evaluators;

    public InterpretedExecutor(Map<String, ConditionEvaluator> evaluators) {
        this.evaluators = Map.copyOf(evaluators);
    }

    @Override
    public EvalResult execute(RuleVersionSnapshot snapshot, EvalContext ctx) {
        boolean satisfied = evaluate(snapshot.conditionAst(), ctx);
        return new EvalResult(satisfied, null, java.util.List.of(),
                java.util.List.of(), null, java.util.List.of());
    }

    private boolean evaluate(AstNode node, EvalContext ctx) {
        return switch (node) {
            case AndNode and   -> evaluateAnd(and, ctx);
            case OrNode or     -> evaluateOr(or, ctx);
            case NotNode not   -> !evaluate(not.child(), ctx);
            case ConditionNode c -> evaluateCondition(c, ctx);
        };
    }

    private boolean evaluateAnd(AndNode and, EvalContext ctx) {
        for (AstNode child : and.children()) {
            if (!evaluate(child, ctx)) return false; // short-circuit
        }
        return true;
    }

    private boolean evaluateOr(OrNode or, EvalContext ctx) {
        for (AstNode child : or.children()) {
            if (evaluate(child, ctx)) return true; // short-circuit
        }
        return false;
    }

    private boolean evaluateCondition(ConditionNode node, EvalContext ctx) {
        ConditionEvaluator evaluator = evaluators.get(node.conditionType());
        if (evaluator == null) {
            throw new IllegalStateException(
                    "No ConditionEvaluator registered for type: " + node.conditionType());
        }
        return evaluator.evaluate(node, ctx);
    }
}
```

- [ ] **Step 4: 运行测试，确认通过**

```bash
mvn test -pl rule-kernel -Dtest=InterpretedExecutorTest
```
Expected: BUILD SUCCESS，8 tests passed

- [ ] **Step 5: 写 ArchUnit 零 Spring 约束测试**

`KernelZeroSpringTest.java`:
```java
package com.sstlfsj.rule.kernel.arch;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(
        packages = "com.sstlfsj.rule.kernel",
        importOptions = ImportOption.DoNotIncludeTests.class
)
public class KernelZeroSpringTest {

    @ArchTest
    static final ArchRule noSpringDependencies =
            noClasses()
                    .that().resideInAPackage("com.sstlfsj.rule.kernel..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("org.springframework..")
                    .because("rule-kernel 是零 Spring 纯 Java 库，不允许任何 Spring 依赖");
}
```

- [ ] **Step 6: 运行 ArchUnit 测试**

```bash
mvn test -pl rule-kernel -Dtest=KernelZeroSpringTest
```
Expected: BUILD SUCCESS，ArchUnit violation = 0（rule-kernel 主类无 Spring import）

- [ ] **Step 7: 运行 rule-kernel 全部测试**

```bash
mvn test -pl rule-kernel
```
Expected: BUILD SUCCESS，所有测试通过

- [ ] **Step 8: Commit**

```bash
git add rule-kernel/src/
git commit -m "feat(kernel): InterpretedExecutor + unit tests + ArchUnit zero-Spring constraint"
```

---

## Task 5: rule-kernel-polling — DbPollingRuleWatcher + DbPollingSceneWatcher 骨架

**Files:**
- Create: `rule-kernel-polling/src/main/java/com/sstlfsj/rule/kernel/polling/DbPollingRuleWatcher.java`
- Create: `rule-kernel-polling/src/main/java/com/sstlfsj/rule/kernel/polling/DbPollingSceneWatcher.java`

- [ ] **Step 1: 创建 DbPollingRuleWatcher 骨架**

`DbPollingRuleWatcher.java`:
```java
package com.sstlfsj.rule.kernel.polling;

import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.spi.watcher.RuleVersionWatcher;

import java.util.function.Consumer;

/**
 * SDK 嵌入模式（无共享 Spring 容器）下的 DB 轮询实现。
 * 定期扫描 rule_version 表变更，触发回调更新倒排索引。
 */
public class DbPollingRuleWatcher implements RuleVersionWatcher {

    private final int intervalSeconds;
    private volatile boolean running = false;

    public DbPollingRuleWatcher(int intervalSeconds) {
        this.intervalSeconds = intervalSeconds;
    }

    @Override
    public void watch(Consumer<RuleVersionSnapshot> onUpdate) {
        running = true;
        // TODO(v2-sdk): 实现 DB 轮询线程，按 intervalSeconds 拉取变更后调用 onUpdate
        throw new UnsupportedOperationException("DbPollingRuleWatcher not yet implemented (SDK v2)");
    }

    @Override
    public void stop() {
        running = false;
    }
}
```

`DbPollingSceneWatcher.java`:
```java
package com.sstlfsj.rule.kernel.polling;

import com.sstlfsj.rule.kernel.api.spi.watcher.SceneWatcher;

import java.util.function.Consumer;

/**
 * SDK 嵌入模式下的 Scene 状态 DB 轮询实现。
 */
public class DbPollingSceneWatcher implements SceneWatcher {

    private final int intervalSeconds;
    private volatile boolean running = false;

    public DbPollingSceneWatcher(int intervalSeconds) {
        this.intervalSeconds = intervalSeconds;
    }

    @Override
    public void watch(Consumer<SceneChangeEvent> onUpdate) {
        running = true;
        // TODO(v2-sdk): 实现 DB 轮询
        throw new UnsupportedOperationException("DbPollingSceneWatcher not yet implemented (SDK v2)");
    }

    @Override
    public void stop() {
        running = false;
    }
}
```

- [ ] **Step 2: 验证编译**

```bash
mvn compile -pl rule-kernel-polling
```
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add rule-kernel-polling/src/
git commit -m "feat(kernel-polling): DbPollingRuleWatcher + DbPollingSceneWatcher stubs (SDK v2)"
```

---

## Task 6: rule-config-svc — 领域类 + Service 接口 + AutoConfiguration

**Files:**
- Create: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/service/ConfigService.java`
- Create: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/service/SceneService.java`
- Create: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/api/service/MetadataService.java`
- Create: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/domain/SceneDef.java`
- Create: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/domain/RuleDefinition.java`
- Create: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/domain/RuleVersion.java`
- Create: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/domain/MetricDefinition.java`
- Create: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/event/RulePublishedEvent.java`
- Create: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/event/SceneChangedEvent.java`
- Create: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/internal/service/ConfigServiceImpl.java`
- Create: `rule-config-svc/src/main/java/com/sstlfsj/rule/config/ConfigAutoConfiguration.java`
- Create: `rule-config-svc/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

- [ ] **Step 1: 创建对外 Service 接口**

`ConfigService.java`:
```java
package com.sstlfsj.rule.config.api.service;

import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;

public interface ConfigService {
    RuleVersionSnapshot publish(String tenantId, Long ruleDefinitionId, String actorId);
    void disable(String tenantId, Long ruleDefinitionId, String actorId);
}
```

`SceneService.java`:
```java
package com.sstlfsj.rule.config.api.service;

public interface SceneService {
    Long createScene(String tenantId, String sceneCode, String name, String actorId);
    void updateScene(String tenantId, String sceneCode, String actorId);
    void disableScene(String tenantId, String sceneCode, String actorId);
}
```

`MetadataService.java`:
```java
package com.sstlfsj.rule.config.api.service;

public interface MetadataService {
    MetadataResponse getSceneMetadata(String tenantId, String sceneCode);

    record MetadataResponse(
            java.util.List<ConditionTypeMeta> conditionTypes,
            java.util.List<ActionTypeMeta> actionTypes,
            java.util.List<MetricMeta> availableMetrics
    ) {}

    record ConditionTypeMeta(String code, String displayName,
                              Object paramsSchema, boolean requiresMetric) {}
    record ActionTypeMeta(String code, String displayName,
                          Object paramsSchema, boolean compensatable) {}
    record MetricMeta(String metricCode, String name,
                      String dataType, String sourceType, boolean allowProvided) {}
}
```

- [ ] **Step 2: 创建领域类（DB 实体）**

`SceneDef.java`:
```java
package com.sstlfsj.rule.config.internal.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("scene")
public class SceneDef {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private String code;
    private String name;
    private String status;
    private String dominantMode;
    private String subjectType;

    // getters / setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getDominantMode() { return dominantMode; }
    public void setDominantMode(String dominantMode) { this.dominantMode = dominantMode; }
    public String getSubjectType() { return subjectType; }
    public void setSubjectType(String subjectType) { this.subjectType = subjectType; }
}
```

`RuleDefinition.java`:
```java
package com.sstlfsj.rule.config.internal.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("rule_definition")
public class RuleDefinition {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private String sceneCode;
    private String name;
    private String status;
    private Long currentVersionId;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getSceneCode() { return sceneCode; }
    public void setSceneCode(String sceneCode) { this.sceneCode = sceneCode; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getCurrentVersionId() { return currentVersionId; }
    public void setCurrentVersionId(Long currentVersionId) { this.currentVersionId = currentVersionId; }
}
```

`RuleVersion.java`:
```java
package com.sstlfsj.rule.config.internal.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("rule_version")
public class RuleVersion {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private Long ruleDefinitionId;
    private String conditionAstJson;
    private String preGatesJson;
    private String decisionBindingsJson;
    private String status;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public Long getRuleDefinitionId() { return ruleDefinitionId; }
    public void setRuleDefinitionId(Long ruleDefinitionId) { this.ruleDefinitionId = ruleDefinitionId; }
    public String getConditionAstJson() { return conditionAstJson; }
    public void setConditionAstJson(String conditionAstJson) { this.conditionAstJson = conditionAstJson; }
    public String getPreGatesJson() { return preGatesJson; }
    public void setPreGatesJson(String preGatesJson) { this.preGatesJson = preGatesJson; }
    public String getDecisionBindingsJson() { return decisionBindingsJson; }
    public void setDecisionBindingsJson(String decisionBindingsJson) { this.decisionBindingsJson = decisionBindingsJson; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
```

`MetricDefinition.java`:
```java
package com.sstlfsj.rule.config.internal.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("metric_definition")
public class MetricDefinition {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String tenantId;
    private String metricCode;
    private String name;
    private String dataType;
    private String sourceType;
    private boolean allowProvided;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getMetricCode() { return metricCode; }
    public void setMetricCode(String metricCode) { this.metricCode = metricCode; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDataType() { return dataType; }
    public void setDataType(String dataType) { this.dataType = dataType; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public boolean isAllowProvided() { return allowProvided; }
    public void setAllowProvided(boolean allowProvided) { this.allowProvided = allowProvided; }
}
```

- [ ] **Step 3: 创建 Modulith 事件类**

`RulePublishedEvent.java`:
```java
package com.sstlfsj.rule.config.internal.event;

public record RulePublishedEvent(
        String tenantId,
        String sceneCode,
        Long ruleVersionId
) {}
```

`SceneChangedEvent.java`:
```java
package com.sstlfsj.rule.config.internal.event;

public record SceneChangedEvent(
        String tenantId,
        String sceneCode,
        boolean active
) {}
```

- [ ] **Step 4: 创建 ConfigServiceImpl 骨架**

`ConfigServiceImpl.java`:
```java
package com.sstlfsj.rule.config.internal.service;

import com.sstlfsj.rule.config.api.service.ConfigService;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import org.springframework.stereotype.Service;

@Service
class ConfigServiceImpl implements ConfigService {

    @Override
    public RuleVersionSnapshot publish(String tenantId, Long ruleDefinitionId, String actorId) {
        // TODO: 加载 RuleDefinition → 生成 RuleVersion 快照 → 发布 RulePublishedEvent
        throw new UnsupportedOperationException("publish not yet implemented");
    }

    @Override
    public void disable(String tenantId, Long ruleDefinitionId, String actorId) {
        // TODO: 更新 rule_definition.status = DISABLED → 发布 SceneChangedEvent
        throw new UnsupportedOperationException("disable not yet implemented");
    }
}
```

- [ ] **Step 5: 创建 AutoConfiguration + imports 文件**

`ConfigAutoConfiguration.java`:
```java
package com.sstlfsj.rule.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

@AutoConfiguration
@ComponentScan("com.sstlfsj.rule.config.internal")
public class ConfigAutoConfiguration {
}
```

`rule-config-svc/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`:
```
com.sstlfsj.rule.config.ConfigAutoConfiguration
```

- [ ] **Step 6: 验证编译**

```bash
mvn compile -pl rule-config-svc
```
Expected: BUILD SUCCESS

- [ ] **Step 7: Commit**

```bash
git add rule-config-svc/src/
git commit -m "feat(config-svc): service interfaces, domain entities, events, AutoConfiguration"
```

---

## Task 7: rule-eval-svc — EvalService + 索引骨架 + AutoConfiguration

**Files:**
- Create: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/api/service/EvalService.java`
- Create: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/index/SceneRuleIndex.java`
- Create: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/internal/service/EvalServiceImpl.java`
- Create: `rule-eval-svc/src/main/java/com/sstlfsj/rule/eval/EvalAutoConfiguration.java`
- Create: `rule-eval-svc/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

- [ ] **Step 1: 创建 EvalService 接口**

`EvalService.java`:
```java
package com.sstlfsj.rule.eval.api.service;

import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;

public interface EvalService {
    /** PUSH 评估（异步）：接收事件，返回 accepted=true/false */
    boolean acceptEvent(RuleEvent event);

    /** PULL 评估（同步）：返回完整 EvalResult */
    EvalResult evaluate(RuleEvent event);

    /** dry-run 评估：返回含 nodeTrace 的 EvalResult，不派发 Action */
    EvalResult dryRun(RuleEvent event, Long ruleVersionId);
}
```

- [ ] **Step 2: 创建 SceneRuleIndex（倒排索引骨架）**

`SceneRuleIndex.java`:
```java
package com.sstlfsj.rule.eval.internal.index;

import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存倒排索引：(tenantId, sceneCode, eventType) → List<RuleVersionSnapshot>
 * 由 RulePublishedEvent / SceneChangedEvent 监听器触发热更。
 */
@Component
public class SceneRuleIndex {

    // key: tenantId + ":" + sceneCode + ":" + eventType
    private final Map<String, List<RuleVersionSnapshot>> index = new ConcurrentHashMap<>();

    public List<RuleVersionSnapshot> match(String tenantId, String sceneCode, String eventType) {
        String key = tenantId + ":" + sceneCode + ":" + eventType;
        return index.getOrDefault(key, List.of());
    }

    public void update(String tenantId, String sceneCode, String eventType,
                       List<RuleVersionSnapshot> snapshots) {
        String key = tenantId + ":" + sceneCode + ":" + eventType;
        index.put(key, List.copyOf(snapshots));
    }

    public void remove(String tenantId, String sceneCode) {
        index.keySet().removeIf(k -> k.startsWith(tenantId + ":" + sceneCode + ":"));
    }
}
```

- [ ] **Step 3: 创建 EvalServiceImpl 骨架**

`EvalServiceImpl.java`:
```java
package com.sstlfsj.rule.eval.internal.service;

import com.sstlfsj.rule.eval.api.service.EvalService;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import org.springframework.stereotype.Service;

@Service
class EvalServiceImpl implements EvalService {

    @Override
    public boolean acceptEvent(RuleEvent event) {
        // TODO: 倒排索引 → Pre-Gate → EvalContext 构建 → 评估 → 异步派发 Action
        throw new UnsupportedOperationException("acceptEvent not yet implemented");
    }

    @Override
    public EvalResult evaluate(RuleEvent event) {
        // TODO: 同步评估链路
        throw new UnsupportedOperationException("evaluate not yet implemented");
    }

    @Override
    public EvalResult dryRun(RuleEvent event, Long ruleVersionId) {
        // TODO: dry-run，不派发 Action
        throw new UnsupportedOperationException("dryRun not yet implemented");
    }
}
```

- [ ] **Step 4: 创建 EvalAutoConfiguration**

`EvalAutoConfiguration.java`:
```java
package com.sstlfsj.rule.eval;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

@AutoConfiguration
@ComponentScan("com.sstlfsj.rule.eval.internal")
public class EvalAutoConfiguration {
}
```

`rule-eval-svc/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`:
```
com.sstlfsj.rule.eval.EvalAutoConfiguration
```

- [ ] **Step 5: 验证编译**

```bash
mvn compile -pl rule-eval-svc
```
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add rule-eval-svc/src/
git commit -m "feat(eval-svc): EvalService interface, SceneRuleIndex, AutoConfiguration"
```

---

## Task 8: rule-audit-svc — AuditService + AutoConfiguration

**Files:**
- Create: `rule-audit-svc/src/main/java/com/sstlfsj/rule/audit/api/service/AuditService.java`
- Create: `rule-audit-svc/src/main/java/com/sstlfsj/rule/audit/internal/service/AuditServiceImpl.java`
- Create: `rule-audit-svc/src/main/java/com/sstlfsj/rule/audit/AuditAutoConfiguration.java`
- Create: `rule-audit-svc/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

- [ ] **Step 1: 创建 AuditService 接口**

`AuditService.java`:
```java
package com.sstlfsj.rule.audit.api.service;

import java.util.List;

public interface AuditService {

    record AuditLogEntry(
            Long id,
            String tenantId,
            String resourceType,
            Long resourceId,
            String action,
            String actorId,
            String actorType,
            String beforeSnapshot,
            String afterSnapshot,
            java.time.Instant occurredAt
    ) {}

    record PageResult<T>(List<T> items, long total, int page, int size) {}

    PageResult<AuditLogEntry> queryAuditLogs(String tenantId, String resourceType,
                                              Long resourceId, int page, int size);

    record EvalSessionEntry(
            String sessionId,
            String tenantId,
            String sceneCode,
            String eventId,
            String status,
            java.time.Instant startedAt
    ) {}

    PageResult<EvalSessionEntry> queryEvalSessions(String tenantId, String eventId,
                                                    int page, int size);
}
```

- [ ] **Step 2: 创建 AuditServiceImpl 骨架**

`AuditServiceImpl.java`:
```java
package com.sstlfsj.rule.audit.internal.service;

import com.sstlfsj.rule.audit.api.service.AuditService;
import org.springframework.stereotype.Service;

@Service
class AuditServiceImpl implements AuditService {

    @Override
    public PageResult<AuditLogEntry> queryAuditLogs(String tenantId, String resourceType,
                                                     Long resourceId, int page, int size) {
        throw new UnsupportedOperationException("queryAuditLogs not yet implemented");
    }

    @Override
    public PageResult<EvalSessionEntry> queryEvalSessions(String tenantId, String eventId,
                                                           int page, int size) {
        throw new UnsupportedOperationException("queryEvalSessions not yet implemented");
    }
}
```

- [ ] **Step 3: 创建 AuditAutoConfiguration**

`AuditAutoConfiguration.java`:
```java
package com.sstlfsj.rule.audit;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

@AutoConfiguration
@ComponentScan("com.sstlfsj.rule.audit.internal")
public class AuditAutoConfiguration {
}
```

`rule-audit-svc/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`:
```
com.sstlfsj.rule.audit.AuditAutoConfiguration
```

- [ ] **Step 4: 验证编译**

```bash
mvn compile -pl rule-audit-svc
```
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add rule-audit-svc/src/
git commit -m "feat(audit-svc): AuditService interface, AutoConfiguration"
```

---

## Task 9: rule-observability — 指标常量 + TraceWriter 实现 + AutoConfiguration

**Files:**
- Create: `rule-observability/src/main/java/com/sstlfsj/rule/observability/api/metrics/RuleMetrics.java`
- Create: `rule-observability/src/main/java/com/sstlfsj/rule/observability/internal/trace/NoopTraceWriter.java`
- Create: `rule-observability/src/main/java/com/sstlfsj/rule/observability/internal/trace/TraceWriterDbImpl.java`
- Create: `rule-observability/src/main/java/com/sstlfsj/rule/observability/ObservabilityAutoConfiguration.java`
- Create: `rule-observability/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

- [ ] **Step 1: 创建 Prometheus 指标名常量**

`RuleMetrics.java`:
```java
package com.sstlfsj.rule.observability.api.metrics;

public final class RuleMetrics {
    private RuleMetrics() {}

    // 评估指标
    public static final String EVAL_DURATION_SECONDS   = "rule_eval_duration_seconds";
    public static final String EVAL_TOTAL              = "rule_eval_total";
    public static final String EVAL_ERROR_TOTAL        = "rule_eval_error_total";

    // metric 预拉
    public static final String METRIC_FETCH_DURATION   = "rule_metric_fetch_duration_seconds";
    public static final String METRIC_CACHE_HIT_TOTAL  = "rule_metric_cache_hit_total";
    public static final String METRIC_CACHE_MISS_TOTAL = "rule_metric_cache_miss_total";

    // Action 派发
    public static final String ACTION_DISPATCH_TOTAL   = "rule_action_dispatch_total";
    public static final String ACTION_DISPATCH_FAILED  = "rule_action_dispatch_failed_total";

    // TraceWriter 队列
    public static final String TRACE_QUEUE_SIZE        = "rule_trace_queue_size";
    public static final String TRACE_WRITE_BATCH_TOTAL = "rule_trace_write_batch_total";
}
```

- [ ] **Step 2: 创建 NoopTraceWriter（完整实现）**

`NoopTraceWriter.java`:
```java
package com.sstlfsj.rule.observability.internal.trace;

import com.sstlfsj.rule.kernel.api.model.NodeTrace;
import com.sstlfsj.rule.kernel.api.spi.trace.TraceWriter;

import java.util.List;

/** 测试 / SDK 模式下的空实现，直接丢弃 trace 数据 */
public class NoopTraceWriter implements TraceWriter {

    @Override
    public void write(String tenantId, String sessionId, List<NodeTrace> traces) {
        // intentionally empty — no-op
    }
}
```

- [ ] **Step 3: 创建 TraceWriterDbImpl 骨架（异步批写）**

`TraceWriterDbImpl.java`:
```java
package com.sstlfsj.rule.observability.internal.trace;

import com.sstlfsj.rule.kernel.api.model.NodeTrace;
import com.sstlfsj.rule.kernel.api.spi.trace.TraceWriter;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;

import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 主服务 TraceWriter 实现：异步 BlockingQueue + 批量落库（D21）。
 * 队列满时直接丢弃（不阻塞热路径）。
 */
public class TraceWriterDbImpl implements TraceWriter, InitializingBean, DisposableBean {

    private final int queueCapacity;
    private final int batchSize;
    private final long flushIntervalMs;

    // 内部队列，存 (tenantId, sessionId, traces) 三元组
    private record TraceEntry(String tenantId, String sessionId, List<NodeTrace> traces) {}
    private LinkedBlockingQueue<TraceEntry> queue;

    private volatile boolean running = false;
    private Thread consumerThread;

    public TraceWriterDbImpl(int queueCapacity, int batchSize, long flushIntervalMs) {
        this.queueCapacity = queueCapacity;
        this.batchSize = batchSize;
        this.flushIntervalMs = flushIntervalMs;
    }

    @Override
    public void afterPropertiesSet() {
        queue = new LinkedBlockingQueue<>(queueCapacity);
        running = true;
        consumerThread = Thread.ofVirtual().name("trace-writer").start(this::consumeLoop);
    }

    @Override
    public void write(String tenantId, String sessionId, List<NodeTrace> traces) {
        // 非阻塞入队；队列满时丢弃（旁路观察通道，不影响热路径，D21）
        queue.offer(new TraceEntry(tenantId, sessionId, traces));
    }

    private void consumeLoop() {
        while (running || !queue.isEmpty()) {
            try {
                Thread.sleep(flushIntervalMs);
                flushBatch();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void flushBatch() {
        // TODO: 从 queue 取 batchSize 条，批量写入 node_trace 表
        // 实现时注入 NodeTraceMapper (MyBatis-Plus)，调用 insertBatch
    }

    @Override
    public void destroy() {
        running = false;
        if (consumerThread != null) {
            consumerThread.interrupt();
        }
    }
}
```

- [ ] **Step 4: 创建 ObservabilityAutoConfiguration**

`ObservabilityAutoConfiguration.java`:
```java
package com.sstlfsj.rule.observability;

import com.sstlfsj.rule.observability.internal.trace.TraceWriterDbImpl;
import com.sstlfsj.rule.observability.internal.trace.NoopTraceWriter;
import com.sstlfsj.rule.kernel.api.spi.trace.TraceWriter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class ObservabilityAutoConfiguration {

    @Bean
    @ConditionalOnProperty(name = "engine.rule.trace.enabled", havingValue = "true", matchIfMissing = true)
    public TraceWriter traceWriterDb() {
        return new TraceWriterDbImpl(10000, 500, 200);
    }

    @Bean
    @ConditionalOnProperty(name = "engine.rule.trace.enabled", havingValue = "false")
    public TraceWriter noopTraceWriter() {
        return new NoopTraceWriter();
    }
}
```

`rule-observability/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`:
```
com.sstlfsj.rule.observability.ObservabilityAutoConfiguration
```

- [ ] **Step 5: 验证编译**

```bash
mvn compile -pl rule-observability
```
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add rule-observability/src/
git commit -m "feat(observability): RuleMetrics constants, NoopTraceWriter, TraceWriterDbImpl skeleton"
```

---

## Task 10: rule-api — HTTP 控制器骨架 + Filter

**Files:**
- Create: `rule-api/src/main/java/com/sstlfsj/rule/web/eval/EvalController.java`
- Create: `rule-api/src/main/java/com/sstlfsj/rule/web/config/RuleController.java`
- Create: `rule-api/src/main/java/com/sstlfsj/rule/web/config/SceneController.java`
- Create: `rule-api/src/main/java/com/sstlfsj/rule/web/config/MetadataController.java`
- Create: `rule-api/src/main/java/com/sstlfsj/rule/web/audit/AuditController.java`
- Create: `rule-api/src/main/java/com/sstlfsj/rule/web/filter/ActorIdFilter.java`
- Create: `rule-api/src/main/java/com/sstlfsj/rule/web/common/ApiResponse.java`

- [ ] **Step 1: 创建通用响应包装**

`ApiResponse.java`:
```java
package com.sstlfsj.rule.web.common;

public record ApiResponse<T>(boolean success, T data, String errorCode, String message) {
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null, null);
    }
    public static <T> ApiResponse<T> error(String errorCode, String message) {
        return new ApiResponse<>(false, null, errorCode, message);
    }
}
```

- [ ] **Step 2: 创建 EvalController（评估 / dry-run）**

`EvalController.java`:
```java
package com.sstlfsj.rule.web.eval;

import com.sstlfsj.rule.eval.api.service.EvalService;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import com.sstlfsj.rule.web.common.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/rule")
public class EvalController {

    private final EvalService evalService;

    public EvalController(EvalService evalService) {
        this.evalService = evalService;
    }

    /** POST /api/v1/rule/event — PUSH 评估（异步，202） */
    @PostMapping("/event")
    public ResponseEntity<ApiResponse<Map<String, Object>>> pushEvent(
            @RequestBody RuleEvent event) {
        boolean accepted = evalService.acceptEvent(event);
        return ResponseEntity.accepted()
                .body(ApiResponse.ok(Map.of("eventId", event.eventId(), "accepted", accepted)));
    }

    /** POST /api/v1/rule/evaluate — PULL 评估（同步，200） */
    @PostMapping("/evaluate")
    public ApiResponse<EvalResult> evaluate(@RequestBody RuleEvent event) {
        return ApiResponse.ok(evalService.evaluate(event));
    }

    /** POST /api/v1/rule/dry-run — dry-run 评估（含 nodeTrace） */
    @PostMapping("/dry-run")
    public ApiResponse<EvalResult> dryRun(
            @RequestBody RuleEvent event,
            @RequestParam(required = false) Long ruleVersionId) {
        return ApiResponse.ok(evalService.dryRun(event, ruleVersionId));
    }
}
```

- [ ] **Step 3: 创建 RuleController、SceneController、MetadataController**

`RuleController.java`:
```java
package com.sstlfsj.rule.web.config;

import com.sstlfsj.rule.config.api.service.ConfigService;
import com.sstlfsj.rule.web.common.ApiResponse;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/rules")
public class RuleController {

    private final ConfigService configService;

    public RuleController(ConfigService configService) {
        this.configService = configService;
    }

    @PostMapping("/{ruleId}/publish")
    public ApiResponse<Object> publish(
            @PathVariable Long ruleId,
            @RequestParam String tenantId,
            @RequestHeader("X-Actor-Id") String actorId) {
        return ApiResponse.ok(configService.publish(tenantId, ruleId, actorId));
    }

    @PostMapping("/{ruleId}/disable")
    public ApiResponse<Void> disable(
            @PathVariable Long ruleId,
            @RequestParam String tenantId,
            @RequestHeader("X-Actor-Id") String actorId) {
        configService.disable(tenantId, ruleId, actorId);
        return ApiResponse.ok(null);
    }
}
```

`SceneController.java`:
```java
package com.sstlfsj.rule.web.config;

import com.sstlfsj.rule.config.api.service.SceneService;
import com.sstlfsj.rule.web.common.ApiResponse;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/scenes")
public class SceneController {

    private final SceneService sceneService;

    public SceneController(SceneService sceneService) {
        this.sceneService = sceneService;
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> createScene(
            @RequestBody Map<String, String> body,
            @RequestHeader("X-Actor-Id") String actorId) {
        Long id = sceneService.createScene(body.get("tenantId"), body.get("code"),
                body.get("name"), actorId);
        return ApiResponse.ok(Map.of("id", id));
    }
}
```

`MetadataController.java`:
```java
package com.sstlfsj.rule.web.config;

import com.sstlfsj.rule.config.api.service.MetadataService;
import com.sstlfsj.rule.web.common.ApiResponse;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/scenes")
public class MetadataController {

    private final MetadataService metadataService;

    public MetadataController(MetadataService metadataService) {
        this.metadataService = metadataService;
    }

    @GetMapping("/{sceneCode}/metadata")
    public ApiResponse<MetadataService.MetadataResponse> getMetadata(
            @PathVariable String sceneCode,
            @RequestParam String tenantId) {
        return ApiResponse.ok(metadataService.getSceneMetadata(tenantId, sceneCode));
    }
}
```

- [ ] **Step 4: 创建 AuditController**

`AuditController.java`:
```java
package com.sstlfsj.rule.web.audit;

import com.sstlfsj.rule.audit.api.service.AuditService;
import com.sstlfsj.rule.web.common.ApiResponse;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class AuditController {

    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping("/evaluation-sessions")
    public ApiResponse<AuditService.PageResult<AuditService.EvalSessionEntry>> querySessions(
            @RequestParam String tenantId,
            @RequestParam(required = false) String eventId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(auditService.queryEvalSessions(tenantId, eventId, page, size));
    }

    @GetMapping("/audit-logs")
    public ApiResponse<AuditService.PageResult<AuditService.AuditLogEntry>> queryAuditLogs(
            @RequestParam String tenantId,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) Long resourceId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(auditService.queryAuditLogs(tenantId, resourceType, resourceId, page, size));
    }
}
```

- [ ] **Step 5: 创建 ActorIdFilter（X-Actor-Id header 校验）**

`ActorIdFilter.java`:
```java
package com.sstlfsj.rule.web.filter;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 从 X-Actor-Id header 提取操作人身份，放入 ThreadLocal 供 Service 层审计使用。
 * D14：引擎不维护用户表，身份来自上游网关 header。
 */
@Component
@Order(1)
public class ActorIdFilter implements Filter {

    private static final ThreadLocal<String> ACTOR_HOLDER = new ThreadLocal<>();

    public static String current() {
        return ACTOR_HOLDER.get();
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response,
                         FilterChain chain) throws IOException, ServletException {
        HttpServletRequest httpReq = (HttpServletRequest) request;
        String actorId = httpReq.getHeader("X-Actor-Id");
        ACTOR_HOLDER.set(actorId != null ? actorId : "UNKNOWN");
        try {
            chain.doFilter(request, response);
        } finally {
            ACTOR_HOLDER.remove();
        }
    }
}
```

- [ ] **Step 6: 验证编译**

```bash
mvn compile -pl rule-api
```
Expected: BUILD SUCCESS

- [ ] **Step 7: Commit**

```bash
git add rule-api/src/
git commit -m "feat(api): HTTP controller skeletons, ActorIdFilter, ApiResponse wrapper"
```

---

## Task 11: rule-app — 启动类 + application.yml

**Files:**
- Create: `rule-app/src/main/java/com/sstlfsj/rule/app/RuleEngineApplication.java`
- Create: `rule-app/src/main/resources/application.yml`

- [ ] **Step 1: 创建 Spring Boot 启动类**

`RuleEngineApplication.java`:
```java
package com.sstlfsj.rule.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.sstlfsj.rule")
public class RuleEngineApplication {
    public static void main(String[] args) {
        SpringApplication.run(RuleEngineApplication.class, args);
    }
}
```

- [ ] **Step 2: 创建 application.yml 骨架**

`application.yml`:
```yaml
spring:
  application:
    name: rule-engine
  datasource:
    url: jdbc:mysql://localhost:3306/rule_engine?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
    username: root
    password: password
    driver-class-name: com.mysql.cj.jdbc.Driver
  modulith:
    events:
      republish-outstanding-events-on-restart: true

mybatis-plus:
  mapper-locations: classpath*:mapper/**/*.xml
  configuration:
    map-underscore-to-camel-case: true

management:
  endpoints:
    web:
      exposure:
        include: health,prometheus,info
  metrics:
    export:
      prometheus:
        enabled: true

engine:
  rule:
    matcher:
      cache-refresh-interval-seconds: 30
    scene:
      watch-interval-seconds: 60
    idempotency:
      redis-ttl-seconds: 86400
    trace:
      enabled: true
      queue-capacity: 10000
      batch-size: 500
      flush-interval-ms: 200
      consumer-threads: 2
    metric:
      default-cache-ttl-seconds: 60
    action:
      default-timeout-ms: 3000
    retention:
      evaluation-session-days: 90
      node-trace-days: 30
      dry-run-session-days: 7
    rollout:
      hash-seed: 42
    observability:
      eval-error-rate-threshold: 0.05
      trace-queue-full-threshold: 0.8

logging:
  level:
    com.sstlfsj.rule: DEBUG
```

- [ ] **Step 3: 尝试打包（跳过测试）**

```bash
mvn package -pl rule-app -am -DskipTests
```
Expected: BUILD SUCCESS，`rule-app/target/rule-app-1.0.0-SNAPSHOT.jar` 生成

> 若遇到 Spring Boot / Spring Modulith 版本兼容问题，检查 Maven Central 并更新根 pom.xml 中的版本号。

- [ ] **Step 4: Commit**

```bash
git add rule-app/src/
git commit -m "feat(app): Spring Boot entry point (RuleEngineApplication) + application.yml"
```

---

## Task 12: Modulith 边界测试 + 最终验证

**Files:**
- Create: `rule-app/src/test/java/com/sstlfsj/rule/app/module/ConfigModuleTest.java`
- Create: `rule-app/src/test/java/com/sstlfsj/rule/app/module/EvalModuleTest.java`
- Create: `rule-app/src/test/java/com/sstlfsj/rule/app/module/AuditModuleTest.java`
- Create: `rule-app/src/test/java/com/sstlfsj/rule/app/arch/KernelArchTest.java`

- [ ] **Step 1: 创建 ArchUnit 零 Spring 约束测试（在 rule-app 环境运行）**

`KernelArchTest.java`:
```java
package com.sstlfsj.rule.app.arch;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(
        packages = "com.sstlfsj.rule.kernel",
        importOptions = ImportOption.DoNotIncludeTests.class
)
public class KernelArchTest {

    @ArchTest
    static final ArchRule kernelMustNotDependOnSpring =
            noClasses()
                    .that().resideInAPackage("com.sstlfsj.rule.kernel..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("org.springframework..")
                    .because("rule-kernel 是零 Spring 纯 Java 库（见 09-skeleton.md §五）");

    @ArchTest
    static final ArchRule svcsMustNotDependOnEachOther =
            noClasses()
                    .that().resideInAPackage("com.sstlfsj.rule.config..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("com.sstlfsj.rule.eval..")
                    .because("svc 模块间禁止直接依赖（只通过 Modulith 事件通信，09-skeleton §五）");
}
```

- [ ] **Step 2: 创建 Modulith ApplicationModuleTest**

`ConfigModuleTest.java`:
```java
package com.sstlfsj.rule.app.module;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.Scenario;

@ApplicationModuleTest(module = "config")
class ConfigModuleTest {

    @Test
    void configModuleBootsInIsolation(Scenario scenario) {
        // 验证 config 模块可以在无 eval / audit 依赖的情况下独立启动
        // Modulith 会静态分析依赖图，非法的跨模块访问会在这里报错
    }
}
```

`EvalModuleTest.java`:
```java
package com.sstlfsj.rule.app.module;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.Scenario;

@ApplicationModuleTest(module = "eval")
class EvalModuleTest {

    @Test
    void evalModuleBootsInIsolation(Scenario scenario) {
        // 验证 eval 模块在没有 config 模块直接依赖的情况下能静态通过边界检查
    }
}
```

`AuditModuleTest.java`:
```java
package com.sstlfsj.rule.app.module;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.Scenario;

@ApplicationModuleTest(module = "audit")
class AuditModuleTest {

    @Test
    void auditModuleBootsInIsolation(Scenario scenario) {
        // 验证 audit 模块边界合规
    }
}
```

- [ ] **Step 3: 运行 ArchUnit 测试**

```bash
mvn test -pl rule-app -Dtest=KernelArchTest
```
Expected: BUILD SUCCESS，两条 ArchUnit 规则均通过

- [ ] **Step 4: 运行 Modulith 边界测试（仅静态分析，无需 DB）**

```bash
mvn test -pl rule-app -Dtest="ConfigModuleTest,EvalModuleTest,AuditModuleTest"
```
Expected: BUILD SUCCESS（若有边界违例，测试会列出违规类，按 09-skeleton §五 依赖方向修复）

> 注意：`@ApplicationModuleTest` 会启动精简 Spring 上下文；若报 DataSource 缺失，在测试类上加 `@MockBean DataSource dataSource` 或使用 H2 内存数据库作为测试 DataSource。

- [ ] **Step 5: 全量编译 + 全量测试（排除集成测）**

```bash
mvn test -pl rule-kernel,rule-app -DexcludeGroups=integration
```
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add rule-app/src/test/
git commit -m "test: Modulith boundary tests + ArchUnit zero-Spring and svc-isolation constraints"
```

- [ ] **Step 7: 全量打包确认骨架完整**

```bash
mvn package -DskipTests
```
Expected: BUILD SUCCESS，8 个模块 jar 全部生成

- [ ] **Step 8: 最终 Commit（如有遗漏文件）**

```bash
git status
# 确认所有文件已提交
git commit -m "chore: backend skeleton complete — 8 modules compile, ArchUnit + Modulith tests pass" \
    --allow-empty  # 仅在确实有未提交改动时省略 --allow-empty
```
