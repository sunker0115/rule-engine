package com.sstlfsj.rule.eval.internal.listener;

import com.sstlfsj.rule.eval.internal.snapshot.SceneSnapshotLoader;
import com.sstlfsj.rule.eval.internal.snapshot.ScriptWarmer;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.spi.scheduler.Scheduler;
import com.sstlfsj.rule.kernel.internal.index.SceneRuleIndex;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 跨实例 config 变更广播消费端——收 Scheduler.scheduleBroadcast 透传的 param，
 * 从 DB 全量重载该 scene 的索引（与 RuleIndexEventListener/SceneIndexEventListener 同构，幂等）。
 *
 * <p>Scheduler 条件装配，ObjectProvider 惰性注入：无调度后端时不注册广播 handler。
 */
@Component
public class ConfigChangeBroadcastHandler {

    private static final Logger log = LoggerFactory.getLogger(ConfigChangeBroadcastHandler.class);
    private static final String BROADCAST_CODE = "config-change";

    private final ObjectProvider<Scheduler> schedulerProvider;
    private final SceneRuleIndex index;
    private final SceneSnapshotLoader loader;
    private final ScriptWarmer scriptWarmer;

    public ConfigChangeBroadcastHandler(ObjectProvider<Scheduler> schedulerProvider,
                                        SceneRuleIndex index, SceneSnapshotLoader loader,
                                        ScriptWarmer scriptWarmer) {
        this.schedulerProvider = schedulerProvider;
        this.index = index;
        this.loader = loader;
        this.scriptWarmer = scriptWarmer;
    }

    @PostConstruct
    void register() {
        schedulerProvider.ifAvailable(s -> s.scheduleBroadcast(BROADCAST_CODE, this::onConfigChange));
    }

    /**
     * 广播回调：param 格式 type:tenantId:sceneCode[:active]，全量重载该 scene 索引。
     */
    void onConfigChange(String param) {
        String[] parts = param.split(":");
        if (parts.length < 3) {
            log.warn("config-change 广播 param 非法，跳过 param={}", param);
            return;
        }
        String tenantId = parts[1];
        String sceneCode = parts[2];
        // 全量重载该 scene（含已删除/禁用规则；scene=false 时空结果自动清索引）
        Map<String, List<RuleVersionSnapshot>> byEventType =
                loader.loadBySceneWithStrategy(tenantId, sceneCode, index);
        index.replaceScene(tenantId, sceneCode, byEventType);
        Set<RuleVersionSnapshot> distinct = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Map.Entry<String, List<RuleVersionSnapshot>> entry : byEventType.entrySet()) {
            distinct.addAll(entry.getValue());
        }
        scriptWarmer.warmUpIfEager(new ArrayList<>(distinct));
        log.debug("config-change 广播重载完成 tenant={} scene={}", tenantId, sceneCode);
    }
}
