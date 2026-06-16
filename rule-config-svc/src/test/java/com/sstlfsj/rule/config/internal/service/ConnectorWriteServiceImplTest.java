package com.sstlfsj.rule.config.internal.service;

import com.sstlfsj.rule.config.api.connector.CompareOp;
import com.sstlfsj.rule.config.api.connector.ConnectorDescriptor;
import com.sstlfsj.rule.config.api.connector.HttpMethod;
import com.sstlfsj.rule.config.api.connector.HttpRequestTemplate;
import com.sstlfsj.rule.config.api.connector.Predicate;
import com.sstlfsj.rule.config.api.connector.ResiliencePolicy;
import com.sstlfsj.rule.config.api.connector.ResponseMapping;
import com.sstlfsj.rule.config.api.connector.StaticHeaderAuth;
import com.sstlfsj.rule.config.api.event.ConnectorChangedEvent;
import com.sstlfsj.rule.config.api.service.ConnectorWriteService.ConnectorWriteCommand;
import com.sstlfsj.rule.config.internal.domain.ConnectorDefinition;
import com.sstlfsj.rule.config.internal.domain.ConnectorStatus;
import com.sstlfsj.rule.config.internal.event.ConnectorChangedSnapshot;
import com.sstlfsj.rule.config.internal.event.OperationAuditedEvent;
import com.sstlfsj.rule.config.internal.repository.ConnectorDefinitionMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConnectorWriteServiceImplTest {

    private final ConnectorDefinitionMapper mapper = mock(ConnectorDefinitionMapper.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

    private ConnectorWriteCommand cmd() {
        return new ConnectorWriteCommand("风控打分", ConnectorDescriptor.builder()
                .endpointRef("risk")
                .request(HttpRequestTemplate.builder()
                        .method(HttpMethod.GET).pathTemplate("/s/{payload.id}")
                        .query(List.of()).headers(List.of()).bodyTemplate(null).build())
                .response(new ResponseMapping(new Predicate("ok", CompareOp.EQ, true), "v"))
                .auth(new StaticHeaderAuth("X-Key", "k"))
                .resilience(ResiliencePolicy.builder()
                        .connectTimeoutMs(200).readTimeoutMs(300).retries(0)
                        .retryOn(Set.of()).circuitBreaker(null).build())
                .errorMapping(List.of()).build());
    }

    @Test
    void createInsertsActiveAndPublishesChangedEvent() {
        ConnectorWriteServiceImpl svc = newServiceWithEndpoint("risk");
        when(mapper.findByCode(1L, "risk-svc")).thenReturn(null);

        svc.create(1L, "risk-svc", cmd(), "u1");

        ArgumentCaptor<ConnectorDefinition> saved = ArgumentCaptor.forClass(ConnectorDefinition.class);
        verify(mapper).insert(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(ConnectorStatus.ACTIVE);
        assertThat(saved.getValue().getConnectorCode()).isEqualTo("risk-svc");

        verify(events).publishEvent(new ConnectorChangedEvent("1", "risk-svc"));
        verify(events).publishEvent(argThat((Object e) ->
                e instanceof OperationAuditedEvent a
                        && "connector_definition".equals(a.targetType())
                        && "CREATE".equals(a.action())));
    }

    @Test
    void createRejectsDuplicateCode() {
        ConnectorWriteServiceImpl svc = newServiceWithEndpoint("risk");
        when(mapper.findByCode(1L, "risk-svc")).thenReturn(new ConnectorDefinition());

        assertThatThrownBy(() -> svc.create(1L, "risk-svc", cmd(), "u1"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(mapper, never()).insert(any(ConnectorDefinition.class));
    }

    @Test
    void updateRejectsMissingCode() {
        ConnectorWriteServiceImpl svc = newServiceWithEndpoint("risk");
        when(mapper.findByCode(1L, "risk-svc")).thenReturn(null);

        assertThatThrownBy(() -> svc.update(1L, "risk-svc", cmd(), "u1"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(mapper, never()).updateById(any(ConnectorDefinition.class));
    }

    @Test
    void updateAppliesChangesAndPublishesEvents() {
        ConnectorWriteServiceImpl svc = newServiceWithEndpoint("risk");
        ConnectorDefinition existing = new ConnectorDefinition();
        existing.setId(7L);
        existing.setConnectorCode("risk-svc");
        existing.setStatus(ConnectorStatus.ACTIVE);
        when(mapper.findByCode(1L, "risk-svc")).thenReturn(existing);

        svc.update(1L, "risk-svc", cmd(), "u2");

        verify(mapper).updateById(existing);
        assertThat(existing.getName()).isEqualTo("风控打分");
        assertThat(existing.getUpdatedBy()).isEqualTo("u2");
        verify(events).publishEvent(new ConnectorChangedEvent("1", "risk-svc"));
        verify(events).publishEvent(argThat((Object e) ->
                e instanceof OperationAuditedEvent a && "UPDATE".equals(a.action())));
    }

    @Test
    void listActiveMapsStatusToName() {
        ConnectorWriteServiceImpl svc = newServiceWithEndpoint("risk");
        ConnectorDefinition c = new ConnectorDefinition();
        c.setConnectorCode("risk-svc");
        c.setName("风控打分");
        c.setStatus(ConnectorStatus.ACTIVE);
        when(mapper.findActiveByTenant(1L)).thenReturn(List.of(c));

        var views = svc.listActive(1L);

        assertThat(views).hasSize(1);
        assertThat(views.getFirst().status()).isEqualTo("ACTIVE");
        assertThat(views.getFirst().connectorCode()).isEqualTo("risk-svc");
        // createdAt/updatedAt 为 null（mock 对象未设置时间字段）
        assertThat(views.getFirst().createdAt()).isNull();
    }

    @Test
    void disableSetsDisabledAndPublishesBothEvents() {
        ConnectorWriteServiceImpl svc = newServiceWithEndpoint("risk");
        ConnectorDefinition existing = new ConnectorDefinition();
        existing.setId(7L);
        existing.setConnectorCode("risk-svc");
        existing.setName("风控打分");
        existing.setStatus(ConnectorStatus.ACTIVE);
        when(mapper.findByCode(1L, "risk-svc")).thenReturn(existing);

        svc.disable(1L, "risk-svc", "u3");

        verify(mapper).updateById(existing);
        assertThat(existing.getStatus()).isEqualTo(ConnectorStatus.DISABLED);
        assertThat(existing.getUpdatedBy()).isEqualTo("u3");
        verify(events).publishEvent(new ConnectorChangedEvent("1", "risk-svc"));

        // 审计快照须坐实状态变迁：before=ACTIVE、after=DISABLED
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(events, atLeastOnce()).publishEvent(captor.capture());
        OperationAuditedEvent audit = captor.getAllValues().stream()
                .filter(e -> e instanceof OperationAuditedEvent a && "DISABLE".equals(a.action()))
                .map(OperationAuditedEvent.class::cast)
                .findFirst().orElseThrow();
        assertThat(audit.targetType()).isEqualTo("connector_definition");
        assertThat(((ConnectorChangedSnapshot) audit.beforeSnapshot()).status()).isEqualTo("ACTIVE");
        assertThat(((ConnectorChangedSnapshot) audit.afterSnapshot()).status()).isEqualTo("DISABLED");
    }

    @Test
    void disableRejectsMissingCode() {
        ConnectorWriteServiceImpl svc = newServiceWithEndpoint("risk");
        when(mapper.findByCode(1L, "risk-svc")).thenReturn(null);

        assertThatThrownBy(() -> svc.disable(1L, "risk-svc", "u3"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(mapper, never()).updateById(any(ConnectorDefinition.class));
    }

    @Test
    void getByCodeReturnsDetailWithTypedDescriptor() {
        ConnectorWriteServiceImpl svc = newServiceWithEndpoint("risk");
        ConnectorDefinition c = new ConnectorDefinition();
        c.setConnectorCode("risk-svc");
        c.setName("风控打分");
        c.setDescriptor(cmd().descriptor());
        c.setStatus(ConnectorStatus.DISABLED);
        when(mapper.findByCode(1L, "risk-svc")).thenReturn(c);

        var detail = svc.getByCode(1L, "risk-svc");

        assertThat(detail.connectorCode()).isEqualTo("risk-svc");
        assertThat(detail.name()).isEqualTo("风控打分");
        assertThat(detail.status()).isEqualTo("DISABLED");
        assertThat(detail.descriptor()).isSameAs(c.getDescriptor());
        // mock 对象未设置时间，createdAt/updatedAt 为 null
        assertThat(detail.createdAt()).isNull();
    }

    @Test
    void getByCodeRejectsMissingCode() {
        ConnectorWriteServiceImpl svc = newServiceWithEndpoint("risk");
        when(mapper.findByCode(1L, "risk-svc")).thenReturn(null);

        assertThatThrownBy(() -> svc.getByCode(1L, "risk-svc"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // 工厂：构造 impl，注入 endpoint 名集合 {endpoint} 使 ConnectorSafetyValidator 通过
    private ConnectorWriteServiceImpl newServiceWithEndpoint(String endpoint) {
        return new ConnectorWriteServiceImpl(mapper, events, () -> Set.of(endpoint));
    }
}
