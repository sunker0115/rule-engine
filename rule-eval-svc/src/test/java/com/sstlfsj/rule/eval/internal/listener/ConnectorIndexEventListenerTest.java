package com.sstlfsj.rule.eval.internal.listener;

import com.sstlfsj.rule.config.api.event.ConnectorChangedEvent;
import com.sstlfsj.rule.eval.internal.metric.http.ConnectorDefinitionResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ConnectorIndexEventListenerTest {

    @Mock ConnectorDefinitionResolver resolver;
    @InjectMocks ConnectorIndexEventListener listener;

    /** 连接器变更事件触发对应租户与编码的缓存失效。 */
    @Test
    void onConnectorChanged_invalidatesResolverCache() {
        ConnectorChangedEvent event = new ConnectorChangedEvent("7", "risk-svc");

        listener.onConnectorChanged(event);

        verify(resolver).invalidate(7L, "risk-svc");
    }
}
