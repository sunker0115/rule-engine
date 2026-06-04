package com.sstlfsj.rule.eval.internal.snapshot;

import com.sstlfsj.rule.eval.internal.repository.RuleVersionReadMapper;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.SceneExecutionStrategy;
import com.sstlfsj.rule.kernel.internal.codec.RuleVersionRow;
import com.sstlfsj.rule.kernel.internal.codec.SnapshotAssembler;
import com.sstlfsj.rule.kernel.internal.index.SceneRuleIndex;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 从数据库加载规则版本快照，供倒排索引使用。
 * 返回值按 eventType 分组，key = eventType，value = 该 eventType 对应的快照列表。
 */
@Component
public class SceneSnapshotLoader {

    private final RuleVersionReadMapper mapper;
    private final SnapshotAssembler assembler;

    public SceneSnapshotLoader(RuleVersionReadMapper mapper, SnapshotAssembler assembler) {
        this.mapper = mapper;
        this.assembler = assembler;
    }

    /**
     * 全量加载所有 ACTIVE 规则版本，按 (tenantId:sceneCode, eventType) 分组。
     * 外层 key = "tenantId:sceneCode"，内层 key = eventType（空 triggerEventTypes 归入 "*" 通配）。
     *
     * @return 双层 Map，外层 key = tenantId:sceneCode，内层 key = eventType
     */
    public Map<String, Map<String, List<RuleVersionSnapshot>>> loadAll() {
        List<RuleVersionRow> rows = mapper.loadAllActive();
        List<RuleVersionSnapshot> snapshots = assembler.assembleAll(rows);
        return groupBySceneAndEventType(snapshots);
    }

    /**
     * 全量加载并同步将场景执行策略写入索引。
     * 供 IndexStartupLoader 使用，避免二次查询 scene 表。
     *
     * @param index 目标倒排索引
     * @return 双层 Map，外层 key = tenantId:sceneCode，内层 key = eventType
     */
    public Map<String, Map<String, List<RuleVersionSnapshot>>> loadAllWithStrategy(SceneRuleIndex index) {
        List<RuleVersionRow> rows = mapper.loadAllActive();
        applyStrategiesToIndex(rows, index);
        List<RuleVersionSnapshot> snapshots = assembler.assembleAll(rows);
        return groupBySceneAndEventType(snapshots);
    }

    /**
     * 加载指定租户 + 场景的所有 ACTIVE 规则版本，按 eventType 分组。
     * triggerEventTypes 为空时归入 "*" 通配桶；非空时按实际值分桶。
     *
     * @param tenantId  租户 ID 字符串（需可解析为 Long）
     * @param sceneCode 场景编码
     * @return key = eventType，value = 快照列表
     */
    public Map<String, List<RuleVersionSnapshot>> loadByScene(String tenantId, String sceneCode) {
        List<RuleVersionRow> rows = mapper.loadActiveByScene(Long.parseLong(tenantId), sceneCode);
        List<RuleVersionSnapshot> snapshots = assembler.assembleAll(rows);
        return groupByEventType(snapshots);
    }

    /**
     * 加载指定场景快照并同步将执行策略写入索引。
     * 供 SceneIndexEventListener 热更新时使用。
     *
     * @param tenantId  租户 ID 字符串
     * @param sceneCode 场景编码
     * @param index     目标倒排索引
     * @return key = eventType，value = 快照列表
     */
    public Map<String, List<RuleVersionSnapshot>> loadBySceneWithStrategy(
            String tenantId, String sceneCode, SceneRuleIndex index) {
        List<RuleVersionRow> rows = mapper.loadActiveByScene(Long.parseLong(tenantId), sceneCode);
        applyStrategiesToIndex(rows, index);
        List<RuleVersionSnapshot> snapshots = assembler.assembleAll(rows);
        return groupByEventType(snapshots);
    }

    /** 从 rows 中提取 (tenantId, sceneCode, decisionStrategy) 并写入 index，每个 scene 只写一次。 */
    private void applyStrategiesToIndex(List<RuleVersionRow> rows, SceneRuleIndex index) {
        for (RuleVersionRow row : rows) {
            String strategyStr = row.decisionStrategy();
            SceneExecutionStrategy strategy = strategyStr != null
                    ? parseStrategy(strategyStr)
                    : SceneExecutionStrategy.HIGHEST_PRIORITY;
            index.setStrategy(String.valueOf(row.tenantId()), row.sceneCode(), strategy);
        }
    }

    private static SceneExecutionStrategy parseStrategy(String value) {
        try {
            return SceneExecutionStrategy.valueOf(value);
        } catch (IllegalArgumentException e) {
            return SceneExecutionStrategy.HIGHEST_PRIORITY;
        }
    }

    /**
     * 按 ruleVersionId 加载单条快照（dry-run 指定版本时使用）。
     *
     * @param ruleVersionId 规则版本 ID
     * @return 快照，不存在时返回 null
     */
    public RuleVersionSnapshot loadById(Long ruleVersionId) {
        RuleVersionRow row = mapper.loadById(ruleVersionId);
        if (row == null) return null;
        List<RuleVersionSnapshot> list = assembler.assembleAll(List.of(row));
        return list.isEmpty() ? null : list.get(0);
    }

    private Map<String, Map<String, List<RuleVersionSnapshot>>> groupBySceneAndEventType(
            List<RuleVersionSnapshot> snapshots) {
        Map<String, Map<String, List<RuleVersionSnapshot>>> result = new HashMap<>();
        for (RuleVersionSnapshot snap : snapshots) {
            String outerKey = snap.tenantId() + ":" + snap.sceneCode();
            Map<String, List<RuleVersionSnapshot>> inner =
                    result.computeIfAbsent(outerKey, k -> new HashMap<>());
            for (String key : eventTypeKeys(snap)) {
                inner.computeIfAbsent(key, k -> new ArrayList<>()).add(snap);
            }
        }
        return result;
    }

    private Map<String, List<RuleVersionSnapshot>> groupByEventType(
            List<RuleVersionSnapshot> snapshots) {
        Map<String, List<RuleVersionSnapshot>> result = new HashMap<>();
        for (RuleVersionSnapshot snap : snapshots) {
            for (String key : eventTypeKeys(snap)) {
                result.computeIfAbsent(key, k -> new ArrayList<>()).add(snap);
            }
        }
        return result;
    }

    /**
     * 返回快照应归入的 eventType key 列表。
     * triggerEventTypes 为空时归入 "*" 通配桶，否则按实际值分桶。
     */
    private static List<String> eventTypeKeys(RuleVersionSnapshot snap) {
        List<String> types = snap.triggerEventTypes();
        return (types == null || types.isEmpty()) ? List.of("*") : types;
    }
}
