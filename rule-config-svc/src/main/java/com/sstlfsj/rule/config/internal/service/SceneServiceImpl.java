package com.sstlfsj.rule.config.internal.service;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.sstlfsj.rule.config.api.dto.PayloadFieldSpec;
import com.sstlfsj.rule.config.api.dto.SceneDetailDto;
import com.sstlfsj.rule.config.api.dto.SceneListItem;
import com.sstlfsj.rule.config.api.event.SceneChangedEvent;
import com.sstlfsj.rule.config.api.service.SceneService;
import com.sstlfsj.rule.config.internal.domain.AuditLog;
import com.sstlfsj.rule.config.internal.domain.SceneDef;
import com.sstlfsj.rule.config.internal.domain.ScenePayloadSchemaHistory;
import com.sstlfsj.rule.config.internal.repository.AuditLogMapper;
import com.sstlfsj.rule.config.internal.repository.SceneMapper;
import com.sstlfsj.rule.config.internal.repository.ScenePayloadSchemaHistoryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** SceneService 实现：Scene CRUD + payloadSchema 演进快照 + SceneChangedEvent（D13）。 */
@Service
@RequiredArgsConstructor
class SceneServiceImpl implements SceneService {

    private final SceneMapper sceneMapper;
    private final AuditLogMapper auditLogMapper;
    private final ScenePayloadSchemaHistoryMapper schemaHistoryMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public Long createScene(String tenantId, String sceneCode, String name,
                            String description, String dominantMode, String subjectType,
                            String eventTypesJson, String payloadSchemaJson, String defaultParamsJson,
                            String actorId) {
        SceneDef scene = new SceneDef();
        scene.setTenantId(Long.valueOf(tenantId));
        scene.setCode(sceneCode);
        scene.setName(name);
        scene.setDescription(description);
        scene.setDominantMode(dominantMode != null ? dominantMode : "PUSH");
        scene.setDecisionStrategy("HIGHEST_PRIORITY");
        scene.setSubjectType(subjectType != null ? subjectType : "USER");
        scene.setEventTypes(eventTypesJson != null ? eventTypesJson : "[]");
        scene.setPayloadSchema(payloadSchemaJson);
        scene.setDefaultParams(defaultParamsJson);
        scene.setPayloadSchemaVersion(1);
        scene.setStatus("ACTIVE");
        scene.setCreatedBy(actorId);
        sceneMapper.insert(scene);

        // 有 payloadSchema 时写入初始历史快照（version=1）
        if (payloadSchemaJson != null) {
            snapshotSchema(scene.getId(), 1, payloadSchemaJson, actorId);
        }

        writeAudit(Long.valueOf(tenantId), actorId, "CREATE", "scene",
                scene.getId() != null ? scene.getId().toString() : sceneCode);
        return scene.getId();
    }

    @Override
    @Transactional
    public void updateScene(String tenantId, String sceneCode,
                            String name, String eventTypesJson,
                            String payloadSchemaJson, String defaultParamsJson,
                            String actorId) {
        SceneDef scene = findScene(Long.valueOf(tenantId), sceneCode);

        if (name != null) scene.setName(name);
        if (eventTypesJson != null) scene.setEventTypes(eventTypesJson);
        if (defaultParamsJson != null) scene.setDefaultParams(defaultParamsJson);

        // payloadSchema 变更时快照旧版本并自增版本号
        if (payloadSchemaJson != null && !payloadSchemaJson.equals(scene.getPayloadSchema())) {
            int oldVersion = scene.getPayloadSchemaVersion() != null
                    ? scene.getPayloadSchemaVersion() : 1;
            // 旧版本不为 null 时才写历史（创建时已写 version=1 快照）
            if (scene.getPayloadSchema() != null) {
                snapshotSchema(scene.getId(), oldVersion, scene.getPayloadSchema(), actorId);
            }
            scene.setPayloadSchema(payloadSchemaJson);
            scene.setPayloadSchemaVersion(oldVersion + 1);
        }

        scene.setUpdatedBy(actorId);
        scene.setUpdatedAt(LocalDateTime.now());
        sceneMapper.updateById(scene);
        writeAudit(Long.valueOf(tenantId), actorId, "UPDATE", "scene", scene.getId().toString());
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
                        s.getDominantMode(), s.getSubjectType(), s.getStatus()))
                .toList();
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
        SceneDef scene = sceneMapper.findByCode(tenantId, sceneCode);
        if (scene == null) {
            throw new IllegalArgumentException("Scene 不存在: " + sceneCode);
        }
        return scene;
    }

    private void snapshotSchema(Long sceneId, int version, String schemaJson, String actorId) {
        ScenePayloadSchemaHistory hist = new ScenePayloadSchemaHistory();
        hist.setSceneId(sceneId);
        hist.setVersion(version);
        hist.setSchemaJson(schemaJson);
        hist.setCreatedBy(actorId);
        hist.setCreatedAt(LocalDateTime.now());
        schemaHistoryMapper.insert(hist);
    }

    private SceneDetailDto toDto(SceneDef scene) {
        List<String> eventTypes = parseStringList(scene.getEventTypes());
        List<PayloadFieldSpec> payloadSchema = scene.getPayloadSchema() != null
                ? parseSchemaList(scene.getPayloadSchema())
                : List.of();
        Map<String, Object> defaultParams = scene.getDefaultParams() != null
                ? parseMap(scene.getDefaultParams())
                : Map.of();
        int version = scene.getPayloadSchemaVersion() != null ? scene.getPayloadSchemaVersion() : 1;
        return new SceneDetailDto(
                scene.getId(),
                String.valueOf(scene.getTenantId()),
                scene.getCode(),
                scene.getName(),
                scene.getDescription(),
                scene.getDominantMode(),
                scene.getSubjectType(),
                eventTypes,
                payloadSchema,
                defaultParams,
                version,
                scene.getStatus()
        );
    }

    private List<String> parseStringList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<PayloadFieldSpec> parseSchemaList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<PayloadFieldSpec>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private Map<String, Object> parseMap(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
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
