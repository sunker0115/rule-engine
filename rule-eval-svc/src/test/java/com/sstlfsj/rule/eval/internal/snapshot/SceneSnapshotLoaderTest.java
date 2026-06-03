package com.sstlfsj.rule.eval.internal.snapshot;

import com.sstlfsj.rule.eval.internal.mapper.RuleVersionReadMapper;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 验证 SceneSnapshotLoader 的分组逻辑：
 * v1 快照不含 triggerEventTypes，所有快照归入 "*" 通配桶。
 */
@ExtendWith(MockitoExtension.class)
class SceneSnapshotLoaderTest {

    @Mock
    RuleVersionReadMapper mapper;

    @Spy
    AstJsonCodec codec = new AstJsonCodec();

    @Mock
    SnapshotAssembler assembler;

    @InjectMocks
    SceneSnapshotLoader loader;

    /** loadByScene 返回空列表时，结果 Map 为空。 */
    @Test
    void loadByScene_emptyRows_returnsEmptyMap() {
        when(mapper.loadActiveByScene(1L, "fraud_check")).thenReturn(List.of());
        when(assembler.assembleAll(List.of())).thenReturn(List.of());

        Map<String, List<RuleVersionSnapshot>> result = loader.loadByScene(1L, "fraud_check");

        assertTrue(result.isEmpty());
    }

    /** loadByScene 有数据时，所有快照归入 "*" 桶。 */
    @Test
    void loadByScene_withSnapshots_groupedUnderWildcard() {
        RuleVersionRow row = new RuleVersionRow(
                42L, "fraud_check", 1L,
                "{\"type\":\"ConditionNode\",\"conditionType\":\"GT\",\"metricCode\":\"score\",\"params\":{}}",
                "[]", "[{\"decisionCode\":\"REJECT\",\"priority\":10}]", "[]"
        );
        RuleVersionSnapshot snap = new RuleVersionSnapshot(42L, "fraud_check", "1", null, List.of(),
                List.of(new RuleVersionSnapshot.DecisionBinding("REJECT", 10)));

        when(mapper.loadActiveByScene(1L, "fraud_check")).thenReturn(List.of(row));
        when(assembler.assembleAll(List.of(row))).thenReturn(List.of(snap));

        Map<String, List<RuleVersionSnapshot>> result = loader.loadByScene(1L, "fraud_check");

        // v1 使用 "*" 通配，精确 eventType 不存在
        assertEquals(1, result.size());
        assertTrue(result.containsKey("*"));
        assertEquals(List.of(snap), result.get("*"));
    }

    /** loadByScene 多条快照全部归入同一 "*" 桶，顺序与 assembleAll 输出一致。 */
    @Test
    void loadByScene_multipleSnapshots_allInWildcardBucket() {
        RuleVersionSnapshot snap1 = new RuleVersionSnapshot(1L, "scene", "1", null, List.of(), List.of());
        RuleVersionSnapshot snap2 = new RuleVersionSnapshot(2L, "scene", "1", null, List.of(), List.of());

        when(mapper.loadActiveByScene(1L, "scene")).thenReturn(List.of());
        when(assembler.assembleAll(anyList())).thenReturn(List.of(snap1, snap2));

        Map<String, List<RuleVersionSnapshot>> result = loader.loadByScene(1L, "scene");

        List<RuleVersionSnapshot> bucket = result.get("*");
        assertNotNull(bucket);
        assertEquals(2, bucket.size());
        assertEquals(1L, bucket.get(0).ruleVersionId());
        assertEquals(2L, bucket.get(1).ruleVersionId());
    }

    /** loadById 对应 row 不存在时返回 null。 */
    @Test
    void loadById_notFound_returnsNull() {
        when(mapper.loadById(99L)).thenReturn(null);

        RuleVersionSnapshot result = loader.loadById(99L);

        assertNull(result);
    }

    /** loadById 存在时返回对应快照。 */
    @Test
    void loadById_found_returnsSnapshot() {
        RuleVersionRow row = new RuleVersionRow(
                7L, "s1", 1L,
                "{\"type\":\"ConditionNode\",\"conditionType\":\"EQ\",\"metricCode\":null,\"params\":{}}",
                "[]", "[]", "[]"
        );
        RuleVersionSnapshot snap = new RuleVersionSnapshot(7L, "s1", "1", null, List.of(), List.of());

        when(mapper.loadById(7L)).thenReturn(row);
        when(assembler.assembleAll(List.of(row))).thenReturn(List.of(snap));

        RuleVersionSnapshot result = loader.loadById(7L);

        assertNotNull(result);
        assertEquals(7L, result.ruleVersionId());
    }

    /** loadAll 空库返回空 Map。 */
    @Test
    void loadAll_emptyRows_returnsEmptyMap() {
        when(mapper.loadAllActive()).thenReturn(List.of());
        when(assembler.assembleAll(List.of())).thenReturn(List.of());

        Map<String, Map<String, List<RuleVersionSnapshot>>> result = loader.loadAll();

        assertTrue(result.isEmpty());
    }

    /** loadAll 按 tenantId:sceneCode 分外层，"*" 分内层。 */
    @Test
    void loadAll_groupsByTenantAndScene() {
        RuleVersionSnapshot snapA = new RuleVersionSnapshot(1L, "sceneA", "t1", null, List.of(), List.of());
        RuleVersionSnapshot snapB = new RuleVersionSnapshot(2L, "sceneB", "t1", null, List.of(), List.of());

        when(mapper.loadAllActive()).thenReturn(List.of());
        when(assembler.assembleAll(anyList())).thenReturn(List.of(snapA, snapB));

        Map<String, Map<String, List<RuleVersionSnapshot>>> result = loader.loadAll();

        assertEquals(2, result.size());
        assertTrue(result.containsKey("t1:sceneA"));
        assertTrue(result.containsKey("t1:sceneB"));
        assertEquals(List.of(snapA), result.get("t1:sceneA").get("*"));
        assertEquals(List.of(snapB), result.get("t1:sceneB").get("*"));
    }
}
