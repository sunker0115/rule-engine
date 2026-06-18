package com.sstlfsj.rule.config.internal.repository;

import com.sstlfsj.rule.config.api.connector.BearerAuth;
import com.sstlfsj.rule.config.api.connector.CompareOp;
import com.sstlfsj.rule.config.api.connector.ConnectorDescriptor;
import com.sstlfsj.rule.config.api.connector.HttpMethod;
import com.sstlfsj.rule.config.api.connector.HttpRequestTemplate;
import com.sstlfsj.rule.config.api.connector.OAuth2ClientCredentialsAuth;
import com.sstlfsj.rule.config.api.connector.Predicate;
import com.sstlfsj.rule.config.api.connector.ResiliencePolicy;
import com.sstlfsj.rule.config.api.connector.ResponseMapping;
import com.sstlfsj.rule.config.internal.domain.ConnectorDefinition;
import com.sstlfsj.rule.config.internal.domain.ConnectorStatus;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 连接器定义 JSON 列往返集成测试：真 MySQL（Testcontainers）+ Flyway V1_34 建表，
 * 验证 typed descriptor（含 sealed 多态 auth）经 Jackson3TypeHandler 真往返、status enum↔varchar 真往返。
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class ConnectorDefinitionMapperIT {

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

    @Autowired
    private ConnectorDefinitionMapper mapper;

    @Test
    void insertAndReadBackPreservesTypedDescriptor() {
        ConnectorDefinition c = new ConnectorDefinition();
        c.setTenantId(1L);
        c.setConnectorCode("risk-svc");
        c.setName("风控打分");
        c.setStatus(ConnectorStatus.ACTIVE);
        c.setDescriptor(ConnectorDescriptor.builder()
                .endpointRef("risk")
                .request(HttpRequestTemplate.builder()
                        .method(HttpMethod.POST).pathTemplate("/score/{subjectId}")
                        .query(List.of()).headers(List.of()).bodyTemplate("{}").build())
                .response(new ResponseMapping(new Predicate("code", CompareOp.EQ, 0), "data.score"))
                .auth(new BearerAuth("riskToken"))
                .resilience(ResiliencePolicy.builder()
                        .connectTimeoutMs(200).readTimeoutMs(300).retries(0)
                        .retryOn(Set.of()).circuitBreaker(null).build())
                .errorMapping(List.of())
                .build());

        mapper.insert(c);

        // 红旗判定：descriptor 读回为 null 说明 autoResultMap/typeHandler 未生效
        ConnectorDefinition back = mapper.findByCode(1L, "risk-svc");
        assertThat(back).isNotNull();
        assertThat(back.getDescriptor()).isNotNull();
        assertThat(back.getStatus()).isEqualTo(ConnectorStatus.ACTIVE);
        assertThat(back.getDescriptor().endpointRef()).isEqualTo("risk");
        assertThat(back.getDescriptor().auth()).isInstanceOf(BearerAuth.class);
        assertThat(((BearerAuth) back.getDescriptor().auth()).tokenRef()).isEqualTo("riskToken");
        assertThat(back.getDescriptor().response().valuePath()).isEqualTo("data.score");
        assertThat(back.getDescriptor().response().successWhen().op()).isEqualTo(CompareOp.EQ);
        // 验 @TableField("created_at") 映射生效：insert 后 DB 由 DEFAULT CURRENT_TIMESTAMP 填充，查回不应为 null
        assertThat(back.getCreatedAt()).as("autoResultMap @TableField created_at 映射应生效").isNotNull();
        assertThat(back.getUpdatedAt()).as("autoResultMap @TableField updated_at 映射应生效").isNotNull();
    }

    @Test
    void roundTripsOAuth2AuthAndStatusEnum() {
        ConnectorDefinition c = new ConnectorDefinition();
        c.setTenantId(2L);
        c.setConnectorCode("oauth-svc");
        c.setName("OAuth 连接器");
        c.setStatus(ConnectorStatus.DISABLED);
        c.setDescriptor(ConnectorDescriptor.builder()
                .endpointRef("auth-ep")
                .request(HttpRequestTemplate.builder()
                        .method(HttpMethod.GET).pathTemplate("/q/{payload.id}")
                        .query(List.of()).headers(List.of()).bodyTemplate(null).build())
                .response(new ResponseMapping(new Predicate("ok", CompareOp.EQ, true), "v"))
                .auth(OAuth2ClientCredentialsAuth.builder()
                        .tokenUrl("https://auth/token").clientIdRef("cid").clientSecretRef("sec")
                        .scopes(List.of("score")).build())
                .resilience(ResiliencePolicy.builder()
                        .connectTimeoutMs(200).readTimeoutMs(300).retries(1)
                        .retryOn(Set.of()).circuitBreaker(null).build())
                .errorMapping(List.of())
                .build());

        mapper.insert(c);

        ConnectorDefinition back = mapper.findByCode(2L, "oauth-svc");
        assertThat(back).isNotNull();
        assertThat(back.getStatus()).isEqualTo(ConnectorStatus.DISABLED);
        assertThat(back.getDescriptor().auth()).isInstanceOf(OAuth2ClientCredentialsAuth.class);
        OAuth2ClientCredentialsAuth auth = (OAuth2ClientCredentialsAuth) back.getDescriptor().auth();
        assertThat(auth.clientIdRef()).isEqualTo("cid");
        assertThat(auth.scopes()).containsExactly("score");
        // DISABLED 连接器不应出现在 findActiveByTenant 结果中
        assertThat(mapper.findActiveByTenant(2L)).isEmpty();
    }
}
