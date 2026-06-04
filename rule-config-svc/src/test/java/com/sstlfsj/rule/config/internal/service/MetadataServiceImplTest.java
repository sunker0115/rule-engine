package com.sstlfsj.rule.config.internal.service;

import com.sstlfsj.rule.config.api.service.MetadataService;
import com.sstlfsj.rule.config.internal.domain.MetricDefinition;
import com.sstlfsj.rule.config.internal.domain.SceneDef;
import com.sstlfsj.rule.config.internal.repository.MetricDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.SceneMapper;
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
}
