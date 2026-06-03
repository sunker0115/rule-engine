package com.sstlfsj.rule.eval.internal.listener;

import com.sstlfsj.rule.config.api.event.SceneChangedEvent;
import com.sstlfsj.rule.eval.internal.index.SceneRuleIndex;
import com.sstlfsj.rule.eval.internal.snapshot.SceneSnapshotLoader;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** 监听 SceneChangedEvent，场景禁用时从索引移除，场景启用时重新加载快照。 */
@Component
public class SceneIndexEventListener {

    private final SceneRuleIndex index;
    private final SceneSnapshotLoader loader;

    public SceneIndexEventListener(SceneRuleIndex index, SceneSnapshotLoader loader) {
        this.index = index;
        this.loader = loader;
    }

    /**
     * 场景状态变更时更新倒排索引。
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
        Long tenantId = Long.valueOf(event.tenantId());
        Map<String, List<RuleVersionSnapshot>> byEventType =
                loader.loadByScene(tenantId, event.sceneCode());
        for (Map.Entry<String, List<RuleVersionSnapshot>> entry : byEventType.entrySet()) {
            index.update(event.tenantId(), event.sceneCode(),
                         entry.getKey(), entry.getValue());
        }
    }
}
