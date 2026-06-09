package com.sstlfsj.rule.config.internal.service;

import com.sstlfsj.rule.config.api.event.SceneChangedEvent;
import com.sstlfsj.rule.config.api.service.SceneActionBindingService;
import com.sstlfsj.rule.config.internal.domain.AuditLog;
import com.sstlfsj.rule.config.internal.domain.SceneActionBindingDef;
import com.sstlfsj.rule.config.internal.domain.SceneDef;
import com.sstlfsj.rule.config.internal.repository.AuditLogMapper;
import com.sstlfsj.rule.config.internal.repository.SceneActionBindingMapper;
import com.sstlfsj.rule.config.internal.repository.SceneMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** SceneActionBindingService 实现：白名单整组覆盖 + 审计 + SceneChangedEvent 失效。 */
@Service
@RequiredArgsConstructor
class SceneActionBindingServiceImpl implements SceneActionBindingService {

    private static final Logger log = LoggerFactory.getLogger(SceneActionBindingServiceImpl.class);
    private static final String ACTIVE = "ACTIVE";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final SceneMapper sceneMapper;
    private final SceneActionBindingMapper bindingMapper;
    private final AuditLogMapper auditLogMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    @Override
    public List<SceneActionBindingItem> list(String tenantId, String sceneCode) {
        SceneDef scene = findScene(Long.valueOf(tenantId), sceneCode);
        return bindingMapper.findBySceneId(scene.getId()).stream()
                .map(b -> new SceneActionBindingItem(b.getActionType(), parse(b.getDefaultParams())))
                .toList();
    }

    @Override
    @Transactional
    public void replace(String tenantId, String sceneCode,
                        List<SceneActionBindingItem> items, String actorId) {
        SceneDef scene = findScene(Long.valueOf(tenantId), sceneCode);

        // payload 内 actionType 不可重复（前端不该送重复，重复必属脏数据）
        Map<String, SceneActionBindingItem> targets = new HashMap<>();
        for (SceneActionBindingItem item : items) {
            if (targets.put(item.actionType(), item) != null) {
                throw new IllegalArgumentException("actionType 重复: " + item.actionType());
            }
        }

        // 现有集合 vs 目标集合：删多余、更新已存在、插入新增
        Map<String, SceneActionBindingDef> existing = new HashMap<>();
        for (SceneActionBindingDef def : bindingMapper.findBySceneId(scene.getId())) {
            existing.put(def.getActionType(), def);
        }

        Set<String> toRemove = new HashSet<>(existing.keySet());
        toRemove.removeAll(targets.keySet());
        for (String actionType : toRemove) {
            bindingMapper.deleteById(existing.get(actionType).getId());
        }

        for (SceneActionBindingItem item : targets.values()) {
            SceneActionBindingDef def = existing.get(item.actionType());
            if (def == null) {
                SceneActionBindingDef fresh = new SceneActionBindingDef();
                fresh.setSceneId(scene.getId());
                fresh.setActionType(item.actionType());
                fresh.setDefaultParams(write(item.defaultParams()));
                fresh.setCreatedBy(actorId);
                bindingMapper.insert(fresh);
            } else {
                def.setDefaultParams(write(item.defaultParams()));
                def.setUpdatedBy(actorId);
                def.setUpdatedAt(LocalDateTime.now());
                bindingMapper.updateById(def);
            }
        }

        writeAudit(Long.valueOf(tenantId), actorId, "REPLACE_ACTION_BINDING",
                "scene_action_binding", scene.getId().toString());
        // active 取场景真实状态：禁用场景改 binding 不得复活其索引（发 false → 索引移除/no-op）
        eventPublisher.publishEvent(new SceneChangedEvent(
                tenantId, sceneCode, ACTIVE.equals(scene.getStatus())));
    }

    /** JSON 对象 → 存库 JSON 串；null 返回 null。 */
    private String write(Map<String, Object> obj) {
        return obj == null ? null : objectMapper.writeValueAsString(obj);
    }

    /** 存库 JSON 串 → JSON 对象；null/空返回 null，解析失败记 warn 返回 null（不阻塞列表）。 */
    private Map<String, Object> parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (RuntimeException e) {
            log.warn("scene_action_binding JSON 解析失败,返回 null: {}", json, e);
            return null;
        }
    }

    private SceneDef findScene(Long tenantId, String sceneCode) {
        SceneDef scene = sceneMapper.findByCode(tenantId, sceneCode);
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
