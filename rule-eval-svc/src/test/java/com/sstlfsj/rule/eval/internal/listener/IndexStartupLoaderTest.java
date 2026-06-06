package com.sstlfsj.rule.eval.internal.listener;

import com.sstlfsj.rule.kernel.internal.index.SceneRuleIndex;
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
class IndexStartupLoaderTest {

    @Mock SceneRuleIndex index;
    @Mock SceneSnapshotLoader loader;
    @InjectMocks IndexStartupLoader startupLoader;

    /** loadAll 返回空 Map 时，不调用 index.update。 */
    @Test
    void onApplicationReady_emptySnapshot_noIndexUpdate() {
        when(loader.loadAllWithStrategy(index)).thenReturn(Map.of());

        startupLoader.onApplicationReady();

        verifyNoInteractions(index);
    }

    /** loadAll 返回数据时，按 tenantId:sceneCode 拆分后写入索引。 */
    @Test
    void onApplicationReady_withSnapshots_updatesIndex() {
        RuleVersionSnapshot snap = new RuleVersionSnapshot(1L, "fraud_check", "t1",
                null, List.of(), List.of(), null, null);
        Map<String, Map<String, List<RuleVersionSnapshot>>> all = Map.of(
                "t1:fraud_check", Map.of("*", List.of(snap))
        );
        when(loader.loadAllWithStrategy(index)).thenReturn(all);

        startupLoader.onApplicationReady();

        verify(index).update("t1", "fraud_check", "*", List.of(snap));
    }

    /** 多个场景各自独立写入索引，每个 (tenantId, sceneCode, eventType) 都调用一次 update。 */
    @Test
    void onApplicationReady_multipleScenes_updatesEachSeparately() {
        RuleVersionSnapshot snapA = new RuleVersionSnapshot(1L, "sceneA", "t1", null, List.of(), List.of(), null, null);
        RuleVersionSnapshot snapB = new RuleVersionSnapshot(2L, "sceneB", "t1", null, List.of(), List.of(), null, null);
        Map<String, Map<String, List<RuleVersionSnapshot>>> all = Map.of(
                "t1:sceneA", Map.of("*", List.of(snapA)),
                "t1:sceneB", Map.of("*", List.of(snapB))
        );
        when(loader.loadAllWithStrategy(index)).thenReturn(all);

        startupLoader.onApplicationReady();

        verify(index).update("t1", "sceneA", "*", List.of(snapA));
        verify(index).update("t1", "sceneB", "*", List.of(snapB));
        verifyNoMoreInteractions(index);
    }
}
