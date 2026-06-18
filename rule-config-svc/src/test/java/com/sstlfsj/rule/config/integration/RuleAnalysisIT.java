package com.sstlfsj.rule.config.integration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sstlfsj.rule.config.api.dto.DraftCreatedResult;
import com.sstlfsj.rule.config.api.dto.RuleContent;
import com.sstlfsj.rule.config.api.service.ConfigService;
import com.sstlfsj.rule.config.api.service.DecisionService;
import com.sstlfsj.rule.config.api.service.MetricWriteService;
import com.sstlfsj.rule.config.api.service.MetricWriteService.MetricWriteCommand;
import com.sstlfsj.rule.config.api.service.RuleAnalysisService;
import com.sstlfsj.rule.config.api.service.SceneService;
import com.sstlfsj.rule.config.internal.domain.DecisionDefinition;
import com.sstlfsj.rule.config.internal.domain.MetricDefinition;
import com.sstlfsj.rule.config.internal.domain.RuleDefinition;
import com.sstlfsj.rule.config.internal.domain.RuleVersion;
import com.sstlfsj.rule.config.internal.domain.SceneDef;
import com.sstlfsj.rule.config.internal.repository.DecisionDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.MetricDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.RuleDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.RuleVersionMapper;
import com.sstlfsj.rule.config.internal.repository.SceneMapper;
import com.sstlfsj.rule.kernel.api.analysis.RuleSetAnalysisReport;
import com.sstlfsj.rule.kernel.api.analysis.Severity;
import com.sstlfsj.rule.kernel.api.model.ConditionParams;
import com.sstlfsj.rule.kernel.api.model.ConditionTypes;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot.DecisionBinding;
import com.sstlfsj.rule.kernel.api.model.ValueRef;
import com.sstlfsj.rule.kernel.api.model.ast.AndNode;
import com.sstlfsj.rule.kernel.api.model.ast.AstNode;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * B31 规则集静态分析真落库集成测试：经真实写路径（scene/metric/decision 写服务 + createDraft + publish）
 * 把若干条带有「死规则 / 重叠 / 矛盾」关系的 AST_BOOLEAN 规则发布为 ACTIVE，使 conditionAst 经真实
 * Jackson TypeHandler 往返 MySQL，再调 {@link RuleAnalysisService#analyze} 验证报告。
 *
 * <p>单测以 mock mapper 直接喂 typed AstNode，覆盖不到「真 DB → TypeHandler → typed AstNode → 分析器」
 * 这一段;本测试填补该缺口——验证持久化的 AST 能正确反序列化并贯通分析器。
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class RuleAnalysisIT {

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
    private static final String SCENE = "risk.analysis";
    private static final String EVENT = "transfer";
    private static final String ACTOR = "dev";

    @Autowired RuleAnalysisService ruleAnalysisService;
    @Autowired SceneService sceneService;
    @Autowired MetricWriteService metricWriteService;
    @Autowired DecisionService decisionService;
    @Autowired ConfigService configService;

    @Autowired RuleVersionMapper ruleVersionMapper;
    @Autowired RuleDefinitionMapper ruleDefinitionMapper;
    @Autowired MetricDefinitionMapper metricDefinitionMapper;
    @Autowired DecisionDefinitionMapper decisionDefinitionMapper;
    @Autowired SceneMapper sceneMapper;

    @BeforeEach
    void clean() {
        // FK 序清理：version → definition → metric → decision → scene
        ruleVersionMapper.delete(new LambdaQueryWrapper<RuleVersion>().isNotNull(RuleVersion::getId));
        ruleDefinitionMapper.delete(new LambdaQueryWrapper<RuleDefinition>().isNotNull(RuleDefinition::getId));
        metricDefinitionMapper.delete(new LambdaQueryWrapper<MetricDefinition>().isNotNull(MetricDefinition::getId));
        decisionDefinitionMapper.delete(new LambdaQueryWrapper<DecisionDefinition>().isNotNull(DecisionDefinition::getId));
        sceneMapper.delete(new LambdaQueryWrapper<SceneDef>().isNotNull(SceneDef::getId));
    }

    /** 顶层 AND-of-Condition 的单条数值比较条件（GT/LT 等），经 CubeProjector 可投影。 */
    private static ConditionNode cmp(String type, String metric, Object threshold) {
        return new ConditionNode(type, metric, null,
                Map.of(ConditionParams.THRESHOLD, threshold), 0.0, null, ValueRef.METRIC);
    }

    private static AstNode flatAnd(ConditionNode... conds) {
        return new AndNode(List.of(conds), null, null);
    }

    /** 经写服务造一个 ACTIVE metric（ATTRIBUTE 源，AST 引用的 metric 须 ACTIVE 才能发布）。 */
    private void createMetric(String code) {
        metricWriteService.create(TENANT, code,
                new MetricWriteCommand(code, "ATTRIBUTE", "LONG", Map.of(), null, true), ACTOR);
    }

    /**
     * 经真实写路径建草稿并发布为 ACTIVE：conditionAst 经 TypeHandler 落库 + 读回。
     *
     * <p>注意:草稿期 binding priority 是占位,发布(freezeDecisionBindings)时由 decision_definition.priority
     * 回填——故规则的有效优先级取自其引用 decision 的优先级,而非 binding 入参。死规则判定靠"高优先级决策覆盖低优先级"。
     */
    private void publishRule(String code, AstNode ast, String decisionCode) {
        DraftCreatedResult draft = configService.createDraft(TENANT, SCENE, code,
                new RuleContent(code, "AST_BOOLEAN", ast,
                        List.of(new DecisionBinding(decisionCode, 0)),
                        List.of(), List.of(EVENT), null),
                ACTOR);
        configService.publish(TENANT, draft.ruleDefinitionId(), ACTOR);
    }

    @Test
    void findActiveWithDecisionByRuleDefIds_readsDecisionBindingsFromDb() {
        // 回归守卫：findActiveByRuleDefIds 的部分列投影不含 decision_bindings，真 DB 上会回填 null；
        // 此处经真实写路径发布带 decision binding 的规则，验证专用投影方法 findActiveWithDecisionByRuleDefIds
        // 真能读出 decisionBindings（非 null），守住 DecisionServiceImpl 反向血缘/计数不踩投影坑。
        sceneService.createScene(TENANT, SCENE, "风控分析场景", null,
                "PUSH", "USER", List.of(EVENT), null, null, ACTOR);
        createMetric("amount");
        decisionService.create(TENANT, "BLOCK", "拦截", 100, null, ACTOR);
        publishRule("R_block", flatAnd(cmp(ConditionTypes.GT, "amount", 1000)), "BLOCK");

        Long ruleDefId = ruleDefinitionMapper.findBySceneAndCode(
                TENANT, sceneMapper.findByCode(TENANT, SCENE).getId(), "R_block").getId();
        List<RuleVersion> active = ruleVersionMapper.findActiveWithDecisionByRuleDefIds(List.of(ruleDefId));

        assertThat(active).hasSize(1);
        assertThat(active.get(0).getDecisionBindings())
                .extracting(DecisionBinding::decisionCode)
                .containsExactly("BLOCK");
    }

    @Test
    void persistedAst_flowsThroughAnalyzer_surfacesDeadOverlapAndIncoherence() {
        // scene：createScene 固定 HIGHEST_PRIORITY 策略（严格优先级 → 死规则判定生效）
        sceneService.createScene(TENANT, SCENE, "风控分析场景", null,
                "PUSH", "USER", List.of(EVENT), null, null, ACTOR);
        createMetric("amount");
        createMetric("score");
        createMetric("age");
        // 决策码须先存在，否则 createDraft 期 resolveAndValidate 抛 DECISION_CODE_NOT_FOUND。
        // 规则有效优先级 = 其引用 decision 的优先级（发布期回填）：BLOCK(100) > REVIEW(50) > ALLOW(10)。
        decisionService.create(TENANT, "BLOCK", "拦截", 100, null, ACTOR);
        decisionService.create(TENANT, "REVIEW", "复核", 50, null, ACTOR);
        decisionService.create(TENANT, "ALLOW", "放行", 10, null, ACTOR);

        // 死规则：amount>1000(BLOCK,prio100) 完全覆盖 amount>5000(REVIEW,prio50) 且严格更高优先级 → R_narrow 死。
        // 异决策 + 不同优先级 → HIGHEST_PRIORITY 下高优先级确定性胜出，不构成冲突。
        publishRule("R_wide", flatAnd(cmp(ConditionTypes.GT, "amount", 1000)), "BLOCK");
        publishRule("R_narrow", flatAnd(cmp(ConditionTypes.GT, "amount", 5000)), "REVIEW");
        // 重叠(INFO)：score>10 与 score>20 区间相交、同决策(REVIEW=同优先级) → 仅重叠，不冲突不死。
        publishRule("R_overlap_a", flatAnd(cmp(ConditionTypes.GT, "score", 10)), "REVIEW");
        publishRule("R_overlap_b", flatAnd(cmp(ConditionTypes.GT, "score", 20)), "REVIEW");
        // 矛盾(ERROR)：单规则 age>30 AND age<10 同维度取交为空 → 永不命中。
        publishRule("R_incoherent",
                flatAnd(cmp(ConditionTypes.GT, "age", 30), cmp(ConditionTypes.LT, "age", 10)), "ALLOW");

        // 真落库自检：5 条规则的 ACTIVE 版本 conditionAst 经 TypeHandler 读回为 typed AstNode（非 String/Map）
        RuleVersion wideActive = ruleVersionMapper.findActiveVersion(
                ruleDefinitionMapper.findBySceneAndCode(TENANT, sceneMapper.findByCode(TENANT, SCENE).getId(), "R_wide").getId());
        assertThat(wideActive.getConditionAst()).isInstanceOf(AndNode.class);

        RuleSetAnalysisReport report = ruleAnalysisService.analyze(TENANT, SCENE);

        assertThat(report.sceneCode()).isEqualTo(SCENE);

        // 死规则：R_narrow 被 R_wide 覆盖（WARN）
        assertThat(report.deadRules())
                .extracting(f -> f.deadRuleCode(), f -> f.coveredByRuleCode(), f -> f.severity())
                .contains(tuple("R_narrow", "R_wide", Severity.WARN));

        // 重叠：R_overlap_a 与 R_overlap_b 同决策相交（INFO）
        assertThat(report.overlaps())
                .extracting(f -> f.locA(), f -> f.locB(), f -> f.severity())
                .contains(tuple("R_overlap_a", "R_overlap_b", Severity.INFO));

        // 矛盾：R_incoherent 永不命中（ERROR）
        assertThat(report.incoherences())
                .extracting(f -> f.ruleCode(), f -> f.severity())
                .contains(tuple("R_incoherent", Severity.ERROR));
    }
}
