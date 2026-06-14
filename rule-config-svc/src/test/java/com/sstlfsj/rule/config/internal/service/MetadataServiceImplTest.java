package com.sstlfsj.rule.config.internal.service;

import com.sstlfsj.rule.config.api.service.MetadataService;
import com.sstlfsj.rule.config.internal.domain.MetricDefinition;
import com.sstlfsj.rule.config.internal.domain.MetricStatus;
import com.sstlfsj.rule.config.internal.domain.RuleDefinition;
import com.sstlfsj.rule.config.internal.domain.RuleVersion;
import com.sstlfsj.rule.config.internal.domain.RuleVersionStatus;
import com.sstlfsj.rule.config.internal.domain.SceneDef;
import com.sstlfsj.rule.config.internal.repository.MetricDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.RuleDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.RuleVersionMapper;
import com.sstlfsj.rule.config.internal.repository.SceneMapper;
import com.sstlfsj.rule.kernel.api.model.MetricDependency;
import com.sstlfsj.rule.kernel.api.model.MetricDescriptor;
import com.sstlfsj.rule.kernel.api.model.PayloadDependency;
import com.sstlfsj.rule.kernel.api.operator.OperatorSpec;
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

    private MetadataServiceImpl newService(List<OperatorSpec> customSpecs) {
        return new MetadataServiceImpl(sceneMapper, metricDefinitionMapper,
                ruleDefinitionMapper, ruleVersionMapper, customSpecs, List.of());
    }

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

        MetadataService.MetadataResponse response = newService(List.of()).getSceneMetadata("1", "PAYMENT");

        assertThat(response.availableMetrics()).hasSize(1);
        assertThat(response.availableMetrics().get(0).metricCode()).isEqualTo("user.age");
        // conditionType 来自内置算子目录，非空
        assertThat(response.conditionTypes()).isNotEmpty();
    }

    @Test
    void getSceneMetadata_returnsConditionTypesFromCatalog() {
        SceneDef scene = new SceneDef();
        scene.setId(5L);
        scene.setTenantId(1L);
        scene.setCode("PAYMENT");
        when(sceneMapper.findByCode(any(), any())).thenReturn(scene);
        when(metricDefinitionMapper.findActiveByTenant(any())).thenReturn(List.of());

        MetadataService.MetadataResponse resp = newService(List.of()).getSceneMetadata("1", "PAYMENT");

        assertThat(resp.conditionTypes()).isNotEmpty();
        OperatorSpec gt = resp.conditionTypes().stream()
                .filter(c -> c.code().equals("GT")).findFirst().orElseThrow();
        assertThat(gt.displayName()).isEqualTo("大于");
        assertThat(gt.requiresMetric()).isTrue();
        assertThat(gt.requiredParamKeys()).contains("threshold");
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
        row.setParams(java.util.Map.of("window", "30d"));
        when(metricDefinitionMapper.findActiveByTenant(any())).thenReturn(List.of(row));

        MetadataServiceImpl service = newService(List.of());

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

        MetadataServiceImpl service = newService(List.of());

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
        rv.setStatus(RuleVersionStatus.ACTIVE);
        rv.setMetricDependencies(List.of(new MetricDependency("risk.score", 1)));
        when(ruleVersionMapper.findActiveByRuleDefIds(any())).thenReturn(List.of(rv));

        // DECLARED 分支按精确 (code,version) 查 selectOne，返回 risk.score 定义
        MetricDefinition riskScore = new MetricDefinition();
        riskScore.setMetricCode("risk.score");
        riskScore.setVersion(1);
        riskScore.setSourceType("SQL_AGGREGATE");
        riskScore.setDataType("LONG");
        riskScore.setAllowProvided(false);
        riskScore.setStatus(MetricStatus.ACTIVE);
        when(metricDefinitionMapper.findByCodeAndVersion(any(), any(), any())).thenReturn(riskScore);

        MetadataServiceImpl service = newService(List.of());

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
        rv.setStatus(RuleVersionStatus.ACTIVE);
        rv.setMetricDependencies(List.of());   // 规则不引用任何 metric
        when(ruleVersionMapper.findActiveByRuleDefIds(any())).thenReturn(List.of(rv));

        MetadataServiceImpl service = newService(List.of());

        // scenes 给了但规则无 metric 依赖 → 空列表（收紧语义，区别于旧的全量返回）
        assertThat(service.listMetricDefinitions("1", List.of("fraud"))).isEmpty();
    }

    @Test
    void listMetricDefinitions_unknownScene_returnsEmpty() {
        when(sceneMapper.findByCodes(any(), any())).thenReturn(List.of());   // scene code 不存在

        MetadataServiceImpl service = newService(List.of());

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
        riskV1.setStatus(MetricStatus.SUPERSEDED);

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
        rv.setStatus(RuleVersionStatus.ACTIVE);
        // 对象数组格式：绑 risk.score v1
        rv.setMetricDependencies(List.of(new MetricDependency("risk.score", 1)));
        when(ruleVersionMapper.findActiveByRuleDefIds(any())).thenReturn(List.of(rv));

        // DECLARED 分支按精确 (code,version) 查，不限 status（含 SUPERSEDED）
        when(metricDefinitionMapper.findByCodeAndVersion(any(), any(), any())).thenReturn(riskV1);

        MetadataServiceImpl service = newService(List.of());

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
        rv.setStatus(RuleVersionStatus.ACTIVE);
        rv.setMetricDependencies(List.of(new MetricDependency("ghost.metric", 2)));
        when(ruleVersionMapper.findActiveByRuleDefIds(any())).thenReturn(List.of(rv));

        // 定义已被物理删除：selectOne 返回 null
        when(metricDefinitionMapper.findByCodeAndVersion(any(), any(), any())).thenReturn(null);

        MetadataServiceImpl service = newService(List.of());

        // 容错：跳过缺失定义，不抛异常，返回空列表
        assertThat(service.listMetricDefinitions("1", List.of("fraud"))).isEmpty();
    }

    @Test
    void getInputManifest_unionsPayloadDeps_dedupByName() {
        // demo.login → scene id 5 → ruleDef id 11 → 两条 ACTIVE rule_version 的 payloadDependencies 并集
        SceneDef scene = new SceneDef();
        scene.setId(5L);
        scene.setTenantId(1L);
        scene.setCode("demo.login");
        when(sceneMapper.findByCode(any(), any())).thenReturn(scene);

        RuleDefinition def = new RuleDefinition();
        def.setId(11L);
        def.setTenantId(1L);
        def.setSceneId(5L);
        when(ruleDefinitionMapper.findByTenantAndSceneIds(any(), any())).thenReturn(List.of(def));

        RuleVersion rv1 = new RuleVersion();
        rv1.setRuleDefinitionId(11L);
        rv1.setStatus(RuleVersionStatus.ACTIVE);
        rv1.setTriggerEventTypes(List.of());
        rv1.setPayloadDependencies(List.of(new PayloadDependency("amount", "DECIMAL", true)));

        RuleVersion rv2 = new RuleVersion();
        rv2.setRuleDefinitionId(11L);
        rv2.setStatus(RuleVersionStatus.ACTIVE);
        rv2.setTriggerEventTypes(List.of());
        rv2.setPayloadDependencies(List.of(
                new PayloadDependency("amount", "DECIMAL", true),
                new PayloadDependency("country", "STRING", true)));
        when(ruleVersionMapper.findActiveWithPayloadByRuleDefIds(any())).thenReturn(List.of(rv1, rv2));

        MetadataService.InputManifestResponse resp =
                newService(List.of()).getInputManifest("1", "demo.login", null);

        assertThat(resp.fields())
                .extracting(MetadataService.InputFieldSpec::name)
                .containsExactlyInAnyOrder("amount", "country");
    }

    @Test
    void getSceneMetadata_customSpecBean_appearsInConditionTypes() {
        SceneDef scene = new SceneDef();
        scene.setId(5L);
        scene.setTenantId(1L);
        scene.setCode("PAYMENT");
        when(sceneMapper.findByCode(any(), any())).thenReturn(scene);
        when(metricDefinitionMapper.findActiveByTenant(any())).thenReturn(List.of());

        OperatorSpec custom = OperatorSpec.builder()
                .code("geo.within").displayName("地理围栏")
                .requiredParamKeys(java.util.Set.of("lat", "lng", "radius"))
                .allowedDataTypes(java.util.Set.of())
                .requiresMetric(false).build();

        MetadataService.MetadataResponse resp =
                newService(List.of(custom)).getSceneMetadata("1", "PAYMENT");

        assertThat(resp.conditionTypes()).extracting(OperatorSpec::code).contains("geo.within");
    }

    @Test
    void getSceneMetadata_customSpec_doesNotOverrideBuiltin() {
        SceneDef scene = new SceneDef();
        scene.setId(5L);
        scene.setTenantId(1L);
        scene.setCode("PAYMENT");
        when(sceneMapper.findByCode(any(), any())).thenReturn(scene);
        when(metricDefinitionMapper.findActiveByTenant(any())).thenReturn(List.of());

        OperatorSpec fake = OperatorSpec.builder()
                .code("GT").displayName("fake")
                .requiredParamKeys(java.util.Set.of())
                .allowedDataTypes(java.util.Set.of())
                .requiresMetric(false).build();

        MetadataService.MetadataResponse resp =
                newService(List.of(fake)).getSceneMetadata("1", "PAYMENT");

        // 内置优先：自定义 GT 不覆盖内置 GT
        OperatorSpec gt = resp.conditionTypes().stream()
                .filter(s -> "GT".equals(s.code())).findFirst().orElseThrow();
        assertThat(gt.displayName()).isEqualTo("大于");
    }
}
