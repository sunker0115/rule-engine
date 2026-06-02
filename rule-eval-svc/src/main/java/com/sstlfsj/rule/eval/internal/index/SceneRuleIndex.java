package com.sstlfsj.rule.eval.internal.index;

import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory inverted index: (tenantId, sceneCode, eventType) → List&lt;RuleVersionSnapshot&gt;.
 * Updated by RulePublishedEvent and SceneChangedEvent listeners.
 */
@Component
public class SceneRuleIndex {

    private final Map<String, List<RuleVersionSnapshot>> index = new ConcurrentHashMap<>();

    /**
     * Returns the active rule version snapshots for the given tenant, scene, and event type.
     *
     * @param tenantId  tenant identifier
     * @param sceneCode scene code
     * @param eventType event type to match
     * @return matching snapshots, or empty list if none
     */
    public List<RuleVersionSnapshot> match(String tenantId, String sceneCode, String eventType) {
        String key = tenantId + ":" + sceneCode + ":" + eventType;
        return index.getOrDefault(key, List.of());
    }

    /**
     * Updates the index entry for the given tenant, scene, and event type.
     *
     * @param tenantId  tenant identifier
     * @param sceneCode scene code
     * @param eventType event type
     * @param snapshots new list of active snapshots to store
     */
    public void update(String tenantId, String sceneCode, String eventType,
                       List<RuleVersionSnapshot> snapshots) {
        String key = tenantId + ":" + sceneCode + ":" + eventType;
        index.put(key, List.copyOf(snapshots));
    }

    /**
     * Removes all index entries for the given tenant and scene (e.g., when a scene is disabled).
     *
     * @param tenantId  tenant identifier
     * @param sceneCode scene to remove
     */
    public void remove(String tenantId, String sceneCode) {
        index.keySet().removeIf(k -> k.startsWith(tenantId + ":" + sceneCode + ":"));
    }
}
