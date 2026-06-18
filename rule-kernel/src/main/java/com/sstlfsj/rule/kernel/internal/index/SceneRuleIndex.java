package com.sstlfsj.rule.kernel.internal.index;

import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.SceneExecutionStrategy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final Map<String, Map<String, Object>> defaultParams = new ConcurrentHashMap<>();

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

        // exact 优先入列，wildcard 仅补充未出现过的 ruleVersionId（Set 去重，线性）
        Set<Long> seen = new HashSet<>(exact.size() * 2);
        List<RuleVersionSnapshot> merged = new ArrayList<>(exact.size() + wildcard.size());
        for (RuleVersionSnapshot snap : exact) {
            seen.add(snap.ruleVersionId());
            merged.add(snap);
        }
        for (RuleVersionSnapshot snap : wildcard) {
            if (seen.add(snap.ruleVersionId())) merged.add(snap);
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
     * 原子替换某 scene 的全部 eventType 桶：先写入新桶，再删除该 scene 下不在新集合中的旧桶。
     * 比逐个 {@link #update} 安全——当 byEventType 为空（该 scene 规则全部禁用/删除）时也能摘除残留旧桶，
     * 且新桶先 put 再删旧，避免"全清→重填"的空窗口。
     *
     * @param tenantId    租户标识
     * @param sceneCode   场景编码
     * @param byEventType eventType → 快照列表（该 scene 当前完整集合，可为空 Map）
     */
    public void replaceScene(String tenantId, String sceneCode,
                             Map<String, List<RuleVersionSnapshot>> byEventType) {
        String prefix = tenantId + ":" + sceneCode + ":";
        Set<String> newKeys = new HashSet<>();
        for (Map.Entry<String, List<RuleVersionSnapshot>> e : byEventType.entrySet()) {
            String key = prefix + e.getKey();
            index.put(key, List.copyOf(e.getValue()));
            newKeys.add(key);
        }
        // 删除该 scene 下已不在新集合的旧桶（规则禁用/删除后，对应 eventType 桶不再出现 → 摘除）
        index.keySet().removeIf(k -> k.startsWith(prefix) && !newKeys.contains(k));
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
        defaultParams.remove(tenantId + ":" + sceneCode);
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

    /**
     * 设置场景默认参数(scene.default_params)。
     *
     * @param tenantId  租户标识
     * @param sceneCode 场景编码
     * @param params    默认参数 map(不可变拷贝)
     */
    public void setDefaultParams(String tenantId, String sceneCode, Map<String, Object> params) {
        defaultParams.put(tenantId + ":" + sceneCode, params == null ? Map.of() : Map.copyOf(params));
    }

    /**
     * 获取场景默认参数,未设置返回空 map。
     *
     * @param tenantId  租户标识
     * @param sceneCode 场景编码
     * @return 默认参数 map(不可变)
     */
    public Map<String, Object> getDefaultParams(String tenantId, String sceneCode) {
        return defaultParams.getOrDefault(tenantId + ":" + sceneCode, Map.of());
    }
}
