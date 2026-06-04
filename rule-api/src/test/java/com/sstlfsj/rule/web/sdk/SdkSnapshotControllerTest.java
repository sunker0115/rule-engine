package com.sstlfsj.rule.web.sdk;

import com.sstlfsj.rule.eval.internal.snapshot.SceneSnapshotLoader;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.ast.AndNode;
import com.sstlfsj.rule.web.common.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SdkSnapshotControllerTest {

    private MockMvc mockMvc;
    private SceneSnapshotLoader snapshotLoader;

    @BeforeEach
    void setUp() {
        snapshotLoader = mock(SceneSnapshotLoader.class);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new SdkSnapshotController(snapshotLoader))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void getSnapshots_declaredScenes_returns200() throws Exception {
        RuleVersionSnapshot snap = new RuleVersionSnapshot(1L, "payment", "t1",
                new AndNode(List.of(), null, null), List.of(),
                List.of(new RuleVersionSnapshot.DecisionBinding("BLOCK", 10)),
                List.of("ORDER"), "AST_BOOLEAN");
        when(snapshotLoader.loadByScene(eq("t1"), eq("payment")))
                .thenReturn(Map.of("ORDER", List.of(snap)));

        mockMvc.perform(get("/api/v1/sdk/snapshots")
                        .param("tenantId", "t1")
                        .param("scenes", "payment"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].ruleVersionId").value(1));
    }

    @Test
    void getSnapshots_missingTenantId_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/sdk/snapshots"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getSnapshots_emptyScenes_callsLoadAll_returns200() throws Exception {
        when(snapshotLoader.loadAll()).thenReturn(Map.of());

        mockMvc.perform(get("/api/v1/sdk/snapshots")
                        .param("tenantId", "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }
}
