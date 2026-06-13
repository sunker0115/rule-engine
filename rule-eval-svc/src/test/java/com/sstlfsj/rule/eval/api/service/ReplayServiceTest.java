package com.sstlfsj.rule.eval.api.service;

import com.sstlfsj.rule.kernel.api.model.EvalResult;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/** ReplayService 契约：replay(String tenantId, Long sessionId) -> EvalResult。行为测试见 ReplayServiceImplTest。 */
class ReplayServiceTest {

    @Test
    void declaresReplay_withTenantAndSessionId_returningEvalResult() throws Exception {
        Method m = ReplayService.class.getMethod("replay", String.class, Long.class);
        assertThat(m.getReturnType()).isEqualTo(EvalResult.class);
    }
}
