package com.sstlfsj.rule.config.integration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sstlfsj.rule.config.api.dto.RuleBundle;
import com.sstlfsj.rule.config.api.dto.RuleImportResult;
import com.sstlfsj.rule.config.api.service.RuleBundleService;
import com.sstlfsj.rule.config.internal.domain.DecisionDefinition;
import com.sstlfsj.rule.config.internal.domain.MetricDefinition;
import com.sstlfsj.rule.config.internal.domain.MetricStatus;
import com.sstlfsj.rule.config.internal.domain.RuleDefinition;
import com.sstlfsj.rule.config.internal.domain.RuleDefinitionStatus;
import com.sstlfsj.rule.config.internal.domain.RuleVersion;
import com.sstlfsj.rule.config.internal.domain.RuleVersionStatus;
import com.sstlfsj.rule.config.internal.domain.SceneDef;
import com.sstlfsj.rule.config.internal.domain.SceneStatus;
import com.sstlfsj.rule.config.internal.repository.DecisionDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.MetricDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.RuleDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.RuleVersionMapper;
import com.sstlfsj.rule.config.internal.repository.SceneMapper;
import com.sstlfsj.rule.kernel.api.model.MetricDependency;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot.DecisionAction;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot.DecisionBinding;
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

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** B7 端到端集成测试：seed 两条已发布规则 → 按 scene 批量导出 → 跨租户导入 → 校验落库 + 重复导入。 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class RuleBundleIntegrationTest {

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

    private static final long SRC_TENANT = 1L;
    private static final long DST_TENANT = 2L;

    @Autowired RuleBundleService ruleBundleService;
    @Autowired SceneMapper sceneMapper;
    @Autowired MetricDefinitionMapper metricDefinitionMapper;
    @Autowired DecisionDefinitionMapper decisionDefinitionMapper;
    @Autowired RuleDefinitionMapper ruleDefinitionMapper;
    @Autowired RuleVersionMapper ruleVersionMapper;

    @BeforeEach
    void clean() {
        ruleVersionMapper.delete(new LambdaQueryWrapper<RuleVersion>().isNotNull(RuleVersion::getId));
        ruleDefinitionMapper.delete(new LambdaQueryWrapper<RuleDefinition>().isNotNull(RuleDefinition::getId));
        metricDefinitionMapper.delete(new LambdaQueryWrapper<MetricDefinition>().isNotNull(MetricDefinition::getId));
        decisionDefinitionMapper.delete(new LambdaQueryWrapper<DecisionDefinition>().isNotNull(DecisionDefinition::getId));
        sceneMapper.delete(new LambdaQueryWrapper<SceneDef>().isNotNull(SceneDef::getId));
    }

    /** seed 一个 scene + metric + decision + 两条已发布规则，返回 sceneId。 */
    private Long seedTwoPublishedRules() {
        SceneDef scene = new SceneDef();
        scene.setTenantId(SRC_TENANT); scene.setCode("risk.transfer"); scene.setName("转账风控");
        scene.setSubjectType("USER"); scene.setDominantMode("PUSH"); scene.setDecisionStrategy("HIGHEST_PRIORITY");
        scene.setEventTypes(java.util.List.of("transfer")); scene.setPayloadSchema(java.util.List.of());
        scene.setDefaultParams(java.util.Map.of());
        scene.setPayloadSchemaVersion(1); scene.setStatus(SceneStatus.ACTIVE); scene.setCreatedBy("seed");
        scene.setCreatedAt(LocalDateTime.now());
        sceneMapper.insert(scene);

        MetricDefinition metric = new MetricDefinition();
        metric.setTenantId(SRC_TENANT); metric.setMetricCode("account.age"); metric.setVersion(1);
        metric.setName("账户年龄"); metric.setSourceType("ATTRIBUTE"); metric.setDataType("LONG");
        metric.setParams(java.util.Map.of()); metric.setCacheTtlSeconds(3600); metric.setAllowProvided(true);
        metric.setStatus(MetricStatus.ACTIVE); metric.setCreatedBy("seed"); metric.setCreatedAt(LocalDateTime.now());
        metricDefinitionMapper.insert(metric);

        DecisionDefinition decision = new DecisionDefinition();
        decision.setTenantId(SRC_TENANT); decision.setCode("BLOCK"); decision.setName("拦截");
        decision.setPriority(100); decision.setDescription("拦截交易");
        decision.setActions(java.util.List.of(new DecisionAction("a1", "BLOCK_TRANSACTION", 0, java.util.Map.of())));
        decision.setStatus("ACTIVE"); decision.setCreatedBy("seed"); decision.setCreatedAt(LocalDateTime.now());
        decisionDefinitionMapper.insert(decision);

        seedRule(scene.getId(), "rule.night.transfer", "夜间大额转账");
        seedRule(scene.getId(), "rule.new.account", "新户拦截");
        return scene.getId();
    }

    private void seedRule(Long sceneId, String code, String name) {
        RuleDefinition rd = new RuleDefinition();
        rd.setTenantId(SRC_TENANT); rd.setSceneId(sceneId); rd.setCode(code);
        rd.setName(name); rd.setStatus(RuleDefinitionStatus.PUBLISHED); rd.setKind("AST_BOOLEAN");
        rd.setCreatedBy("seed"); rd.setCreatedAt(LocalDateTime.now());
        ruleDefinitionMapper.insert(rd);

        RuleVersion rv = new RuleVersion();
        rv.setRuleDefinitionId(rd.getId()); rv.setVersion(1L);
        rv.setConditionAst(new AndNode(java.util.List.of(), null, null));
        rv.setDecisionBindings(java.util.List.of(new DecisionBinding("BLOCK", 100)));
        rv.setPreGates(java.util.List.of()); rv.setKind("AST_BOOLEAN");
        rv.setTriggerEventTypes(java.util.List.of("transfer"));
        rv.setMetricDependencies(java.util.List.of(new MetricDependency("account.age", 1)));
        rv.setStatus(RuleVersionStatus.ACTIVE); rv.setPublishedBy("seed"); rv.setPublishedAt(LocalDateTime.now());
        rv.setCreatedAt(LocalDateTime.now());
        ruleVersionMapper.insert(rv);

        rd.setCurrentVersion(rv.getId());
        ruleDefinitionMapper.updateById(rd);
    }

    @Test
    void exportSceneThenImportToAnotherTenant_reconstructsBothRulesAsDraft() {
        Long sceneId = seedTwoPublishedRules();

        RuleBundle bundle = ruleBundleService.export(String.valueOf(SRC_TENANT), null, sceneId);
        assertThat(bundle.rules()).hasSize(2);
        assertThat(bundle.scenes()).hasSize(1);
        assertThat(bundle.metricDefinitions()).hasSize(1);    // 去重
        assertThat(bundle.decisionDefinitions()).hasSize(1);  // 去重
        assertThat(bundle.actionTypeManifest()).containsExactly("BLOCK_TRANSACTION");

        RuleImportResult result = ruleBundleService.importBundle(String.valueOf(DST_TENANT), bundle, "dev");

        assertThat(result.rules()).hasSize(2);
        assertThat(result.rules()).allMatch(ir -> !ir.ruleAlreadyExisted() && ir.version() == 1L);
        assertThat(result.scenesCreated()).containsExactly("risk.transfer");
        assertThat(result.metricsCreated()).containsExactly("account.age");
        assertThat(result.decisionsCreated()).containsExactly("BLOCK");

        // 目标租户下依赖与规则均落库，AST 原文无损、状态 DRAFT
        SceneDef dstScene = sceneMapper.selectOne(new LambdaQueryWrapper<SceneDef>()
                .eq(SceneDef::getTenantId, DST_TENANT).eq(SceneDef::getCode, "risk.transfer"));
        assertThat(dstScene).isNotNull();
        long draftCount = ruleVersionMapper.selectCount(new LambdaQueryWrapper<RuleVersion>()
                .eq(RuleVersion::getStatus, RuleVersionStatus.DRAFT)
                .in(RuleVersion::getRuleDefinitionId,
                        result.rules().stream().map(RuleImportResult.ImportedRule::ruleDefinitionId).toList()));
        assertThat(draftCount).isEqualTo(2);
        RuleVersion anyDraft = ruleVersionMapper.selectById(result.rules().getFirst().ruleVersionId());
        // typed 列经 MySQL JSON 往返后反序列化回 AndNode，验证 AST 内容无损搬运
        assertThat(anyDraft.getConditionAst()).isInstanceOf(AndNode.class);
        assertThat(((AndNode) anyDraft.getConditionAst()).children()).isEmpty();
    }

    @Test
    void reimportSameBundle_appendsSecondDraftVersionPerRule() {
        Long sceneId = seedTwoPublishedRules();
        RuleBundle bundle = ruleBundleService.export(String.valueOf(SRC_TENANT), null, sceneId);

        RuleImportResult first = ruleBundleService.importBundle(String.valueOf(DST_TENANT), bundle, "dev");
        RuleImportResult second = ruleBundleService.importBundle(String.valueOf(DST_TENANT), bundle, "dev");

        assertThat(first.rules()).allMatch(ir -> !ir.ruleAlreadyExisted() && ir.version() == 1L);
        assertThat(second.rules()).allMatch(ir -> ir.ruleAlreadyExisted() && ir.version() == 2L);
        assertThat(second.scenesSkippedExisting()).containsExactly("risk.transfer");
        assertThat(second.metricsSkippedExisting()).containsExactly("account.age");
        assertThat(second.decisionsSkippedExisting()).containsExactly("BLOCK");
    }
}
