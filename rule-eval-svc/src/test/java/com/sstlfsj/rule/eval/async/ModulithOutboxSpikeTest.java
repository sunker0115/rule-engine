package com.sstlfsj.rule.eval.async;

import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.Serializable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * spike：验证 Spring Modulith 持久事件（events-jdbc outbox）在事务内发布、AFTER_COMMIT 异步消费，
 * 且事件经 EVENT_PUBLICATION 表持久。Task 1 去风险。
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class ModulithOutboxSpikeTest {

    record SpikeEvent(String payload) implements Serializable {}

    static class SpikePublisher {
        private final ApplicationEventPublisher publisher;
        SpikePublisher(ApplicationEventPublisher publisher) { this.publisher = publisher; }
        @Transactional
        void fire(String p) { publisher.publishEvent(new SpikeEvent(p)); }
    }

    static class SpikeConsumer {
        static final CountDownLatch latch = new CountDownLatch(1);
        static volatile String received;
        @ApplicationModuleListener
        void on(SpikeEvent e) { received = e.payload(); latch.countDown(); }
    }

    @SpringBootApplication(scanBasePackages = {
            "com.sstlfsj.rule.eval.internal",
            "com.sstlfsj.rule.observability.internal"
    })
    @MapperScan({
            "com.sstlfsj.rule.eval.internal.repository",
            "com.sstlfsj.rule.config.internal.repository",
            "com.sstlfsj.rule.observability.internal.repository"
    })
    static class TestApp {
        @Bean ObjectMapper objectMapper() { return JsonMapper.builder().build(); }
        @Bean SpikePublisher spikePublisher(ApplicationEventPublisher p) { return new SpikePublisher(p); }
        @Bean SpikeConsumer spikeConsumer() { return new SpikeConsumer(); }
    }

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("rule_engine_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void overrideDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @Autowired SpikePublisher publisher;
    @Autowired JdbcTemplate jdbc;

    @Test
    void persistentEventPublishedInTxAndConsumedAfterCommit() throws InterruptedException {
        publisher.fire("hello");

        assertThat(SpikeConsumer.latch.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(SpikeConsumer.received).isEqualTo("hello");
        // 事件确实落了 outbox 表
        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM EVENT_PUBLICATION", Integer.class);
        assertThat(rows).isGreaterThanOrEqualTo(1);
    }
}
