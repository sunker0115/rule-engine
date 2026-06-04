package com.sstlfsj.rule.sdk.source;

import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.internal.index.SceneRuleIndex;

import java.util.ArrayList;
import java.util.List;

/** 代码 DSL 模式：直接持有 RuleVersionSnapshot 列表，零网络、零 IO。 */
public class DslRuleSource implements RuleSource {

    private final List<RuleVersionSnapshot> snapshots;

    public DslRuleSource(List<RuleVersionSnapshot> snapshots) {
        this.snapshots = List.copyOf(snapshots);
    }

    @Override
    public void loadInto(SceneRuleIndex index) {
        for (RuleVersionSnapshot snap : snapshots) {
            List<String> eventTypes = snap.triggerEventTypes().isEmpty()
                    ? List.of("*") : snap.triggerEventTypes();
            for (String et : eventTypes) {
                List<RuleVersionSnapshot> existing = new ArrayList<>(
                        index.match(snap.tenantId(), snap.sceneCode(), et));
                if (existing.stream().noneMatch(s -> s.ruleVersionId().equals(snap.ruleVersionId()))) {
                    existing.add(snap);
                }
                index.update(snap.tenantId(), snap.sceneCode(), et, existing);
            }
        }
    }
}
