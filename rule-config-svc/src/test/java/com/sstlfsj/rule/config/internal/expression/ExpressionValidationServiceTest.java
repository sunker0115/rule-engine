package com.sstlfsj.rule.config.internal.expression;

import com.sstlfsj.rule.config.api.dto.PayloadFieldSpec;
import com.sstlfsj.rule.config.internal.domain.MetricDefinition;
import com.sstlfsj.rule.config.internal.domain.SceneDef;
import com.sstlfsj.rule.config.internal.repository.MetricDefinitionMapper;
import com.sstlfsj.rule.config.internal.repository.SceneMapper;
import com.sstlfsj.rule.kernel.api.spi.expression.ExpressionCompileException;
import com.sstlfsj.rule.kernel.api.spi.expression.ExpressionEngine;
import com.sstlfsj.rule.kernel.api.spi.expression.ScriptTypeEnv;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ExpressionValidationServiceTest {

    @Mock SceneMapper sceneMapper;
    @Mock MetricDefinitionMapper metricMapper;
    @Mock ExpressionEngine celEngine;
    @Mock ExpressionEngine aviatorEngine;
    @InjectMocks ExpressionValidationService service;

    @Test
    void unknownLang_throwsIllegalArg() {
        when(celEngine.lang()).thenReturn("CEL");
        when(aviatorEngine.lang()).thenReturn("AVIATOR");
        var svc = new ExpressionValidationService(List.of(celEngine, aviatorEngine), sceneMapper, metricMapper);
        assertThatThrownBy(() -> svc.validate(1L, "X", "GROOVY", "1 > 0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("GROOVY");
    }

    @Test
    void sceneNotFound_throwsIllegalArg() {
        when(celEngine.lang()).thenReturn("CEL");
        when(sceneMapper.findByCode(1L, "X")).thenReturn(null);
        var svc = new ExpressionValidationService(List.of(celEngine), sceneMapper, metricMapper);
        assertThatThrownBy(() -> svc.validate(1L, "X", "CEL", "1 > 0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Scene");
    }

    @Test
    void typeCheck_ok_returnsNull() {
        when(celEngine.lang()).thenReturn("CEL");
        when(sceneMapper.findByCode(1L, "S1")).thenReturn(sceneWithSchema());
        when(metricMapper.findActiveByTenant(1L)).thenReturn(List.of(metric("m1", "LONG")));
        var svc = new ExpressionValidationService(List.of(celEngine), sceneMapper, metricMapper);

        assertThat(svc.validate(1L, "S1", "CEL", "metrics.m1 > 0")).isNull();
    }

    @Test
    void typeCheck_error_returnsMessage() {
        when(celEngine.lang()).thenReturn("CEL");
        when(sceneMapper.findByCode(1L, "S1")).thenReturn(sceneWithSchema());
        when(metricMapper.findActiveByTenant(1L)).thenReturn(List.of(metric("m1", "STRING")));
        doThrow(new ExpressionCompileException("CEL 类型检查失败: expected int, found string"))
                .when(celEngine).typeCheck(eq("metrics.m1 > 0"), any(ScriptTypeEnv.class));
        var svc = new ExpressionValidationService(List.of(celEngine), sceneMapper, metricMapper);

        assertThat(svc.validate(1L, "S1", "CEL", "metrics.m1 > 0"))
                .contains("类型检查失败");
    }

    @Test
    void weakEngine_typeCheckIsNoop() {
        when(celEngine.lang()).thenReturn("CEL");
        when(aviatorEngine.lang()).thenReturn("AVIATOR");
        when(sceneMapper.findByCode(1L, "S1")).thenReturn(sceneWithSchema());
        when(metricMapper.findActiveByTenant(1L)).thenReturn(List.of());
        var svc = new ExpressionValidationService(List.of(celEngine, aviatorEngine), sceneMapper, metricMapper);

        assertThat(svc.validate(1L, "S1", "AVIATOR", "any invalid stuff")).isNull();
    }

    private static SceneDef sceneWithSchema() {
        var scene = new SceneDef();
        scene.setPayloadSchema(List.of(
                new PayloadFieldSpec("amount", "NUMBER", false, null, null, null, null, "金额"),
                new PayloadFieldSpec("name", "STRING", false, null, null, null, null, "名称")
        ));
        return scene;
    }

    private static MetricDefinition metric(String code, String dataType) {
        var m = new MetricDefinition();
        m.setMetricCode(code);
        m.setDataType(dataType);
        return m;
    }
}
