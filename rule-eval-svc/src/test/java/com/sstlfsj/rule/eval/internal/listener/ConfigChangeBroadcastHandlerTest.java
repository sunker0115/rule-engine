package com.sstlfsj.rule.eval.internal.listener;

import com.sstlfsj.rule.eval.internal.snapshot.SceneSnapshotLoader;
import com.sstlfsj.rule.eval.internal.snapshot.ScriptWarmer;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.internal.index.SceneRuleIndex;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConfigChangeBroadcastHandlerTest {

    @Test
    void onConfigChange_reloadsSceneByParam() {
        SceneRuleIndex index = mock(SceneRuleIndex.class);
        SceneSnapshotLoader loader = mock(SceneSnapshotLoader.class);
        ScriptWarmer warmer = mock(ScriptWarmer.class);
        Map<String, List<RuleVersionSnapshot>> byType = Map.of();
        when(loader.loadBySceneWithStrategy(eq("9100"), eq("fraud_check"), any())).thenReturn(byType);

        ConfigChangeBroadcastHandler handler =
                new ConfigChangeBroadcastHandler(null, index, loader, warmer);
        handler.onConfigChange("scene:9100:fraud_check:true");

        verify(loader).loadBySceneWithStrategy(eq("9100"), eq("fraud_check"), any());
        verify(index).replaceScene(eq("9100"), eq("fraud_check"), eq(byType));
    }

    @Test
    void onConfigChange_ruleParam_alsoReloads() {
        SceneRuleIndex index = mock(SceneRuleIndex.class);
        SceneSnapshotLoader loader = mock(SceneSnapshotLoader.class);
        ScriptWarmer warmer = mock(ScriptWarmer.class);
        when(loader.loadBySceneWithStrategy(eq("9100"), eq("fraud_check"), any())).thenReturn(Map.of());

        ConfigChangeBroadcastHandler handler =
                new ConfigChangeBroadcastHandler(null, index, loader, warmer);
        handler.onConfigChange("rule:9100:fraud_check");

        verify(index).replaceScene(eq("9100"), eq("fraud_check"), any());
    }
}
