package com.sstlfsj.rule.eval.internal.snapshot;

import com.sstlfsj.rule.eval.internal.repository.RuleVersionReadMapper;
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
 * triggerEventTypes 为空时归入 "*" 通配桶，非空时按实际值分桶。
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

        Map<String, List<RuleVersionSnapshot>> result = loader.loadByScene("1", "fraud_check");

        assertTrue(result.isEmpty());
    }

    /** triggerEventTypes 为空时，快照归入 "*" 通配桶。 */
    @Test
    void loadByScene_emptyTriggerEventTypes_groupedUnderWildcard() {
        RuleVersionRow row = new RuleVersionRow(
                42L, "fraud_check", 1L,
                "{\"type\":\"ConditionNode\",\"conditionType\":\"GT\",\"metricCode\":\"score\",\"params\":{}}",
                "[]", "[{\"decisionCode\":\"REJECT\",\"priority\":10}]", "[]", "AST_BOOLEAN"
        );
        RuleVersionSnapshot snap = new RuleVersionSnapshot(42L, "fraud_check", "1", null, List.of(),
                List.of(new RuleVersionSnapshot.DecisionBinding("REJECT", 10)), List.of(), null);

        when(mapper.loadActiveByScene(1L, "fraud_check")).thenReturn(List.of(row));
        when(assembler.assembleAll(List.of(row))).thenReturn(List.of(snap));

        Map<String, List<RuleVersionSnapshot>> result = loader.loadByScene("1", "fraud_check");

        assertEquals(1, result.size());
        assertTrue(result.containsKey("*"));
        assertEquals(List.of(snap), result.get("*"));
    }

    /** triggerEventTypes 非空时，快照按实际 eventType 分桶，不归入通配桶。 */
    @Test
    void loadByScene_withTriggerEventTypes_groupedByExactEventType() {
        RuleVersionSnapshot snap = new RuleVersionSnapshot(
                10L, "fraud_check", "1", null, List.of(), List.of(), List.of("login", "payment"), null);

        when(mapper.loadActiveByScene(1L, "fraud_check")).thenReturn(List.of());
        when(assembler.assembleAll(anyList())).thenReturn(List.of(snap));

        Map<String, List<RuleVersionSnapshot>> result = loader.loadByScene("1", "fraud_check");

        assertFalse(result.containsKey("*"), "精确 eventType 不应归入通配桶");
        assertEquals(List.of(snap), result.get("login"));
        assertEquals(List.of(snap), result.get("payment"));
    }

    /** 混合场景：部分快照通配，部分精确，各自归入对应桶。 */
    @Test
    void loadByScene_mixedSnapshots_correctBuckets() {
        RuleVersionSnapshot snapWild = new RuleVersionSnapshot(
                1L, "scene", "1", null, List.of(), List.of(), List.of(), null);
        RuleVersionSnapshot snapExact = new RuleVersionSnapshot(
                2L, "scene", "1", null, List.of(), List.of(), List.of("login"), null);

        when(mapper.loadActiveByScene(1L, "scene")).thenReturn(List.of());
        when(assembler.assembleAll(anyList())).thenReturn(List.of(snapWild, snapExact));

        Map<String, List<RuleVersionSnapshot>> result = loader.loadByScene("1", "scene");

        assertEquals(List.of(snapWild), result.get("*"));
        assertEquals(List.of(snapExact), result.get("login"));
    }

    /** loadByScene 多条空 triggerEventTypes 快照全部归入同一 "*" 桶。 */
    @Test
    void loadByScene_multipleWildcardSnapshots_allInWildcardBucket() {
        RuleVersionSnapshot snap1 = new RuleVersionSnapshot(1L, "scene", "1", null, List.of(), List.of(), null, null);
        RuleVersionSnapshot snap2 = new RuleVersionSnapshot(2L, "scene", "1", null, List.of(), List.of(), null, null);

        when(mapper.loadActiveByScene(1L, "scene")).thenReturn(List.of());
        when(assembler.assembleAll(anyList())).thenReturn(List.of(snap1, snap2));

        Map<String, List<RuleVersionSnapshot>> result = loader.loadByScene("1", "scene");

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
                "[]", "[]", "[]", "AST_BOOLEAN"
        );
        RuleVersionSnapshot snap = new RuleVersionSnapshot(7L, "s1", "1", null, List.of(), List.of(), null, null);

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

    /** loadAll 空 triggerEventTypes 时按 tenantId:sceneCode 分外层，"*" 分内层。 */
    @Test
    void loadAll_groupsByTenantAndScene_wildcard() {
        RuleVersionSnapshot snapA = new RuleVersionSnapshot(1L, "sceneA", "t1", null, List.of(), List.of(), null, null);
        RuleVersionSnapshot snapB = new RuleVersionSnapshot(2L, "sceneB", "t1", null, List.of(), List.of(), null, null);

        when(mapper.loadAllActive()).thenReturn(List.of());
        when(assembler.assembleAll(anyList())).thenReturn(List.of(snapA, snapB));

        Map<String, Map<String, List<RuleVersionSnapshot>>> result = loader.loadAll();

        assertEquals(2, result.size());
        assertTrue(result.containsKey("t1:sceneA"));
        assertTrue(result.containsKey("t1:sceneB"));
        assertEquals(List.of(snapA), result.get("t1:sceneA").get("*"));
        assertEquals(List.of(snapB), result.get("t1:sceneB").get("*"));
    }

    /** loadAll 精确 triggerEventTypes 时按实际 eventType 分内层桶。 */
    @Test
    void loadAll_groupsByTenantAndScene_exactEventType() {
        RuleVersionSnapshot snap = new RuleVersionSnapshot(
                3L, "sceneA", "t1", null, List.of(), List.of(), List.of("login"), null);

        when(mapper.loadAllActive()).thenReturn(List.of());
        when(assembler.assembleAll(anyList())).thenReturn(List.of(snap));

        Map<String, Map<String, List<RuleVersionSnapshot>>> result = loader.loadAll();

        assertFalse(result.get("t1:sceneA").containsKey("*"));
        assertEquals(List.of(snap), result.get("t1:sceneA").get("login"));
    }
}
