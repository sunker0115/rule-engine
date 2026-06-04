package com.sstlfsj.rule.eval.internal.listener;

import com.sstlfsj.rule.config.api.event.RulePublishedEvent;
import com.sstlfsj.rule.config.api.event.SceneChangedEvent;
import com.sstlfsj.rule.kernel.internal.index.SceneRuleIndex;
import com.sstlfsj.rule.eval.internal.snapshot.SceneSnapshotLoader;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RuleIndexEventListenerTest {

    @Mock SceneRuleIndex index;
    @Mock SceneSnapshotLoader loader;
    @InjectMocks RuleIndexEventListener ruleListener;
    @InjectMocks SceneIndexEventListener sceneListener;

    @Test
    void onRulePublished_reloadsSnapshotsForScene() {
        RulePublishedEvent event = new RulePublishedEvent("1", "fraud_check", 42L);
        ConditionNode condNode = new ConditionNode("GT", "score", null, Map.of(), 0.0);
        RuleVersionSnapshot snap = new RuleVersionSnapshot(
                42L, "fraud_check", "1", condNode, List.of(),
                List.of(new RuleVersionSnapshot.DecisionBinding("REJECT", 10)), null, null);
        when(loader.loadByScene("1", "fraud_check")).thenReturn(
                Map.of("RISK_EVENT", List.of(snap)));

        ruleListener.onRulePublished(event);

        verify(index).update("1", "fraud_check", "RISK_EVENT", List.of(snap));
    }

    @Test
    void onSceneDisabled_removesFromIndex() {
        SceneChangedEvent event = new SceneChangedEvent("1", "fraud_check", false);

        sceneListener.onSceneChanged(event);

        verify(index).remove("1", "fraud_check");
        verifyNoInteractions(loader);
    }

    @Test
    void onSceneEnabled_reloadsSnapshots() {
        ConditionNode condNode = new ConditionNode("EQ", "status", null, Map.of(), 0.0);
        RuleVersionSnapshot snap = new RuleVersionSnapshot(
                10L, "fraud_check", "1", condNode, List.of(), List.of(), null, null);
        SceneChangedEvent event = new SceneChangedEvent("1", "fraud_check", true);
        when(loader.loadByScene("1", "fraud_check")).thenReturn(Map.of("*", List.of(snap)));

        sceneListener.onSceneChanged(event);

        verify(loader).loadByScene("1", "fraud_check");
        verify(index).update("1", "fraud_check", "*", List.of(snap));
    }
}
