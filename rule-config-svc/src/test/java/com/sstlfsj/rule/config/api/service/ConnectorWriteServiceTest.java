package com.sstlfsj.rule.config.api.service;

import com.sstlfsj.rule.config.api.connector.AuthKind;
import com.sstlfsj.rule.config.api.connector.CompareOp;
import com.sstlfsj.rule.config.api.connector.ConnectorDescriptor;
import com.sstlfsj.rule.config.api.connector.HttpMethod;
import com.sstlfsj.rule.config.api.connector.HttpRequestTemplate;
import com.sstlfsj.rule.config.api.connector.Predicate;
import com.sstlfsj.rule.config.api.connector.ResiliencePolicy;
import com.sstlfsj.rule.config.api.connector.ResponseMapping;
import com.sstlfsj.rule.config.api.connector.StaticHeaderAuth;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 验证 ConnectorWriteService 内嵌 record ConnectorWriteCommand / ConnectorView 的构造与 accessor。 */
class ConnectorWriteServiceTest {

    private ConnectorDescriptor descriptor() {
        return ConnectorDescriptor.builder()
                .endpointRef("risk")
                .request(HttpRequestTemplate.builder()
                        .method(HttpMethod.GET).pathTemplate("/s/{payload.id}")
                        .query(List.of()).headers(List.of()).bodyTemplate(null).build())
                .response(new ResponseMapping(new Predicate("ok", CompareOp.EQ, true), "v"))
                .auth(new StaticHeaderAuth("X-Key", "k"))
                .resilience(ResiliencePolicy.builder()
                        .connectTimeoutMs(200).readTimeoutMs(300).retries(0)
                        .retryOn(Set.of()).circuitBreaker(null).build())
                .errorMapping(List.of())
                .build();
    }

    @Test
    void connectorWriteCommand_recordAccessors() {
        ConnectorDescriptor d = descriptor();
        var cmd = new ConnectorWriteService.ConnectorWriteCommand("风控打分", d);

        assertEquals("风控打分", cmd.name());
        assertSame(d, cmd.descriptor());
        assertEquals(AuthKind.STATIC_HEADER, cmd.descriptor().auth().kind());
    }

    @Test
    void connectorWriteCommand_recordEquality() {
        ConnectorDescriptor d = descriptor();
        var a = new ConnectorWriteService.ConnectorWriteCommand("名称", d);
        var b = new ConnectorWriteService.ConnectorWriteCommand("名称", d);
        assertEquals(a, b);
    }

    @Test
    void connectorView_recordAccessors() {
        var view = new ConnectorWriteService.ConnectorView("risk-svc", "风控打分", "ACTIVE");

        assertEquals("risk-svc", view.connectorCode());
        assertEquals("风控打分", view.name());
        assertEquals("ACTIVE", view.status());
    }

    @Test
    void connectorView_recordEquality() {
        var a = new ConnectorWriteService.ConnectorView("risk-svc", "风控打分", "ACTIVE");
        var b = new ConnectorWriteService.ConnectorView("risk-svc", "风控打分", "ACTIVE");
        assertEquals(a, b);
    }
}
