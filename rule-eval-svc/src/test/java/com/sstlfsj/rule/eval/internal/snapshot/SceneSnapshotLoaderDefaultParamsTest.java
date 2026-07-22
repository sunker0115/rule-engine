package com.sstlfsj.rule.eval.internal.snapshot;

import com.sstlfsj.rule.eval.internal.repository.RuleVersionReadMapper;
import com.sstlfsj.rule.kernel.internal.codec.RuleVersionRow;
import com.sstlfsj.rule.kernel.internal.codec.SnapshotAssembler;
import com.sstlfsj.rule.kernel.internal.index.SceneRuleIndex;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 验证 scene.default_params 经 RuleVersionRow JOIN 通道流入 SceneRuleIndex。
 * 用真 ObjectMapper 解析,assembler 装配旁路(返回空快照),只验 default_params 写 index。
 */
class SceneSnapshotLoaderDefaultParamsTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    /** defaultParamsJson 经 loadAllWithStrategy 解析并写入 index。 */
    @Test
    void loadAllWithStrategy_writesDefaultParamsToIndex() {
        RuleVersionRow row = new RuleVersionRow(
                1L, "fraud", 1L, "{}", "[]", "[]", "[]", "AST_BOOLEAN", "HIGHEST_PRIORITY",
                null, null, "code", 1L, null, null, null, "{\"timezone\":\"Asia/Shanghai\"}");

        RuleVersionReadMapper mapper = mock(RuleVersionReadMapper.class);
        SnapshotAssembler assembler = mock(SnapshotAssembler.class);
        when(mapper.loadAllActive()).thenReturn(List.of(row));
        when(assembler.assembleAll(List.of(row))).thenReturn(List.of());

        SceneSnapshotLoader loader = new SceneSnapshotLoader(mapper, assembler, objectMapper);
        SceneRuleIndex index = new SceneRuleIndex();
        loader.loadAllWithStrategy(index);

        assertEquals("Asia/Shanghai", index.getDefaultParams("1", "fraud").get("timezone"));
    }

    /** defaultParamsJson 为 null 时写入空 map,不阻断索引加载。 */
    @Test
    void loadAllWithStrategy_nullDefaultParams_writesEmptyMap() {
        RuleVersionRow row = new RuleVersionRow(
                2L, "payment", 1L, "{}", "[]", "[]", "[]", "AST_BOOLEAN", "HIGHEST_PRIORITY",
                null, null, "code", 1L, null, null, null, null);

        RuleVersionReadMapper mapper = mock(RuleVersionReadMapper.class);
        SnapshotAssembler assembler = mock(SnapshotAssembler.class);
        when(mapper.loadAllActive()).thenReturn(List.of(row));
        when(assembler.assembleAll(List.of(row))).thenReturn(List.of());

        SceneSnapshotLoader loader = new SceneSnapshotLoader(mapper, assembler, objectMapper);
        SceneRuleIndex index = new SceneRuleIndex();
        loader.loadAllWithStrategy(index);

        assertTrue(index.getDefaultParams("1", "payment").isEmpty());
    }

    /** defaultParamsJson 为非法 JSON 时回退空 map,不抛异常。 */
    @Test
    void loadAllWithStrategy_invalidJson_writesEmptyMap() {
        RuleVersionRow row = new RuleVersionRow(
                3L, "scene", 1L, "{}", "[]", "[]", "[]", "AST_BOOLEAN", "HIGHEST_PRIORITY",
                null, null, "code", 1L, null, null, null, "not-json");

        RuleVersionReadMapper mapper = mock(RuleVersionReadMapper.class);
        SnapshotAssembler assembler = mock(SnapshotAssembler.class);
        when(mapper.loadAllActive()).thenReturn(List.of(row));
        when(assembler.assembleAll(List.of(row))).thenReturn(List.of());

        SceneSnapshotLoader loader = new SceneSnapshotLoader(mapper, assembler, objectMapper);
        SceneRuleIndex index = new SceneRuleIndex();
        loader.loadAllWithStrategy(index);

        assertTrue(index.getDefaultParams("1", "scene").isEmpty());
    }
}
