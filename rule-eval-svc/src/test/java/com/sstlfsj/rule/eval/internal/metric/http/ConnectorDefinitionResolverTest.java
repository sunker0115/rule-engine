package com.sstlfsj.rule.eval.internal.metric.http;

import com.sstlfsj.rule.config.api.connector.ConnectorDescriptor;
import com.sstlfsj.rule.eval.internal.domain.ConnectorDefinitionRow;
import com.sstlfsj.rule.eval.internal.repository.ConnectorDefinitionReadMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConnectorDefinitionResolverTest {

    @Mock ConnectorDefinitionReadMapper mapper;

    private ConnectorDefinitionRow row(ConnectorDescriptor descriptor) {
        ConnectorDefinitionRow r = new ConnectorDefinitionRow();
        r.setTenantId(1L);
        r.setConnectorCode("risk-svc");
        r.setStatus("ACTIVE");
        r.setDescriptor(descriptor);
        return r;
    }

    @Test
    void resolve_returnsDescriptor() {
        ConnectorDescriptor d = ConnectorDescriptor.builder().endpointRef("risk").build();
        when(mapper.findActive(1L, "risk-svc")).thenReturn(row(d));

        ConnectorDefinitionResolver resolver = new ConnectorDefinitionResolver(mapper);

        assertThat(resolver.resolve(1L, "risk-svc")).isSameAs(d);
    }

    @Test
    void resolve_secondCall_servedFromCache() {
        ConnectorDescriptor d = ConnectorDescriptor.builder().endpointRef("risk").build();
        when(mapper.findActive(1L, "risk-svc")).thenReturn(row(d));
        ConnectorDefinitionResolver resolver = new ConnectorDefinitionResolver(mapper);

        resolver.resolve(1L, "risk-svc");
        resolver.resolve(1L, "risk-svc");

        verify(mapper, times(1)).findActive(1L, "risk-svc");
    }

    @Test
    void resolve_missing_returnsNull_andCachesNegative() {
        when(mapper.findActive(1L, "ghost")).thenReturn(null);
        ConnectorDefinitionResolver resolver = new ConnectorDefinitionResolver(mapper);

        assertThat(resolver.resolve(1L, "ghost")).isNull();
        // 负结果缓存：第二次不再查库
        assertThat(resolver.resolve(1L, "ghost")).isNull();
        verify(mapper, times(1)).findActive(1L, "ghost");
    }

    @Test
    void invalidate_forcesReload() {
        ConnectorDescriptor d = ConnectorDescriptor.builder().endpointRef("risk").build();
        when(mapper.findActive(1L, "risk-svc")).thenReturn(row(d));
        ConnectorDefinitionResolver resolver = new ConnectorDefinitionResolver(mapper);

        resolver.resolve(1L, "risk-svc");
        resolver.invalidate(1L, "risk-svc");
        resolver.resolve(1L, "risk-svc");

        verify(mapper, times(2)).findActive(1L, "risk-svc");
    }
}
