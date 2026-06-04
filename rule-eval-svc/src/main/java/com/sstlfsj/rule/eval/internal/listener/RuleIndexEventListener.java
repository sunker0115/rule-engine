package com.sstlfsj.rule.eval.internal.listener;

import com.sstlfsj.rule.config.api.event.RulePublishedEvent;
import com.sstlfsj.rule.kernel.internal.index.SceneRuleIndex;
import com.sstlfsj.rule.eval.internal.snapshot.SceneSnapshotLoader;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** 监听 RulePublishedEvent，刷新倒排索引中该场景的所有 ACTIVE 快照。 */
@Component
public class RuleIndexEventListener {

    private final SceneRuleIndex index;
    private final SceneSnapshotLoader loader;

    public RuleIndexEventListener(SceneRuleIndex index, SceneSnapshotLoader loader) {
        this.index = index;
        this.loader = loader;
    }

    /**
     * 规则发布后重新加载该场景全部 ACTIVE 快照，并刷新倒排索引。
     * 使用 @ApplicationModuleListener 确保在 config-svc 事务提交后执行。
     *
     * @param event 规则发布事件
     */
    @ApplicationModuleListener
    public void onRulePublished(RulePublishedEvent event) {
        Map<String, List<RuleVersionSnapshot>> byEventType =
                loader.loadByScene(event.tenantId(), event.sceneCode());
        for (Map.Entry<String, List<RuleVersionSnapshot>> entry : byEventType.entrySet()) {
            index.update(event.tenantId(), event.sceneCode(),
                         entry.getKey(), entry.getValue());
        }
    }
}
