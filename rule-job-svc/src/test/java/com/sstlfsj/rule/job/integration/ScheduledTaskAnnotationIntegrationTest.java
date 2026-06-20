package com.sstlfsj.rule.job.integration;

import com.sstlfsj.rule.eval.api.service.OutcomeIngestionConfig;
import com.sstlfsj.rule.eval.api.service.SqlOutcomeSourceConfig;
import com.sstlfsj.rule.job.api.SubjectTarget;
import com.sstlfsj.rule.job.api.TaskStatus;
import com.sstlfsj.rule.job.api.TriggerConfig;
import com.sstlfsj.rule.job.api.annotation.TriggerTask;
import com.sstlfsj.rule.job.internal.domain.ScheduledTask;
import com.sstlfsj.rule.job.internal.domain.ScheduledTaskExecution;
import com.sstlfsj.rule.job.internal.repository.ScheduledTaskMapper;
import com.sstlfsj.rule.job.internal.service.ScheduledTaskScheduleManager;
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
 * 注解式 TRIGGER 任务真实链路端到端测试：{@code @TriggerTask} 启动自动落库 + 触发经真实评估产数据。
 *
 * <p>验证 ScheduledTaskScanner 扫描注解方法 → upsert scheduled_task（TRIGGER 型、typed TriggerConfig）→
 * 经真实 MySQL JSON 列 + Jackson3TypeHandler 往返读回 config 子类型 → 触发时反射调用业务方法查主体 →
 * 合成 RuleEvent → 真实 acceptEvent 评估 → 产生 evaluation_session + scheduled_task_execution。
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class ScheduledTaskAnnotationIntegrationTest {

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

    /** 测试用注解任务：主体查询方法返回 2 个目标。 */
    static class AnnotatedFraudJob {
        @TriggerTask(code = "test-anno-job", cron = "0 0 0 1 1 *", tenant = "1",
                scene = "fraud_check", eventType = "login", name = "测试注解任务")
        public List<SubjectTarget> subjects() {
            return List.of(SubjectTarget.of("u1"), SubjectTarget.of("u2"));
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
    private ScheduledTaskScheduleManager scheduleManager;
    @Autowired
    private ScheduledTaskMapper taskMapper;
    @Autowired
    private SceneSnapshotLoader snapshotLoader;
    @Autowired
    private SceneRuleIndex index;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        // 不清 scheduled_task：保留 ScheduledTaskScanner 启动期 upsert 的注解任务
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
    void annotationTaskUpsertedToDbAsTriggerWithRawJsonConfig() {
        // 从真实 MySQL JSON 列读回 —— 去中心化后 config 是原始 JSON String;手动反序列化回 TriggerConfig 验证内容
        ScheduledTask task = taskMapper.findByTenantCode(1L, "test-anno-job");
        assertThat(task).isNotNull();
        assertThat(task.getStatus()).isEqualTo(TaskStatus.ACTIVE);
        assertThat(task.getTaskType()).isEqualTo("TRIGGER");
        assertThat(task.getConfig()).isInstanceOf(String.class);
        TriggerConfig config = objectMapper.readValue(task.getConfig(), TriggerConfig.class);
        assertThat(config.sceneCode()).isEqualTo("fraud_check");
        assertThat(config.eventType()).isEqualTo("login");
        assertThat(config.subjectQuery()).isNotNull();
    }

    @Test
    void annotationTaskTriggerProducesExecutionAndEvaluationSessions() throws InterruptedException {
        ScheduledTask task = taskMapper.findByTenantCode(1L, "test-anno-job");
        ScheduledTaskExecution exec = scheduleManager.runOnce(task.getId());

        // 执行记录真落库：状态 SUCCESS、2 主体全注入
        assertThat(exec.getStatus().name()).isEqualTo("SUCCESS");
        assertThat(exec.getSuccessCount()).isEqualTo(2);
        Integer execRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM scheduled_task_execution WHERE scheduled_task_id = ?",
                Integer.class, task.getId());
        assertThat(execRows).isGreaterThanOrEqualTo(1);

        Integer count = 0;
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM evaluation_session WHERE tenant_id = 1", Integer.class);
            if (count != null && count >= 2) break;
            Thread.sleep(100);
        }
        assertThat(count).isEqualTo(2);

        // TRIGGER 路径合成的事件渠道应记为 JOB
        Integer jobSourceCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM evaluation_session WHERE tenant_id = 1 AND source = 'JOB'",
                Integer.class);
        assertThat(jobSourceCount).isEqualTo(2);
    }

    @Test
    void outcomeIngestionConfig_roundTripsThroughJsonColumn() {
        // OUTCOME_INGESTION 配置(静态定义):去中心化后 config 以原始 JSON String 存入 MySQL JSON 列;
        // 嵌套 sealed source(SQL 子类型)由各 handler 在派发时反序列化;运行态游标存于独立 run_cursor 列(state-not-config)
        OutcomeIngestionConfig config = new OutcomeIngestionConfig(
                new SqlOutcomeSourceConfig("biz",
                        "SELECT event_id, outcome_label, outcome_value, labeled_at FROM biz_label "
                                + "WHERE tenant_id = :tenantId AND (:watermark IS NULL OR labeled_at > :watermark)"));
        String configJson = objectMapper.writeValueAsString(config);

        ScheduledTask task = new ScheduledTask();
        task.setTenantId(1L);
        task.setCode("ingest-rt-test");
        task.setName("回灌往返测试");
        task.setTaskType("OUTCOME_INGESTION");
        task.setCron("0 0 4 * * *");
        task.setConfig(configJson);
        task.setRunCursor("2026-06-18T10:00:00Z");
        task.setStatus(TaskStatus.ACTIVE);
        taskMapper.insert(task);

        // 读回:task_type(开放 string)+ config 原始 JSON String;手动反序列化还原嵌套 source 子类型;run_cursor 列单独持久化
        ScheduledTask read = taskMapper.selectById(task.getId());
        assertThat(read.getTaskType()).isEqualTo("OUTCOME_INGESTION");
        assertThat(read.getConfig()).isInstanceOf(String.class);
        OutcomeIngestionConfig readConfig = objectMapper.readValue(read.getConfig(), OutcomeIngestionConfig.class);
        assertThat(readConfig.source()).isInstanceOf(SqlOutcomeSourceConfig.class);
        assertThat(((SqlOutcomeSourceConfig) readConfig.source()).datasource()).isEqualTo("biz");
        assertThat(read.getRunCursor()).isEqualTo("2026-06-18T10:00:00Z");

        // 模拟调度框架写回:推进 run_cursor 列后 updateById,再读回确认新游标真持久化(config 不动)
        read.setRunCursor("2026-06-19T10:00:00Z");
        taskMapper.updateById(read);

        ScheduledTask afterWriteBack = taskMapper.selectById(task.getId());
        assertThat(afterWriteBack.getRunCursor()).isEqualTo("2026-06-19T10:00:00Z");
        assertThat(objectMapper.readValue(afterWriteBack.getConfig(), OutcomeIngestionConfig.class).source())
                .isInstanceOf(SqlOutcomeSourceConfig.class);

        // 清理本测试新建的行,不污染同类其它用例
        taskMapper.deleteById(task.getId());
    }
}
