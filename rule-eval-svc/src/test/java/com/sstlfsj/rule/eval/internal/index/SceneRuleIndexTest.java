package com.sstlfsj.rule.eval.internal.index;

import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 验证 SceneRuleIndex 的 match / update / remove 行为。 */
class SceneRuleIndexTest {

    private SceneRuleIndex index;

    @BeforeEach
    void setUp() {
        index = new SceneRuleIndex();
    }

    @Test
    void match_returnsEmptyListWhenNoEntry() {
        List<RuleVersionSnapshot> result = index.match("t1", "scene1", "ORDER_PLACED");
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void update_thenMatch_returnsStoredSnapshots() {
        RuleVersionSnapshot snapshot = new RuleVersionSnapshot(1L, "scene1", "t1", null, null, null);
        index.update("t1", "scene1", "ORDER_PLACED", List.of(snapshot));

        List<RuleVersionSnapshot> result = index.match("t1", "scene1", "ORDER_PLACED");
        assertEquals(1, result.size());
        assertEquals(1L, result.get(0).ruleVersionId());
    }

    @Test
    void match_differentKey_returnsEmpty() {
        RuleVersionSnapshot snapshot = new RuleVersionSnapshot(1L, "scene1", "t1", null, null, null);
        index.update("t1", "scene1", "ORDER_PLACED", List.of(snapshot));

        assertTrue(index.match("t1", "scene1", "OTHER_EVENT").isEmpty());
        assertTrue(index.match("t2", "scene1", "ORDER_PLACED").isEmpty());
    }

    @Test
    void remove_deletesAllEntriesForTenantAndScene() {
        RuleVersionSnapshot s1 = new RuleVersionSnapshot(1L, "scene1", "t1", null, null, null);
        RuleVersionSnapshot s2 = new RuleVersionSnapshot(2L, "scene1", "t1", null, null, null);
        index.update("t1", "scene1", "ORDER_PLACED", List.of(s1));
        index.update("t1", "scene1", "ORDER_SHIPPED", List.of(s2));

        index.remove("t1", "scene1");

        assertTrue(index.match("t1", "scene1", "ORDER_PLACED").isEmpty());
        assertTrue(index.match("t1", "scene1", "ORDER_SHIPPED").isEmpty());
    }

    @Test
    void remove_doesNotAffectOtherScenes() {
        RuleVersionSnapshot s1 = new RuleVersionSnapshot(1L, "scene1", "t1", null, null, null);
        RuleVersionSnapshot s2 = new RuleVersionSnapshot(2L, "scene2", "t1", null, null, null);
        index.update("t1", "scene1", "E1", List.of(s1));
        index.update("t1", "scene2", "E1", List.of(s2));

        index.remove("t1", "scene1");

        assertTrue(index.match("t1", "scene1", "E1").isEmpty());
        assertEquals(1, index.match("t1", "scene2", "E1").size());
    }

    @Test
    void update_returnsImmutableList() {
        RuleVersionSnapshot snapshot = new RuleVersionSnapshot(1L, "scene1", "t1", null, null, null);
        index.update("t1", "scene1", "E1", List.of(snapshot));

        List<RuleVersionSnapshot> result = index.match("t1", "scene1", "E1");
        assertThrows(UnsupportedOperationException.class, () -> result.add(snapshot));
    }

    /** 只有通配 "*" 条目时，match 任意 eventType 均返回通配桶内容。 */
    @Test
    void match_wildcardOnly_returnedForAnyEventType() {
        RuleVersionSnapshot snap = new RuleVersionSnapshot(10L, "scene1", "t1", null, null, null);
        index.update("t1", "scene1", "*", List.of(snap));

        List<RuleVersionSnapshot> result = index.match("t1", "scene1", "ORDER_PLACED");
        assertEquals(1, result.size());
        assertEquals(10L, result.get(0).ruleVersionId());
    }

    /** 精确 key 和通配 key 同时存在时，match 返回两者去重合并。 */
    @Test
    void match_exactAndWildcard_returnsMerged() {
        RuleVersionSnapshot snapExact    = new RuleVersionSnapshot(1L, "scene1", "t1", null, null, null);
        RuleVersionSnapshot snapWildcard = new RuleVersionSnapshot(2L, "scene1", "t1", null, null, null);
        index.update("t1", "scene1", "ORDER_PLACED", List.of(snapExact));
        index.update("t1", "scene1", "*", List.of(snapWildcard));

        List<RuleVersionSnapshot> result = index.match("t1", "scene1", "ORDER_PLACED");
        assertEquals(2, result.size());
        assertTrue(result.stream().anyMatch(s -> s.ruleVersionId().equals(1L)));
        assertTrue(result.stream().anyMatch(s -> s.ruleVersionId().equals(2L)));
    }

    /** 精确 key 和通配 key 中有相同快照时，合并结果不出现重复。 */
    @Test
    void match_exactAndWildcard_deduplicatesBySameVersionId() {
        RuleVersionSnapshot snap = new RuleVersionSnapshot(5L, "scene1", "t1", null, null, null);
        index.update("t1", "scene1", "ORDER_PLACED", List.of(snap));
        index.update("t1", "scene1", "*", List.of(snap));

        List<RuleVersionSnapshot> result = index.match("t1", "scene1", "ORDER_PLACED");
        assertEquals(1, result.size());
        assertEquals(5L, result.get(0).ruleVersionId());
    }
}
