# rule-job-xxl Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新增 Maven 模块 `rule-job-xxl`,把 xxl-job 作为 rule-kernel `Scheduler` SPI 的另一适配实现接入,复用 `JobRunner` 整条触发链路不重写组装。

**Architecture:** xxl 模块提供 `XxlJobSchedulerAdapter implements Scheduler`,`schedule()` 时把 `() -> runById(jobId)` 闭包动态注册成一个 `IJobHandler`(名=jobCode),并把 job seed 到 admin(语义"有了不管":已存在则保持不动,admin 控制台为 cron 权威源)。装配经已预留的 `engine.rule.job.scheduler=xxl-job` 钩子接管进程内实现,内制侧(`JobDefinition`/`JobExecution`/`JobRunner`)零改动。admin 接入用 JDK `java.net.http.HttpClient` + 注入的全局 `ObjectMapper`,不引 hutool。

**Tech Stack:** Java 25 / Spring Boot 4.0.6 / `com.xuxueli:xxl-job-core:3.4.0`(稳定版) / JDK HttpClient / Jackson 3(`tools.jackson`) / JUnit5 + Mockito + AssertJ。

---

## 关键事实(实现前必读,均经研究核实)

1. **xxl-job-core 3.4.0 自身基于 Spring Boot 4.0.5 构建**,core jar 内无 `javax.*`(全 jakarta),Spring Framework 7 / Jakarta EE 11 下无 javax→jakarta 适配坑。传递依赖会带入 netty 4.2.12 / xxl-tool 2.5.0 / gson 2.13.2 / groovy 5。
2. **`IJobHandler` 是抽象类**(不是 interface):`public abstract void execute() throws Exception`(**无参、返回 void**)。job 参数走 `XxlJobHelper.getJobParam()` 静态取,结果走 `XxlJobHelper.handleSuccess()/handleFail()`(不写则默认成功)。
3. **编程式动态注册**:`XxlJobExecutor.registryJobHandler(String name, IJobHandler handler)`(**静态方法,拼写带 `y`**),底层 `ConcurrentHashMap.put`,返回被覆盖的旧 handler。`loadJobHandler(name)` 取(不存在返 null)。**注意**:registry 是 `ConcurrentHashMap`,不接受 null value 且无公开注销 API → 注销=覆盖为 no-op tombstone handler。
4. **执行器** `XxlJobSpringExecutor extends XxlJobExecutor implements SmartInitializingSingleton, DisposableBean`。配置走 setter(`setAdminAddresses/setAccessToken/setAppname/setAddress/setIp/setPort/setLogPath/setLogRetentionDays/setEnabled`)。**自动 start**(`afterSingletonsInstantiated()`),**不要手动调 `start()`**;`setEnabled(false)` 则 start 直接跳过(测试用)。EmbedServer 是 Netty,默认端口 9999。
5. **admin REST 契约(3.4.0,与旧参考实现使用的 2.4.0 断层,必须按 3.4.0 写)**:
   - 登录:`POST /auth/doLogin`,form `userName`/`password`,返回 `Response{code,msg,data}`,**`data` 直接是 sso token**。后续请求带 `Cookie: xxl_sso_token=<token>`。
   - jobgroup:`POST /jobgroup/pageList`(form `offset/pagesize/appname/title`)、`POST /jobgroup/insert`(form `appname/title/addressType=0/addressList`)。写操作要求登录账号具 ADMIN_ROLE。
   - jobinfo:`POST /jobinfo/pageList`(form `offset/pagesize/jobGroup/triggerStatus/jobDesc/executorHandler/author`,**全部必填**,空串占位)、`POST /jobinfo/insert`(XxlJobInfo 全字段 form)。
   - **统一返回** `Response{code,msg,data}`:成功 `code==200`;**pageList 行在 `data.data`(数组)、总数 `data.total`**(`PageModel`,不是 `recordsTotal`);insert 新 id 在 `data`。
6. **"有了不管" seed**:`/jobinfo/pageList` 的 `executorHandler` 入参是**模糊匹配**,需在客户端对结果再 `equals` 精确过滤;命中即 return 其 id **不发任何写请求**(与旧参考实现的"有了就 update"相反)。
7. **jobCode 形如 `"job:" + jobId`**(来自 `JobScheduleManager.key()`),handler 名直接用它;冒号在 admin executorHandler 字段是普通字符串,保持原样。若构建时发现 admin 拒绝冒号,再做转义(默认不转)。

**需在构建/联调时验证(未编造,不要当成已知)**:(a) admin 实际 context-path 与端口;(b) `@XxlSso` 拦截器是否接受 cookie `xxl_sso_token`(本计划用 cookie 方式,SSO 默认 tokenKey=`xxl_sso_token`);(c) seed 账号是否具 ADMIN_ROLE;(d) GraalVM native image 下 netty/groovy/xxl-tool 的 reachability(Task 7 go/no-go 门控)。

## 范围之外(本计划不含)

- **对外 REST 触发入口**(`POST /jobs/{jobId}/trigger` in rule-api,架在 `JobService.triggerOnce` 上):spec 列为独立可即时落的瘦入口,与 xxl 接入正交,**单独成计划**,本计划不含。
- 内制侧任何改动(`JobDefinition`/`JobExecution`/`JobRunner`/`Scheduler` SPI):零改动。

## 文件结构

```
rule-job-xxl/
├── pom.xml                                                    (Task 1)
├── src/main/java/com/sstlfsj/rule/job/xxl/
│   ├── XxlJobAutoConfiguration.java                           (Task 6)
│   ├── XxlJobProperties.java                                  (Task 2)
│   └── internal/
│       ├── XxlJobAdminClient.java        (接口,seed 契约)     (Task 3)
│       ├── HttpXxlJobAdminClient.java    (JDK HttpClient 实现) (Task 3/4)
│       └── XxlJobSchedulerAdapter.java   (implements Scheduler)(Task 5)
├── src/main/resources/META-INF/spring/
│   └── org.springframework.boot.autoconfigure.AutoConfiguration.imports  (Task 6)
└── src/test/java/com/sstlfsj/rule/job/xxl/
    ├── XxlJobPropertiesTest.java                              (Task 2)
    ├── internal/HttpXxlJobAdminClientTest.java                (Task 3/4)
    ├── internal/XxlJobSchedulerAdapterTest.java               (Task 5)
    └── XxlJobAutoConfigurationTest.java                       (Task 6)
```

根 `pom.xml` 改动:`<modules>` 加 `rule-job-xxl`、`<properties>` 加 `xxl-job.version`、`<dependencyManagement>` 加 `xxl-job-core` + `rule-job-xxl`(Task 1)。`rule-app/pom.xml` 加 `xxl` profile(Task 7)。

---

## Task 1: 模块脚手架 + 根 pom 接入

