package com.sstlfsj.rule.config.internal.service;

import com.sstlfsj.rule.config.internal.domain.AuditLog;
import com.sstlfsj.rule.config.internal.domain.SceneDef;
import com.sstlfsj.rule.config.api.event.SceneChangedEvent;
import com.sstlfsj.rule.config.internal.repository.AuditLogMapper;
import com.sstlfsj.rule.config.internal.repository.SceneMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SceneServiceImplTest {

    @Mock SceneMapper sceneMapper;
    @Mock AuditLogMapper auditLogMapper;
    @Mock ApplicationEventPublisher eventPublisher;
    @InjectMocks SceneServiceImpl sceneService;

    @Test
    void createScene_insertsSceneAndWritesAuditLog() {
        when(sceneMapper.insert((SceneDef) any())).thenReturn(1);
        when(auditLogMapper.insert((AuditLog) any())).thenReturn(1);

        sceneService.createScene("1", "PAYMENT", "支付场景", "actor1");

        ArgumentCaptor<SceneDef> sceneCaptor = ArgumentCaptor.forClass(SceneDef.class);
        verify(sceneMapper).insert(sceneCaptor.capture());
        assertThat(sceneCaptor.getValue().getCode()).isEqualTo("PAYMENT");
        assertThat(sceneCaptor.getValue().getStatus()).isEqualTo("ACTIVE");
        verify(auditLogMapper).insert((AuditLog) any());
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
}
