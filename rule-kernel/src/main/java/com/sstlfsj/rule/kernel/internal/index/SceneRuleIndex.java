package com.sstlfsj.rule.kernel.internal.index;

import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.SceneExecutionStrategy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存倒排索引：(tenantId, sceneCode, eventType) → List&lt;RuleVersionSnapshot&gt;。
 * 纯 Java，无 Spring 依赖，可在 rule-eval-svc 和 rule-sdk 中共用。
 * 支持通配 eventType "*"：match() 同时查精确 key 和 tenantId:sceneCode:* 的并集。
 * 同时持有场景级执行策略，key = "tenantId:sceneCode"。
 */
public class SceneRuleIndex {

    private final Map<String, List<RuleVersionSnapshot>> index = new ConcurrentHashMap<>();
    private final Map<String, SceneExecutionStrategy> strategies = new ConcurrentHashMap<>();

    /**
     * 返回给定租户、场景和事件类型对应的活跃规则版本快照列表。
     * 先查精确 key，再查通配 key（"*"），合并去重返回。
     *
     * @param tenantId  租户标识
     * @param sceneCode 场景编码
     * @param eventType 待匹配的事件类型
     * @return 匹配的快照列表，无匹配则返回空列表
     */
    public List<RuleVersionSnapshot> match(String tenantId, String sceneCode, String eventType) {
        String exactKey    = tenantId + ":" + sceneCode + ":" + eventType;
        String wildcardKey = tenantId + ":" + sceneCode + ":*";

        List<RuleVersionSnapshot> exact    = index.getOrDefault(exactKey, List.of());
        List<RuleVersionSnapshot> wildcard = index.getOrDefault(wildcardKey, List.of());

        if (exact.isEmpty()) return wildcard;
        if (wildcard.isEmpty()) return exact;

        // 合并，使用 ruleVersionId 去重
        List<RuleVersionSnapshot> merged = new ArrayList<>(exact);
        for (RuleVersionSnapshot snap : wildcard) {
            if (exact.stream().noneMatch(s -> s.ruleVersionId().equals(snap.ruleVersionId()))) {
                merged.add(snap);
            }
        }
        return List.copyOf(merged);
    }

    /**
     * 更新给定租户、场景和事件类型的索引条目。
     *
     * @param tenantId  租户标识
     * @param sceneCode 场景编码
     * @param eventType 事件类型（可为 "*" 通配）
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
        strategies.remove(tenantId + ":" + sceneCode);
    }

    /**
     * 设置场景级执行策略。
     *
     * @param tenantId  租户标识
     * @param sceneCode 场景编码
     * @param strategy  执行策略
     */
    public void setStrategy(String tenantId, String sceneCode, SceneExecutionStrategy strategy) {
        strategies.put(tenantId + ":" + sceneCode, strategy);
    }

    /**
     * 获取场景级执行策略，未设置时返回 {@link SceneExecutionStrategy#HIGHEST_PRIORITY}。
     *
     * @param tenantId  租户标识
     * @param sceneCode 场景编码
     * @return 该场景的执行策略
     */
    public SceneExecutionStrategy getStrategy(String tenantId, String sceneCode) {
        return strategies.getOrDefault(tenantId + ":" + sceneCode,
                SceneExecutionStrategy.HIGHEST_PRIORITY);
    }
}
