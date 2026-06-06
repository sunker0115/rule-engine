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
        when(sceneMapper.selectOne(any())).thenReturn(scene);

        MetricDefinition metric = new MetricDefinition();
        metric.setMetricCode("user.age");
        metric.setName("用户年龄");
        metric.setDataType("LONG");
        metric.setSourceType("ATTRIBUTE");
        metric.setAllowProvided(false);
        when(metricDefinitionMapper.selectList(any())).thenReturn(List.of(metric));

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
        when(sceneMapper.selectOne(any())).thenReturn(scene);

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

        when(metricDefinitionMapper.selectList(any())).thenReturn(List.of(provided, notProvided));

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
        when(sceneMapper.selectOne(any())).thenReturn(scene);

        MetricDefinition notProvided = new MetricDefinition();
        notProvided.setMetricCode("account.balance");
        notProvided.setName("余额");
        notProvided.setDataType("DECIMAL");
        notProvided.setSourceType("SQL_AGGREGATE");
        notProvided.setAllowProvided(false);

        when(metricDefinitionMapper.selectList(any())).thenReturn(List.of(notProvided));

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
        when(metricDefinitionMapper.selectList(any())).thenReturn(List.of(row));

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
        when(metricDefinitionMapper.selectList(any())).thenReturn(List.of(row));

        MetadataServiceImpl service = new MetadataServiceImpl(
                sceneMapper, metricDefinitionMapper, ruleDefinitionMapper, ruleVersionMapper,
                new tools.jackson.databind.ObjectMapper());

        List<MetricDescriptor> defs = service.listMetricDefinitions("1", List.of());
        assertThat(defs.get(0).cacheTtlSeconds()).isZero();
        assertThat(defs.get(0).params()).containsEntry("dataType", "LONG");
    }

    @Test
    void listMetricDefinitions_withScenes_filtersToMetricDependencyUnion() {
        // 租户有 2 个 ACTIVE 定义：risk.score / account.balance
        MetricDefinition riskScore = new MetricDefinition();
        riskScore.setMetricCode("risk.score");
        riskScore.setSourceType("SQL_AGGREGATE");
        riskScore.setDataType("LONG");
        riskScore.setAllowProvided(false);
        MetricDefinition balance = new MetricDefinition();
        balance.setMetricCode("account.balance");
        balance.setSourceType("SQL_AGGREGATE");
        balance.setDataType("DECIMAL");
        balance.setAllowProvided(false);
        when(metricDefinitionMapper.selectList(any())).thenReturn(List.of(riskScore, balance));

        // scenes=["fraud"] → scene id 5 → ruleDef id 11 → ACTIVE rule_version 依赖 ["risk.score"]
        SceneDef scene = new SceneDef();
        scene.setId(5L);
        scene.setTenantId(1L);
        scene.setCode("fraud");
        when(sceneMapper.selectList(any())).thenReturn(List.of(scene));
        RuleDefinition def = new RuleDefinition();
        def.setId(11L);
        def.setTenantId(1L);
        def.setSceneId(5L);
        when(ruleDefinitionMapper.selectList(any())).thenReturn(List.of(def));
        RuleVersion rv = new RuleVersion();
        rv.setRuleDefinitionId(11L);
        rv.setStatus("ACTIVE");
        rv.setMetricDependencies("[\"risk.score\"]");
        when(ruleVersionMapper.selectList(any())).thenReturn(List.of(rv));

        MetadataServiceImpl service = new MetadataServiceImpl(
                sceneMapper, metricDefinitionMapper, ruleDefinitionMapper, ruleVersionMapper,
                new tools.jackson.databind.ObjectMapper());

        List<MetricDescriptor> defs = service.listMetricDefinitions("1", List.of("fraud"));

        // 仅返回并集内的 risk.score；account.balance 被过滤
        assertThat(defs).extracting(MetricDescriptor::metricCode).containsExactly("risk.score");
    }

    @Test
    void listMetricDefinitions_withScenes_noMetricDeps_returnsEmpty() {
        MetricDefinition balance = new MetricDefinition();
        balance.setMetricCode("account.balance");
        balance.setSourceType("SQL_AGGREGATE");
        balance.setDataType("DECIMAL");
        balance.setAllowProvided(false);
        when(metricDefinitionMapper.selectList(any())).thenReturn(List.of(balance));

        SceneDef scene = new SceneDef();
        scene.setId(5L);
        scene.setTenantId(1L);
        scene.setCode("fraud");
        when(sceneMapper.selectList(any())).thenReturn(List.of(scene));
        RuleDefinition def = new RuleDefinition();
        def.setId(11L);
        def.setTenantId(1L);
        def.setSceneId(5L);
        when(ruleDefinitionMapper.selectList(any())).thenReturn(List.of(def));
        RuleVersion rv = new RuleVersion();
        rv.setRuleDefinitionId(11L);
        rv.setStatus("ACTIVE");
        rv.setMetricDependencies("[]");   // 规则不引用任何 metric
        when(ruleVersionMapper.selectList(any())).thenReturn(List.of(rv));

        MetadataServiceImpl service = new MetadataServiceImpl(
                sceneMapper, metricDefinitionMapper, ruleDefinitionMapper, ruleVersionMapper,
                new tools.jackson.databind.ObjectMapper());

        // scenes 给了但规则无 metric 依赖 → 空列表（收紧语义，区别于旧的全量返回）
        assertThat(service.listMetricDefinitions("1", List.of("fraud"))).isEmpty();
    }

    @Test
    void listMetricDefinitions_unknownScene_returnsEmpty() {
        MetricDefinition balance = new MetricDefinition();
        balance.setMetricCode("account.balance");
        balance.setSourceType("SQL_AGGREGATE");
        balance.setDataType("DECIMAL");
        balance.setAllowProvided(false);
        when(metricDefinitionMapper.selectList(any())).thenReturn(List.of(balance));
        when(sceneMapper.selectList(any())).thenReturn(List.of());   // scene code 不存在

        MetadataServiceImpl service = new MetadataServiceImpl(
                sceneMapper, metricDefinitionMapper, ruleDefinitionMapper, ruleVersionMapper,
                new tools.jackson.databind.ObjectMapper());

        assertThat(service.listMetricDefinitions("1", List.of("nope"))).isEmpty();
    }
}
