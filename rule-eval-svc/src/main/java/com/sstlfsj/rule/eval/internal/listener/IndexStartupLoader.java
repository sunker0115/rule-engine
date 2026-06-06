package com.sstlfsj.rule.eval.internal.listener;

import com.sstlfsj.rule.eval.internal.snapshot.SceneSnapshotLoader;
import com.sstlfsj.rule.kernel.internal.index.SceneRuleIndex;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** 应用启动完成后全量加载所有 ACTIVE 规则版本到倒排索引。 */
@Component
public class IndexStartupLoader {

    private final SceneRuleIndex index;
    private final SceneSnapshotLoader loader;

    public IndexStartupLoader(SceneRuleIndex index, SceneSnapshotLoader loader) {
        this.index = index;
        this.loader = loader;
    }

    /**
     * 全量加载所有 ACTIVE 规则快照到内存索引。
     * ApplicationReadyEvent 触发（Spring 上下文就绪后，接收请求前）。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        Map<String, Map<String, List<RuleVersionSnapshot>>> all = loader.loadAllWithStrategy(index);
        for (Map.Entry<String, Map<String, List<RuleVersionSnapshot>>> outerEntry : all.entrySet()) {
            // 外层 key 格式为 tenantId:sceneCode
            String[] parts = outerEntry.getKey().split(":", 2);
            String tenantId = parts[0];
            String sceneCode = parts.length > 1 ? parts[1] : "";
            for (Map.Entry<String, List<RuleVersionSnapshot>> innerEntry : outerEntry.getValue().entrySet()) {
                index.update(tenantId, sceneCode, innerEntry.getKey(), innerEntry.getValue());
            }
        }
    }
}
