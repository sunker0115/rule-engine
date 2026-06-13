package com.sstlfsj.rule.config.internal.service;

import com.sstlfsj.rule.config.api.dto.PayloadFieldSpec;
import com.sstlfsj.rule.config.api.dto.PayloadFieldType;
import com.sstlfsj.rule.config.api.dto.SceneDetailDto;
import com.sstlfsj.rule.config.api.dto.SceneListItem;
import com.sstlfsj.rule.config.api.event.SceneChangedEvent;
import com.sstlfsj.rule.config.api.service.SceneService;
import com.sstlfsj.rule.config.internal.domain.SceneDef;
import com.sstlfsj.rule.config.internal.domain.SceneStatus;
import com.sstlfsj.rule.config.internal.event.AuditSnapshot;
import com.sstlfsj.rule.config.internal.event.OperationAuditedEvent;
import com.sstlfsj.rule.config.internal.event.SceneSnapshot;
import com.sstlfsj.rule.config.internal.repository.SceneMapper;
import com.sstlfsj.rule.config.internal.domain.DominantMode;
import com.sstlfsj.rule.config.internal.domain.DecisionStrategy;
import com.sstlfsj.rule.kernel.api.model.SceneDefaultParams;
import com.sstlfsj.rule.kernel.api.model.SubjectType;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** SceneService 实现：Scene CRUD + 变更走 audit_log 前后快照 + SceneChangedEvent（D13/D14）。 */
@Service
@RequiredArgsConstructor
class SceneServiceImpl implements SceneService {

    private final SceneMapper sceneMapper;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    @Transactional
    public Long createScene(String tenantId, String sceneCode, String name,
                            String description, String dominantMode, String subjectType,
                            List<String> eventTypes, List<PayloadFieldSpec> payloadSchema,
                            Map<String, Object> defaultParams, String actorId) {
        SceneDef scene = new SceneDef();
        scene.setTenantId(Long.valueOf(tenantId));
        scene.setCode(sceneCode);
        scene.setName(name);
        scene.setDescription(description);
        scene.setDominantMode(DominantMode.valueOf(dominantMode != null ? dominantMode : "PUSH"));
        scene.setDecisionStrategy(DecisionStrategy.HIGHEST_PRIORITY);
        scene.setSubjectType(SubjectType.valueOf(subjectType != null ? subjectType : "USER"));
        scene.setEventTypes(eventTypes != null ? eventTypes : List.of());
        validatePayloadSchemaTypes(payloadSchema);
        scene.setPayloadSchema(payloadSchema);
        validateDefaultParams(defaultParams);
        scene.setDefaultParams(defaultParams);
        scene.setStatus(SceneStatus.ACTIVE);
        scene.setCreatedBy(actorId);
        sceneMapper.insert(scene);

        publishAudit(Long.valueOf(tenantId), actorId, "CREATE", "scene",
                scene.getId() != null ? scene.getId().toString() : sceneCode,
                null, snapshotOf(scene));
        return scene.getId();
    }

    @Override
    @Transactional
    public void updateScene(String tenantId, String sceneCode,
                            String name, List<String> eventTypes,
                            List<PayloadFieldSpec> payloadSchema, Map<String, Object> defaultParams,
                            String actorId) {
        SceneDef scene = findScene(Long.valueOf(tenantId), sceneCode);
        SceneSnapshot before = snapshotOf(scene);

        if (name != null) scene.setName(name);
        if (eventTypes != null) scene.setEventTypes(eventTypes);
        if (defaultParams != null) {
            validateDefaultParams(defaultParams);
            scene.setDefaultParams(defaultParams);
        }
        if (payloadSchema != null) {
            validatePayloadSchemaTypes(payloadSchema);
            scene.setPayloadSchema(payloadSchema);
        }

        scene.setUpdatedBy(actorId);
        scene.setUpdatedAt(LocalDateTime.now());
        sceneMapper.updateById(scene);
        publishAudit(Long.valueOf(tenantId), actorId, "UPDATE", "scene",
                scene.getId().toString(), before, snapshotOf(scene));
    }

