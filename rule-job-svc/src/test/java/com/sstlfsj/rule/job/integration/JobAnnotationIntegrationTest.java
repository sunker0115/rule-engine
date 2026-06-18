package com.sstlfsj.rule.job.integration;

import com.sstlfsj.rule.job.api.JobTarget;
import com.sstlfsj.rule.job.api.annotation.RuleJob;
import com.sstlfsj.rule.job.api.dto.JobExecutionVO;
import com.sstlfsj.rule.job.api.service.JobService;
import com.sstlfsj.rule.job.internal.domain.JobDefinition;
import com.sstlfsj.rule.job.internal.domain.JobStatus;
import com.sstlfsj.rule.job.internal.repository.JobDefinitionMapper;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.internal.index.SceneRuleIndex;
import com.sstlfsj.rule.eval.internal.snapshot.SceneSnapshotLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 注解式 Job 真实链路端到端测试：{@code @RuleJob} 启动自动落库 + 触发经真实评估产数据。
 *
 * <p>验证 RuleJobScanner 扫描注解方法 → upsert job_definition（BEAN_METHOD 类型）→ 触发时
 * 反射调用业务方法查主体 → 合成 RuleEvent → 真实 acceptEvent 评估 → 产生 evaluation_session。
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class JobAnnotationIntegrationTest {

    @SpringBootApplication(scanBasePackages = {
            "com.sstlfsj.rule.job.internal",
            "com.sstlfsj.rule.eval.internal",
            "com.sstlfsj.rule.observability.internal"
    })
    @MapperScan({
            "com.sstlfsj.rule.job.internal.repository",
            "com.sstlfsj.rule.eval.internal.repository",
            "com.sstlfsj.rule.config.internal.repository",
            "com.sstlfsj.rule.observability.internal.repository"
    })
    static class TestApp {
        @Bean
        ObjectMapper objectMapper() {
            return JsonMapper.builder().build();
        }

        @Bean
        AnnotatedFraudJob annotatedFraudJob() {
            return new AnnotatedFraudJob();
        }
    }

    /** 测试用注解 Job：主体查询方法返回 2 个目标。 */
    static class AnnotatedFraudJob {
        @RuleJob(code = "test-anno-job", cron = "0 0 0 1 1 *", tenant = "1",
                scene = "fraud_check", eventType = "login", name = "测试注解Job")
        public List<JobTarget> subjects() {
            return List.of(JobTarget.of("u1"), JobTarget.of("u2"));
        }
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
    private JobService jobService;
    @Autowired
    private JobDefinitionMapper jobMapper;
    @Autowired
    private SceneSnapshotLoader snapshotLoader;
    @Autowired
    private SceneRuleIndex index;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        // 不清 job_definition：保留 RuleJobScanner 启动期 upsert 的注解 Job
        jdbc.execute("DELETE FROM evaluation_session");
        seedSceneAndRule();
        Map<String, List<RuleVersionSnapshot>> snapshots =
                snapshotLoader.loadByScene("1", "fraud_check");
        for (Map.Entry<String, List<RuleVersionSnapshot>> e : snapshots.entrySet()) {
            index.update("1", "fraud_check", e.getKey(), e.getValue());
        }
    }

    private void seedSceneAndRule() {
        jdbc.execute("INSERT IGNORE INTO tenant (id, code, name, is_default, status) "
                + "VALUES (1, 'test-tenant', '测试租户', 1, 'ACTIVE')");
        jdbc.execute("INSERT IGNORE INTO scene (id, tenant_id, code, name, dominant_mode, "
                + "decision_strategy, subject_type, event_types, status) "
                + "VALUES (1, 1, 'fraud_check', '欺诈检测', 'PUSH', 'HIGHEST_PRIORITY', "
                + "'USER', JSON_ARRAY('login'), 'ACTIVE')");
        jdbc.execute("INSERT IGNORE INTO decision_definition (id, tenant_id, code, name, priority, "
                + "status) VALUES (1, 1, 'REJECT', '拒绝', 100, 'ACTIVE')");
        jdbc.execute("INSERT IGNORE INTO rule_definition (id, tenant_id, scene_id, code, name, "
                + "status, kind, current_version) VALUES (1, 1, 1, 'fraud-rule-001', '欺诈检测规则', "
                + "'PUBLISHED', 'AST_BOOLEAN', 1)");
        jdbc.execute("INSERT IGNORE INTO rule_version (id, rule_definition_id, version, "
                + "condition_ast, decision_bindings, pre_gates, kind, trigger_event_types, "
                + "metric_dependencies, status) VALUES (1, 1, 1, "
                + "'{\"type\":\"AndNode\",\"children\":[],\"displayLabel\":null,\"weight\":null}', "
                + "'[{\"decisionCode\":\"REJECT\",\"priority\":100}]', '[]', "
                + "'AST_BOOLEAN', '[\"login\"]', '[]', 'ACTIVE')");
    }

    @Test
    void annotationJobUpsertedToDbAsBeanMethod() {
        JobDefinition def = jobMapper.findByTenantSceneCode(1L, "fraud_check", "test-anno-job");
        assertThat(def).isNotNull();
        assertThat(def.getStatus()).isEqualTo(JobStatus.ACTIVE);
        assertThat(def.getEventType()).isEqualTo("login");
        assertThat(def.getSubjectQuery()).contains("BEAN_METHOD").contains("AnnotatedFraudJob#subjects");
    }

    @Test
    void annotationJobTriggerProducesEvaluationSessions() throws InterruptedException {
        JobDefinition def = jobMapper.findByTenantSceneCode(1L, "fraud_check", "test-anno-job");
        JobExecutionVO exec = jobService.triggerOnce(1L, def.getId());

        assertThat(exec.status()).isEqualTo("SUCCESS");
        assertThat(exec.successCount()).isEqualTo(2);

        Integer count = 0;
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM evaluation_session WHERE tenant_id = 1", Integer.class);
            if (count != null && count >= 2) break;
            Thread.sleep(100);
        }
        assertThat(count).isEqualTo(2);

        // Job 路径合成的事件渠道应记为 JOB
        Integer jobSourceCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM evaluation_session WHERE tenant_id = 1 AND source = 'JOB'",
                Integer.class);
        assertThat(jobSourceCount).isEqualTo(2);
    }
}
