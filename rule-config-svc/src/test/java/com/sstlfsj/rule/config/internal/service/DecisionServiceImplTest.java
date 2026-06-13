package com.sstlfsj.rule.config.internal.service;

import com.sstlfsj.rule.config.internal.domain.DecisionDefinition;
import com.sstlfsj.rule.config.internal.domain.DecisionStatus;
import com.sstlfsj.rule.config.internal.event.OperationAuditedEvent;
import com.sstlfsj.rule.config.internal.repository.DecisionDefinitionMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/** DecisionServiceImpl 单元测试：tenant 级 CRUD + 审计事件。 */
class DecisionServiceImplTest {

    private final DecisionDefinitionMapper mapper = mock(DecisionDefinitionMapper.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final DecisionServiceImpl svc = new DecisionServiceImpl(mapper, publisher);

    @Test
    void create_persistsActiveAndAudits() {
        svc.create(9001L, "REJECT", "拒绝", 10, "高风险拒绝", "actor");

        ArgumentCaptor<DecisionDefinition> cap = ArgumentCaptor.forClass(DecisionDefinition.class);
        verify(mapper).insert(cap.capture());
        assertThat(cap.getValue().getCode()).isEqualTo("REJECT");
        assertThat(cap.getValue().getStatus()).isEqualTo(DecisionStatus.ACTIVE);
        verify(publisher).publishEvent(any(OperationAuditedEvent.class));
    }

    @Test
    void create_rejectsDuplicateCode() {
        when(mapper.findByCode(9001L, "REJECT")).thenReturn(new DecisionDefinition());
        assertThatThrownBy(() -> svc.create(9001L, "REJECT", "拒绝", 10, null, "actor"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已存在");
    }

    @Test
    void disable_setsStatusDisabled() {
        DecisionDefinition existing = new DecisionDefinition();
        existing.setId(7L); existing.setTenantId(9001L); existing.setCode("REJECT");
        existing.setStatus(DecisionStatus.ACTIVE);
        when(mapper.findByCode(9001L, "REJECT")).thenReturn(existing);

        svc.disable(9001L, "REJECT", "actor");

        ArgumentCaptor<DecisionDefinition> cap = ArgumentCaptor.forClass(DecisionDefinition.class);
        verify(mapper).updateById(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo(DecisionStatus.DISABLED);
    }

    @Test
    void update_rejectsWhenNotFound() {
        when(mapper.findByCode(9001L, "X")).thenReturn(null);
        assertThatThrownBy(() -> svc.update(9001L, "X", "n", 1, null, "actor"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不存在");
    }
}
