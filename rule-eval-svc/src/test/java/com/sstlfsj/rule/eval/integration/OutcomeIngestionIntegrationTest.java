package com.sstlfsj.rule.eval.integration;

import com.sstlfsj.rule.eval.api.service.IngestResult;
import com.sstlfsj.rule.eval.api.service.OutcomeIngestionService;
import com.sstlfsj.rule.eval.api.service.SqlOutcomeSourceConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OUTCOME_INGESTION（B32 回灌）真实 MySQL 集成测试：
 * 验证 SqlOutcomeSource 的 RowMapper 列映射 + watermark 增量推进 + decision_outcome 真实 upsert。
 * 容器既作 Spring 主数据源（Flyway 建 decision_outcome），又经 engine.rule.fetch.datasources 注册为
 * MetricDataSourceRegistry 的命名只读源（拉 biz_label 标签）。
 * <p>TestApp 置于 integration 包（非被扫描的 internal 包），与 EvalIntegrationTest 同口径，避免两个内嵌 app 互相扫到。
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class OutcomeIngestionIntegrationTest {

    /** 内嵌测试专用 SpringBootApplication：与 EvalIntegrationTest 同口径扫 eval + observability，MapperScan 含 config。 */
    @SpringBootApplication(
            scanBasePackages = {
                    "com.sstlfsj.rule.eval.internal",
                    "com.sstlfsj.rule.observability.internal"
            }
    )
    @MapperScan({
            "com.sstlfsj.rule.eval.internal.repository",
            "com.sstlfsj.rule.config.internal.repository",
            "com.sstlfsj.rule.observability.internal.repository"
    })
    static class TestApp {
        @Bean
        ObjectMapper objectMapper() {
            return JsonMapper.builder().build();
        }
    }

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("rule_engine_test")
            .withUsername("test")
            .withPassword("test");

    /**
     * 同一容器既配为 Spring 主源（Flyway 跑真实迁移建 decision_outcome），
     * 又注册为 engine.rule.fetch 的命名只读源 "biz"（SqlOutcomeSource 经 registry 拉 biz_label）。
     */
    @DynamicPropertySource
    static void overrideDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("engine.rule.fetch.datasources[0].name", () -> "biz");
        registry.add("engine.rule.fetch.datasources[0].url", mysql::getJdbcUrl);
        registry.add("engine.rule.fetch.datasources[0].username", mysql::getUsername);
        registry.add("engine.rule.fetch.datasources[0].password", mysql::getPassword);
    }

    private static final long TENANT = 1L;
    private static final String SQL = """
            SELECT event_id, outcome_label, outcome_value, labeled_at
            FROM biz_label
            WHERE tenant_id = :tenantId AND (:watermark IS NULL OR labeled_at > :watermark)
            ORDER BY labeled_at ASC
            """;
    private static final SqlOutcomeSourceConfig CONFIG = new SqlOutcomeSourceConfig("biz", SQL);

    @Autowired
    private OutcomeIngestionService ingestionService;

    @Autowired
    private JdbcTemplate jdbc;

    /** 重建 biz_label 标签源表并清空 decision_outcome，保证测试隔离。 */
    @BeforeEach
    void setUp() {
        jdbc.execute("DROP TABLE IF EXISTS biz_label");
        jdbc.execute("""
                CREATE TABLE biz_label(
                  event_id VARCHAR(128),
                  outcome_label VARCHAR(64),
                  outcome_value DECIMAL(18,4),
                  labeled_at TIMESTAMP(3),
                  tenant_id BIGINT)
                """);
        jdbc.execute("DELETE FROM decision_outcome");
    }

    /** 标签行（labeled_at 用 TIMESTAMP(3) 字面量，毫秒精度与 RowMapper 一致）。 */
    private void insertLabel(String eventId, String label, String value, String labeledAt, long tenantId) {
        jdbc.update("INSERT INTO biz_label(event_id, outcome_label, outcome_value, labeled_at, tenant_id) "
                + "VALUES (?, ?, ?, ?, ?)", eventId, label, value, labeledAt, tenantId);
    }

    /**
     * TIMESTAMP 墙钟字符串 → 期望 Instant：JDBC getTimestamp().toInstant() 按 JVM 默认时区解释墙钟，
     * 与 SqlOutcomeSource RowMapper / DecisionOutcomeMapper 写回口径一致，故用 systemDefault 换算。
     */
    private static Instant watermarkOf(String wallClock) {
        return LocalDateTime.parse(wallClock.replace(' ', 'T'))
                .atZone(ZoneId.systemDefault()).toInstant();
    }

    /**
     * 首次全量（watermark=null）：accepted==行数，newWatermark==max labeled_at，
     * 且 decision_outcome 真实落库了 RowMapper 映射的列值（event_id/label/value/labeled_at）。
     */
    @Test
    void firstFullIngest_mapsColumnsAndUpsertsRows() {
        insertLabel("e1", "FRAUD", "100.5000", "2026-01-01 10:00:00.000", TENANT);
        insertLabel("e2", "NOT_FRAUD", null, "2026-01-02 11:00:00.000", TENANT);
        insertLabel("e3", "FRAUD", "250.0000", "2026-01-03 12:00:00.000", TENANT);

        IngestResult result = ingestionService.ingest(TENANT, CONFIG, null);

        assertThat(result.accepted()).isEqualTo(3);
        // newWatermark == max labeled_at = e3 的 2026-01-03 12:00:00（墙钟按 systemDefault 换算成 Instant）
        assertThat(result.newWatermark()).isEqualTo(watermarkOf("2026-01-03 12:00:00.000"));

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT event_id, outcome_label, outcome_value, labeled_at, source "
                        + "FROM decision_outcome WHERE tenant_id = ? ORDER BY labeled_at ASC", TENANT);
        assertThat(rows).hasSize(3);

        Map<String, Object> r1 = rows.get(0);
        assertThat(r1.get("event_id")).isEqualTo("e1");
        assertThat(r1.get("outcome_label")).isEqualTo("FRAUD");
        assertThat(((BigDecimal) r1.get("outcome_value"))).isEqualByComparingTo("100.5000");
        assertThat(r1.get("source")).isEqualTo("ingest:biz");

        // 空数值列正确落 null（RowMapper getBigDecimal → null）
        assertThat(rows.get(1).get("event_id")).isEqualTo("e2");
        assertThat(rows.get(1).get("outcome_value")).isNull();

        assertThat(rows.get(2).get("event_id")).isEqualTo("e3");
        assertThat(((BigDecimal) rows.get(2).get("outcome_value"))).isEqualByComparingTo("250.0000");
    }

    /**
     * 增量：先全量，再插一行 labeled_at 在前次 max 之后，以 newWatermark 二次 ingest，
     * 仅拉到新增 1 行（watermark 过滤），decision_outcome 含全部 3 行（幂等 upsert 无重复）。
     */
    @Test
    void incrementalIngest_pullsOnlyNewRowsAfterWatermark() {
        insertLabel("e1", "FRAUD", "100.0000", "2026-01-01 10:00:00.000", TENANT);
        insertLabel("e2", "NOT_FRAUD", "0.0000", "2026-01-02 11:00:00.000", TENANT);

        IngestResult first = ingestionService.ingest(TENANT, CONFIG, null);
        assertThat(first.accepted()).isEqualTo(2);
        assertThat(first.newWatermark()).isEqualTo(watermarkOf("2026-01-02 11:00:00.000"));

        // 新增一行，labeled_at 严格大于前次 max
        insertLabel("e3", "FRAUD", "300.0000", "2026-01-03 12:00:00.000", TENANT);

        IngestResult second = ingestionService.ingest(TENANT, CONFIG, first.newWatermark());
        // 仅拉到 e3（watermark 过滤掉 e1/e2）
        assertThat(second.accepted()).isEqualTo(1);
        assertThat(second.newWatermark()).isEqualTo(watermarkOf("2026-01-03 12:00:00.000"));

        // decision_outcome 共 3 行（e1/e2 不重复），uk_tenant_event 保证幂等
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM decision_outcome WHERE tenant_id = ?", Long.class, TENANT);
        assertThat(total).isEqualTo(3L);
    }

    /**
     * 重叠窗口（覆盖语义）：以更早 watermark 重拉已落库的事件，标签被修正而非产生重复行。
     */
    @Test
    void overlappingRepull_upsertsOverwriteInsteadOfDuplicating() {
        insertLabel("e1", "FRAUD", "100.0000", "2026-01-01 10:00:00.000", TENANT);
        ingestionService.ingest(TENANT, CONFIG, null);

        // 业务修正 e1 的标签与数值
        jdbc.update("UPDATE biz_label SET outcome_label = ?, outcome_value = ? WHERE event_id = ?",
                "NOT_FRAUD", "0.0000", "e1");

        // 以更早 watermark 重拉，覆盖
        IngestResult repull = ingestionService.ingest(TENANT, CONFIG, Instant.parse("2025-12-31T00:00:00Z"));
        assertThat(repull.accepted()).isEqualTo(1);

        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM decision_outcome WHERE tenant_id = ? AND event_id = ?",
                Long.class, TENANT, "e1");
        assertThat(total).isEqualTo(1L);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT outcome_label, outcome_value FROM decision_outcome WHERE tenant_id = ? AND event_id = ?",
                TENANT, "e1");
        assertThat(row.get("outcome_label")).isEqualTo("NOT_FRAUD");
        assertThat(((BigDecimal) row.get("outcome_value"))).isEqualByComparingTo("0.0000");
    }
}
