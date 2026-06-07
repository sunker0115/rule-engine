package com.sstlfsj.rule.job.integration;

import com.sstlfsj.rule.job.api.dto.CreateJobCommand;
import com.sstlfsj.rule.job.api.dto.JobExecutionVO;
import com.sstlfsj.rule.job.api.service.JobService;
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
 * Job 真实链路端到端集成测试：不使用替身，拉起真实 config/eval 全栈。
 *
 * <p>验证 job 触发后合成的 RuleEvent 真正驱动评估链路、产生正确数据：
 * triggerOnce → SubjectQuery 查 2 主体 → 真实 EvalService.acceptEvent（异步 PUSH）→
 * 评估命中 → evaluation_session 落 2 条；同时 job_execution 记 SUCCESS / success_count=2。
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class JobEndToEndIntegrationTest {

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
    private SceneSnapshotLoader snapshotLoader;
    @Autowired
    private SceneRuleIndex index;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.execute("DELETE FROM evaluation_session");
        jdbc.execute("DELETE FROM job_execution");
        jdbc.execute("DELETE FROM job_definition");
        insertBaseDataIfAbsent();
        // 把已发布规则快照加载到内存倒排索引（供评估匹配，与 IndexStartupLoader 等价）
        Map<String, List<RuleVersionSnapshot>> snapshots =
                snapshotLoader.loadByScene("1", "fraud_check");
        for (Map.Entry<String, List<RuleVersionSnapshot>> e : snapshots.entrySet()) {
            index.update("1", "fraud_check", e.getKey(), e.getValue());
        }
    }

    /** seed 真实配置：tenant / scene(PUSH,event=login) / decision / rule / rule_version(空 AndNode 必命中)。 */
    private void insertBaseDataIfAbsent() {
        jdbc.execute("""
                INSERT IGNORE INTO tenant (id, code, name, is_default, status)
                VALUES (1, 'test-tenant', '测试租户', 1, 'ACTIVE')
                """);
        jdbc.execute("""
                INSERT IGNORE INTO scene (id, tenant_id, code, name, dominant_mode, decision_strategy,
                    subject_type, event_types, status)
                VALUES (1, 1, 'fraud_check', '欺诈检测', 'PUSH', 'HIGHEST_PRIORITY',
                    'USER', JSON_ARRAY('login'), 'ACTIVE')
                """);
        jdbc.execute("""
                INSERT IGNORE INTO decision_definition (id, tenant_id, code, name, priority, actions, status)
                VALUES (1, 1, 'REJECT', '拒绝', 100, JSON_ARRAY(), 'ACTIVE')
                """);
        jdbc.execute("""
                INSERT IGNORE INTO rule_definition (id, tenant_id, scene_id, code, name,
                    status, kind, current_version)
                VALUES (1, 1, 1, 'fraud-rule-001', '欺诈检测规则',
                    'PUBLISHED', 'AST_BOOLEAN', 1)
                """);
        jdbc.execute("""
                INSERT IGNORE INTO rule_version (id, rule_definition_id, version,
                    condition_ast, decision_bindings, pre_gates,
                    kind, trigger_event_types, metric_dependencies, status)
                VALUES (1, 1, 1,
                    '{"type":"AndNode","children":[],"displayLabel":null,"weight":null}',
                    '[{"decisionCode":"REJECT","priority":100}]',
                    '[]',
                    'AST_BOOLEAN',
                    '["login"]',
                    '[]',
                    'ACTIVE')
                """);
    }

    @Test
    void triggerProducesEvaluationSessionsThroughRealEvalChain() throws InterruptedException {
        // 主体查询返回 2 个主体；eventType=login 匹配 scene/rule
        String subjectQuery = "{\"type\":\"SQL\",\"sql\":"
                + "\"SELECT 'user-001' AS subjectId UNION SELECT 'user-002' AS subjectId\"}";
        Long jobId = jobService.createJob(new CreateJobCommand(
                "1", "fraud_check", "daily-fraud", "每日欺诈扫描",
                "0 0 0 1 1 *", subjectQuery, "login", null, "tester"));

        JobExecutionVO exec = jobService.triggerOnce("1", jobId);

        // job_execution：2 主体全部成功注入
        assertThat(exec.status()).isEqualTo("SUCCESS");
        assertThat(exec.subjectCount()).isEqualTo(2);
        assertThat(exec.successCount()).isEqualTo(2);
        assertThat(exec.errorCount()).isZero();

        // evaluation_session：acceptEvent 异步评估，轮询最多 5 秒等 2 条落库
        Integer count = 0;
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM evaluation_session WHERE tenant_id = 1", Integer.class);
            if (count != null && count >= 2) break;
            Thread.sleep(100);
        }
        assertThat(count).isEqualTo(2);

        // 命中规则 → status=HIT
        Integer hitCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM evaluation_session WHERE tenant_id = 1 AND status = 'HIT'",
                Integer.class);
        assertThat(hitCount).isEqualTo(2);
    }

    @Test
    void recentExecutionsReflectsTriggeredRun() {
        String subjectQuery = "{\"type\":\"SQL\",\"sql\":\"SELECT 'user-001' AS subjectId\"}";
        Long jobId = jobService.createJob(new CreateJobCommand(
                "1", "fraud_check", "single", "单主体", "0 0 0 1 1 *",
                subjectQuery, "login", null, "tester"));
        jobService.triggerOnce("1", jobId);

        List<JobExecutionVO> recent = jobService.recentExecutions("1", jobId, 10);
        assertThat(recent).hasSize(1);
        assertThat(recent.get(0).successCount()).isEqualTo(1);
    }
}
