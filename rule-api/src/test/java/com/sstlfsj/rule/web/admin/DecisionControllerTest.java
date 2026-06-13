package com.sstlfsj.rule.web.admin;

import com.sstlfsj.rule.config.api.service.DecisionService;
import com.sstlfsj.rule.config.internal.domain.DecisionDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** DecisionController 委托校验：路由方法把参数透传给 DecisionService。 */
class DecisionControllerTest {

    private final DecisionService service = mock(DecisionService.class);
    private final DecisionController controller = new DecisionController(service);

    @Test
    void create_delegatesToService() {
        when(service.create(9001L, "REJECT", "拒绝", 10, "desc", "actor")).thenReturn(7L);

        var resp = controller.create(9001L, "actor",
                new DecisionController.DecisionRequest("REJECT", "拒绝", 10, "desc"));

        assertThat(resp.data()).isEqualTo(7L);
        verify(service).create(9001L, "REJECT", "拒绝", 10, "desc", "actor");
    }

    @Test
    void disable_delegatesToService() {
        controller.disable("REJECT", 9001L, "actor");
        verify(service).disable(9001L, "REJECT", "actor");
    }

    @Test
    void list_delegatesToService() {
        when(service.list(9001L)).thenReturn(List.of(new DecisionDefinition()));
        var resp = controller.list(9001L);
        assertThat(resp.data()).hasSize(1);
    }
}
