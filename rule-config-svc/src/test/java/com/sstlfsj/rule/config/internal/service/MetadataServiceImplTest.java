package com.sstlfsj.rule.config.internal.service;

import com.sstlfsj.rule.config.api.service.MetadataService;
import com.sstlfsj.rule.config.internal.domain.MetricDefinition;
import com.sstlfsj.rule.config.internal.domain.RuleDefinition;
import com.sstlfsj.rule.config.internal.domain.RuleVersion;
import com.sstlfsj.rule.config.internal.domain.SceneDef;
import com.sstlfsj.rule.config.internal.repository.MetricDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.RuleDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.RuleVersionMapper;
import com.sstlfsj.rule.config.internal.repository.SceneMapper;
import com.sstlfsj.rule.kernel.api.model.MetricDescriptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MetadataServiceImplTest {

    @Mock SceneMapper sceneMapper;
    @Mock MetricDefinitionMapper metricDefinitionMapper;
    @Mock RuleDefinitionMapper ruleDefinitionMapper;
    @Mock RuleVersionMapper ruleVersionMapper;
    @InjectMocks MetadataServiceImpl metadataService;

    @Test
    void getSceneMetadata_returnsAvailableMetrics() {
        SceneDef scene = new SceneDef();
        scene.setId(5L);
        scene.setTenantId(1L);
        scene.setCode("PAYMENT");
        when(sceneMapper.findByCode(any(), any())).thenReturn(scene);

        MetricDefinition metric = new MetricDefinition();
        metric.setMetricCode("user.age");
        metric.setName("用户年龄");
        metric.setDataType("LONG");
        metric.setSourceType("ATTRIBUTE");
        metric.setAllowProvided(false);
        when(metricDefinitionMapper.findActiveByTenant(any())).thenReturn(List.of(metric));

        MetadataService.MetadataResponse response = metadataService.getSceneMetadata("1", "PAYMENT");

        assertThat(response.availableMetrics()).hasSize(1);
        assertThat(response.availableMetrics().get(0).metricCode()).isEqualTo("user.age");
        // conditionType / actionType v1 返回空列表
        assertThat(response.conditionTypes()).isEmpty();
        assertThat(response.actionTypes()).isEmpty();
    }

    @Test
    void getProvidedMetrics_只返回allowProvided为true的指标() {
        SceneDef scene = new SceneDef();
        scene.setId(5L);
        scene.setTenantId(1L);
        scene.setCode("PAYMENT");
        when(sceneMapper.findByCode(any(), any())).thenReturn(scene);

        MetricDefinition provided = new MetricDefinition();
        provided.setMetricCode("user.kyc.level");
        provided.setName("KYC等级");
        provided.setDataType("LONG");
        provided.setSourceType("ATTRIBUTE");
        provided.setAllowProvided(true);

        MetricDefinition notProvided = new MetricDefinition();
        notProvided.setMetricCode("account.balance");
        notProvided.setName("余额");
        notProvided.setDataType("DECIMAL");
        notProvided.setSourceType("SQL_AGGREGATE");
        notProvided.setAllowProvided(false);

        when(metricDefinitionMapper.findActiveByTenant(any())).thenReturn(List.of(provided, notProvided));

        MetadataService.ProvidedMetricsResponse response =
                metadataService.getProvidedMetrics("1", "PAYMENT");

        assertThat(response.metrics()).hasSize(1);
        assertThat(response.metrics().get(0).metricCode()).isEqualTo("user.kyc.level");
        assertThat(response.metrics().get(0).allowProvided()).isTrue();
    }

    @Test
    void getProvidedMetrics_无allowProvided指标时返回空列表() {
        SceneDef scene = new SceneDef();
        scene.setId(5L);
        scene.setTenantId(1L);
        scene.setCode("PAYMENT");
        when(sceneMapper.findByCode(any(), any())).thenReturn(scene);

        MetricDefinition notProvided = new MetricDefinition();
        notProvided.setMetricCode("account.balance");
        notProvided.setName("余额");
        notProvided.setDataType("DECIMAL");
        notProvided.setSourceType("SQL_AGGREGATE");
        notProvided.setAllowProvided(false);

        when(metricDefinitionMapper.findActiveByTenant(any())).thenReturn(List.of(notProvided));

        MetadataService.ProvidedMetricsResponse response =
                metadataService.getProvidedMetrics("1", "PAYMENT");

        assertThat(response.metrics()).isEmpty();
    }

    @Test
    void listMetricDefinitions_mapsRowToDescriptor_withParamsAndDataType() {
        MetricDefinition row = new MetricDefinition();
        row.setMetricCode("account.balance");
        row.setName("余额");
        row.setSourceType("SQL_AGGREGATE");
        row.setDataType("DECIMAL");
        row.setAllowProvided(false);
        row.setCacheTtlSeconds(60);
        row.setParams("{\"window\":\"30d\"}");
        when(metricDefinitionMapper.findActiveByTenant(any())).thenReturn(List.of(row));

        MetadataServiceImpl service = new MetadataServiceImpl(
                sceneMapper, metricDefinitionMapper, ruleDefinitionMapper, ruleVersionMapper,
                new tools.jackson.databind.ObjectMapper());

        List<MetricDescriptor> defs = service.listMetricDefinitions("1", List.of());

        assertThat(defs).hasSize(1);
        MetricDescriptor d = defs.get(0);
        assertThat(d.metricCode()).isEqualTo("account.balance");
        assertThat(d.sourceType()).isEqualTo("SQL_AGGREGATE");
        assertThat(d.allowProvided()).isFalse();
        assertThat(d.cacheTtlSeconds()).isEqualTo(60);
        assertThat(d.params()).containsEntry("window", "30d");
        assertThat(d.params()).containsEntry("dataType", "DECIMAL");
    }

    @Test
    void listMetricDefinitions_nullCacheTtl_defaultsToZero() {
        MetricDefinition row = new MetricDefinition();
        row.setMetricCode("user.age");
        row.setSourceType("ATTRIBUTE");
        row.setDataType("LONG");
        row.setAllowProvided(true);
        row.setCacheTtlSeconds(null);
        row.setParams(null);
        when(metricDefinitionMapper.findActiveByTenant(any())).thenReturn(List.of(row));

        MetadataServiceImpl service = new MetadataServiceImpl(
                sceneMapper, metricDefinitionMapper, ruleDefinitionMapper, ruleVersionMapper,
                new tools.jackson.databind.ObjectMapper());

        List<MetricDescriptor> defs = service.listMetricDefinitions("1", List.of());
        assertThat(defs.get(0).cacheTtlSeconds()).isZero();
        assertThat(defs.get(0).params()).containsEntry("dataType", "LONG");
    }

    @Test
    void listMetricDefinitions_withScenes_filtersToMetricDependencyUnion() {
        // scenes=["fraud"] → scene id 5 → ruleDef id 11 → ACTIVE rule_version 依赖对象数组格式 risk.score v1
        SceneDef scene = new SceneDef();
        scene.setId(5L);
        scene.setTenantId(1L);
        scene.setCode("fraud");
        when(sceneMapper.findByCodes(any(), any())).thenReturn(List.of(scene));
        RuleDefinition def = new RuleDefinition();
        def.setId(11L);
        def.setTenantId(1L);
        def.setSceneId(5L);
        when(ruleDefinitionMapper.findByTenantAndSceneIds(any(), any())).thenReturn(List.of(def));
        RuleVersion rv = new RuleVersion();
        rv.setRuleDefinitionId(11L);
        rv.setStatus("ACTIVE");
        rv.setMetricDependencies("[{\"metricCode\":\"risk.score\",\"metricVersion\":1}]");
        when(ruleVersionMapper.findActiveByRuleDefIds(any())).thenReturn(List.of(rv));

        // DECLARED 分支按精确 (code,version) 查 selectOne，返回 risk.score 定义
        MetricDefinition riskScore = new MetricDefinition();
        riskScore.setMetricCode("risk.score");
        riskScore.setVersion(1);
        riskScore.setSourceType("SQL_AGGREGATE");
        riskScore.setDataType("LONG");
        riskScore.setAllowProvided(false);
        riskScore.setStatus("ACTIVE");
        when(metricDefinitionMapper.findByCodeAndVersion(any(), any(), any())).thenReturn(riskScore);

        MetadataServiceImpl service = new MetadataServiceImpl(
                sceneMapper, metricDefinitionMapper, ruleDefinitionMapper, ruleVersionMapper,
                new tools.jackson.databind.ObjectMapper());

        List<MetricDescriptor> defs = service.listMetricDefinitions("1", List.of("fraud"));

        // 仅返回并集内的 risk.score；account.balance 未被引用故不下发
        assertThat(defs).extracting(MetricDescriptor::metricCode).containsExactly("risk.score");
    }

    @Test
    void listMetricDefinitions_withScenes_noMetricDeps_returnsEmpty() {
        SceneDef scene = new SceneDef();
        scene.setId(5L);
        scene.setTenantId(1L);
        scene.setCode("fraud");
        when(sceneMapper.findByCodes(any(), any())).thenReturn(List.of(scene));
        RuleDefinition def = new RuleDefinition();
        def.setId(11L);
        def.setTenantId(1L);
        def.setSceneId(5L);
        when(ruleDefinitionMapper.findByTenantAndSceneIds(any(), any())).thenReturn(List.of(def));
        RuleVersion rv = new RuleVersion();
        rv.setRuleDefinitionId(11L);
        rv.setStatus("ACTIVE");
        rv.setMetricDependencies("[]");   // 规则不引用任何 metric
        when(ruleVersionMapper.findActiveByRuleDefIds(any())).thenReturn(List.of(rv));

        MetadataServiceImpl service = new MetadataServiceImpl(
                sceneMapper, metricDefinitionMapper, ruleDefinitionMapper, ruleVersionMapper,
                new tools.jackson.databind.ObjectMapper());

        // scenes 给了但规则无 metric 依赖 → 空列表（收紧语义，区别于旧的全量返回）
        assertThat(service.listMetricDefinitions("1", List.of("fraud"))).isEmpty();
    }

    @Test
    void listMetricDefinitions_unknownScene_returnsEmpty() {
        when(sceneMapper.findByCodes(any(), any())).thenReturn(List.of());   // scene code 不存在

        MetadataServiceImpl service = new MetadataServiceImpl(
                sceneMapper, metricDefinitionMapper, ruleDefinitionMapper, ruleVersionMapper,
                new tools.jackson.databind.ObjectMapper());

        assertThat(service.listMetricDefinitions("1", List.of("nope"))).isEmpty();
    }

    /**
     * DECLARED 模式下发须含 SUPERSEDED 旧版定义：
     * risk.score 有 v1(SUPERSEDED) + v2(ACTIVE)，规则绑 metricVersion=1，
     * 断言 listMetricDefinitions 返回的定义含 metricVersion=1 的那一行。
     */
    @Test
    void listMetricDefinitions_declaredMode_includesSupersededVersionReferencedByRule() {
        // risk.score v1(SUPERSEDED)：规则绑定的旧版
        MetricDefinition riskV1 = new MetricDefinition();
        riskV1.setMetricCode("risk.score");
        riskV1.setVersion(1);
        riskV1.setSourceType("SQL_AGGREGATE");
        riskV1.setDataType("LONG");
        riskV1.setAllowProvided(false);
        riskV1.setCacheTtlSeconds(0);
        riskV1.setStatus("SUPERSEDED");

        // scenes=["fraud"] → scene id 5 → ruleDef id 11 → ACTIVE rule_version 绑对象数组 v1
        SceneDef scene = new SceneDef();
        scene.setId(5L);
        scene.setTenantId(1L);
        scene.setCode("fraud");
        when(sceneMapper.findByCodes(any(), any())).thenReturn(List.of(scene));

        RuleDefinition def = new RuleDefinition();
        def.setId(11L);
        def.setTenantId(1L);
        def.setSceneId(5L);
        when(ruleDefinitionMapper.findByTenantAndSceneIds(any(), any())).thenReturn(List.of(def));

        RuleVersion rv = new RuleVersion();
        rv.setRuleDefinitionId(11L);
        rv.setStatus("ACTIVE");
        // 对象数组格式：绑 risk.score v1
        rv.setMetricDependencies("[{\"metricCode\":\"risk.score\",\"metricVersion\":1}]");
        when(ruleVersionMapper.findActiveByRuleDefIds(any())).thenReturn(List.of(rv));

        // DECLARED 分支按精确 (code,version) 查，不限 status（含 SUPERSEDED）
        when(metricDefinitionMapper.findByCodeAndVersion(any(), any(), any())).thenReturn(riskV1);

        MetadataServiceImpl service = new MetadataServiceImpl(
                sceneMapper, metricDefinitionMapper, ruleDefinitionMapper, ruleVersionMapper,
                new tools.jackson.databind.ObjectMapper());

        List<MetricDescriptor> defs = service.listMetricDefinitions("1", List.of("fraud"));

        assertThat(defs).hasSize(1);
        MetricDescriptor d = defs.get(0);
        assertThat(d.metricCode()).isEqualTo("risk.score");
        // 关键断言：下发的版本是 1（SUPERSEDED 旧版），而非当前 ACTIVE 的 v2
        assertThat(d.metricVersion()).isEqualTo(1);
    }

    /**
     * DECLARED 模式：按精确 (code,version) 查不到定义时容错跳过，返回空列表（不抛异常）。
     * 对应 warn 日志分支——定义被物理删除等数据一致性异常场景。
     */
    @Test
    void listMetricDefinitions_declaredMode_missingDefinition_skipsGracefully() {
        SceneDef scene = new SceneDef();
        scene.setId(5L);
        scene.setTenantId(1L);
        scene.setCode("fraud");
        when(sceneMapper.findByCodes(any(), any())).thenReturn(List.of(scene));

        RuleDefinition def = new RuleDefinition();
        def.setId(11L);
        def.setTenantId(1L);
        def.setSceneId(5L);
        when(ruleDefinitionMapper.findByTenantAndSceneIds(any(), any())).thenReturn(List.of(def));

        RuleVersion rv = new RuleVersion();
        rv.setRuleDefinitionId(11L);
        rv.setStatus("ACTIVE");
        rv.setMetricDependencies("[{\"metricCode\":\"ghost.metric\",\"metricVersion\":2}]");
        when(ruleVersionMapper.findActiveByRuleDefIds(any())).thenReturn(List.of(rv));

        // 定义已被物理删除：selectOne 返回 null
        when(metricDefinitionMapper.findByCodeAndVersion(any(), any(), any())).thenReturn(null);

        MetadataServiceImpl service = new MetadataServiceImpl(
                sceneMapper, metricDefinitionMapper, ruleDefinitionMapper, ruleVersionMapper,
                new tools.jackson.databind.ObjectMapper());

        // 容错：跳过缺失定义，不抛异常，返回空列表
        assertThat(service.listMetricDefinitions("1", List.of("fraud"))).isEmpty();
    }
}