**Files:**
- Create: `rule-job-xxl/pom.xml`
- Create: `rule-job-xxl/src/main/java/com/sstlfsj/rule/job/xxl/package-info.java`
- Modify: `pom.xml`(根)`<modules>` L25 后、`<properties>` L49 后、`<dependencyManagement>` L228 后

- [ ] **Step 1: 根 pom 加模块**

`pom.xml` L25 `<module>rule-job-svc</module>` 之后插入一行:

```xml
        <module>rule-job-svc</module>
        <module>rule-job-xxl</module>
        <module>rule-audit-svc</module>
```

- [ ] **Step 2: 根 pom 加 xxl-job 版本属性**

`pom.xml` `<properties>` 段 L49 `<springdoc.version>3.0.3</springdoc.version>` 之后插入:

```xml
        <!-- xxl-job-core 3.4.0：稳定版（2026-04-05），自身基于 Spring Boot 4.0.5 构建，无 javax 引用 -->
        <xxl-job.version>3.4.0</xxl-job.version>
```

- [ ] **Step 3: 根 pom dependencyManagement 加 xxl-job-core 与内部模块条目**

`pom.xml` L228（`rule-job-svc` 内部模块条目）之后插入 `rule-job-xxl` 条目;并在内部模块段之前(L201 springdoc 条目之后)加 xxl-job-core 第三方条目:

```xml
            <!-- xxl-job 执行器核心（adapter 模块按需引入；admin 端独立部署不在此） -->
            <dependency>
                <groupId>com.xuxueli</groupId>
                <artifactId>xxl-job-core</artifactId>
                <version>${xxl-job.version}</version>
            </dependency>
```

内部模块段 `rule-job-svc` 条目之后:

```xml
            <dependency>
                <groupId>com.sstlfsj.rule</groupId>
                <artifactId>rule-job-xxl</artifactId>
                <version>${project.version}</version>
            </dependency>
```

- [ ] **Step 4: 写模块 pom**

`rule-job-xxl/pom.xml`:

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
        <version>${revision}</version>
    </parent>
    <artifactId>rule-job-xxl</artifactId>

    <dependencies>
        <!-- Scheduler SPI（唯一被实现的契约） -->
        <dependency>
            <groupId>com.sstlfsj.rule</groupId>
            <artifactId>rule-kernel</artifactId>
        </dependency>
        <!-- xxl-job 执行器 + IJobHandler + 动态注册 API -->
        <dependency>
            <groupId>com.xuxueli</groupId>
            <artifactId>xxl-job-core</artifactId>
        </dependency>
        <!-- @AutoConfiguration / @ConditionalOnProperty / @ConfigurationProperties -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-autoconfigure</artifactId>
        </dependency>
        <!-- admin 响应 JSON 解析（Jackson 3，版本由 Spring Boot BOM 管理） -->
        <dependency>
            <groupId>tools.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
        </dependency>
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
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

- [ ] **Step 5: 写 package-info（占位,让模块有源码可编译）**

`rule-job-xxl/src/main/java/com/sstlfsj/rule/job/xxl/package-info.java`:

```java
/** xxl-job 作为 rule-kernel Scheduler SPI 的适配实现（按需引入，gate 在 engine.rule.job.scheduler=xxl-job）。 */
package com.sstlfsj.rule.job.xxl;
```

- [ ] **Step 6: 编译验证**

设置环境后(`mvn-env` skill),运行:

```bash
$MVN -pl rule-job-xxl -am compile
```

Expected: BUILD SUCCESS;依赖树含 `com.xuxueli:xxl-job-core:3.4.0` 及传递的 netty/xxl-tool/groovy。

- [ ] **Step 7: Commit**

```bash
git add pom.xml rule-job-xxl/pom.xml rule-job-xxl/src/main/java/com/sstlfsj/rule/job/xxl/package-info.java
git commit -m "feat(job-xxl): 脚手架 rule-job-xxl 模块 + 接入根 pom（xxl-job-core 3.4.0）"
```

---

## Task 2: XxlJobProperties

**Files:**
- Create: `rule-job-xxl/src/main/java/com/sstlfsj/rule/job/xxl/XxlJobProperties.java`
- Test: `rule-job-xxl/src/test/java/com/sstlfsj/rule/job/xxl/XxlJobPropertiesTest.java`

- [ ] **Step 1: 写失败测试**

`XxlJobPropertiesTest.java`:

```java
package com.sstlfsj.rule.job.xxl;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证 engine.rule.job.xxl 前缀绑定与默认值。 */
class XxlJobPropertiesTest {

    private XxlJobProperties bind(MockEnvironment env) {
        var sources = ConfigurationPropertySources.from(env.getPropertySources());
        return new Binder(sources).bind("engine.rule.job.xxl", XxlJobProperties.class).get();
    }

    @Test
    void bindsAdminAndExecutorFields() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("engine.rule.job.xxl.admin-addresses", "http://a/xxl-job-admin")
                .withProperty("engine.rule.job.xxl.appname", "rule-engine")
                .withProperty("engine.rule.job.xxl.access-token", "secret")
                .withProperty("engine.rule.job.xxl.admin-username", "admin")
                .withProperty("engine.rule.job.xxl.admin-password", "pwd");

        XxlJobProperties p = bind(env);

        assertThat(p.getAdminAddresses()).isEqualTo("http://a/xxl-job-admin");
        assertThat(p.getAppname()).isEqualTo("rule-engine");
        assertThat(p.getAccessToken()).isEqualTo("secret");
        assertThat(p.getAdminUsername()).isEqualTo("admin");
        assertThat(p.getAdminPassword()).isEqualTo("pwd");
    }

    @Test
    void appliesDefaults() {
        XxlJobProperties p = bind(new MockEnvironment()
                .withProperty("engine.rule.job.xxl.admin-addresses", "http://a/xxl-job-admin"));

        assertThat(p.getPort()).isEqualTo(9999);
        assertThat(p.getLogRetentionDays()).isEqualTo(30);
        assertThat(p.isEnabled()).isTrue();
    }
}
```

- [ ] **Step 2: 运行测试,确认编译失败**

```bash
$MVN -pl rule-job-xxl -am test -Dtest=XxlJobPropertiesTest
```

Expected: 编译失败(`XxlJobProperties` 不存在)。

- [ ] **Step 3: 写 XxlJobProperties**

`XxlJobProperties.java`:

