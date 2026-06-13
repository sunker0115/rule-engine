package com.sstlfsj.rule.web.admin;

import com.sstlfsj.rule.eval.api.service.ReplayService;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReplayControllerTest {

    @Test
    void replay_delegatesToService_withTenantAndSessionId() {
        EvalResult expected = EvalResult.hit();
        ReplayService svc = (tenantId, sessionId) -> {
            assertThat(tenantId).isEqualTo("1");
            assertThat(sessionId).isEqualTo(100L);
            return expected;
        };
        ReplayController c = new ReplayController(svc);

        assertThat(c.replay(100L, "1").data()).isSameAs(expected);
    }
}
