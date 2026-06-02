package com.sstlfsj.rule.eval.internal.index;

import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存倒排索引：(tenantId, sceneCode, eventType) → List&lt;RuleVersionSnapshot&gt;。
 * 由 RulePublishedEvent / SceneChangedEvent 监听器触发热更。
 */
@Component
public class SceneRuleIndex {

    private final Map<String, List<RuleVersionSnapshot>> index = new ConcurrentHashMap<>();

    /**
     * 返回给定租户、场景和事件类型对应的活跃规则版本快照列表。
     *
     * @param tenantId  租户标识
     * @param sceneCode 场景编码
     * @param eventType 待匹配的事件类型
     * @return 匹配的快照列表，无匹配则返回空列表
     */
    public List<RuleVersionSnapshot> match(String tenantId, String sceneCode, String eventType) {
        String key = tenantId + ":" + sceneCode + ":" + eventType;
        return index.getOrDefault(key, List.of());
    }

    /**
     * 更新给定租户、场景和事件类型的索引条目。
     *
     * @param tenantId  租户标识
     * @param sceneCode 场景编码
     * @param eventType 事件类型
     * @param snapshots 新的活跃快照列表
     */
    public void update(String tenantId, String sceneCode, String eventType,
                       List<RuleVersionSnapshot> snapshots) {
        String key = tenantId + ":" + sceneCode + ":" + eventType;
        index.put(key, List.copyOf(snapshots));
    }

    /**
     * 删除给定租户和场景的所有索引条目（如场景被禁用时调用）。
     *
     * @param tenantId  租户标识
     * @param sceneCode 待删除的场景编码
     */
    public void remove(String tenantId, String sceneCode) {
        index.keySet().removeIf(k -> k.startsWith(tenantId + ":" + sceneCode + ":"));
    }
}