```java
package com.sstlfsj.rule.job.xxl;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * xxl-job 适配器配置：executor 注册参数 + admin 接入凭证。
 *
 * <p>敏感字段（accessToken / adminPassword）按 secret 处理，yml 用 ${XXL_ACCESS_TOKEN} 等占位符经环境注入，
 * 不入库、不硬编码。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "engine.rule.job.xxl")
public class XxlJobProperties {

    /** admin 根地址（含 context-path，逗号分隔多实例），如 http://127.0.0.1:8080/xxl-job-admin。 */
    private String adminAddresses;

    /** 执行器 appname，对应 admin 侧 jobgroup 的 appname。 */
    private String appname = "rule-engine";

    /** 执行器对外注册地址（空则由 ip:port 拼）。 */
    private String address;

    /** 执行器 ip（空则自动探测）。 */
    private String ip;

    /** 执行器回调端口（Netty EmbedServer 监听；<=0 时从 9999 起找可用口）。 */
    private int port = 9999;

    /** admin / executor 通信令牌（敏感，经 ${XXL_ACCESS_TOKEN} 注入）。 */
    private String accessToken;

    /** 执行器本地日志目录。 */
    private String logPath = "/data/applogs/xxl-job/jobhandler";

    /** 执行器日志保留天数。 */
    private int logRetentionDays = 30;

    /** seed job 到 admin 所需的登录账号（敏感，需 ADMIN_ROLE）。 */
    private String adminUsername;

    /** seed job 到 admin 所需的登录密码（敏感，经 ${XXL_ADMIN_PASSWORD} 注入）。 */
    private String adminPassword;

    /** 是否启动执行器（false 则 XxlJobExecutor.start 跳过，不绑端口、不注册 admin；测试 / 仅装配场景用）。 */
    private boolean enabled = true;
}
```

- [ ] **Step 4: 运行测试,确认通过**

```bash
$MVN -pl rule-job-xxl -am test -Dtest=XxlJobPropertiesTest
```

Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add rule-job-xxl/src/main/java/com/sstlfsj/rule/job/xxl/XxlJobProperties.java rule-job-xxl/src/test/java/com/sstlfsj/rule/job/xxl/XxlJobPropertiesTest.java
git commit -m "feat(job-xxl): XxlJobProperties（engine.rule.job.xxl，敏感字段经环境注入）"
```

---

## Task 3: XxlJobAdminClient 接口 + 登录(懒登录 + 失效重试)

**Files:**
- Create: `rule-job-xxl/src/main/java/com/sstlfsj/rule/job/xxl/internal/XxlJobAdminClient.java`
- Create: `rule-job-xxl/src/main/java/com/sstlfsj/rule/job/xxl/internal/HttpXxlJobAdminClient.java`
- Test: `rule-job-xxl/src/test/java/com/sstlfsj/rule/job/xxl/internal/HttpXxlJobAdminClientTest.java`

> 本任务实现接口 + 登录链路 + 通用 form POST 骨架,seed 业务(ensureJobSeeded 的 group/jobinfo 逻辑)在 Task 4 补。测试用 `com.sun.net.httpserver.HttpServer` 起本地 stub admin,断言登录被调用且带凭证。

- [ ] **Step 1: 写接口**

`XxlJobAdminClient.java`:

```java
package com.sstlfsj.rule.job.xxl.internal;

/** xxl-job admin 接入 SPI：登录态管理 + job seed（"有了不管"语义由实现保证）。 */
public interface XxlJobAdminClient {

    /**
     * 确保 admin 侧存在 executorHandler=handlerName 的 jobinfo：不存在则按 cron 新建，已存在则保持不动
     * （admin 控制台为 cron 权威源，不覆盖运维改动）。
     *
     * @param handlerName 执行器 handler 名（= jobCode）
     * @param cron        cron 表达式（仅新建时写入）
     * @return admin 侧该 job 的 id
     */
    long ensureJobSeeded(String handlerName, String cron);
}
```

- [ ] **Step 2: 写失败测试(登录链路)**

`HttpXxlJobAdminClientTest.java`:

```java
package com.sstlfsj.rule.job.xxl.internal;

import com.sstlfsj.rule.job.xxl.XxlJobProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** 用本地 stub admin（com.sun.net.httpserver）验证 HttpXxlJobAdminClient 的登录与 seed 行为。 */
class HttpXxlJobAdminClientTest {

    private HttpServer server;
    private String base;
    /** 记录每个 path 收到的请求次数，供断言"有了不管"是否发了写请求。 */
    private final Map<String, Integer> hits = new ConcurrentHashMap<>();
    /** 各 path 的预置响应体（JSON）。 */
    private final Map<String, String> responses = new ConcurrentHashMap<>();
    /** 记录 jobinfo/pageList 请求体，便于断言过滤参数。 */
    private final List<String> capturedBodies = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/xxl-job-admin/", this::handle);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort() + "/xxl-job-admin";
        // 默认：登录成功返回 token
        responses.put("/xxl-job-admin/auth/doLogin", "{\"code\":200,\"msg\":null,\"data\":\"tok-123\"}");
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        hits.merge(path, 1, Integer::sum);
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        capturedBodies.add(path + "?" + body);
        String resp = responses.getOrDefault(path, "{\"code\":500,\"msg\":\"no stub\",\"data\":null}");
        byte[] bytes = resp.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private HttpXxlJobAdminClient client() {
        XxlJobProperties p = new XxlJobProperties();
        p.setAdminAddresses(base);
        p.setAppname("rule-engine");
        p.setAdminUsername("admin");
        p.setAdminPassword("pwd");
        return new HttpXxlJobAdminClient(p, JsonMapper.builder().build());
    }

    @Test
    void seedLogsInBeforeAdminCall() {
        // group 已存在 + jobinfo 已存在 → 不应发写请求，但应已登录
        responses.put("/xxl-job-admin/jobgroup/pageList",
                "{\"code\":200,\"data\":{\"data\":[{\"id\":7,\"appname\":\"rule-engine\"}],\"total\":1}}");
        responses.put("/xxl-job-admin/jobinfo/pageList",
                "{\"code\":200,\"data\":{\"data\":[{\"id\":42,\"executorHandler\":\"job:1\"}],\"total\":1}}");

        long id = client().ensureJobSeeded("job:1", "0 0 * * * ?");

        assertThat(id).isEqualTo(42L);
        assertThat(hits).containsKey("/xxl-job-admin/auth/doLogin");
        assertThat(hits.getOrDefault("/xxl-job-admin/jobinfo/insert", 0)).isZero();
    }
}
```

- [ ] **Step 3: 运行测试,确认失败**

```bash
$MVN -pl rule-job-xxl -am test -Dtest=HttpXxlJobAdminClientTest
```

Expected: 编译失败(`HttpXxlJobAdminClient` 不存在)。

- [ ] **Step 4: 写 HttpXxlJobAdminClient(登录 + form POST 骨架 + seed 入口)**

`HttpXxlJobAdminClient.java`:

```java
package com.sstlfsj.rule.job.xxl.internal;

