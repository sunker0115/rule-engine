package com.sstlfsj.rule.config.integration;

import com.sstlfsj.rule.config.internal.domain.DecisionDefinition;
import com.sstlfsj.rule.config.internal.domain.DecisionStatus;
import com.sstlfsj.rule.config.internal.repository.DecisionDefinitionMapper;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * testcontainers 可用性基准集成测试：真 MySQL（Testcontainers）+ Flyway 全量迁移建表，
 * 经 {@link DecisionDefinitionMapper} 验证「真 DB 写入 → status enum ↔ varchar 往返 → 读回」
 * 这一段单测 mock 不掉的持久层链路。
 *
 * <p>装配要点：TestApp 用最小 mapper 切片配置——{@code @EnableAutoConfiguration} 提供
 * DataSource / MyBatis-Plus / Flyway，{@code @MapperScan} 注册仓储。<b>刻意排除</b>
 * {@code ConfigAutoConfiguration}：它带 {@code @ComponentScan("...config.internal")}，与本测试的
 * {@code @MapperScan("...config.internal.repository")} 会对同一批 Mapper 形成两条注册路径，
 * 在 config-svc 模块内（target/classes 直扫）触发 ConflictingBeanDefinitionException。
 * mapper 往返验证不需要完整 service 层，故排除 auto-config 只保留 mapper 切片。
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class DecisionDefinitionRoundTripIT {

    @Configuration
    @EnableAutoConfiguration(excludeName = "com.sstlfsj.rule.config.ConfigAutoConfiguration")
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

    @Autowired
    private DecisionDefinitionMapper decisionMapper;

    @Test
    void insertThenFindByCode_roundTripsStatusEnum() {
        DecisionDefinition decision = new DecisionDefinition();
        decision.setTenantId(1L);
        decision.setCode("REJECT");
        decision.setName("拒绝");
        decision.setPriority(10);
        decision.setStatus(DecisionStatus.ACTIVE);

        int inserted = decisionMapper.insert(decision);
        assertThat(inserted).isEqualTo(1);
        // 自增主键回填
        assertThat(decision.getId()).isNotNull();

        DecisionDefinition loaded = decisionMapper.findByCode(1L, "REJECT");
        assertThat(loaded).isNotNull();
        assertThat(loaded.getId()).isEqualTo(decision.getId());
        assertThat(loaded.getName()).isEqualTo("拒绝");
        assertThat(loaded.getPriority()).isEqualTo(10);
        // status enum ↔ varchar 往返（MybatisEnumTypeHandler 按 name）
        assertThat(loaded.getStatus()).isEqualTo(DecisionStatus.ACTIVE);
    }
}
