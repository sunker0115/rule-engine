package com.sstlfsj.rule.config.internal.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sstlfsj.rule.config.api.service.MetricWriteService;
import com.sstlfsj.rule.config.api.service.MetricWriteService.MetricWriteCommand;
import com.sstlfsj.rule.config.internal.domain.AuditLog;
import com.sstlfsj.rule.config.internal.domain.MetricDefinition;
import com.sstlfsj.rule.config.internal.repository.AuditLogMapper;
import com.sstlfsj.rule.config.internal.repository.MetricDefinitionMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** MetricWriteServiceImpl 单元测试（mock mapper，不依赖 Spring 容器）。 */
@ExtendWith(MockitoExtension.class)
class MetricWriteServiceImplTest {

    @Mock MetricDefinitionMapper metricDefinitionMapper;
    @Mock AuditLogMapper auditLogMapper;
    @InjectMocks MetricWriteServiceImpl sut;

    private static final Long TENANT = 1L;
    private static final String CODE = "user.age";
    private static final String ACTOR = "dev";

    private MetricWriteCommand cmd() {
        return new MetricWriteCommand("用户年龄", "ATTRIBUTE", "LONG", "{}", 60, false);
    }

    // ── create ────────────────────────────────────────────────────────────────

    @Test
    void create_insertsVersion1ActiveRow() {
        // mapper.insert 回填 id（模拟 MyBatis-Plus AUTO 策略通过 keyProperty 回填）
        doAnswer(inv -> {
            MetricDefinition m = inv.getArgument(0);
            m.setId(100L);
            return 1;
        }).when(metricDefinitionMapper).insert(any(MetricDefinition.class));

        Long id = sut.create(TENANT, CODE, cmd(), ACTOR);

        assertThat(id).isEqualTo(100L);

        // 断言 insert 被调一次，且插入行字段正确
        ArgumentCaptor<MetricDefinition> captor = ArgumentCaptor.forClass(MetricDefinition.class);
        verify(metricDefinitionMapper, times(1)).insert(captor.capture());
        MetricDefinition inserted = captor.getValue();
        assertThat(inserted.getVersion()).isEqualTo(1);
        assertThat(inserted.getStatus()).isEqualTo("ACTIVE");
        assertThat(inserted.getTenantId()).isEqualTo(TENANT);
        assertThat(inserted.getMetricCode()).isEqualTo(CODE);
        assertThat(inserted.getName()).isEqualTo("用户年龄");
        assertThat(inserted.getSourceType()).isEqualTo("ATTRIBUTE");
        assertThat(inserted.getDataType()).isEqualTo("LONG");
        assertThat(inserted.getCreatedBy()).isEqualTo(ACTOR);

        // 断言 audit_log 写入一次
        verify(auditLogMapper, times(1)).insert(any(AuditLog.class));
    }

    // ── update breakingChange=false ───────────────────────────────────────────

    @Test
    void update_nonBreaking_updatesActiveRowInPlace() {
        MetricDefinition active = activeRow(2);
        when(metricDefinitionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(active);

        int version = sut.update(TENANT, CODE, cmd(), false, ACTOR);

        assertThat(version).isEqualTo(2);
        verify(metricDefinitionMapper, times(1)).updateById(active);
        verify(metricDefinitionMapper, never()).insert((MetricDefinition) any());
        assertThat(active.getStatus()).isEqualTo("ACTIVE");
        verify(auditLogMapper, times(1)).insert(any(AuditLog.class));
    }

    // ── update breakingChange=true ────────────────────────────────────────────

    @Test
    void update_breaking_supersedesOldRowAndInsertsNewVersion() {
        MetricDefinition active = activeRow(2);
        when(metricDefinitionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(active);

        doAnswer(inv -> {
            MetricDefinition m = inv.getArgument(0);
            m.setId(200L);
            return 1;
        }).when(metricDefinitionMapper).insert(any(MetricDefinition.class));

        int version = sut.update(TENANT, CODE, cmd(), true, ACTOR);

        assertThat(version).isEqualTo(3);

        // 旧行改为 SUPERSEDED
        verify(metricDefinitionMapper, times(1)).updateById(active);
        assertThat(active.getStatus()).isEqualTo("SUPERSEDED");

        // 新行插入 version=3 ACTIVE
        ArgumentCaptor<MetricDefinition> captor = ArgumentCaptor.forClass(MetricDefinition.class);
        verify(metricDefinitionMapper, times(1)).insert(captor.capture());
        MetricDefinition newRow = captor.getValue();
        assertThat(newRow.getVersion()).isEqualTo(3);
        assertThat(newRow.getStatus()).isEqualTo("ACTIVE");

        verify(auditLogMapper, times(1)).insert(any(AuditLog.class));
    }

    // ── update 无 ACTIVE 行 ───────────────────────────────────────────────────

    @Test
    void update_noActiveRow_throwsIllegalArgumentException() {
        when(metricDefinitionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        assertThatThrownBy(() -> sut.update(TENANT, CODE, cmd(), false, ACTOR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(CODE);
    }

    // ── 辅助 ──────────────────────────────────────────────────────────────────

    private MetricDefinition activeRow(int version) {
        MetricDefinition m = new MetricDefinition();
        m.setId(10L);
        m.setTenantId(TENANT);
        m.setMetricCode(CODE);
        m.setVersion(version);
        m.setName("旧名称");
        m.setSourceType("ATTRIBUTE");
        m.setDataType("LONG");
        m.setStatus("ACTIVE");
        return m;
    }
}
