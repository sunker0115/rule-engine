package com.sstlfsj.rule.example;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.sstlfsj.rule.RuleEngineApplication;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.MySQLContainer;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(
        classes = RuleEngineApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class ScenarioSupport {

    public static final String TENANT_ID = "9001";
    public static final String TENANT_CODE = "example";
    static final String ACTOR_ID = "scenario-runner";

    // JVM 级 singleton：所有 Scenario 类共享同一容器/WireMock，保证 Spring context 缓存复用、
    // 不被某个类的 afterAll 提前停掉（@Container/@BeforeAll 按类管理生命周期会导致后续类拿到死容器）。
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("rule-engine-example");

    static final WireMockServer WIREMOCK = new WireMockServer(
            WireMockConfiguration.options().dynamicPort());

    static {
        MYSQL.start();
        WIREMOCK.start();
    }

    @LocalServerPort
    public int localPort;

    @Autowired
    protected JdbcTemplate jdbc;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        // SQL_AGGREGATE 命名只读数据源（business-db）→ 同一 MySQL 容器
        registry.add("engine.rule.fetch.datasources[0].name", () -> "business-db");
        registry.add("engine.rule.fetch.datasources[0].url", MYSQL::getJdbcUrl);
        registry.add("engine.rule.fetch.datasources[0].username", MYSQL::getUsername);
        registry.add("engine.rule.fetch.datasources[0].password", MYSQL::getPassword);
        // EXTERNAL_HTTP 命名端点（credit-api）→ WireMock
        registry.add("engine.rule.fetch.endpoints[0].name", () -> "credit-api");
        registry.add("engine.rule.fetch.endpoints[0].base-url",
                () -> "http://localhost:" + WIREMOCK.port());
        // SEND_ALERT webhook → WireMock
        registry.add("engine.rule.action.send-alert.url",
                () -> "http://localhost:" + WIREMOCK.port() + "/webhook/alert");
        registry.add("engine.rule.action.send-alert.timeout-ms", () -> "2000");
    }

    @BeforeEach
    void resetState() {
        // test 前清空业务表：上个 test 已在方法内 await 自己的异步落库完成，此处清干净，
        // 消除"异步 best-effort 落库晚于 @AfterEach truncate 造成残留污染下个 test"的竞态
        truncateBusinessTables();
        jdbc.update("INSERT IGNORE INTO tenant (id, code, name) VALUES (?, ?, ?)",
                Long.parseLong(TENANT_ID), TENANT_CODE, "示例租户");
        // WireMock static DSL（stubFor/verify）默认指向 8080，需绑定到本实例的动态端口
        WireMock.configureFor("localhost", WIREMOCK.port());
        WIREMOCK.resetAll();
    }

    private void truncateBusinessTables() {
        // 动态获取当前库所有业务表（排除 flyway 元数据与租户基线），自适应 schema 演进
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = DATABASE() "
                        + "AND table_name NOT IN ('flyway_schema_history', 'tenant')",
                String.class);
        jdbc.execute("SET FOREIGN_KEY_CHECKS=0");
        for (String table : tables) {
            jdbc.execute("TRUNCATE TABLE `" + table + "`");
        }
        jdbc.execute("SET FOREIGN_KEY_CHECKS=1");
    }

    // ---- 便捷方法 ----

    protected String adminUrl(String path) {
        return "http://localhost:" + localPort + "/admin/v1" + path;
    }

    protected String apiUrl(String path) {
        return "http://localhost:" + localPort + "/api/v1" + path;
    }

    /** POST 到 admin API（带 Actor 头）；body 为 null 时发无请求体的 POST（如 publish/disable） */
    private Map<String, Object> adminPost(String path, Object body) {
        var req = RestClient.create()
                .post()
                .uri(adminUrl(path))
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .header("X-Actor-Id", ACTOR_ID);
        var resp = (body != null ? req.body(body) : req)
                .retrieve()
                .toEntity(Map.class);
        return resp.getBody();
    }

    /** POST 到 public API */
    private Map<String, Object> apiPost(String path, Object body) {
        var resp = RestClient.create()
                .post()
                .uri(apiUrl(path))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toEntity(Map.class);
        return resp.getBody();
    }

    protected Long createScene(String sceneCode, String name, String dominantMode,
                                String subjectType, List<String> eventTypes) {
        return createScene(sceneCode, name, dominantMode, subjectType, eventTypes, List.of());
    }

    @SuppressWarnings("unchecked")
    protected Long createScene(String sceneCode, String name, String dominantMode,
                                String subjectType, List<String> eventTypes,
                                List<Map<String, Object>> payloadSchema) {
        var body = Map.of(
                "tenantId", TENANT_ID, "sceneCode", sceneCode, "name", name,
                "dominantMode", dominantMode, "subjectType", subjectType,
                "eventTypes", eventTypes != null ? eventTypes : List.of(),
                "payloadSchema", payloadSchema != null ? payloadSchema : List.of(),
                "defaultParams", Map.of()
        );
        var resp = adminPost("/scenes", body);
        // CreateSceneResponse 的 data 是 {id: ...} 对象，非裸 id
        Map<String, Object> data = (Map<String, Object>) resp.get("data");
        return ((Number) data.get("id")).longValue();
    }

    protected Long createDecision(String code, String name, int priority,
                                   List<Map<String, Object>> actions) {
        var body = Map.of(
                "code", code, "name", name, "priority", priority,
                "description", "scenario-test",
                "actions", actions != null ? actions : List.of()
        );
        var resp = adminPost("/decisions?tenantId=" + TENANT_ID, body);
        return ((Number) resp.get("data")).longValue();
    }

    protected Long createMetric(String metricCode, String name, String sourceType,
                                 String dataType, Map<String, Object> params,
                                 int cacheTtlSeconds, boolean allowProvided) {
        var body = Map.of(
                "name", name, "sourceType", sourceType, "dataType", dataType,
                "params", params != null ? params : Map.of(),
                "cacheTtlSeconds", cacheTtlSeconds, "allowProvided", allowProvided
        );
        var resp = adminPost("/metrics?tenantId=" + TENANT_ID + "&metricCode=" + metricCode, body);
        return ((Number) resp.get("data")).longValue();
    }

    @SuppressWarnings("unchecked")
    protected Map<String, Object> createRule(String sceneCode, String code, String name,
                     Object conditionAst, List<Map<String, String>> decisionBindings,
                     List<String> triggerEventTypes, String kind) {
        var body = new java.util.LinkedHashMap<>();
        body.put("tenantId", TENANT_ID);
        body.put("sceneCode", sceneCode);
        body.put("code", code);
        body.put("name", name);
        body.put("conditionAst", conditionAst);
        body.put("decisionBindings", decisionBindings != null ? decisionBindings : List.of());
        body.put("preGates", List.of());
        body.put("triggerEventTypes", triggerEventTypes != null ? triggerEventTypes : List.of());
        body.put("kind", kind);
        var resp = adminPost("/rules", body);
        return (Map<String, Object>) resp.get("data");
    }

    @SuppressWarnings("unchecked")
    protected Map<String, Object> publishRule(Long ruleId) {
        var resp = adminPost("/rules/" + ruleId + "/publish?tenantId=" + TENANT_ID, null);
        return (Map<String, Object>) resp.get("data");
    }

    @SuppressWarnings("unchecked")
    protected Map<String, Object> evaluate(String sceneCode, String eventType,
                                            String subjectId, Map<String, Object> payload) {
        var body = Map.of(
                "tenantCode", TENANT_CODE, "sceneCode", sceneCode,
                "eventType", eventType, "subjectId", subjectId,
                "eventId", java.util.UUID.randomUUID().toString(),
                "occurredAt", java.time.Instant.now().toString(),
                "payload", payload != null ? payload : Map.of()
        );
        var resp = apiPost("/rule/evaluate", body);
        return (Map<String, Object>) resp.get("data");
    }

    @SuppressWarnings("unchecked")
    protected Map<String, Object> pushEvent(String sceneCode, String eventType,
                                             String subjectId, Map<String, Object> payload) {
        var body = Map.of(
                "tenantCode", TENANT_CODE, "sceneCode", sceneCode,
                "eventType", eventType, "subjectId", subjectId,
                "eventId", java.util.UUID.randomUUID().toString(),
                "occurredAt", java.time.Instant.now().toString(),
                "payload", payload != null ? payload : Map.of()
        );
        var resp = apiPost("/rule/event", body);
        return (Map<String, Object>) resp.get("data");
    }

    @SuppressWarnings("unchecked")
    protected Map<String, Object> dryRun(Long ruleVersionId, Long ruleId,
                                          String sceneCode, String eventType,
                                          String subjectId, Map<String, Object> payload) {
        var body = Map.of(
                "tenantCode", TENANT_CODE, "sceneCode", sceneCode,
                "eventType", eventType, "subjectId", subjectId,
                "eventId", java.util.UUID.randomUUID().toString(),
                "occurredAt", java.time.Instant.now().toString(),
                "payload", payload != null ? payload : Map.of()
        );
        String url = "/rule/dry-run";
        if (ruleVersionId != null) url += "?ruleVersionId=" + ruleVersionId;
        else if (ruleId != null) url += "?ruleId=" + ruleId;
        var resp = apiPost(url, body);
        return (Map<String, Object>) resp.get("data");
    }

    protected int countRows(String table) {
        Integer c = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return c != null ? c : 0;
    }

    /** 等待某表行数达到期望值（评估审计/trace/action 落库是异步 best-effort，须轮询而非立即断言）。 */
    protected void awaitRowCount(String table, int expected) {
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(countRows(table)).isEqualTo(expected));
    }

    /** 等待某表行数至少达到下限。 */
    protected void awaitRowCountAtLeast(String table, int min) {
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(countRows(table)).isGreaterThanOrEqualTo(min));
    }

    /**
     * 按业务键过滤计数：评估审计是异步 best-effort 落库，落库线程可能跨 test 边界继续写，
     * 全表 count 会被其他 test 的异步残留污染。断言须限定本 test 的业务键（scene_code/decision_code）。
     */
    protected int countRowsWhere(String table, String where) {
        Integer c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + where, Integer.class);
        return c != null ? c : 0;
    }

    /** 等待按业务键过滤的行数达到期望值。 */
    protected void awaitRowCountWhere(String table, String where, int expected) {
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(countRowsWhere(table, where)).isEqualTo(expected));
    }

    /** 等待按业务键过滤的行数至少达到下限。 */
    protected void awaitRowCountWhereAtLeast(String table, String where, int min) {
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(countRowsWhere(table, where)).isGreaterThanOrEqualTo(min));
    }

    protected List<Map<String, Object>> query(String sql, Object... args) {
        return jdbc.queryForList(sql, args);
    }
}
