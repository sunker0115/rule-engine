package com.sstlfsj.rule.eval.internal.snapshot;

import com.sstlfsj.rule.eval.internal.repository.RuleVersionReadMapper;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
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
     * 外层 key = "tenantId:sceneCode"，内层 key = eventType（v1 使用 "*" 通配）。
     *
     * @return 双层 Map，外层 key = tenantId:sceneCode，内层 key = eventType
     */
    public Map<String, Map<String, List<RuleVersionSnapshot>>> loadAll() {
        List<RuleVersionRow> rows = mapper.loadAllActive();
        List<RuleVersionSnapshot> snapshots = assembler.assembleAll(rows);
        return groupBySceneAndEventType(snapshots);
    }

    /**
     * 加载指定租户 + 场景的所有 ACTIVE 规则版本，按 eventType 分组。
     * v1 中 triggerEventTypes 未在快照模型中，使用 "*" 通配。
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
            // v1：RuleVersionSnapshot 不含 triggerEventTypes，使用 "*" 通配
            result.computeIfAbsent(outerKey, k -> new HashMap<>())
                    .computeIfAbsent("*", k -> new ArrayList<>())
                    .add(snap);
        }
        return result;
    }

    private Map<String, List<RuleVersionSnapshot>> groupByEventType(
            List<RuleVersionSnapshot> snapshots) {
        Map<String, List<RuleVersionSnapshot>> result = new HashMap<>();
        for (RuleVersionSnapshot snap : snapshots) {
            // v1：triggerEventTypes 未在 RuleVersionSnapshot 模型中，用 "*" 作通配符
            result.computeIfAbsent("*", k -> new ArrayList<>()).add(snap);
        }
        return result;
    }
}
