package com.sstlfsj.rule.example;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.sstlfsj.rule.RuleEngineApplication;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

@SpringBootTest(
        classes = RuleEngineApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
public abstract class ScenarioSupport {

    static final String TENANT_ID = "9001";
    static final String TENANT_CODE = "example";
    static final String ACTOR_ID = "scenario-runner";

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("rule-engine-example");

    static final WireMockServer WIREMOCK = new WireMockServer(
            WireMockConfiguration.options().dynamicPort());

    @LocalServerPort
    int localPort;

    @Autowired
    private RestClient.Builder restClientBuilder;

    @Autowired
    protected JdbcTemplate jdbc;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("engine.rule.metric.source.business-db.url", MYSQL::getJdbcUrl);
        registry.add("engine.rule.metric.source.business-db.username", MYSQL::getUsername);
        registry.add("engine.rule.metric.source.business-db.password", MYSQL::getPassword);
        registry.add("engine.rule.metric.source.mock-api.url",
                () -> "http://localhost:" + WIREMOCK.port());
        registry.add("engine.rule.action.send-alert.url",
                () -> "http://localhost:" + WIREMOCK.port() + "/webhook/alert");
        registry.add("engine.rule.action.send-alert.timeout-ms", () -> "2000");
    }

    @BeforeAll
    static void startWireMock() {
        WIREMOCK.start();
    }

    @AfterAll
    static void stopWireMock() {
        WIREMOCK.stop();
    }

    @BeforeEach
    void seedTenant() {
        jdbc.update("INSERT IGNORE INTO tenant (id, code, name) VALUES (?, ?, ?)",
                Long.parseLong(TENANT_ID), TENANT_CODE, "示例租户");
        WIREMOCK.resetAll();
    }

    @AfterEach
    void truncateAll() {
        jdbc.execute("SET FOREIGN_KEY_CHECKS=0");
        for (String table : ALL_TABLES) {
            jdbc.execute("TRUNCATE TABLE " + table);
        }
        jdbc.execute("SET FOREIGN_KEY_CHECKS=1");
    }

    private static final String[] ALL_TABLES = {
            "action_execution", "dry_run_action_execution",
            "node_trace", "dry_run_node_trace",
            "evaluation_session", "dry_run_session",
            "job_execution",
            "rule_version", "rule_definition",
            "scene", "scene_version",
            "metric_definition",
            "decision_definition",
            "audit_log",
            "job_definition",
            "orders"
    };

    // ---- 便捷方法 ----

    protected String adminUrl(String path) {
        return "http://localhost:" + localPort + "/admin/v1" + path;
    }

    protected String apiUrl(String path) {
        return "http://localhost:" + localPort + "/api/v1" + path;
    }

    /** POST 到 admin API（带 Actor 头） */
    private Map<String, Object> adminPost(String path, Object body) {
        var resp = restClientBuilder.build()
                .post()
                .uri(adminUrl(path))
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .header("X-Actor-Id", ACTOR_ID)
                .body(body)
                .retrieve()
                .toEntity(Map.class);
        return resp.getBody();
    }

    /** POST 到 public API */
    private Map<String, Object> apiPost(String path, Object body) {
        var resp = restClientBuilder.build()
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
        var body = Map.of(
                "tenantId", TENANT_ID, "sceneCode", sceneCode, "name", name,
                "dominantMode", dominantMode, "subjectType", subjectType,
                "eventTypes", eventTypes != null ? eventTypes : List.of(),
                "payloadSchema", List.of(), "defaultParams", Map.of()
        );
        var resp = adminPost("/scenes", body);
        return ((Number) resp.get("data")).longValue();
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

    protected List<Map<String, Object>> query(String sql, Object... args) {
        return jdbc.queryForList(sql, args);
    }
}
