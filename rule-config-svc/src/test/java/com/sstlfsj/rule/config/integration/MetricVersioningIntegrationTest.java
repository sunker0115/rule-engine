package com.sstlfsj.rule.config.integration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sstlfsj.rule.config.api.service.MetricWriteService;
import com.sstlfsj.rule.config.api.service.MetricWriteService.MetricWriteCommand;
import com.sstlfsj.rule.config.internal.domain.AuditLog;
import com.sstlfsj.rule.config.internal.domain.MetricDefinition;
import com.sstlfsj.rule.config.internal.repository.AuditLogMapper;
import com.sstlfsj.rule.config.internal.repository.MetricDefinitionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B6 集成测试：真 MySQL（Testcontainers）+ Flyway 建表，端到端验证
 * MetricWriteService create / update(breaking) / update(non-breaking) 的版本行状态与 UK 约束。
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class MetricVersioningIntegrationTest {

    @SpringBootApplication(scanBasePackages = "com.sstlfsj.rule.config.internal")
    @MapperScan("com.sstlfsj.rule.config.internal.repository")
    static class TestApp {
    }

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("rule_engine_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    private static final long TENANT = 1L;
    private static final String CODE = "user.age";
    private static final String ACTOR = "dev";

    @Autowired private MetricWriteService metricWriteService;
    @Autowired private MetricDefinitionMapper metricDefinitionMapper;
    @Autowired private AuditLogMapper auditLogMapper;

    @BeforeEach
    void clean() {
        metricDefinitionMapper.delete(new LambdaQueryWrapper<MetricDefinition>()
                .isNotNull(MetricDefinition::getId));
        auditLogMapper.delete(new LambdaQueryWrapper<AuditLog>()
                .isNotNull(AuditLog::getId));
    }

    private MetricWriteCommand cmd(String name) {
        return new MetricWriteCommand(name, "ATTRIBUTE", "LONG", "{}", 60, false);
    }

    @Test
    void create_insertsVersion1ActiveRow() {
        Long id = metricWriteService.create(TENANT, CODE, cmd("用户年龄"), ACTOR);

        assertThat(id).isNotNull();
        MetricDefinition row = metricDefinitionMapper.selectById(id);
        assertThat(row.getVersion()).isEqualTo(1);
        assertThat(row.getStatus()).isEqualTo("ACTIVE");
        assertThat(row.getMetricCode()).isEqualTo(CODE);
        assertThat(row.getName()).isEqualTo("用户年龄");

        // 审计日志写入
        List<AuditLog> logs = auditLogMapper.selectList(new LambdaQueryWrapper<AuditLog>()
                .eq(AuditLog::getTargetType, "metric_definition")
                .eq(AuditLog::getAction, "CREATE"));
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getTargetId()).isEqualTo(id.toString());
    }

    @Test
    void update_breakingChange_producesTwoRows_oldSupersededNewActive() {
        // create v1
        metricWriteService.create(TENANT, CODE, cmd("用户年龄v1"), ACTOR);

        // breaking update → v2
        int newVersion = metricWriteService.update(TENANT, CODE, cmd("用户年龄v2"), true, ACTOR);
        assertThat(newVersion).isEqualTo(2);

        // 查库：同 (tenant, code) 应有两行
        List<MetricDefinition> rows = metricDefinitionMapper.selectList(
                new LambdaQueryWrapper<MetricDefinition>()
                        .eq(MetricDefinition::getTenantId, TENANT)
                        .eq(MetricDefinition::getMetricCode, CODE)
                        .orderByAsc(MetricDefinition::getVersion));
        assertThat(rows).hasSize(2);

        MetricDefinition v1 = rows.get(0);
        assertThat(v1.getVersion()).isEqualTo(1);
        assertThat(v1.getStatus()).isEqualTo("SUPERSEDED");

        MetricDefinition v2 = rows.get(1);
        assertThat(v2.getVersion()).isEqualTo(2);
        assertThat(v2.getStatus()).isEqualTo("ACTIVE");
        assertThat(v2.getName()).isEqualTo("用户年龄v2");

        // UK uk_tenant_code_version 未冲突（否则 insert 已抛异常）
    }

    @Test
    void update_nonBreaking_noNewRow_versionUnchanged() {
        metricWriteService.create(TENANT, CODE, cmd("用户年龄v1"), ACTOR);

        int version = metricWriteService.update(TENANT, CODE, cmd("用户年龄v1改"), false, ACTOR);
        assertThat(version).isEqualTo(1);

        List<MetricDefinition> rows = metricDefinitionMapper.selectList(
                new LambdaQueryWrapper<MetricDefinition>()
                        .eq(MetricDefinition::getTenantId, TENANT)
                        .eq(MetricDefinition::getMetricCode, CODE));
        // 仍只有一行
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getVersion()).isEqualTo(1);
        assertThat(rows.get(0).getStatus()).isEqualTo("ACTIVE");
        assertThat(rows.get(0).getName()).isEqualTo("用户年龄v1改");
    }

    @Test
    void update_noActiveRow_throwsIllegalArgumentException() {
        assertThatThrownBy(() ->
                metricWriteService.update(TENANT, "nonexistent.code", cmd("x"), false, ACTOR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nonexistent.code");
    }
}
