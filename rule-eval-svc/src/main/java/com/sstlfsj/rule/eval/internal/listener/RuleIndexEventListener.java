package com.sstlfsj.rule.eval.internal.listener;

import com.sstlfsj.rule.config.api.event.RulePublishedEvent;
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

/** 监听 RulePublishedEvent，刷新倒排索引中该场景的所有 ACTIVE 快照。 */
@Component
public class RuleIndexEventListener {

    private final SceneRuleIndex index;
    private final SceneSnapshotLoader loader;
    private final ScriptWarmer scriptWarmer;

    public RuleIndexEventListener(SceneRuleIndex index, SceneSnapshotLoader loader, ScriptWarmer scriptWarmer) {
        this.index = index;
        this.loader = loader;
        this.scriptWarmer = scriptWarmer;
    }

    /**
     * 规则发布后重新加载该场景全部 ACTIVE 快照，刷新倒排索引并按预编译配置预热脚本规则。
     * 使用 @ApplicationModuleListener 确保在 config-svc 事务提交后执行。
     *
     * @param event 规则发布事件
     */
    @ApplicationModuleListener
    public void onRulePublished(RulePublishedEvent event) {
        Map<String, List<RuleVersionSnapshot>> byEventType =
                loader.loadBySceneWithStrategy(event.tenantId(), event.sceneCode(), index);
        // 同一快照可分属多个 eventType 桶,按引用去重后预热一次
        Set<RuleVersionSnapshot> distinct = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Map.Entry<String, List<RuleVersionSnapshot>> entry : byEventType.entrySet()) {
            index.update(event.tenantId(), event.sceneCode(),
                         entry.getKey(), entry.getValue());
            distinct.addAll(entry.getValue());
        }
        scriptWarmer.warmUpIfEager(new ArrayList<>(distinct));
    }
}
