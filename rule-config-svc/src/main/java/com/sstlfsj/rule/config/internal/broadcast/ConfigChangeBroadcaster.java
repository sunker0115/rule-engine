package com.sstlfsj.rule.config.internal.broadcast;

import com.sstlfsj.rule.config.api.event.RulePublishedEvent;
import com.sstlfsj.rule.config.api.event.SceneChangedEvent;
import com.sstlfsj.rule.kernel.api.spi.scheduler.Scheduler;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * 把 config 变更经调度后端广播到所有 eval 实例（多实例索引收敛）。
 *
 * <p>用 {@code @ApplicationModuleListener}（Modulith 提交后异步）与 eval-svc 既有索引
 * listener 对齐，保证事务落地后才广播——其他实例回 DB 重载时数据已可见。
 *
 * <p>{@code Scheduler} 是条件装配 bean，用 {@link ObjectProvider} 惰性注入：
 * 无后端时不广播（单 JVM 进程内事件已足够），不致启动失败。
 */
@Component
@RequiredArgsConstructor
public class ConfigChangeBroadcaster {

    private static final String BROADCAST_CODE = "config-change";

    private final ObjectProvider<Scheduler> schedulerProvider;

    @ApplicationModuleListener
    public void onSceneChanged(SceneChangedEvent event) {
        String param = "scene:" + event.tenantId() + ":" + event.sceneCode() + ":" + event.active();
        schedulerProvider.ifAvailable(s -> s.triggerBroadcast(BROADCAST_CODE, param));
    }

    @ApplicationModuleListener
    public void onRulePublished(RulePublishedEvent event) {
        String param = "rule:" + event.tenantId() + ":" + event.sceneCode();
        schedulerProvider.ifAvailable(s -> s.triggerBroadcast(BROADCAST_CODE, param));
    }
}
