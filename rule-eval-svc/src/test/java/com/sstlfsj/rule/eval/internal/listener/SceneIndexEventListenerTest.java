package com.sstlfsj.rule.eval.internal.listener;

import com.sstlfsj.rule.config.api.event.SceneChangedEvent;
import com.sstlfsj.rule.eval.internal.index.SceneRuleIndex;
import com.sstlfsj.rule.eval.internal.snapshot.SceneSnapshotLoader;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SceneIndexEventListenerTest {

    @Mock SceneRuleIndex index;
    @Mock SceneSnapshotLoader loader;
    @InjectMocks SceneIndexEventListener listener;

    /** 场景禁用时从索引移除，不触发 loader。 */
    @Test
    void onSceneChanged_disabled_removesFromIndex() {
        SceneChangedEvent event = new SceneChangedEvent("1", "fraud_check", false);

        listener.onSceneChanged(event);

        verify(index).remove("1", "fraud_check");
        verifyNoInteractions(loader);
    }

    /** 场景启用时调用 loader 重新加载快照。 */
    @Test
    void onSceneChanged_enabled_reloadsSnapshots() {
        SceneChangedEvent event = new SceneChangedEvent("1", "fraud_check", true);
        when(loader.loadByScene("1", "fraud_check")).thenReturn(Map.of());

        listener.onSceneChanged(event);

        verify(loader).loadByScene("1", "fraud_check");
        verifyNoMoreInteractions(index);
    }

    /** 场景启用且 loader 返回快照时，每个 eventType 桶都写入索引。 */
    @Test
    void onSceneChanged_enabled_updatesIndexForEachEventType() {
        SceneChangedEvent event = new SceneChangedEvent("1", "fraud_check", true);
        RuleVersionSnapshot snap = new RuleVersionSnapshot(42L, "fraud_check", "1",
                null, List.of(), List.of(), null);
        when(loader.loadByScene("1", "fraud_check"))
                .thenReturn(Map.of("*", List.of(snap)));

        listener.onSceneChanged(event);

        verify(index).update("1", "fraud_check", "*", List.of(snap));
    }
}
