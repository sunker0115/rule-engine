package com.sstlfsj.rule.config.internal.service;

import com.sstlfsj.rule.config.api.event.SceneChangedEvent;
import com.sstlfsj.rule.config.api.service.SceneActionBindingService.SceneActionBindingItem;
import com.sstlfsj.rule.config.internal.domain.AuditLog;
import com.sstlfsj.rule.config.internal.domain.SceneActionBindingDef;
import com.sstlfsj.rule.config.internal.domain.SceneDef;
import com.sstlfsj.rule.config.internal.repository.AuditLogMapper;
import com.sstlfsj.rule.config.internal.repository.SceneActionBindingMapper;
import com.sstlfsj.rule.config.internal.repository.SceneMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证 SceneActionBindingServiceImpl 的整组覆盖 reconcile、事件 active 取值与异常。 */
class SceneActionBindingServiceImplTest {

    private SceneMapper sceneMapper;
    private SceneActionBindingMapper bindingMapper;
    private AuditLogMapper auditLogMapper;
    private ApplicationEventPublisher eventPublisher;
    private SceneActionBindingServiceImpl service;

    @BeforeEach
    void setUp() {
        sceneMapper = mock(SceneMapper.class);
        bindingMapper = mock(SceneActionBindingMapper.class);
        auditLogMapper = mock(AuditLogMapper.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        service = new SceneActionBindingServiceImpl(
                sceneMapper, bindingMapper, auditLogMapper, eventPublisher);
    }

    private SceneDef scene(Long id, String status) {
        SceneDef s = new SceneDef();
        s.setId(id);
        s.setTenantId(1L);
        s.setCode("PAY");
        s.setStatus(status);
        return s;
    }

    private SceneActionBindingDef existing(Long id, String actionType) {
        SceneActionBindingDef d = new SceneActionBindingDef();
        d.setId(id);
        d.setSceneId(10L);
        d.setActionType(actionType);
        return d;
    }

    @Test
    void replace_emptyExisting_insertsAll_publishesActiveTrue() {
        when(sceneMapper.findByCode(1L, "PAY")).thenReturn(scene(10L, "ACTIVE"));
        when(bindingMapper.findBySceneId(10L)).thenReturn(List.of());

        service.replace("1", "PAY", List.of(
                new SceneActionBindingItem("BLOCK_TX", Map.<String, Object>of("a", 1)),
                new SceneActionBindingItem("SEND_ALERT", null)), "alice");

        verify(bindingMapper, times(2)).insert(any(SceneActionBindingDef.class));
        verify(bindingMapper, never()).updateById(any(SceneActionBindingDef.class));
        verify(bindingMapper, never()).deleteById(any(Long.class));
        verify(auditLogMapper).insert(any(AuditLog.class));

        ArgumentCaptor<SceneChangedEvent> ev = ArgumentCaptor.forClass(SceneChangedEvent.class);
        verify(eventPublisher).publishEvent(ev.capture());
        assertThat(ev.getValue().tenantId()).isEqualTo("1");
        assertThat(ev.getValue().sceneCode()).isEqualTo("PAY");
        assertThat(ev.getValue().active()).isTrue();
    }

    @Test
    void replace_reconciles_removeUpdateInsert() {
        when(sceneMapper.findByCode(1L, "PAY")).thenReturn(scene(10L, "ACTIVE"));
        // 现有 BLOCK_TX(保留→更新) + OLD(缺失→删除)；目标含 BLOCK_TX(更新) + NEW(新增)
        when(bindingMapper.findBySceneId(10L)).thenReturn(List.of(
                existing(100L, "BLOCK_TX"), existing(101L, "OLD")));

        service.replace("1", "PAY", List.of(
                new SceneActionBindingItem("BLOCK_TX", Map.<String, Object>of("x", 1)),
                new SceneActionBindingItem("NEW", null)), "bob");

        verify(bindingMapper).deleteById(101L);                              // OLD 被删
        verify(bindingMapper).updateById(any(SceneActionBindingDef.class));  // BLOCK_TX 被更新
        verify(bindingMapper, times(1)).insert(any(SceneActionBindingDef.class)); // NEW 被插入
    }

    @Test
    void replace_disabledScene_publishesActiveFalse() {
        when(sceneMapper.findByCode(1L, "PAY")).thenReturn(scene(10L, "DISABLED"));
        when(bindingMapper.findBySceneId(10L)).thenReturn(List.of());

        service.replace("1", "PAY",
                List.of(new SceneActionBindingItem("BLOCK_TX", null)), "bob");

        ArgumentCaptor<SceneChangedEvent> ev = ArgumentCaptor.forClass(SceneChangedEvent.class);
        verify(eventPublisher).publishEvent(ev.capture());
        assertThat(ev.getValue().active()).isFalse();
    }

    @Test
    void replace_sceneNotFound_throws() {
        when(sceneMapper.findByCode(1L, "PAY")).thenReturn(null);

        assertThatThrownBy(() -> service.replace("1", "PAY", List.of(), "bob"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void replace_duplicateActionType_throws() {
        when(sceneMapper.findByCode(1L, "PAY")).thenReturn(scene(10L, "ACTIVE"));
        when(bindingMapper.findBySceneId(10L)).thenReturn(List.of());

        assertThatThrownBy(() -> service.replace("1", "PAY", List.of(
                new SceneActionBindingItem("BLOCK_TX", null),
                new SceneActionBindingItem("BLOCK_TX", null)), "bob"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void list_returnsMappedItems() {
        when(sceneMapper.findByCode(1L, "PAY")).thenReturn(scene(10L, "ACTIVE"));
        SceneActionBindingDef d = existing(100L, "BLOCK_TX");
        d.setDefaultParams(Map.of("a", 1));
        when(bindingMapper.findBySceneId(10L)).thenReturn(List.of(d));

        List<SceneActionBindingItem> items = service.list("1", "PAY");

        assertThat(items).hasSize(1);
        assertThat(items.getFirst().actionType()).isEqualTo("BLOCK_TX");
        // 实体 Map 字段(TypeHandler 转换)直传，对外类型化 Map
        assertThat(items.getFirst().defaultParams()).isEqualTo(Map.of("a", 1));
    }
}
