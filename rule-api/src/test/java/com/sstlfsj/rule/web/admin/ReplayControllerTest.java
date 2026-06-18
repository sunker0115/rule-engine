package com.sstlfsj.rule.web.admin;

import com.sstlfsj.rule.config.api.service.SceneService;
import com.sstlfsj.rule.eval.api.service.ReplayService;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.NodeTrace;
import com.sstlfsj.rule.web.mask.SensitiveRefsResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReplayControllerTest {

    private ReplayService replayService;
    private SensitiveRefsResolver sensitiveRefsResolver;
    private ReplayController replayController;

    @BeforeEach
    void setUp() {
        replayService = mock(ReplayService.class);
        sensitiveRefsResolver = mock(SensitiveRefsResolver.class);
        replayController = new ReplayController(replayService, sensitiveRefsResolver);
    }

    @Test
    void replay_delegatesToService_withTenantAndSessionId() {
        EvalResult expected = EvalResult.hit();
        when(replayService.replay(1L, 100L)).thenReturn(expected);

        assertThat(replayController.replay(100L, 1L).data().ruleHit()).isTrue();
    }

    @Test
    void replay_masksSensitiveMetricLeaf() {
        when(sensitiveRefsResolver.forSession(100L, 1L))
                .thenReturn(new SceneService.SensitiveRefs(Set.of(), Set.of("user.idno")));
        NodeTrace leaf = new NodeTrace(
                "ConditionNode", "EQ", "user.idno", true, "511...", "FETCHED",
                null, List.of(), 1L, "ruleA", 1L, null, null);
        when(replayService.replay(100L, 1L))
                .thenReturn(new EvalResult(true, null, List.of(), List.of(leaf),
                        null, null, null, null));

        var resp = replayController.replay(1L, 100L);

        assertThat(resp.data().nodeTrace().get(0).actualValue()).isEqualTo("***");
    }
}
