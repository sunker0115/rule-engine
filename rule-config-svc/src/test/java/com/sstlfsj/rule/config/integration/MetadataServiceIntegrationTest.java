package com.sstlfsj.rule.config.integration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sstlfsj.rule.config.api.service.MetadataService;
import com.sstlfsj.rule.config.internal.domain.MetricDefinition;
import com.sstlfsj.rule.config.internal.domain.MetricStatus;
import com.sstlfsj.rule.config.internal.domain.RuleDefinition;
import com.sstlfsj.rule.config.internal.domain.RuleDefinitionStatus;
import com.sstlfsj.rule.config.internal.domain.RuleVersion;
import com.sstlfsj.rule.config.internal.domain.RuleVersionStatus;
import com.sstlfsj.rule.config.internal.domain.SceneDef;
import com.sstlfsj.rule.config.internal.domain.SceneStatus;
import com.sstlfsj.rule.config.internal.repository.MetricDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.RuleDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.RuleVersionMapper;
import com.sstlfsj.rule.config.internal.repository.SceneMapper;
import com.sstlfsj.rule.kernel.api.model.MetricDependency;
import com.sstlfsj.rule.kernel.api.model.MetricDescriptor;
import com.sstlfsj.rule.kernel.api.model.PayloadDependency;
import com.sstlfsj.rule.kernel.api.model.ast.AndNode;
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

