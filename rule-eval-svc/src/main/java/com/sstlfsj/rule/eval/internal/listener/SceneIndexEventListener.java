package com.sstlfsj.rule.eval.internal.listener;

import com.sstlfsj.rule.config.api.event.SceneChangedEvent;
import com.sstlfsj.rule.kernel.internal.index.SceneRuleIndex;
import com.sstlfsj.rule.eval.internal.snapshot.SceneSnapshotLoader;
import com.sstlfsj.rule.eval.internal.snapshot.ScriptWarmer;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 监听 SceneChangedEvent，场景禁用时从索引移除，场景启用时重新加载快照。 */
@Component
public class SceneIndexEventListener {

    private final SceneRuleIndex index;
    private final SceneSnapshotLoader loader;
    private final ScriptWarmer scriptWarmer;

    public SceneIndexEventListener(SceneRuleIndex index, SceneSnapshotLoader loader, ScriptWarmer scriptWarmer) {
        this.index = index;
        this.loader = loader;
        this.scriptWarmer = scriptWarmer;
    }

    /**
     * 场景状态变更时更新倒排索引,并按预编译配置预热脚本规则。
     * 禁用场景（active=false）→ 从索引移除全部条目；
     * 启用场景（active=true）→ 重新加载 ACTIVE 快照。
     *
     * @param event 场景变更事件
     */
    @ApplicationModuleListener
    public void onSceneChanged(SceneChangedEvent event) {
        if (!event.active()) {
            index.remove(event.tenantId(), event.sceneCode());
            return;
        }
        Map<String, List<RuleVersionSnapshot>> byEventType =
                loader.loadBySceneWithStrategy(event.tenantId(), event.sceneCode(), index);
        // 原子替换该 scene 全部桶（空结果也能摘除残留旧桶，避免 torn 索引）
        index.replaceScene(event.tenantId(), event.sceneCode(), byEventType);
        // 同一快照可分属多个 eventType 桶,按引用去重后预热一次
        Set<RuleVersionSnapshot> distinct = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Map.Entry<String, List<RuleVersionSnapshot>> entry : byEventType.entrySet()) {
            distinct.addAll(entry.getValue());
        }
        scriptWarmer.warmUpIfEager(new ArrayList<>(distinct));
    }
}
