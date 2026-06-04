package com.sstlfsj.rule.config.internal.service;

import com.sstlfsj.rule.config.internal.domain.AuditLog;
import com.sstlfsj.rule.config.internal.domain.SceneDef;
import com.sstlfsj.rule.config.internal.domain.ScenePayloadSchemaHistory;
import com.sstlfsj.rule.config.api.event.SceneChangedEvent;
import com.sstlfsj.rule.config.internal.repository.AuditLogMapper;
import com.sstlfsj.rule.config.internal.repository.SceneMapper;
import com.sstlfsj.rule.config.internal.repository.ScenePayloadSchemaHistoryMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SceneServiceImplTest {

    @Mock SceneMapper sceneMapper;
    @Mock AuditLogMapper auditLogMapper;
    @Mock ScenePayloadSchemaHistoryMapper schemaHistoryMapper;
    @Mock ApplicationEventPublisher eventPublisher;

    SceneServiceImpl sceneService;

    @BeforeEach
    void setUp() {
        // objectMapper 内部 new ObjectMapper()，不再需要外部传入
        sceneService = new SceneServiceImpl(sceneMapper, auditLogMapper,
                schemaHistoryMapper, eventPublisher);
    }

    @Test
    void createScene_insertsSceneAndWritesAuditLog() {
        when(sceneMapper.insert((SceneDef) any())).thenReturn(1);
        when(auditLogMapper.insert((AuditLog) any())).thenReturn(1);

        // 新签名：10 参数
        sceneService.createScene("1", "PAYMENT", "支付场景",
                null, null, null, null, null, null, "actor1");

        ArgumentCaptor<SceneDef> sceneCaptor = ArgumentCaptor.forClass(SceneDef.class);
        verify(sceneMapper).insert(sceneCaptor.capture());
        assertThat(sceneCaptor.getValue().getCode()).isEqualTo("PAYMENT");
        assertThat(sceneCaptor.getValue().getStatus()).isEqualTo("ACTIVE");
        verify(auditLogMapper).insert((AuditLog) any());
    }

    @Test
    void createScene_withPayloadSchema_持久化所有D13字段() {
        // doAnswer 回填 id，模拟 MyBatis-Plus insert 后对实体赋值的行为
        doAnswer(invocation -> {
            SceneDef arg = invocation.getArgument(0);
            arg.setId(100L);
            return 1;
        }).when(sceneMapper).insert((SceneDef) any());
        when(auditLogMapper.insert((AuditLog) any())).thenReturn(1);
        when(schemaHistoryMapper.insert((ScenePayloadSchemaHistory) any())).thenReturn(1);

        sceneService.createScene("1", "PAYMENT", "支付场景",
                "支付业务场景", "PUSH", "USER",
                "[\"payment.initiated\"]",
                "[{\"name\":\"amount\",\"type\":\"NUMBER\",\"required\":true}]",
                "{\"timezone\":\"Asia/Shanghai\"}", "actor1");

        ArgumentCaptor<SceneDef> sceneCaptor = ArgumentCaptor.forClass(SceneDef.class);
        verify(sceneMapper).insert(sceneCaptor.capture());
        SceneDef saved = sceneCaptor.getValue();
        assertThat(saved.getEventTypes()).isEqualTo("[\"payment.initiated\"]");
        assertThat(saved.getPayloadSchema()).contains("amount");
        assertThat(saved.getDefaultParams()).contains("Asia/Shanghai");
        assertThat(saved.getPayloadSchemaVersion()).isEqualTo(1);

        // 有 payloadSchema 时应写入初始历史快照，且 sceneId 与插入后回填的 id 一致
        ArgumentCaptor<ScenePayloadSchemaHistory> histCaptor =
                ArgumentCaptor.forClass(ScenePayloadSchemaHistory.class);
        verify(schemaHistoryMapper).insert(histCaptor.capture());
        assertThat(histCaptor.getValue().getVersion()).isEqualTo(1);
        assertThat(histCaptor.getValue().getSceneId()).isEqualTo(100L);
    }

    @Test
    void createScene_withoutPayloadSchema_不写历史快照() {
        when(sceneMapper.insert((SceneDef) any())).thenReturn(1);
        when(auditLogMapper.insert((AuditLog) any())).thenReturn(1);

        sceneService.createScene("1", "PAYMENT", "支付场景",
                null, null, null, null, null, null, "actor1");

        verify(sceneMapper).insert((SceneDef) any());
        verify(schemaHistoryMapper, never()).insert((ScenePayloadSchemaHistory) any());
    }

    @Test
    void disableScene_updatesStatusAndPublishesEvent() {
        SceneDef scene = new SceneDef();
        scene.setId(5L);
        scene.setTenantId(1L);
        scene.setCode("PAYMENT");
        scene.setStatus("ACTIVE");
        when(sceneMapper.selectOne(any())).thenReturn(scene);
        when(sceneMapper.updateById((SceneDef) any())).thenReturn(1);
        when(auditLogMapper.insert((AuditLog) any())).thenReturn(1);

        sceneService.disableScene("1", "PAYMENT", "actor1");

        ArgumentCaptor<SceneDef> sceneCaptor = ArgumentCaptor.forClass(SceneDef.class);
        verify(sceneMapper).updateById(sceneCaptor.capture());
        assertThat(sceneCaptor.getValue().getStatus()).isEqualTo("DISABLED");

        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue()).isInstanceOf(SceneChangedEvent.class);
        SceneChangedEvent event = (SceneChangedEvent) eventCaptor.getValue();
        assertThat(event.sceneCode()).isEqualTo("PAYMENT");
        assertThat(event.active()).isFalse();
    }

    @Test
    void updateScene_payloadSchema变更_快照旧版本并自增版本号() {
        SceneDef existing = new SceneDef();
        existing.setId(10L);
        existing.setTenantId(1L);
        existing.setCode("PAYMENT");
        existing.setPayloadSchema("[{\"name\":\"amount\",\"type\":\"NUMBER\",\"required\":true}]");
        existing.setPayloadSchemaVersion(1);
        existing.setEventTypes("[]");
        when(sceneMapper.selectOne(any())).thenReturn(existing);
        when(sceneMapper.updateById((SceneDef) any())).thenReturn(1);
        when(auditLogMapper.insert((AuditLog) any())).thenReturn(1);
        when(schemaHistoryMapper.insert((ScenePayloadSchemaHistory) any())).thenReturn(1);

        String newSchema = "[{\"name\":\"amount\",\"type\":\"NUMBER\",\"required\":true},"
                + "{\"name\":\"currency\",\"type\":\"STRING\",\"required\":true}]";
        sceneService.updateScene("1", "PAYMENT", null, null, newSchema, null, "actor1");

        // 旧版本应写入历史
        ArgumentCaptor<ScenePayloadSchemaHistory> histCaptor =
                ArgumentCaptor.forClass(ScenePayloadSchemaHistory.class);
        verify(schemaHistoryMapper).insert(histCaptor.capture());
        assertThat(histCaptor.getValue().getVersion()).isEqualTo(1);
        assertThat(histCaptor.getValue().getSchemaJson()).contains("amount");

        // scene 版本号自增为 2
        ArgumentCaptor<SceneDef> sceneCaptor = ArgumentCaptor.forClass(SceneDef.class);
        verify(sceneMapper).updateById(sceneCaptor.capture());
        assertThat(sceneCaptor.getValue().getPayloadSchemaVersion()).isEqualTo(2);
        assertThat(sceneCaptor.getValue().getPayloadSchema()).contains("currency");
    }

    @Test
    void updateScene_payloadSchema未变更_不写历史不变版本号() {
        SceneDef existing = new SceneDef();
        existing.setId(10L);
        existing.setTenantId(1L);
        existing.setCode("PAYMENT");
        existing.setPayloadSchema("[{\"name\":\"amount\",\"type\":\"NUMBER\"}]");
        existing.setPayloadSchemaVersion(2);
        existing.setEventTypes("[\"payment.initiated\"]");
        when(sceneMapper.selectOne(any())).thenReturn(existing);
        when(sceneMapper.updateById((SceneDef) any())).thenReturn(1);
        when(auditLogMapper.insert((AuditLog) any())).thenReturn(1);

        // 传入与现有相同的 payloadSchema
        sceneService.updateScene("1", "PAYMENT", "新名称", null,
                "[{\"name\":\"amount\",\"type\":\"NUMBER\"}]", null, "actor1");

        verify(schemaHistoryMapper, never()).insert((ScenePayloadSchemaHistory) any());
        ArgumentCaptor<SceneDef> sceneCaptor = ArgumentCaptor.forClass(SceneDef.class);
        verify(sceneMapper).updateById(sceneCaptor.capture());
        assertThat(sceneCaptor.getValue().getPayloadSchemaVersion()).isEqualTo(2);
        assertThat(sceneCaptor.getValue().getName()).isEqualTo("新名称");
    }

    @Test
    void getScene_返回完整SceneDetailDto() {
        SceneDef scene = new SceneDef();
        scene.setId(5L);
        scene.setTenantId(1L);
        scene.setCode("PAYMENT");
        scene.setName("支付场景");
        scene.setDominantMode("PUSH");
        scene.setSubjectType("USER");
        scene.setEventTypes("[\"payment.initiated\"]");
        scene.setPayloadSchema("[{\"name\":\"amount\",\"type\":\"NUMBER\",\"required\":true}]");
        scene.setDefaultParams("{\"timezone\":\"Asia/Shanghai\"}");
        scene.setPayloadSchemaVersion(2);
        scene.setStatus("ACTIVE");
        when(sceneMapper.selectOne(any())).thenReturn(scene);

        com.sstlfsj.rule.config.api.dto.SceneDetailDto dto =
                sceneService.getScene("1", "PAYMENT");

        assertThat(dto.sceneCode()).isEqualTo("PAYMENT");
        assertThat(dto.payloadSchemaVersion()).isEqualTo(2);
        assertThat(dto.eventTypes()).containsExactly("payment.initiated");
        assertThat(dto.payloadSchema()).hasSize(1);
        assertThat(dto.payloadSchema().get(0).name()).isEqualTo("amount");
        assertThat(dto.defaultParams()).containsEntry("timezone", "Asia/Shanghai");
    }

    @Test
    void getScene_sceneNotFound_抛IllegalArgument() {
        when(sceneMapper.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> sceneService.getScene("1", "NOT_EXIST"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Scene 不存在");
    }
}
