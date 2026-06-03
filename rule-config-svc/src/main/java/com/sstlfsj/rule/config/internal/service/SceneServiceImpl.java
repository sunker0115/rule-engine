package com.sstlfsj.rule.config.internal.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sstlfsj.rule.config.api.service.SceneService;
import com.sstlfsj.rule.config.internal.domain.AuditLog;
import com.sstlfsj.rule.config.internal.domain.SceneDef;
import com.sstlfsj.rule.config.api.event.SceneChangedEvent;
import com.sstlfsj.rule.config.internal.repository.AuditLogMapper;
import com.sstlfsj.rule.config.internal.repository.SceneMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** SceneService 实现：Scene CRUD + SceneChangedEvent 发布。 */
@Service
@RequiredArgsConstructor
class SceneServiceImpl implements SceneService {

    private final SceneMapper sceneMapper;
    private final AuditLogMapper auditLogMapper;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    @Transactional
    public Long createScene(String tenantId, String sceneCode, String name, String actorId) {
        SceneDef scene = new SceneDef();
        scene.setTenantId(Long.valueOf(tenantId));
        scene.setCode(sceneCode);
        scene.setName(name);
        scene.setDominantMode("PUSH");
        scene.setDecisionStrategy("HIGHEST_PRIORITY");
        scene.setSubjectType("USER");
        scene.setEventTypes("[]");
        scene.setStatus("ACTIVE");
        scene.setCreatedBy(actorId);
        sceneMapper.insert(scene);

        writeAudit(Long.valueOf(tenantId), actorId, "CREATE", "scene",
                scene.getId() != null ? scene.getId().toString() : sceneCode);
        return scene.getId();
    }

    @Override
    @Transactional
    public void updateScene(String tenantId, String sceneCode, String actorId) {
        SceneDef scene = findScene(Long.valueOf(tenantId), sceneCode);
        scene.setUpdatedBy(actorId);
        scene.setUpdatedAt(LocalDateTime.now());
        sceneMapper.updateById(scene);
        writeAudit(Long.valueOf(tenantId), actorId, "UPDATE", "scene", scene.getId().toString());
    }

    @Override
    @Transactional
    public void disableScene(String tenantId, String sceneCode, String actorId) {
        SceneDef scene = findScene(Long.valueOf(tenantId), sceneCode);
        scene.setStatus("DISABLED");
        scene.setUpdatedBy(actorId);
        scene.setUpdatedAt(LocalDateTime.now());
        sceneMapper.updateById(scene);

        writeAudit(Long.valueOf(tenantId), actorId, "DISABLE", "scene", scene.getId().toString());
        eventPublisher.publishEvent(new SceneChangedEvent(tenantId, sceneCode, false));
    }

    private SceneDef findScene(Long tenantId, String sceneCode) {
        SceneDef scene = sceneMapper.selectOne(
                new LambdaQueryWrapper<SceneDef>()
                        .eq(SceneDef::getTenantId, tenantId)
                        .eq(SceneDef::getCode, sceneCode));
        if (scene == null) {
            throw new IllegalArgumentException("Scene 不存在: " + sceneCode);
        }
        return scene;
    }

    private void writeAudit(Long tenantId, String actor, String action,
                             String targetType, String targetId) {
        AuditLog log = new AuditLog();
        log.setTenantId(tenantId);
        log.setActor(actor);
        log.setActorType("USER");
        log.setAction(action);
        log.setTargetType(targetType);
        log.setTargetId(targetId);
        log.setOperatedAt(LocalDateTime.now());
        auditLogMapper.insert(log);
    }
}