/**
 * B23 集成测试：真 MySQL（Testcontainers）+ Flyway 建表，端到端验证
 * {@link MetadataService#listMetricDefinitions} 的 scenes 过滤 SQL：
 * ALL 模式返回租户全部 ACTIVE 定义；DECLARED 模式按 scenes 下 ACTIVE rule_version 的
 * metricDependencies 并集过滤。seed 走 MyBatis-Plus 仓储 + 领域实体（与生产数据层一致，回填生成 id 维持关联）。
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class MetadataServiceIntegrationTest {

    /** 内嵌测试应用：扫 config.internal 全部 Bean（含 JacksonConfig 的 ObjectMapper）+ MapperScan 仓储。 */
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
    private static final AndNode AND_NODE = new AndNode(List.of(), null, null);

    @Autowired private MetadataService metadataService;
    @Autowired private SceneMapper sceneMapper;
    @Autowired private MetricDefinitionMapper metricDefinitionMapper;
    @Autowired private RuleDefinitionMapper ruleDefinitionMapper;
    @Autowired private RuleVersionMapper ruleVersionMapper;

    @BeforeEach
    void seed() {
        // 清理（带 WHERE id IS NOT NULL，规避全表删除拦截；auto_increment 不复位，故下方用回填 id 维持关联）
        ruleVersionMapper.delete(new LambdaQueryWrapper<RuleVersion>().isNotNull(RuleVersion::getId));
        ruleDefinitionMapper.delete(new LambdaQueryWrapper<RuleDefinition>().isNotNull(RuleDefinition::getId));
        metricDefinitionMapper.delete(new LambdaQueryWrapper<MetricDefinition>().isNotNull(MetricDefinition::getId));
        sceneMapper.delete(new LambdaQueryWrapper<SceneDef>().isNotNull(SceneDef::getId));

        SceneDef fraud = scene("fraud", List.of("login"));
        sceneMapper.insert(fraud);
        SceneDef payment = scene("payment", List.of("pay"));
        sceneMapper.insert(payment);

        // 三个 ACTIVE metric 定义；user.age 不被任何规则引用
        metricDefinitionMapper.insert(metric("risk.score", "SQL_AGGREGATE", "LONG"));
        metricDefinitionMapper.insert(metric("account.balance", "SQL_AGGREGATE", "DOUBLE"));
        metricDefinitionMapper.insert(metric("user.age", "ATTRIBUTE", "LONG"));

        // 两条规则：fraud 引用 risk.score，payment 引用 account.balance（用回填的 scene/ruleDef id 关联）
        RuleDefinition rdFraud = ruleDef(fraud.getId(), "r-fraud");
        ruleDefinitionMapper.insert(rdFraud);
        RuleDefinition rdPay = ruleDef(payment.getId(), "r-pay");
        ruleDefinitionMapper.insert(rdPay);

        ruleVersionMapper.insert(ruleVersion(rdFraud.getId(),
                List.of(new MetricDependency("risk.score", 1))));
        ruleVersionMapper.insert(ruleVersion(rdPay.getId(),
                List.of(new MetricDependency("account.balance", 1))));
    }

    private SceneDef scene(String code, List<String> eventTypes) {
        SceneDef s = new SceneDef();
        s.setTenantId(TENANT);
        s.setCode(code);
        s.setName(code);
        s.setDominantMode(com.sstlfsj.rule.config.internal.domain.DominantMode.PUSH);
        s.setDecisionStrategy(com.sstlfsj.rule.config.internal.domain.DecisionStrategy.HIGHEST_PRIORITY);
        s.setSubjectType(com.sstlfsj.rule.kernel.api.model.SubjectType.USER);
        s.setEventTypes(eventTypes);
        s.setStatus(SceneStatus.ACTIVE);
        return s;
    }

    private MetricDefinition metric(String code, String sourceType, String dataType) {
        MetricDefinition m = new MetricDefinition();
        m.setTenantId(TENANT);
        m.setMetricCode(code);
        m.setName(code);
        m.setSourceType(sourceType);
        m.setDataType(dataType);
        m.setParams(java.util.Map.of());
        m.setCacheTtlSeconds(60);
        m.setAllowProvided(false);
        m.setStatus(MetricStatus.ACTIVE);
        return m;
    }

    private RuleDefinition ruleDef(Long sceneId, String code) {
        RuleDefinition r = new RuleDefinition();
        r.setTenantId(TENANT);
        r.setSceneId(sceneId);
        r.setCode(code);
        r.setName(code);
        r.setStatus(RuleDefinitionStatus.PUBLISHED);
        r.setKind(com.sstlfsj.rule.kernel.api.model.RuleKind.AST_BOOLEAN);
        r.setCurrentVersion(1L);
        return r;
    }

    private RuleVersion ruleVersion(Long ruleDefinitionId, List<MetricDependency> metricDeps) {
        return ruleVersion(ruleDefinitionId, metricDeps, List.of());
    }

    private RuleVersion ruleVersion(Long ruleDefinitionId, List<MetricDependency> metricDeps,
                                    List<PayloadDependency> payloadDeps) {
        RuleVersion v = new RuleVersion();
        v.setRuleDefinitionId(ruleDefinitionId);
        v.setVersion(1L);
        v.setConditionAst(AND_NODE);
        v.setDecisionBindings(List.of());
        v.setPreGates(List.of());
        v.setKind(com.sstlfsj.rule.kernel.api.model.RuleKind.AST_BOOLEAN);
        v.setTriggerEventTypes(List.of("e"));
        v.setMetricDependencies(metricDeps);
        v.setPayloadDependencies(payloadDeps);
        v.setStatus(RuleVersionStatus.ACTIVE);
        return v;
    }

    @Test
    void allMode_noScenes_returnsAllActiveDefinitions() {
        List<MetricDescriptor> defs = metadataService.listMetricDefinitions(1L, List.of());
        assertThat(defs).extracting(MetricDescriptor::metricCode)
                .containsExactlyInAnyOrder("risk.score", "account.balance", "user.age");
    }

    @Test
    void declaredMode_singleScene_filtersToMetricDependencyUnion() {
        List<MetricDescriptor> defs = metadataService.listMetricDefinitions(1L, List.of("fraud"));
        assertThat(defs).extracting(MetricDescriptor::metricCode).containsExactly("risk.score");
    }

    @Test
    void declaredMode_multiScene_unionOfDeps_excludesUnreferenced() {
        List<MetricDescriptor> defs = metadataService.listMetricDefinitions(1L, List.of("fraud", "payment"));
        // risk.score + account.balance 在并集内；user.age 未被引用，排除
        assertThat(defs).extracting(MetricDescriptor::metricCode)
                .containsExactlyInAnyOrder("risk.score", "account.balance");
    }

    @Test
    void declaredMode_unknownScene_returnsEmpty() {
        assertThat(metadataService.listMetricDefinitions(1L, List.of("nope"))).isEmpty();
    }

    /**
     * input-manifest 场景级并集：fraud 场景下两条 ACTIVE 规则的 payloadDependencies
     * （rule1: amount；rule2: amount+country）经真 DB 持久化/读回后，按 name 去重并集为 [amount, country]。
     */
    @Test
    void getInputManifest_unionsPayloadDepsAcrossRules_dedupByName() {
        // fraud 场景已 seed 一条无 payload 依赖的规则；再补两条带 payload 依赖的规则
        SceneDef fraud = sceneMapper.findByCode(TENANT, "fraud");

        RuleDefinition rd1 = ruleDef(fraud.getId(), "r-pay-amount");
        ruleDefinitionMapper.insert(rd1);
        ruleVersionMapper.insert(ruleVersion(rd1.getId(), List.of(),
                List.of(new PayloadDependency("amount", "DECIMAL", true))));

        RuleDefinition rd2 = ruleDef(fraud.getId(), "r-pay-amount-country");
        ruleDefinitionMapper.insert(rd2);
        ruleVersionMapper.insert(ruleVersion(rd2.getId(), List.of(),
                List.of(new PayloadDependency("amount", "DECIMAL", true),
                        new PayloadDependency("country", "STRING", true))));

        MetadataService.InputManifestResponse resp =
                metadataService.getInputManifest(1L, "fraud", null);

        assertThat(resp.fields())
                .extracting(MetadataService.InputFieldSpec::name)
                .containsExactlyInAnyOrder("amount", "country");
    }
}
