package com.sstlfsj.rule.job.integration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sstlfsj.rule.config.api.dto.SceneDetailDto;
import com.sstlfsj.rule.config.api.dto.SceneListItem;
import com.sstlfsj.rule.config.api.service.SceneService;
import com.sstlfsj.rule.eval.api.service.EvalService;
import com.sstlfsj.rule.job.api.dto.CreateJobCommand;
import com.sstlfsj.rule.job.api.dto.JobExecutionVO;
import com.sstlfsj.rule.job.api.service.JobService;
import com.sstlfsj.rule.job.internal.domain.JobDefinition;
import com.sstlfsj.rule.job.internal.domain.JobExecution;
import com.sstlfsj.rule.job.internal.repository.JobDefinitionMapper;
import com.sstlfsj.rule.job.internal.repository.JobExecutionMapper;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.SpringBootTest;
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
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Job 模块集成测试：真 MySQL（Testcontainers）+ Flyway 建表。
 *
 * <p>SceneService / EvalService 用测试替身，聚焦 job 模块自身职责——JobDefinition / JobExecution
 * 落库、subjectQuery SQL 取数、triggerOnce 端到端注入统计、PULL Scene 拒绝。
 * 跨模块真实评估链路由 eval-svc 自身集成测试覆盖。
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class JobIntegrationTest {

    @SpringBootApplication(
            scanBasePackages = "com.sstlfsj.rule.job.internal",
            exclude = {
                    com.sstlfsj.rule.config.ConfigAutoConfiguration.class,
                    com.sstlfsj.rule.eval.EvalAutoConfiguration.class,
                    com.sstlfsj.rule.observability.ObservabilityAutoConfiguration.class
            })
    @MapperScan("com.sstlfsj.rule.job.internal.repository")
    static class TestApp {

        @Bean
        SceneService sceneService() {
            return new StubSceneService();
        }

        @Bean
        CapturingEvalService evalService() {
            return new CapturingEvalService();
        }

        // 生产由 config-svc JacksonConfig 提供全局 ObjectMapper；本测试排除了 config，故自备一个
        @Bean
        ObjectMapper objectMapper() {
            return JsonMapper.builder().build();
        }
    }

    /** sceneCode 含 "pull" 返回 PULL，其余 PUSH。 */
    static class StubSceneService implements SceneService {
        @Override
        public SceneDetailDto getScene(String tenantId, String sceneCode) {
            String mode = sceneCode.contains("pull") ? "PULL" : "PUSH";
            return new SceneDetailDto(1L, tenantId, sceneCode, "n", null, mode, "USER",
                    List.of(), List.of(), Map.of(), 1, "ACTIVE");
        }

        @Override
        public List<SceneListItem> listScenes(String tenantId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Long createScene(String t, String c, String n, String d, String dm, String st,
                                String et, String ps, String dp, String a) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void updateScene(String t, String c, String n, String et, String ps, String dp, String a) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void disableScene(String t, String c, String a) {
            throw new UnsupportedOperationException();
        }
    }

    /** 记录被注入的 RuleEvent，acceptEvent 一律接受。 */
    static class CapturingEvalService implements EvalService {
        final List<RuleEvent> accepted = new CopyOnWriteArrayList<>();

        @Override
        public boolean acceptEvent(RuleEvent event) {
            accepted.add(event);
            return true;
        }

        @Override
        public EvalResult evaluate(RuleEvent event) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EvalResult dryRun(RuleEvent event, Long ruleVersionId) {
            throw new UnsupportedOperationException();
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

    private static final String TENANT = "1";
    // 远期 cron，避免测试期被进程内调度器触发
    private static final String CRON = "0 0 0 1 1 *";
    private static final String SUBJECT_SQL =
            "{\"type\":\"SQL\",\"sql\":\"SELECT 'u1' AS subjectId UNION SELECT 'u2' AS subjectId\"}";

    @Autowired
    private JobService jobService;
    @Autowired
    private JobDefinitionMapper jobMapper;
    @Autowired
    private JobExecutionMapper executionMapper;
    @Autowired
    private CapturingEvalService evalService;

    @BeforeEach
    void clean() {
        executionMapper.delete(new LambdaQueryWrapper<JobExecution>().isNotNull(JobExecution::getId));
        jobMapper.delete(new LambdaQueryWrapper<JobDefinition>().isNotNull(JobDefinition::getId));
        evalService.accepted.clear();
    }

    private CreateJobCommand cmd(String sceneCode, String code) {
        return new CreateJobCommand(TENANT, sceneCode, code, "Job-" + code, CRON,
                SUBJECT_SQL, "trade.completed", null, "actor");
    }

    @Test
    void persistsJobForPushScene() {
        Long id = jobService.createJob(cmd("push-scene", "j1"));
        assertThat(id).isNotNull();
        JobDefinition persisted = jobMapper.selectById(id);
        assertThat(persisted).isNotNull();
        assertThat(persisted.getSceneCode()).isEqualTo("push-scene");
        assertThat(persisted.getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void triggerOnceQueriesSubjectsInjectsAndRecordsExecution() {
        Long id = jobService.createJob(cmd("push-scene", "j2"));
        JobExecutionVO exec = jobService.triggerOnce(TENANT, id);

        assertThat(exec.status()).isEqualTo("SUCCESS");
        assertThat(exec.subjectCount()).isEqualTo(2);
        assertThat(exec.successCount()).isEqualTo(2);
        assertThat(exec.errorCount()).isZero();
        // 两个主体各合成一个 RuleEvent 注入
        assertThat(evalService.accepted).hasSize(2);
        assertThat(evalService.accepted).allSatisfy(e -> {
            assertThat(e.sceneCode()).isEqualTo("push-scene");
            assertThat(e.eventType()).isEqualTo("trade.completed");
            assertThat(e.tenantId()).isEqualTo(TENANT);
        });
        assertThat(evalService.accepted).extracting(RuleEvent::subjectId)
                .containsExactlyInAnyOrder("u1", "u2");
    }

    @Test
    void executionRecordsAreQueryable() {
        Long id = jobService.createJob(cmd("push-scene", "j3"));
        jobService.triggerOnce(TENANT, id);
        List<JobExecutionVO> recent = jobService.recentExecutions(TENANT, id, 10);
        assertThat(recent).hasSize(1);
        assertThat(recent.get(0).status()).isEqualTo("SUCCESS");
    }

    @Test
    void rejectsJobBindingForPullScene() {
        assertThatThrownBy(() -> jobService.createJob(cmd("pull-scene", "j4")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PULL Scene 不允许绑定 Job");
    }
}
