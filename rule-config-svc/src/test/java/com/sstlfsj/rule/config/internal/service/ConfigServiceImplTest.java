package com.sstlfsj.rule.config.internal.service;

import com.sstlfsj.rule.config.internal.domain.AuditLog;
import com.sstlfsj.rule.config.internal.domain.RuleDefinition;
import com.sstlfsj.rule.config.internal.publish.PublishService;
import com.sstlfsj.rule.config.internal.repository.AuditLogMapper;
import com.sstlfsj.rule.config.internal.repository.RuleDefinitionMapper;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConfigServiceImplTest {

    @Mock PublishService publishService;
    @Mock RuleDefinitionMapper ruleDefinitionMapper;
    @Mock AuditLogMapper auditLogMapper;
    @InjectMocks ConfigServiceImpl configService;

    @Test
    void publish_delegates_to_publishService() {
        RuleVersionSnapshot expected = new RuleVersionSnapshot(
                42L, "PAYMENT", "1",
                new ConditionNode("c.type", null, null, Map.of()),
                List.of(), List.of(), null
        );
        when(publishService.publish(1L, 10L, "actor1")).thenReturn(expected);

        RuleVersionSnapshot result = configService.publish("1", 10L, "actor1");

        assertThat(result.ruleVersionId()).isEqualTo(42L);
        verify(publishService).publish(1L, 10L, "actor1");
    }

    @Test
    void disable_updatesStatusAndWritesAuditLog() {
        RuleDefinition rule = new RuleDefinition();
        rule.setId(10L);
        rule.setTenantId(1L);
        rule.setStatus("PUBLISHED");
        when(ruleDefinitionMapper.selectById(10L)).thenReturn(rule);
        when(ruleDefinitionMapper.updateById((RuleDefinition) any())).thenReturn(1);
        when(auditLogMapper.insert((AuditLog) any())).thenReturn(1);

        configService.disable("1", 10L, "actor1");

        ArgumentCaptor<RuleDefinition> rdCaptor = ArgumentCaptor.forClass(RuleDefinition.class);
        verify(ruleDefinitionMapper).updateById(rdCaptor.capture());
        assertThat(rdCaptor.getValue().getStatus()).isEqualTo("DISABLED");

        verify(auditLogMapper).insert((AuditLog) any());
    }
}
