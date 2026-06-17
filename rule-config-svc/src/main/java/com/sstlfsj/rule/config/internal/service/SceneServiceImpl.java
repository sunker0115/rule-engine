package com.sstlfsj.rule.config.internal.service;

import com.sstlfsj.rule.config.api.dto.PayloadFieldSpec;
import com.sstlfsj.rule.config.api.dto.PayloadFieldType;
import com.sstlfsj.rule.config.api.dto.SceneDetailDto;
import com.sstlfsj.rule.config.api.dto.SceneListItem;
import com.sstlfsj.rule.config.api.dto.UpdateSceneCommand;
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
import java.util.Set;
import java.util.stream.Collectors;

/** SceneService 实现：Scene CRUD + 变更走 audit_log 前后快照 + SceneChangedEvent（D13/D14）。 */
@Service
@RequiredArgsConstructor
class SceneServiceImpl implements SceneService {

    private final SceneMapper sceneMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final com.sstlfsj.rule.config.internal.repository.MetricDefinitionMapper metricDefinitionMapper;

    @Override
    @Transactional
    public Long createScene(Long tenantId, String sceneCode, String name,
                            String description, String dominantMode, String subjectType,
                            List<String> eventTypes, List<PayloadFieldSpec> payloadSchema,
                            Map<String, Object> defaultParams, String actorId) {
        SceneDef scene = new SceneDef();
        scene.setTenantId(tenantId);
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

        publishAudit(tenantId, actorId, "CREATE", "scene",
                scene.getId() != null ? scene.getId().toString() : sceneCode,
                null, snapshotOf(scene));
        return scene.getId();
    }

    @Override
    @Transactional
    public void updateScene(UpdateSceneCommand cmd) {
        SceneDef scene = findScene(cmd.tenantId(), cmd.sceneCode());
        SceneSnapshot before = snapshotOf(scene);

        if (cmd.name() != null) scene.setName(cmd.name());
        if (cmd.description() != null) scene.setDescription(cmd.description());
        if (cmd.eventTypes() != null) scene.setEventTypes(cmd.eventTypes());
        if (cmd.defaultParams() != null) {
            validateDefaultParams(cmd.defaultParams());
            scene.setDefaultParams(cmd.defaultParams());
        }
        if (cmd.payloadSchema() != null) {
            validatePayloadSchemaTypes(cmd.payloadSchema());
            scene.setPayloadSchema(cmd.payloadSchema());
        }

        scene.setUpdatedBy(cmd.actorId());
        scene.setUpdatedAt(LocalDateTime.now());
        sceneMapper.updateById(scene);
        publishAudit(cmd.tenantId(), cmd.actorId(), "UPDATE", "scene",
                scene.getId().toString(), before, snapshotOf(scene));
        // 场景仍 ACTIVE：发 SceneChangedEvent(active=true) 触发 eval 索引重载，
        // 使 payloadSchema / eventTypes / defaultParams(含 timezone)变更 live 生效(不必等规则 republish)。
        // 事件 tenantId 为评估侧 SPI 不透明标识，保持 String
        eventPublisher.publishEvent(new SceneChangedEvent(String.valueOf(cmd.tenantId()), cmd.sceneCode(), true));
    }

    @Override
    public SceneDetailDto getScene(Long tenantId, String sceneCode) {
        SceneDef scene = findScene(tenantId, sceneCode);
        return toDto(scene);
    }

    @Override
    public List<SceneListItem> listScenes(Long tenantId, String status) {
        SceneStatus statusFilter = (status != null && !status.isBlank()) ? SceneStatus.valueOf(status) : null;
        return sceneMapper.findByTenantId(tenantId, statusFilter).stream()
                .map(s -> new SceneListItem(s.getId(), s.getTenantId(), s.getCode(), s.getName(),
                        s.getDominantMode().name(), s.getSubjectType().name(), s.getStatus().name(),
                        s.getCreatedAt(), s.getUpdatedAt()))
                .toList();
    }

    @Override
    @Transactional
    public void disableScene(Long tenantId, String sceneCode, String actorId) {
        SceneDef scene = findScene(tenantId, sceneCode);
        SceneSnapshot before = snapshotOf(scene);
        scene.setStatus(SceneStatus.DISABLED);
        scene.setUpdatedBy(actorId);
        scene.setUpdatedAt(LocalDateTime.now());
        sceneMapper.updateById(scene);
        publishAudit(tenantId, actorId, "DISABLE", "scene",
                scene.getId().toString(), before, snapshotOf(scene));
        eventPublisher.publishEvent(new SceneChangedEvent(String.valueOf(tenantId), sceneCode, false));
    }

    @Override
    @Transactional
    public void toggleSceneStatus(Long tenantId, String sceneCode, boolean enable, String actorId) {
        SceneDef scene = findScene(tenantId, sceneCode);
        SceneStatus newStatus = enable ? SceneStatus.ACTIVE : SceneStatus.DISABLED;
        if (scene.getStatus() == newStatus) return;

        SceneSnapshot before = snapshotOf(scene);
        scene.setStatus(newStatus);
        scene.setUpdatedAt(LocalDateTime.now());
        sceneMapper.updateById(scene);
        String action = enable ? "ENABLE" : "DISABLE";
        publishAudit(tenantId, actorId, action, "scene",
                scene.getId().toString(), before, snapshotOf(scene));
        eventPublisher.publishEvent(new SceneChangedEvent(String.valueOf(tenantId), sceneCode, enable));
    }

    @Override
    public SensitiveRefs getSensitiveRefs(Long tenantId, String sceneCode) {
        // scene 不存在直接抛——调用方(rule-api)捕获后 fail-closed 全抹
        SceneDef scene = findScene(tenantId, sceneCode);
        List<PayloadFieldSpec> schema = scene.getPayloadSchema() != null
                ? scene.getPayloadSchema() : List.of();
        Set<String> payloadFields = schema.stream()
                .filter(PayloadFieldSpec::sensitive)
                .map(PayloadFieldSpec::name)
                .collect(Collectors.toSet());
        // metric 敏感性租户级共享(D54)：取该租户全部 ACTIVE metric 中 sensitive=true 的码
        Set<String> metricCodes = metricDefinitionMapper.findActiveByTenant(tenantId).stream()
                .filter(m -> Boolean.TRUE.equals(m.getSensitive()))
                .map(com.sstlfsj.rule.config.internal.domain.MetricDefinition::getMetricCode)
                .collect(Collectors.toSet());
        return new SensitiveRefs(payloadFields, metricCodes);
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
                scene.getTenantId(),
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
