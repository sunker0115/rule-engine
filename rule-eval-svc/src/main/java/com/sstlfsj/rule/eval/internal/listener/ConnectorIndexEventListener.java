package com.sstlfsj.rule.eval.internal.listener;

import com.sstlfsj.rule.config.api.event.ConnectorChangedEvent;
import com.sstlfsj.rule.eval.internal.metric.http.ConnectorDefinitionResolver;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/** 监听 ConnectorChangedEvent（A 类跨模块集成，提交后异步），失效该连接器的解析缓存。 */
@Component
public class ConnectorIndexEventListener {

    private final ConnectorDefinitionResolver resolver;

    public ConnectorIndexEventListener(ConnectorDefinitionResolver resolver) {
        this.resolver = resolver;
    }

    /**
     * 收到连接器变更事件 → 失效该连接器缓存，下次评估取最新描述符。
     *
     * @param event 连接器变更事件
     */
    @ApplicationModuleListener
    public void onConnectorChanged(ConnectorChangedEvent event) {
        resolver.invalidate(Long.valueOf(event.tenantId()), event.connectorCode());
    }
}