    @Override
    public SceneDetailDto getScene(String tenantId, String sceneCode) {
        SceneDef scene = findScene(Long.valueOf(tenantId), sceneCode);
        return toDto(scene);
    }

    @Override
    public List<SceneListItem> listScenes(String tenantId) {
        return sceneMapper.findByTenantId(Long.valueOf(tenantId)).stream()
                .map(s -> new SceneListItem(s.getId(), s.getCode(), s.getName(),
                        s.getDominantMode().name(), s.getSubjectType().name(), s.getStatus().name()))
                .toList();
    }

    @Override
    @Transactional
    public void disableScene(String tenantId, String sceneCode, String actorId) {
        SceneDef scene = findScene(Long.valueOf(tenantId), sceneCode);
        SceneSnapshot before = snapshotOf(scene);
        scene.setStatus(SceneStatus.DISABLED);
        scene.setUpdatedBy(actorId);
        scene.setUpdatedAt(LocalDateTime.now());
        sceneMapper.updateById(scene);
        publishAudit(Long.valueOf(tenantId), actorId, "DISABLE", "scene",
                scene.getId().toString(), before, snapshotOf(scene));
        eventPublisher.publishEvent(new SceneChangedEvent(tenantId, sceneCode, false));
    }

    private SceneDef findScene(Long tenantId, String sceneCode) {
        SceneDef scene = sceneMapper.findByCode(tenantId, sceneCode);
        if (scene == null) {
            throw new IllegalArgumentException("Scene 不存在: " + sceneCode);
        }
        return scene;
    }

    private void validatePayloadSchemaTypes(List<PayloadFieldSpec> payloadSchema) {
        if (payloadSchema == null) return;
        for (PayloadFieldSpec f : payloadSchema) {
            PayloadFieldType.fromTag(f.type()); // 非法 type 抛 IllegalArgumentException
        }
    }

    /** authoring 期 fail-fast：default_params.timezone 须为合法 IANA 时区名，否则抛 IllegalArgumentException。 */
    private void validateDefaultParams(Map<String, Object> defaultParams) {
        if (defaultParams == null) return;
        Object tz = defaultParams.get(SceneDefaultParams.TIMEZONE);
        if (tz != null) {
            try {
                java.time.ZoneId.of(tz.toString());
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("非法 scene default_params.timezone=" + tz
                        + "（须为合法 IANA 时区名，如 Asia/Shanghai）");
            }
        }
    }

    private SceneDetailDto toDto(SceneDef scene) {
        List<String> eventTypes = scene.getEventTypes() != null ? scene.getEventTypes() : List.of();
        List<PayloadFieldSpec> payloadSchema = scene.getPayloadSchema() != null
                ? scene.getPayloadSchema() : List.of();
        Map<String, Object> defaultParams = scene.getDefaultParams() != null
                ? scene.getDefaultParams() : Map.of();
        return new SceneDetailDto(
                scene.getId(),
                String.valueOf(scene.getTenantId()),
                scene.getCode(),
                scene.getName(),
                scene.getDescription(),
                scene.getDominantMode().name(),
                scene.getSubjectType().name(),
                eventTypes,
                payloadSchema,
                defaultParams,
                scene.getStatus().name()
        );
    }

    private static SceneSnapshot snapshotOf(SceneDef s) {
        return SceneSnapshot.builder()
                .name(s.getName()).eventTypes(s.getEventTypes()).payloadSchema(s.getPayloadSchema())
                .defaultParams(s.getDefaultParams())
                .status(s.getStatus() != null ? s.getStatus().name() : null)
                .build();
    }

    private void publishAudit(Long tenantId, String actor, String action,
                              String targetType, String targetId,
                              AuditSnapshot before, AuditSnapshot after) {
        eventPublisher.publishEvent(new OperationAuditedEvent(
                tenantId, actor, "USER", action, targetType, targetId, before, after, LocalDateTime.now()));
    }
}
