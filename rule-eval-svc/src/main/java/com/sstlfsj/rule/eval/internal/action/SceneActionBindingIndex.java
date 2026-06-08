package com.sstlfsj.rule.eval.internal.action;

import com.sstlfsj.rule.config.api.event.SceneChangedEvent;
import com.sstlfsj.rule.eval.internal.domain.SceneActionBindingFullRow;
import com.sstlfsj.rule.eval.internal.domain.SceneActionBindingRow;
import com.sstlfsj.rule.eval.internal.repository.SceneActionBindingReadMapper;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * scene_action_binding 内存索引，key = "tenantId:sceneCode"。
 *
 * <p>启动期全量载入 + 监听 {@link SceneChangedEvent} 热更，使 action 派发零 DB 读
 * （binding 是场景配置：写极少、读极热）。与 {@code SceneRuleIndex} 同款的「启动载入 + 场景事件刷新」模式。
 *
 * <p>失效覆盖：当前仅 {@link SceneChangedEvent}（场景启停）触发刷新。将来若新增 binding 写 API，
 * 须由其发布场景级变更事件，否则纯 binding 改动在进程重启前不会生效（启动全量载入是唯一兜底）。
 */
@Component
public class SceneActionBindingIndex {

    private final SceneActionBindingReadMapper mapper;
    private final Map<String, List<SceneActionBindingRow>> byScene = new ConcurrentHashMap<>();

    public SceneActionBindingIndex(SceneActionBindingReadMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 返回指定租户 + 场景的 Action 绑定，缺省返回空列表（不触发 DB）。
     *
     * @param tenantId  租户 ID
     * @param sceneCode 场景编码
     * @return 该场景的绑定列表，无绑定时为空列表
     */
    public List<SceneActionBindingRow> get(Long tenantId, String sceneCode) {
        return byScene.getOrDefault(key(tenantId, sceneCode), List.of());
    }

    /** 应用就绪后全量载入所有场景的 binding（ApplicationReadyEvent，接请求前）。 */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        byScene.clear();
        Map<String, List<SceneActionBindingRow>> grouped = mapper.findAll().stream()
                .collect(Collectors.groupingBy(
                        r -> key(r.tenantId(), r.sceneCode()),
                        Collectors.mapping(
                                r -> new SceneActionBindingRow(r.actionType(), r.defaultParamsJson()),
                                Collectors.toList())));
        byScene.putAll(grouped);
    }

    /**
     * 场景变更时刷新该场景 binding：禁用 → 移除；启用 → 重载（蹭现成 SceneChangedEvent，与规则索引同源失效）。
     *
     * @param event 场景变更事件
     */
    @ApplicationModuleListener
    public void onSceneChanged(SceneChangedEvent event) {
        Long tenantId = Long.valueOf(event.tenantId());
        if (!event.active()) {
            byScene.remove(key(tenantId, event.sceneCode()));
            return;
        }
        byScene.put(key(tenantId, event.sceneCode()),
                mapper.findBySceneCode(tenantId, event.sceneCode()));
    }

    private static String key(Long tenantId, String sceneCode) {
        return tenantId + ":" + sceneCode;
    }
}
