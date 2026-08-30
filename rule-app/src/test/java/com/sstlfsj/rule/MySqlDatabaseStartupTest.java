package com.sstlfsj.rule;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.contributor.Status;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** 使用默认应用配置和独立 MySQL 验证正式基线与健康状态，不使用特殊驱动或测试 profile。 */
@SpringBootTest
@Testcontainers
class MySqlDatabaseStartupTest {

    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("rule_engine_startup");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("engine.rule.fetch.datasources[0].url", mysql::getJdbcUrl);
        registry.add("engine.rule.fetch.datasources[0].username", mysql::getUsername);
        registry.add("engine.rule.fetch.datasources[0].password", mysql::getPassword);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private HealthEndpoint healthEndpoint;

    @Test
    void defaultHealthIsUpWithoutRedis() {
        assertEquals(Status.UP, healthEndpoint.health().getStatus());
        assertNotNull(healthEndpoint.healthForPath("db"));
        assertEquals(Status.UP, healthEndpoint.healthForPath("db").getStatus());
    }

    @Test
    void startsWithMySqlAndAppliesBaseline() {
        Integer tableCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name <> 'flyway_schema_history'",
                Integer.class);
        Integer systemTenantCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tenant WHERE code = 'SYSTEM'",
                Integer.class);

        assertEquals(16, tableCount);
        assertEquals(1, systemTenantCount);
        assertEquals("1", jdbcTemplate.queryForObject(
                "SELECT version FROM flyway_schema_history WHERE success = 1", String.class));
    }
}