import com.sstlfsj.rule.job.xxl.XxlJobProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * XxlJobAdminClient 的 HTTP 实现：JDK HttpClient + 注入的全局 ObjectMapper，form-urlencoded 调 admin REST。
 *
 * <p>登录态：懒登录（首个需鉴权请求触发），cookie 失效（code!=200 且含登录提示）时清 token 重试一次；
 * 登录本身失败最多重试 {@value #LOGIN_MAX_RETRY} 次。seed 语义"有了不管"见 {@link #ensureJobSeeded}。
 */
public class HttpXxlJobAdminClient implements XxlJobAdminClient {

    private static final Logger log = LoggerFactory.getLogger(HttpXxlJobAdminClient.class);
    private static final int LOGIN_MAX_RETRY = 3;
    private static final int SUCCESS_CODE = 200;
    /** SSO token 的 cookie 名（admin 默认 tokenKey；若 admin 自定义需同步） */
    private static final String SSO_TOKEN_COOKIE = "xxl_sso_token";

    private final XxlJobProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http;
    private final String adminBase;
    private volatile String token;

    public HttpXxlJobAdminClient(XxlJobProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        // seed 一次即可（多 admin 实例共享库），取第一个地址
        this.adminBase = props.getAdminAddresses().split(",")[0].trim();
    }

    @Override
    public synchronized long ensureJobSeeded(String handlerName, String cron) {
        int groupId = ensureJobGroup();
        Long existing = findJobInfoId(groupId, handlerName);
        if (existing != null) {
            log.info("xxl-job admin 已存在 job handler={} id={}，保持不动（有了不管）", handlerName, existing);
            return existing;
        }
        return insertJobInfo(groupId, handlerName, cron);
    }

    // ---- Task 4 补全 group / jobinfo 逻辑；本任务先留桩占位，保证编译 ----

    private int ensureJobGroup() {
        throw new UnsupportedOperationException("Task 4 实现");
    }

    private Long findJobInfoId(int groupId, String handlerName) {
        throw new UnsupportedOperationException("Task 4 实现");
    }

    private long insertJobInfo(int groupId, String handlerName, String cron) {
        throw new UnsupportedOperationException("Task 4 实现");
    }

    /** form-urlencoded POST；withAuth=true 带登录 cookie，遇登录失效清 token 重试一次。成功返回响应 JSON 根。 */
    JsonNode post(String path, Map<String, String> form, boolean withAuth) {
        JsonNode root = doPost(path, form, withAuth);
        if (withAuth && root.path("code").asInt() != SUCCESS_CODE && looksLikeAuthFailure(root)) {
            token = null;
            root = doPost(path, form, true);
        }
        if (root.path("code").asInt() != SUCCESS_CODE) {
            throw new IllegalStateException("xxl-job admin " + path + " 失败: " + root.path("msg").asString(""));
        }
        return root;
    }

    private JsonNode doPost(String path, Map<String, String> form, boolean withAuth) {
        try {
            HttpRequest.Builder req = HttpRequest.newBuilder()
                    .uri(URI.create(adminBase + path))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(encode(form)));
            if (withAuth) {
                req.header("Cookie", SSO_TOKEN_COOKIE + "=" + token());
            }
            HttpResponse<String> resp = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
            return mapper.readTree(resp.body());
        } catch (IOException e) {
            throw new IllegalStateException("xxl-job admin 通信失败: " + path, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("xxl-job admin 通信中断: " + path, e);
        }
    }

    private boolean looksLikeAuthFailure(JsonNode root) {
        String msg = root.path("msg").asString("");
        return msg.contains("登录") || msg.contains("login") || root.path("code").asInt() == 401;
    }

    /** 懒登录：POST /auth/doLogin 取 Response.data 作为 sso token，失败重试 LOGIN_MAX_RETRY 次。 */
    private String token() {
        String t = token;
        if (t != null) {
            return t;
        }
        for (int attempt = 1; attempt <= LOGIN_MAX_RETRY; attempt++) {
            JsonNode root = doPost("/auth/doLogin",
                    form("userName", props.getAdminUsername(), "password", props.getAdminPassword()), false);
            if (root.path("code").asInt() == SUCCESS_CODE) {
                token = root.path("data").asString();
                return token;
            }
            log.warn("xxl-job admin 登录失败({}/{}): {}", attempt, LOGIN_MAX_RETRY, root.path("msg").asString(""));
        }
        throw new IllegalStateException("xxl-job admin 登录失败，重试 " + LOGIN_MAX_RETRY + " 次");
    }

    static Map<String, String> form(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    private static String encode(Map<String, String> form) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : form.entrySet()) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
              .append('=')
              .append(URLEncoder.encode(e.getValue() == null ? "" : e.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }
}
```

> 注:本步骤 `ensureJobGroup/findJobInfoId/insertJobInfo` 是占位桩,Task 3 的测试 `seedLogsInBeforeAdminCall` 会因桩抛异常而**仍失败**。这是预期——Task 4 补全后该测试转绿。本步骤只验证编译通过 + 登录/post 骨架就位。**先确认编译通过即可,不在 Task 3 强求该测试绿。**

- [ ] **Step 5: 确认编译通过**

```bash
$MVN -pl rule-job-xxl -am test-compile
```

Expected: BUILD SUCCESS(测试 `seedLogsInBeforeAdminCall` 暂红,Task 4 转绿)。

- [ ] **Step 6: Commit**

```bash
git add rule-job-xxl/src/main/java/com/sstlfsj/rule/job/xxl/internal/XxlJobAdminClient.java rule-job-xxl/src/main/java/com/sstlfsj/rule/job/xxl/internal/HttpXxlJobAdminClient.java rule-job-xxl/src/test/java/com/sstlfsj/rule/job/xxl/internal/HttpXxlJobAdminClientTest.java
git commit -m "feat(job-xxl): XxlJobAdminClient 接口 + HTTP 登录链路（/auth/doLogin，懒登录+失效重试）"
```

---

## Task 4: seed "有了不管"(jobgroup ensure + jobinfo pageList 精确过滤 + 仅缺失才 insert)

**Files:**
- Modify: `rule-job-xxl/src/main/java/com/sstlfsj/rule/job/xxl/internal/HttpXxlJobAdminClient.java`(替换三个占位桩)
- Modify: `rule-job-xxl/src/test/java/com/sstlfsj/rule/job/xxl/internal/HttpXxlJobAdminClientTest.java`(补"不存在则 insert"用例)

- [ ] **Step 1: 补失败测试(不存在则 insert,且只 insert 一次)**

在 `HttpXxlJobAdminClientTest.java` 末尾(类内)追加:

```java
    @Test
    void seedInsertsWhenAbsentAndReturnsNewId() {
        responses.put("/xxl-job-admin/jobgroup/pageList",
                "{\"code\":200,\"data\":{\"data\":[{\"id\":7,\"appname\":\"rule-engine\"}],\"total\":1}}");
        // jobinfo 列表为空 → 视为不存在
        responses.put("/xxl-job-admin/jobinfo/pageList",
                "{\"code\":200,\"data\":{\"data\":[],\"total\":0}}");
        responses.put("/xxl-job-admin/jobinfo/insert",
                "{\"code\":200,\"msg\":null,\"data\":99}");

        long id = client().ensureJobSeeded("job:1", "0 0 * * * ?");

        assertThat(id).isEqualTo(99L);
        assertThat(hits.getOrDefault("/xxl-job-admin/jobinfo/insert", 0)).isEqualTo(1);
    }

    @Test
    void seedFuzzyMatchIsRefinedByExactEquals() {
        // pageList 模糊匹配返回了 handler 前缀相近但不相等的行 → 客户端精确 equals 应判为不存在 → insert
        responses.put("/xxl-job-admin/jobgroup/pageList",
                "{\"code\":200,\"data\":{\"data\":[{\"id\":7,\"appname\":\"rule-engine\"}],\"total\":1}}");
        responses.put("/xxl-job-admin/jobinfo/pageList",
                "{\"code\":200,\"data\":{\"data\":[{\"id\":42,\"executorHandler\":\"job:10\"}],\"total\":1}}");
        responses.put("/xxl-job-admin/jobinfo/insert",
                "{\"code\":200,\"data\":100}");

        long id = client().ensureJobSeeded("job:1", "0 0 * * * ?");

        assertThat(id).isEqualTo(100L);
        assertThat(hits.getOrDefault("/xxl-job-admin/jobinfo/insert", 0)).isEqualTo(1);
    }

    @Test
    void seedCreatesJobGroupWhenAbsent() {
        responses.put("/xxl-job-admin/jobgroup/pageList",
                "{\"code\":200,\"data\":{\"data\":[],\"total\":0}}");
        responses.put("/xxl-job-admin/jobgroup/insert",
                "{\"code\":200,\"data\":15}");
        responses.put("/xxl-job-admin/jobinfo/pageList",
                "{\"code\":200,\"data\":{\"data\":[],\"total\":0}}");
        responses.put("/xxl-job-admin/jobinfo/insert",
                "{\"code\":200,\"data\":101}");

        long id = client().ensureJobSeeded("job:1", "0 0 * * * ?");

        assertThat(id).isEqualTo(101L);
        assertThat(hits.getOrDefault("/xxl-job-admin/jobgroup/insert", 0)).isEqualTo(1);
    }
```

- [ ] **Step 2: 运行测试,确认失败**

```bash
$MVN -pl rule-job-xxl -am test -Dtest=HttpXxlJobAdminClientTest
```

Expected: FAIL(占位桩抛 `UnsupportedOperationException`)。

- [ ] **Step 3: 替换三个占位桩为实现**

`HttpXxlJobAdminClient.java` 中,把 `ensureJobGroup` / `findJobInfoId` / `insertJobInfo` 三个占位桩整体替换为:

```java
    /** 确保 appname 对应的 jobgroup 存在，返回其 id（不存在则按 appname 建组）。 */
    private int ensureJobGroup() {
        JsonNode page = post("/jobgroup/pageList", form(
                "offset", "0", "pagesize", "10",
                "appname", props.getAppname(), "title", props.getAppname()), true).path("data");
        for (JsonNode g : page.path("data")) {
            if (props.getAppname().equals(g.path("appname").asString(""))) {
                return g.path("id").asInt();
            }
        }
        JsonNode data = post("/jobgroup/insert", form(
                "appname", props.getAppname(), "title", props.getAppname(),
                "addressType", "0", "addressList", ""), true).path("data");
        log.info("xxl-job admin 新建 jobgroup appname={} id={}", props.getAppname(), data.asInt());
        return data.asInt();
    }

    /** pageList 查同组下 handler，executorHandler 是模糊匹配，需客户端再精确 equals；命中返回 id，否则 null。 */
    private Long findJobInfoId(int groupId, String handlerName) {
        JsonNode page = post("/jobinfo/pageList", form(
                "offset", "0", "pagesize", "100",
                "jobGroup", String.valueOf(groupId), "triggerStatus", "-1",
                "jobDesc", "", "executorHandler", handlerName, "author", ""), true).path("data");
        for (JsonNode job : page.path("data")) {
            if (handlerName.equals(job.path("executorHandler").asString(""))) {
                return job.path("id").asLong();
            }
        }
        return null;
    }

    /** 新建 jobinfo（scheduleType=CRON、glueType=BEAN、executorHandler=handlerName、triggerStatus=1 启用）。 */
    private long insertJobInfo(int groupId, String handlerName, String cron) {
        JsonNode data = post("/jobinfo/insert", form(
                "jobGroup", String.valueOf(groupId),
                "jobDesc", handlerName,
                "author", "rule-engine",
                "scheduleType", "CRON",
                "scheduleConf", cron,
                "glueType", "BEAN",
                "executorHandler", handlerName,
                "executorRouteStrategy", "FIRST",
                "misfireStrategy", "DO_NOTHING",
                "executorBlockStrategy", "SERIAL_EXECUTION",
                "executorTimeout", "0",
                "executorFailRetryCount", "0",
                "triggerStatus", "1"), true).path("data");
        log.info("xxl-job admin 新建 job handler={} id={} cron={}", handlerName, data.asLong(), cron);
        return data.asLong();
    }
```

- [ ] **Step 4: 运行测试,确认全绿**

```bash
$MVN -pl rule-job-xxl -am test -Dtest=HttpXxlJobAdminClientTest
```

Expected: PASS(5 个用例:`seedLogsInBeforeAdminCall` / `seedInsertsWhenAbsentAndReturnsNewId` / `seedFuzzyMatchIsRefinedByExactEquals` / `seedCreatesJobGroupWhenAbsent` 全绿)。

- [ ] **Step 5: Commit**

```bash
git add rule-job-xxl/src/main/java/com/sstlfsj/rule/job/xxl/internal/HttpXxlJobAdminClient.java rule-job-xxl/src/test/java/com/sstlfsj/rule/job/xxl/internal/HttpXxlJobAdminClientTest.java
git commit -m "feat(job-xxl): seed 有了不管（jobgroup ensure + jobinfo 精确 equals + 仅缺失才 insert）"
```

---

## Task 5: XxlJobSchedulerAdapter(注册闭包 handler + seed + no-op 注销)

**Files:**
- Create: `rule-job-xxl/src/main/java/com/sstlfsj/rule/job/xxl/internal/XxlJobSchedulerAdapter.java`
- Test: `rule-job-xxl/src/test/java/com/sstlfsj/rule/job/xxl/internal/XxlJobSchedulerAdapterTest.java`

- [ ] **Step 1: 写失败测试**

`XxlJobSchedulerAdapterTest.java`:

```java
package com.sstlfsj.rule.job.xxl.internal;

import com.xxl.job.core.executor.XxlJobExecutor;
import com.xxl.job.core.handler.IJobHandler;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证 adapter 把 task 闭包注册成 IJobHandler（名=jobCode）、seed admin、注销覆盖为 no-op。 */
class XxlJobSchedulerAdapterTest {

    @Test
    void scheduleRegistersHandlerSeedsAdminAndRunsTask() throws Exception {
        XxlJobAdminClient admin = mock(XxlJobAdminClient.class);
        when(admin.ensureJobSeeded(eq("job:1"), eq("0 0 * * * ?"))).thenReturn(42L);
        XxlJobSchedulerAdapter adapter = new XxlJobSchedulerAdapter(admin);

        AtomicInteger ran = new AtomicInteger();
        adapter.schedule("job:1", "0 0 * * * ?", ran::incrementAndGet);

        // seed 被调用
        verify(admin).ensureJobSeeded("job:1", "0 0 * * * ?");
        // handler 注册到全局 registry，名=jobCode
        IJobHandler handler = XxlJobExecutor.loadJobHandler("job:1");
        assertThat(handler).isNotNull();
        // 触发 handler 即运行 task
        handler.execute();
        assertThat(ran.get()).isEqualTo(1);
    }

    @Test
    void unscheduleReplacesHandlerWithNoop() throws Exception {
        XxlJobAdminClient admin = mock(XxlJobAdminClient.class);
        when(admin.ensureJobSeeded(eq("job:2"), eq("0 0 * * * ?"))).thenReturn(7L);
        XxlJobSchedulerAdapter adapter = new XxlJobSchedulerAdapter(admin);

        AtomicInteger ran = new AtomicInteger();
        adapter.schedule("job:2", "0 0 * * * ?", ran::incrementAndGet);
        adapter.unschedule("job:2");

        // 注销后 handler 仍存在但为 no-op：execute 不再跑 task
        IJobHandler handler = XxlJobExecutor.loadJobHandler("job:2");
        assertThat(handler).isNotNull();
        handler.execute();
        assertThat(ran.get()).isZero();
    }
}
```

- [ ] **Step 2: 运行测试,确认失败**

```bash
$MVN -pl rule-job-xxl -am test -Dtest=XxlJobSchedulerAdapterTest
```

Expected: 编译失败(`XxlJobSchedulerAdapter` 不存在)。

- [ ] **Step 3: 写 XxlJobSchedulerAdapter**

`XxlJobSchedulerAdapter.java`:

```java
package com.sstlfsj.rule.job.xxl.internal;

import com.sstlfsj.rule.kernel.api.spi.scheduler.Scheduler;
import com.xxl.job.core.executor.XxlJobExecutor;
import com.xxl.job.core.handler.IJobHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link Scheduler} 的 xxl-job 适配实现：把 task 闭包动态注册成 {@link IJobHandler}（名=jobCode），
 * 并把 job seed 到 admin（"有了不管"，由 {@link XxlJobAdminClient} 保证）。admin 远程触发该 handler →
 * 执行 task → 复用 JobRunner 整套，与内制完全一条路。
 *
 * <p>注销语义：xxl-job-core 的 handler registry 是 ConcurrentHashMap，无公开注销 API 且不接受 null value，
 * 故 {@link #unschedule} 以一个 no-op tombstone handler 覆盖原闭包；admin 侧 cron / 启停由控制台权威管理，
 * 不在此删除 admin job。
 */
public class XxlJobSchedulerAdapter implements Scheduler {

    private static final Logger log = LoggerFactory.getLogger(XxlJobSchedulerAdapter.class);

    /** 注销后覆盖用的空 handler（共享，无状态）。 */
    private static final IJobHandler NOOP = new IJobHandler() {
        @Override
        public void execute() {
            // 已注销，不执行
        }
    };

    private final XxlJobAdminClient adminClient;

    public XxlJobSchedulerAdapter(XxlJobAdminClient adminClient) {
        this.adminClient = adminClient;
    }

    @Override
    public synchronized void schedule(String jobCode, String cronExpression, Runnable task) {
        XxlJobExecutor.registryJobHandler(jobCode, new IJobHandler() {
            @Override
            public void execute() {
                task.run();
            }
        });
        long adminJobId = adminClient.ensureJobSeeded(jobCode, cronExpression);
        log.info("xxl-job 注册 handler={} adminJobId={} cron={}", jobCode, adminJobId, cronExpression);
    }

    @Override
    public synchronized void unschedule(String jobCode) {
        XxlJobExecutor.registryJobHandler(jobCode, NOOP);
        log.info("xxl-job 注销 handler={}（覆盖为 no-op）", jobCode);
    }
}
```

- [ ] **Step 4: 运行测试,确认通过**

```bash
$MVN -pl rule-job-xxl -am test -Dtest=XxlJobSchedulerAdapterTest
```

Expected: PASS。若 `registryJobHandler` 在你的 3.4.0 版本拼写或签名不符,以 IDE 跳转的实际签名为准修正(关键事实 #3)。

- [ ] **Step 5: Commit**

```bash
git add rule-job-xxl/src/main/java/com/sstlfsj/rule/job/xxl/internal/XxlJobSchedulerAdapter.java rule-job-xxl/src/test/java/com/sstlfsj/rule/job/xxl/internal/XxlJobSchedulerAdapterTest.java
git commit -m "feat(job-xxl): XxlJobSchedulerAdapter（闭包注册 IJobHandler + seed + no-op 注销）"
```

---

## Task 6: XxlJobAutoConfiguration + imports(装配 gate)

**Files:**
- Create: `rule-job-xxl/src/main/java/com/sstlfsj/rule/job/xxl/XxlJobAutoConfiguration.java`
- Create: `rule-job-xxl/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Test: `rule-job-xxl/src/test/java/com/sstlfsj/rule/job/xxl/XxlJobAutoConfigurationTest.java`

- [ ] **Step 1: 写失败测试**

`XxlJobAutoConfigurationTest.java`:

```java
package com.sstlfsj.rule.job.xxl;

import com.sstlfsj.rule.job.xxl.internal.XxlJobSchedulerAdapter;
import com.sstlfsj.rule.kernel.api.spi.scheduler.Scheduler;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证装配 gate：仅 engine.rule.job.scheduler=xxl-job 时提供 Scheduler。 */
class XxlJobAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(ObjectMapper.class, () -> JsonMapper.builder().build())
            .withConfiguration(AutoConfigurations.of(XxlJobAutoConfiguration.class));

    @Test
    void notActiveByDefault() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(Scheduler.class));
    }

    @Test
    void activeWhenSchedulerIsXxlJob() {
        runner.withPropertyValues(
                        "engine.rule.job.scheduler=xxl-job",
                        "engine.rule.job.xxl.enabled=false",
                        "engine.rule.job.xxl.admin-addresses=http://127.0.0.1:1/xxl-job-admin")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(Scheduler.class);
                    assertThat(ctx.getBean(Scheduler.class)).isInstanceOf(XxlJobSchedulerAdapter.class);
                });
    }
}
```

> `engine.rule.job.xxl.enabled=false` 关键:防止 `XxlJobSpringExecutor` 在测试上下文里真的 start(绑 Netty 端口 / 连 admin)。

- [ ] **Step 2: 运行测试,确认失败**

```bash
$MVN -pl rule-job-xxl -am test -Dtest=XxlJobAutoConfigurationTest
```

Expected: 编译失败(`XxlJobAutoConfiguration` 不存在)。

- [ ] **Step 3: 写 XxlJobAutoConfiguration**

`XxlJobAutoConfiguration.java`:

```java
package com.sstlfsj.rule.job.xxl;

import com.sstlfsj.rule.job.xxl.internal.HttpXxlJobAdminClient;
import com.sstlfsj.rule.job.xxl.internal.XxlJobAdminClient;
import com.sstlfsj.rule.job.xxl.internal.XxlJobSchedulerAdapter;
import com.sstlfsj.rule.kernel.api.spi.scheduler.Scheduler;
import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.ObjectMapper;

/**
 * xxl-job 调度适配装配：仅当 {@code engine.rule.job.scheduler=xxl-job} 时生效。
 *
 * <p>提供 {@link Scheduler} 接管进程内实现（{@link ConditionalOnMissingBean} 保证外部自定义 Scheduler 优先），
 * 内制侧 JobDefinition / JobExecution / JobRunner 不变。
 */
@AutoConfiguration
@EnableConfigurationProperties(XxlJobProperties.class)
@ConditionalOnProperty(prefix = "engine.rule.job", name = "scheduler", havingValue = "xxl-job")
public class XxlJobAutoConfiguration {

    /**
     * xxl-job 执行器：注册到 admin + 起 Netty EmbedServer 接收调度回调。
     * 由 {@code SmartInitializingSingleton} 自动 start；{@code enabled=false} 时 start 跳过。
     *
     * @param props xxl 配置
     * @return XxlJobSpringExecutor 实例
     */
    @Bean(destroyMethod = "destroy")
    public XxlJobSpringExecutor xxlJobExecutor(XxlJobProperties props) {
        XxlJobSpringExecutor executor = new XxlJobSpringExecutor();
        executor.setAdminAddresses(props.getAdminAddresses());
        executor.setAccessToken(props.getAccessToken());
        executor.setAppname(props.getAppname());
        executor.setAddress(props.getAddress());
        executor.setIp(props.getIp());
        executor.setPort(props.getPort());
        executor.setLogPath(props.getLogPath());
        executor.setLogRetentionDays(props.getLogRetentionDays());
        executor.setEnabled(props.isEnabled());
        return executor;
    }

    /**
     * admin 接入客户端（JDK HttpClient + 注入的全局 ObjectMapper）。
     *
     * @param props        xxl 配置（admin 地址 / 账号）
     * @param objectMapper 全局 JSON 序列化 Bean
     * @return XxlJobAdminClient 实例
     */
    @Bean
    public XxlJobAdminClient xxlJobAdminClient(XxlJobProperties props, ObjectMapper objectMapper) {
        return new HttpXxlJobAdminClient(props, objectMapper);
    }

    /**
     * Scheduler 的 xxl 实现；外部显式注册的 Scheduler Bean 始终优先。
     *
     * @param adminClient admin 接入客户端
     * @return XxlJobSchedulerAdapter 实例
     */
    @Bean
    @ConditionalOnMissingBean(Scheduler.class)
    public Scheduler scheduler(XxlJobAdminClient adminClient) {
        return new XxlJobSchedulerAdapter(adminClient);
    }
}
```

- [ ] **Step 4: 写 imports 文件**

`rule-job-xxl/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`:

```
com.sstlfsj.rule.job.xxl.XxlJobAutoConfiguration
```

- [ ] **Step 5: 运行测试,确认通过**

```bash
$MVN -pl rule-job-xxl -am test -Dtest=XxlJobAutoConfigurationTest
```

Expected: PASS(默认无 Scheduler;`xxl-job` 时 Scheduler 为 `XxlJobSchedulerAdapter`)。

- [ ] **Step 6: 跑模块全量测试**

```bash
$MVN -pl rule-job-xxl -am test
```

Expected: 全绿(`XxlJobPropertiesTest` / `HttpXxlJobAdminClientTest` / `XxlJobSchedulerAdapterTest` / `XxlJobAutoConfigurationTest`)。

- [ ] **Step 7: Commit**

```bash
git add rule-job-xxl/src/main/java/com/sstlfsj/rule/job/xxl/XxlJobAutoConfiguration.java rule-job-xxl/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports rule-job-xxl/src/test/java/com/sstlfsj/rule/job/xxl/XxlJobAutoConfigurationTest.java
git commit -m "feat(job-xxl): XxlJobAutoConfiguration（gate engine.rule.job.scheduler=xxl-job）+ imports"
```

---

## Task 7: rule-app `xxl` profile + GraalVM native image go/no-go 验证(门控)

> **这是接 xxl 的 go/no-go 前置门控,不是收尾项。** 目的:验证 `xxl-job-core` + Netty(执行器回调端口)+ groovy/xxl-tool 在 native image 下 reachability 可用。失败且补 hint 后仍不可用 → **停下报告,本接入判定 no-go**,不强行继续。

**Files:**
- Modify: `rule-app/pom.xml`(加 `xxl` profile,把 rule-job-xxl 纳入 native 镜像)
- Create(按需,仅当 native 报 reachability 缺失时): `rule-job-xxl/src/main/resources/META-INF/native-image/com.sstlfsj.rule/rule-job-xxl/reachability-metadata.json`

- [ ] **Step 1: rule-app 加 `xxl` profile**

`rule-app/pom.xml` `</dependencies>`(L82)之后、`<build>`(L84)之前插入:

```xml
    <profiles>
        <!-- 按需把 xxl 适配器纳入构建/镜像；默认不引入，保持 in-process 部署的镜像精简 -->
        <profile>
            <id>xxl</id>
            <dependencies>
                <dependency>
                    <groupId>com.sstlfsj.rule</groupId>
                    <artifactId>rule-job-xxl</artifactId>
                </dependency>
            </dependencies>
        </profile>
    </profiles>
```

- [ ] **Step 2: 确认 JVM 下带 xxl 装配能正常起停(轻量预检,先于昂贵的 native)**

用一个临时 properties 让 executor 不真连 admin、不绑端口(enabled=false),验证 `-Pxxl` 下 rule-app 上下文能装配 `XxlJobSchedulerAdapter`。运行 rule-app 现有的 Spring 上下文测试(或 `ApplicationModules` 校验)带 profile:

```bash
$MVN -Pxxl -pl rule-app -am test
```

Expected: BUILD SUCCESS;ArchUnit(`rule-app/src/test/.../arch/KernelArchTest.java`)对 `com.sstlfsj.rule.job..` 的既有约束不被 `com.sstlfsj.rule.job.xxl..` 违反(xxl 仅依赖 rule-kernel,天然合规)。若 ArchUnit 报新违规,据其约束调整(预期不会)。

- [ ] **Step 3: native image 编译(go/no-go 核心)**

确保当前 JDK 是 GraalVM(Java 25)。运行:

```bash
$MVN -Pnative,xxl -pl rule-app -am -DskipTests native:compile
```

> `-DskipTests` 仅用于 native 镜像构建本身(不是绕过失败单测;单测在 Task 2–6 已逐一把关)。`native` profile 由 spring-boot-starter-parent 提供。若本机无 GraalVM 工具链,把本步骤放到项目的 native CI job 执行并取其日志。

Expected(GO):BUILD SUCCESS,生成原生可执行文件,**无** `io.netty.*` / `org.apache.groovy.*` / `com.xxl.tool.*` 的 reachability / reflection 报错。

- [ ] **Step 4: 运行原生镜像,验证执行器启动 + 回调端口**

```bash
./rule-app/target/rule-app \
  --engine.rule.job.scheduler=xxl-job \
  --engine.rule.job.xxl.enabled=true \
  --engine.rule.job.xxl.admin-addresses=http://127.0.0.1:8080/xxl-job-admin \
  --engine.rule.job.xxl.port=9999
```

Expected(GO):进程启动,日志含执行器 EmbedServer 启动(Netty 绑定端口)成功;admin 不可达只产生网络层 WARN(`ExecutorRegistryThread` 注册失败重试),**不是** native reachability/`UnsupportedFeatureError`。出现网络错误即视为 GO(reachability 通过)。

- [ ] **Step 5: 若 NO-GO,补 reachability hint 后重试**

若 Step 3/4 出现 `UnsupportedFeatureError` / `ClassNotFoundException` / 反射/资源缺失:在
`rule-job-xxl/src/main/resources/META-INF/native-image/com.sstlfsj.rule/rule-job-xxl/reachability-metadata.json`
按报错补 reflection / resource / serialization 条目(GraalVM `reachability-metadata` 新格式),重跑 Step 3–4。仍不可解 → **停止,记录失败的类/特性,向用户报告本接入 native go/no-go = NO-GO**,不继续 Task 8。

- [ ] **Step 6: Commit(GO 才提交)**

```bash
git add rule-app/pom.xml
# 若补了 hint：git add rule-job-xxl/src/main/resources/META-INF/native-image/
git commit -m "build(job-xxl): rule-app xxl profile + GraalVM native go/no-go 验证通过"
```

---

## Task 8: 回归 — 内制路径不受影响 + 全量收口

**Files:**
- Test(运行,不新增): `rule-job-svc` 既有测试、`rule-app` 既有测试

- [ ] **Step 1: 内制默认路径回归**

不带任何 xxl 配置(默认 `in-process`),跑 job-svc 全量,确认进程内调度装配与触发不受新模块影响:

```bash
$MVN -pl rule-job-svc -am test
```

Expected: 全绿。`JobAutoConfiguration.scheduler()`(`in-process` / 缺省)仍正常装配 `ThreadPoolSchedulerAdapter`。

- [ ] **Step 2: rule-app 默认(不带 xxl profile)回归**

```bash
$MVN -pl rule-app -am test
```

Expected: 全绿;默认构建**不含** rule-job-xxl,镜像/上下文无 xxl 依赖,Modulith / ArchUnit 校验通过。

- [ ] **Step 3: xxl 模块全量收口**

```bash
$MVN -pl rule-job-xxl -am test
```

Expected: 全绿。

- [ ] **Step 4: Commit(若 Step 1–3 有任何随手修复;无修复则跳过)**

```bash
git add -A
git commit -m "test(job-xxl): 回归内制路径 + xxl 模块全量收口"
```

---

## 收尾(计划外,执行完成后按需)

- **D50 写入 `docs/00-decisions.md`**:本计划落地后,把 spec 末尾的 D50 决策块追加进决策日志。改 `docs/**` 前先跑 `doc-consistency-review` skill(项目纪律)。
- **运维配置文档**:admin 地址 / accessToken / 账号密码的 secret 注入方式(环境变量占位符),记入部署文档,不入库。
- **REST 触发入口**(spec 的独立瘦入口)单独成计划。

---

## Self-Review

**1. Spec coverage(对照 `2026-06-07-rule-job-scheduling-design.md`):**
- `XxlJobSchedulerAdapter implements Scheduler` + 闭包注册 IJobHandler(名=jobCode)→ Task 5 ✅
- 启动期 seed 到 admin、"有了不管"(已存在不 update)→ Task 4 ✅
- 复用 JobRunner 不重写组装、装配经 `engine.rule.job.scheduler=xxl-job` 钩子、内制零改动 → Task 6 gate + 全程不碰 rule-job-svc ✅
- `xxl-job-core:3.4.0` 稳定版 → Task 1 ✅
- 鉴权与配置敏感(secret 注入,不入库)→ Task 2 properties Javadoc + 收尾文档 ✅
- HTTP 客户端用 JDK HttpClient + 注入 ObjectMapper(不引 hutool)→ Task 3 ✅
- 双开关(app `status` 守卫 + admin stop):app 侧守卫是内制 `runById` 既有逻辑(零改动),admin 侧 stop 由控制台 → 设计层覆盖,无需新代码 ✅
- GraalVM native go/no-go 前置 → Task 7 ✅
- 模块命名 `rule-job-xxl`、平铺为根 pom 模块、按需引入 → Task 1 + Task 7 profile ✅
- 测试策略(adapter 单测:注册 handler 名=jobCode、seed 有了不管、注销;装配回归)→ Task 5 / 4 / 6 / 8 ✅
- **gap**:spec 提到 `unschedule` "admin 侧策略见下" 但正文未细化 admin 侧删除;本计划取保守"不删 admin job、仅覆盖 no-op"(关键事实 #3 + Task 5 Javadoc 已明确),与"admin 为权威源"一致。无遗漏。

**2. Placeholder scan:** Task 3 Step 4 的三个 `UnsupportedOperationException` 占位桩是**有意的 TDD 中间态**,Task 4 Step 3 显式替换为完整实现,非计划占位。其余无 TODO/TBD/"add error handling"类空话。

**3. Type consistency:** `XxlJobAdminClient.ensureJobSeeded(String,String):long` 在 Task 3 定义,Task 5 adapter、Task 6 装配、Task 5 测试调用一致;`XxlJobProperties` getter(`getAdminAddresses/getAppname/getAccessToken/getAdminUsername/getAdminPassword/getPort/getLogPath/getLogRetentionDays/getAddress/getIp/isEnabled`)在 Task 2 定义,Task 3/6 引用一致;`HttpXxlJobAdminClient(XxlJobProperties, ObjectMapper)` 构造签名 Task 3 定义、Task 6 装配一致;`registryJobHandler` / `loadJobHandler` / `IJobHandler.execute()` 与关键事实 #2/#3 一致。
