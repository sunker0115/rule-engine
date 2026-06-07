package com.sstlfsj.rule.eval.integration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.sstlfsj.rule.eval.api.service.EvalService;
import com.sstlfsj.rule.eval.internal.domain.DryRunSession;
import com.sstlfsj.rule.eval.internal.domain.EvaluationSession;
import com.sstlfsj.rule.kernel.internal.index.SceneRuleIndex;
import com.sstlfsj.rule.eval.internal.repository.DryRunSessionMapper;
import com.sstlfsj.rule.eval.internal.repository.EvaluationSessionMapper;
import com.sstlfsj.rule.eval.internal.snapshot.SceneSnapshotLoader;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
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

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testcontainers 集成测试：端到端验证 PULL / PUSH / dry-run 评估链路。
 * 使用真实 MySQL 容器 + Flyway 建表 + 真实 Spring 上下文。
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class EvalIntegrationTest {

    /** 内嵌的测试专用 SpringBootApplication，避免 eval-svc 无 main 类的问题。 */
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

    /** MySQL 8.0 容器，整个测试类共享（@Container + static = 容器生命周期绑定到类）。 */
    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("rule_engine_test")
            .withUsername("test")
            .withPassword("test");

    /** 将容器实际端口注入 Spring 上下文，覆盖 application-test.yml 中的占位 URL。 */
    @DynamicPropertySource
    static void overrideDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @Autowired
    private EvalService evalService;

    @Autowired
    private EvaluationSessionMapper sessionMapper;

    @Autowired
    private DryRunSessionMapper dryRunMapper;

    @Autowired
    private SceneSnapshotLoader snapshotLoader;

    @Autowired
    private SceneRuleIndex index;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * 每个测试前清理结果表并插入基准测试数据。
     * 采用"先删后插"而非 COUNT 判断，保证各测试之间数据隔离。
     */
    @BeforeEach
    void setUp() {
        // 清理会话表（不清配置表，避免每次重建数据）
        jdbc.execute("DELETE FROM evaluation_session");
        jdbc.execute("DELETE FROM dry_run_session");

        // 确保配置数据存在（幂等插入：主键固定为 1）
        insertBaseDataIfAbsent();

        // 将快照加载到内存倒排索引（供 EvalServiceImpl 查询）
        Map<String, List<RuleVersionSnapshot>> snapshots =
                snapshotLoader.loadByScene("1", "fraud_check");
        // index.update 的 eventType 使用 "*" 通配，与 SceneSnapshotLoader 一致
        for (Map.Entry<String, List<RuleVersionSnapshot>> entry : snapshots.entrySet()) {
            index.update("1", "fraud_check", entry.getKey(), entry.getValue());
        }
    }

    /** 如果基准数据尚不存在则插入，已存在则跳过（使用 INSERT IGNORE 实现幂等）。 */
    private void insertBaseDataIfAbsent() {
        // 租户
        jdbc.execute("""
                INSERT IGNORE INTO tenant (id, code, name, is_default, status)
                VALUES (1, 'test-tenant', '测试租户', 1, 'ACTIVE')
                """);

        // 场景（fraud_check，dominant_mode=PUSH，event_types 包含 "login"）
        jdbc.execute("""
                INSERT IGNORE INTO scene (id, tenant_id, code, name, dominant_mode, decision_strategy,
                    subject_type, event_types, status)
                VALUES (1, 1, 'fraud_check', '欺诈检测', 'PUSH', 'HIGHEST_PRIORITY',
                    'USER', JSON_ARRAY('login'), 'ACTIVE')
                """);

        // Decision 定义（REJECT，priority=100）
        jdbc.execute("""
                INSERT IGNORE INTO decision_definition (id, tenant_id, code, name, priority, actions, status)
                VALUES (1, 1, 'REJECT', '拒绝', 100, JSON_ARRAY(), 'ACTIVE')
                """);

        // 规则主记录（关联 scene_id=1，current_version=1，status=PUBLISHED）
        jdbc.execute("""
                INSERT IGNORE INTO rule_definition (id, tenant_id, scene_id, code, name,
                    status, kind, current_version)
                VALUES (1, 1, 1, 'fraud-rule-001', '欺诈检测规则',
                    'PUBLISHED', 'AST_BOOLEAN', 1)
                """);

        // 规则版本（id=1，condition_ast=空 AndNode，pre_gates=[]，decision_bindings 含 REJECT，status=ACTIVE）
        // 空 AndNode：children=[] 时 AND 求值结果为 true（空集合 AND 短路不发生），规则命中
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

    /** 构造测试用 RuleEvent 的工厂方法。 */
    private RuleEvent makeEvent(String eventId, String sceneCode) {
        return new RuleEvent(
                "1",                      // tenantId（String）
                sceneCode,
                "login",                  // eventType
                "user-001",               // subjectId
                eventId,
                Instant.now(),
                Map.of(),
                Map.of()
        , com.sstlfsj.rule.kernel.api.model.EventSource.HTTP);
    }

    // ===== 测试 1：PULL 同步评估写入 evaluation_session =====

    /**
     * PULL 模式调用 evaluate() 后，evaluation_session 应有一条 status=HIT 的记录。
     * 空 AndNode 条件在 TracingInterpretedExecutor 中返回 true，故规则命中。
     */
    @Test
    void pull_evaluate_writesSessionToDb() throws InterruptedException {
        RuleEvent event = makeEvent("pull-001", "fraud_check");
        EvalResult result = evalService.evaluate(event);

        // 返回值验证：规则应命中
        assertThat(result.ruleHit()).isTrue();

        // 数据库验证：审计异步落库，轮询等待 evaluation_session 出现 HIT 记录
        List<EvaluationSession> sessions = awaitSessions("pull-001");
        assertThat(sessions).hasSize(1);
        assertThat(sessions.get(0).getStatus()).isEqualTo("HIT");
        // source 取自 event 渠道，mode 由 evaluate() 入口判定为 PULL
        assertThat(sessions.get(0).getSource()).isEqualTo("HTTP");
        assertThat(sessions.get(0).getMode()).isEqualTo("PULL");
    }

    // ===== 测试 2：相同 eventId 幂等，只写一条 session =====

    /**
     * 相同 eventId 两次调用 evaluate()，evaluation_session 只应有 1 条记录（幂等保证）。
     */
    @Test
    void pull_idempotent_duplicateEventId_onlyOneSession() throws InterruptedException {
        RuleEvent event = makeEvent("pull-idem-001", "fraud_check");

        evalService.evaluate(event);
        evalService.evaluate(event);

        awaitSessions("pull-idem-001");   // 等异步落库（第二次重复因 uk_tenant_event 被吞，仅 1 行）
        long count = sessionMapper.selectCount(
                new LambdaQueryWrapper<EvaluationSession>()
                        .eq(EvaluationSession::getEventId, "pull-idem-001")
                        .eq(EvaluationSession::getTenantId, 1L));
        assertThat(count).isEqualTo(1);
    }

    // ===== 测试 3：PUSH 异步投递后最终写入 evaluation_session =====

    /**
     * PUSH 模式 acceptEvent() 提交后，最多等待 3 秒，等 evaluation_session 出现。
     */
    @Test
    void push_acceptEvent_writesSessionEventually() throws InterruptedException {
        RuleEvent event = makeEvent("push-001", "fraud_check");
        boolean accepted = evalService.acceptEvent(event);
        assertThat(accepted).isTrue();

        // 轮询最多 3 秒（每 100ms 检查一次）
        long deadline = System.currentTimeMillis() + 3_000;
        long count = 0;
        while (System.currentTimeMillis() < deadline) {
            count = sessionMapper.selectCount(
                    new LambdaQueryWrapper<EvaluationSession>()
                            .eq(EvaluationSession::getEventId, "push-001")
                            .eq(EvaluationSession::getTenantId, 1L));
            if (count > 0) break;
            Thread.sleep(100);
        }
        assertThat(count).isGreaterThan(0);

        // PUSH 异步路径 mode 应记为 PUSH（acceptEvent 经 dispatcher 以 mode=PUSH 评估）
        List<EvaluationSession> sessions = sessionMapper.selectList(
                new LambdaQueryWrapper<EvaluationSession>()
                        .eq(EvaluationSession::getEventId, "push-001")
                        .eq(EvaluationSession::getTenantId, 1L));
        assertThat(sessions.get(0).getSource()).isEqualTo("HTTP");
        assertThat(sessions.get(0).getMode()).isEqualTo("PUSH");
    }

    // ===== 测试 4：dry-run 写入 dry_run_session，不污染生产表 =====

    /**
     * dryRun() 应写入 dry_run_session，不写 evaluation_session。
     * 使用 ruleVersionId=1（与 setUp 插入的版本一致）。
     */
    @Test
    void dryRun_writesToDryRunSession_notProdSession() {
        RuleEvent event = makeEvent("dry-001", "fraud_check");
        // ruleVersionId=1 指定已存在的版本
        EvalResult result = evalService.dryRun(event, 1L);

        // dry_run_session 应有记录
        List<DryRunSession> dryRuns = dryRunMapper.selectList(
                new LambdaQueryWrapper<DryRunSession>()
                        .eq(DryRunSession::getEventId, "dry-001")
                        .eq(DryRunSession::getTenantId, 1L));
        assertThat(dryRuns).hasSize(1);
        assertThat(dryRuns.get(0).getStatus()).isIn("HIT", "MISS");

        // evaluation_session 不应有记录（dry-run 不写生产表）
        long prodCount = sessionMapper.selectCount(
                new LambdaQueryWrapper<EvaluationSession>()
                        .eq(EvaluationSession::getEventId, "dry-001")
                        .eq(EvaluationSession::getTenantId, 1L));
        assertThat(prodCount).isEqualTo(0);
    }

    // ===== 测试 5：无匹配规则时返回 MISS，不写 session =====

    /**
     * sceneCode=unknown_scene 在索引中无规则，evaluate() 应返回 miss，不写 evaluation_session。
     */
    @Test
    void noMatchingRules_returnsMiss_noSession() {
        RuleEvent event = makeEvent("no-rule-001", "unknown_scene");
        EvalResult result = evalService.evaluate(event);

        assertThat(result.ruleHit()).isFalse();

        long count = sessionMapper.selectCount(
                new LambdaQueryWrapper<EvaluationSession>()
                        .eq(EvaluationSession::getEventId, "no-rule-001")
                        .eq(EvaluationSession::getTenantId, 1L));
        assertThat(count).isEqualTo(0);
    }

    /** 轮询最多 3 秒，等指定 eventId 的 evaluation_session 异步落库出现，返回查到的行。 */
    private List<EvaluationSession> awaitSessions(String eventId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3_000;
        List<EvaluationSession> sessions = List.of();
        while (System.currentTimeMillis() < deadline) {
            sessions = sessionMapper.selectList(
                    new LambdaQueryWrapper<EvaluationSession>()
                            .eq(EvaluationSession::getEventId, eventId)
                            .eq(EvaluationSession::getTenantId, 1L));
            if (!sessions.isEmpty()) break;
            Thread.sleep(100);
        }
        return sessions;
    }
}
